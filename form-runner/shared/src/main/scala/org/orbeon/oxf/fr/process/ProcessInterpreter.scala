/**
 *  Copyright (C) 2013 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr.process

import cats.effect.IO
import org.orbeon.exception.OrbeonFormatter
import org.orbeon.oxf.fr.FormRunnerCommon.spc
import org.orbeon.oxf.fr.{FormRunnerRename, XMLNames}
import org.orbeon.oxf.fr.process.ProcessParser.*
import org.orbeon.oxf.util as u
import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.oxf.util.StringUtils.*
import org.orbeon.oxf.util.{CoreCrossPlatformSupport, FunctionContext, IndentedLogger, Logging}
import org.orbeon.oxf.xforms.XFormsContainingDocument
import org.orbeon.oxf.xforms.action.XFormsAPI.inScopeContainingDocument
import org.orbeon.oxf.xml.XMLConstants.{XHTML_PREFIX, XHTML_SHORT_PREFIX, XML_PREFIX, XSD_PREFIX}
import org.orbeon.oxf.xml.{XMLConstants, XMLUtils}
import org.orbeon.saxon.functions.FunctionLibrary
import org.orbeon.saxon.om
import org.orbeon.saxon.value.BooleanValue
import org.orbeon.scaxon.XPath.*
import org.orbeon.xforms.XFormsNames.*
import org.orbeon.xml.NamespaceMapping

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}


// Independent process interpreter
trait ProcessInterpreter extends Logging {

  import ProcessInterpreter.*

  val EmptyActionParams: ActionParams = Map.empty

  // Must be overridden by implementation
  def findProcessByName(scope: String, name: String): Option[String]
  def processError(t: Throwable): Unit
  def xpathContext: om.Item
  implicit def xpathFunctionLibrary: FunctionLibrary
  def xpathFunctionContext: u.FunctionContext
  def clearSuspendedProcess(): Unit
  def writeSuspendedProcess(processId: String, process: String): Unit
  def readSuspendedProcess: Try[(String, String)]
  def submitContinuation[T, U](message: String, computation: IO[T], continuation: (XFormsContainingDocument, Try[T]) => Either[Try[U], Future[U]]): Future[U]
  def createUniqueProcessId: String = CoreCrossPlatformSupport.randomHexId
  def transactionStart(): Unit
  def transactionRollback(): Unit
  def withRunProcess[T](scope: String, name: String)(body: => T): T
  implicit def logger: IndentedLogger

  // May be overridden by implementation
  def extensionActions: Iterable[(String, Action)] = Nil
  def beforeProcess(): Try[Any] = Try(())
//  def afterProcess():  Try[Any] = Try(())

  private object ProcessRuntime {

    import org.orbeon.oxf.util.DynamicVariable

    private val StandardActions: Map[String, Action] = Map(
      "terminate-with-success" -> tryTerminateWithSuccess,
      "success"                -> tryTerminateWithSuccess, // deprecated
      "terminate-with-failure" -> tryTerminateWithFailure,
      "failure"                -> tryTerminateWithFailure, // deprecated

      "continue-with-success"  -> tryContinueWithSuccess,
      "nop"                    -> tryContinueWithSuccess,  // deprecated
      "continue-with-failure"  -> tryContinueWithFailure,

      "process"                -> tryProcess,

      "suspend"                -> trySuspend,
      "resume-with-success"    -> tryResumeWithSuccess,
      "resume"                 -> tryResumeWithSuccess,    // deprecated
      "resume-with-failure"    -> tryResumeWithFailure,
      "abort"                  -> tryAbort,

      "rollback"               -> tryRollback,
    )

    val AllAllowedActions = StandardActions ++ extensionActions

    // Keep stack frames for the execution of action. They can nest with sub-processes.
    val processStackDyn = new DynamicVariable[Process]

    // Used to interrupt a process
    case class ProcessFailure() extends Throwable with NoStackTrace

    // Scope an empty stack around a process execution
    def withEmptyStack[T](scope: String)(body: => T): T = {
      processStackDyn.withValue(Process(scope, createUniqueProcessId, Nil)) {
        body
      }
    }

    // Push a stack frame, run the body, and pop the frame
    private def withStackFrame[T](group: GroupNode, programCounter: Int)(body: => T): T = {
      processStackDyn.value.get.frames = StackFrame(group, programCounter) :: processStackDyn.value.get.frames
      try body
      finally processStackDyn.value.get.frames = processStackDyn.value.get.frames.tail
    }

    // Return a process string which contains the continuation of the process after the current action
    def serializeContinuation: (String, String) = {

      val process = processStackDyn.value.get

      // Find the continuation, which is the concatenation of the continuation of all the sub-processes up to the
      // top-level process.
      val continuation =
        process.frames flatMap {
          case StackFrame(group, pos) =>
            group.rest drop pos flatMap { case (combinator, expr) =>
              List(combinator.name, expr.serialize)
            }
        }

      // Continuation is either empty or starts with a combinator. We prepend the (always successful) "nop".
      // Don't add parentheses around the continuation: adding parentheses changes the meaning of the expression:
      // https://github.com/orbeon/orbeon-forms/issues/6639
      val serializedContinuation = ("nop" :: continuation).mkString(" ")
      (process.processId, serializedContinuation)
    }

    case class Process(scope: String, processId: String, var frames: List[StackFrame])
    case class StackFrame(group: GroupNode, actionCounter: Int)

    def runSubProcess(process: String, initialTry: Try[Any]): InternalActionResult = {

      def runAction(action: ActionNode): InternalActionResult =
        withDebug("process: running action", List("action" -> action.toString)) {

          val actionResult =
            AllAllowedActions
              .getOrElse(action.name, (_: ActionParams) => tryProcess(Map(Some("name") -> action.name)))
              .apply(action.params)

          actionResult match {
            case r @ ActionResult.Sync(Success(_)) =>
              debugResults(List("result" -> "successful action"))
              r
            case r @ ActionResult.Sync(Failure(e)) =>
              debugResults(List("result" -> s"failed action: ${e.getMessage}"))
              r
            case ActionResult.Async(failure @ Failure(_)) =>
              ActionResult.Sync(failure)
            case ActionResult.Async(Success((computation, continuation))) =>
              debugResults(List("result" -> "suspended asynchronous action"))
              val serializedContinuation = serializeContinuation
              // TODO: we don't support concurrent processes yet so if someone starts another process in the meanwhile,
              //  some state will be lost.
              val processScope = processStackDyn.value.get.scope
              ActionResult.Interrupt(
                None,
                Right(
                  submitContinuation(
                    s"continuation of process $runningProcessId",
                    computation,
                    (xfcd: XFormsContainingDocument, computationResult: Try[Any]) => {
                      runProcess(
                        processScope,
                        serializedContinuation._2,
                        continuation(xfcd, computationResult)
                      )
                    }
                  )
                )
              )
            case r @ ActionResult.Interrupt(_, _) =>
              debugResults(List("result" -> "interrupted action"))
              r
          }
        }

      def runGroup(group: GroupNode): InternalActionResult = {
        val GroupNode(expr, rest) = group
        runGroupRest(group, 1, withStackFrame(group, 0) { runExpr(expr) }, rest)
      }

      @tailrec def runGroupRest(
        group               : GroupNode,
        pos                 : Int,
        previousActionResult: InternalActionResult,
        rest                : List[(Combinator, ExprNode)]
      ): InternalActionResult =
        (previousActionResult, rest) match {
          case (ActionResult.Sync(tried), (nextCombinator, nextExpr) :: tail) =>

            val newActionResult =
              withStackFrame(group, pos) {
                nextCombinator match {
                  case Combinator.Then =>
                    debug("process: combining with then", List("action" -> nextExpr.toString))
                    tried match {
                      case Success(_) =>
                        runExpr(nextExpr)
                      case Failure(_) =>
                        previousActionResult
                    }
                  case Combinator.Recover =>
                    debug("process: combining with recover", List("action" -> nextExpr.toString))
                    tried match {
                      case Success(_) =>
                        previousActionResult
                      case Failure(t) =>
                        debug("process: recovering", List("throwable" -> OrbeonFormatter.format(t)))
                        runExpr(nextExpr)
                    }
                }
              }

            runGroupRest(group, pos + 1, newActionResult, tail)
          case _ =>
            previousActionResult
        }

      def runCondition(condition: ConditionNode): InternalActionResult =
        (Try(evaluateBoolean(condition.xpath)), condition.elseBranch) match {
          case (Success(true), _) =>
            runExpr(condition.thenBranch)
          case (Success(false), Some(elseBranch)) =>
            runExpr(elseBranch)
          case (Success(false), None) =>
            ActionResult.Sync(Success(()))
          case (Failure(t), _) =>
            debug("process: condition failed", List("throwable" -> OrbeonFormatter.format(t)))
            ActionResult.Sync(Failure(t))
        }

      def runExpr(expr: ExprNode): InternalActionResult =
        expr match {
          case e: ActionNode    => runAction(e)
          case e: GroupNode     => runGroup(e)
          case e: ConditionNode => runCondition(e)
        }

      def parseProcess(process: String): Option[GroupNode] =
        process.nonAllBlank option ProcessParser.parse(process)

      (parseProcess(process), initialTry) match {
        case (Some(groupNode), Success(_)) =>
          // Normal process run
          runGroup(groupNode)
        case (Some(groupNode @ GroupNode(_, rest)), failure @ Failure(_)) =>
          // Process run starting with a `Failure` (for continuations)
          runGroupRest(groupNode, 1, ActionResult.Sync(failure), rest)
        case (None, _) =>
          debug("process: empty process, canceling process")
          ActionResult.Sync(Success(()))
      }
    } // end `runSubProcess()`
  }

  import ProcessRuntime.*

  private def rawProcessByName(scope: String, name: String): String =
    findProcessByName(scope, name) getOrElse
    (throw new IllegalArgumentException(s"Non-existing process: $name in scope $scope"))

  // Main entry point for starting a process associated with a named button
  def runProcessByName(scope: String, name: String): ProcessResult =
    withRunProcess(scope, name) {
      runProcess(scope, rawProcessByName(scope, name))
    }

  type ProcessResult = Either[Try[Any], Future[Any]]

  // Main entry point for starting a literal process
  def runProcess(scope: String, process: String, initialTry: Try[Any] = Success(())): ProcessResult =
    withDebug("process: running", List("process" -> process)) {
      transactionStart()
      // Scope the process (for suspend/resume)
      withEmptyStack(scope) {
        beforeProcess().map(_ => runSubProcess(process, initialTry)).recoverWith { case t =>
          // Log and send a user error if there is one
          // NOTE: In the future, it would be good to provide the user with an error id.
          error(OrbeonFormatter.format(t))
          Try(processError(t))
          Failure(t)
        } match {
          case Success(ActionResult.Sync(tried)) =>
            Left(tried)
          case Success(ActionResult.Interrupt(message, Left(success @ Success(_)))) =>
            debug(s"process: process interrupted with `success` action with message `$message`")
            Left(success)
          case Success(ActionResult.Interrupt(message, Left(failure @ Failure(_)))) =>
            debug(s"process: process interrupted with `failure` action with message `$message`")
            Left(failure)
          case Success(ActionResult.Interrupt(message, Right(io))) =>
            debug(s"process: process interrupted due to asynchronous action with message `$message`")
            Right(io)
          case f @ Failure(_) =>
            Left(f)
        }
      }
      // TODO: `transactionEnd()` to clean transient state?
    }

  // Id of the currently running process
  def runningProcessId: Option[String] = processStackDyn.value map (_.processId)

  // Interrupt the process and complete with a success
  private def tryTerminateWithSuccess(params: ActionParams): ActionResult =
    ActionResult.Interrupt(paramByNameUseAvt(params, "message"), Left(Success(())))

  // Interrupt the process and complete with a failure
  private def tryTerminateWithFailure(params: ActionParams): ActionResult =
    ActionResult.Interrupt(paramByNameUseAvt(params, "message"), Left(Failure(ProcessFailure())))

  // Run a sub-process
  private def tryProcess(params: ActionParams): ActionResult =
    Try(paramByNameOrDefaultUseAvt(params, "name").get)
      .map(rawProcessByName(processStackDyn.value.get.scope, _)) match {
      case Success(process) =>
        runSubProcess(process, Success(()))
      case failure @ Failure(_) =>
        ActionResult.Sync(failure)
    }

  // Suspend the process
  private def trySuspend(params: ActionParams): ActionResult =
    Try((writeSuspendedProcess _).tupled(serializeContinuation)) match {
      case Success(_) =>
        tryTerminateWithSuccess(EmptyActionParams) // this will not be caught by `Try.apply()`
        ActionResult.Interrupt(None, Left(Success(())))
      case failure @ Failure(t) =>
        error(s"error suspending process: `${t.getMessage}`")
        ActionResult.Sync(failure)
    }

  // Resume a process
  private def tryResumeWithSuccess(params: ActionParams): ActionResult =
    tryResumeWithTry(params, Success(()))

  private def tryResumeWithFailure(params: ActionParams): ActionResult =
    tryResumeWithTry(params, Failure(ProcessFailure()))

  private def tryResumeWithTry(params: ActionParams, initialTry: Try[Any]): ActionResult =
    readSuspendedProcess match {
      case Success((processId, continuation)) =>
        // TODO: Restore processId
        clearSuspendedProcess()
        runSubProcess(continuation, initialTry)
      case failure @ Failure(t) =>
        error(s"error suspending process: `${t.getMessage}`")
        ActionResult.Sync(failure)
    }

  // Abort a suspended process
  private def tryAbort(params: ActionParams): ActionResult =
    ActionResult.trySync(clearSuspendedProcess())

  // Rollback the current transaction
  private def tryRollback(params: ActionParams): ActionResult =
    ActionResult.trySync {
      val tokens = paramByNameOrDefaultUseAvt(params, "changes") map (_.tokenizeToSet) getOrElse Set.empty

      if (tokens != Set("in-memory-form-data"))
        throw new IllegalArgumentException(s"""`rollback` action must have a `changes = "in-memory-form-data"` parameter""")

      transactionRollback()
    }

  // Don't do anything
  private def tryContinueWithSuccess(params: ActionParams): ActionResult =
    ActionResult.Sync(Success(()))

  private def tryContinueWithFailure(params: ActionParams): ActionResult =
    ActionResult.Sync(Failure(ProcessFailure()))

  private def evaluateBoolean(expr: String, item: om.Item = xpathContext): Boolean =
    evaluateOne(
      expr = u.StaticXPath.makeBooleanExpression(expr),
      item = item
    ).asInstanceOf[BooleanValue].getBooleanValue

  private def exprWithProcessedVarReferences(
    xpathString     : String,
    namespaceMapping: NamespaceMapping
  ): String =
    FormRunnerRename.replaceVarReferencesWithFunctionCalls(
      xpathString      = xpathString,
      namespaceMapping = namespaceMapping,
      library          = xpathFunctionLibrary,
      avt              = false,
      libraryNameOpt   = None,
      norewrite        = Set.empty
    )

  private def namespaceMappingWithFRF(mapping: NamespaceMapping): NamespaceMapping =
    NamespaceMapping(mapping.mapping + ("frf" -> "java:org.orbeon.oxf.fr.FormRunner"))

  def evaluateString(
    expr           : String,
    item           : om.Item          = xpathContext,
    mapping        : NamespaceMapping = ProcessInterpreter.StandardNamespaceMapping,
    functionContext: FunctionContext  = xpathFunctionContext
  ): String =
    evalOne(
      item            = item,
      expr            = u.StaticXPath.makeStringExpression(exprWithProcessedVarReferences(expr, mapping)),
      namespaces      = namespaceMappingWithFRF(mapping),
      functionContext = functionContext
    ).getStringValue

  def evaluateOne(
    expr           : String,
    item           : om.Item          = xpathContext,
    mapping        : NamespaceMapping = ProcessInterpreter.StandardNamespaceMapping,
    functionContext: FunctionContext  = xpathFunctionContext
  ): om.Item =
    evalOne(
      item            = item,
      expr            = exprWithProcessedVarReferences(expr, mapping),
      namespaces      = namespaceMappingWithFRF(mapping),
      functionContext = functionContext
    )

  def evaluateNodes(expr: String, item: om.Item = xpathContext): collection.Seq[om.NodeInfo] =
    evalNodes(
      item            = item,
      expr            = exprWithProcessedVarReferences(expr, ProcessInterpreter.StandardNamespaceMapping),
      namespaces      = namespaceMappingWithFRF(ProcessInterpreter.StandardNamespaceMapping),
      functionContext = xpathFunctionContext
    )

  def evaluateValueTemplate(valueTemplate: String): String =
    if (! XMLUtils.maybeAVT(valueTemplate))
      valueTemplate
    else
      evalValueTemplate(
        item            = xpathContext,
        expr            = valueTemplate,
        namespaces      = ProcessInterpreter.StandardNamespaceMapping,
        functionContext = xpathFunctionContext
      )
}

object ProcessInterpreter {

  val StandardNamespaceMapping =
    NamespaceMapping(
      Map(
        XML_PREFIX           -> XMLConstants.XML_URI,
        XSD_PREFIX           -> XMLConstants.XSD_URI,
        XFORMS_PREFIX        -> XFORMS_NAMESPACE_URI,
        XFORMS_SHORT_PREFIX  -> XFORMS_NAMESPACE_URI,
        XXFORMS_PREFIX       -> XXFORMS_NAMESPACE_URI,
        XXFORMS_SHORT_PREFIX -> XXFORMS_NAMESPACE_URI,
        XHTML_PREFIX         -> XMLConstants.XHTML_NAMESPACE_URI,
        XHTML_SHORT_PREFIX   -> XMLConstants.XHTML_NAMESPACE_URI,
        XHTML_PREFIX         -> XMLConstants.XHTML_NAMESPACE_URI,
        XHTML_SHORT_PREFIX   -> XMLConstants.XHTML_NAMESPACE_URI,
        XMLNames.FRPrefix    -> XMLNames.FR,
        "grid-migration"     -> "java:org.orbeon.oxf.fr.GridDataMigration" // TODO: should be from properties file
      )
    )

  sealed trait ActionResult
  sealed trait InternalActionResult extends ActionResult
  object ActionResult {
    case class Sync(value: Try[Any])                                                    extends InternalActionResult
    case class Async[T](value: Try[(IO[T], (XFormsContainingDocument, Try[T]) => Try[Any])])                        extends ActionResult
    case class Interrupt(message: Option[String], value: Either[Try[Any], Future[Any]]) extends InternalActionResult

    def trySync(body: => Any): ActionResult = ActionResult.Sync(Try(body))
    def tryAsync[T](body: => (IO[T], (XFormsContainingDocument, Try[T]) => Try[Any])): ActionResult = ActionResult.Async(Try(body))
  }

  type ActionParams = Map[Option[String], String]
  type Action       = ActionParams => ActionResult

  def paramByName(params: ActionParams, name: String): Option[String] =
    params.get(Some(name))

  def booleanParamByName(params: ActionParams, name: String, default: Boolean): Boolean =
    params.get(Some(name)) map (_ == "true") getOrElse default

  def paramByNameOrDefault(params: ActionParams, name: String): Option[String] =
    params.get(Some(name)) orElse params.get(None)

  def requiredParamByName(params: ActionParams, action: String, name: String): String =
    params.getOrElse(Some(name), missingArgument(action, name))

  def paramByNameUseAvt(params: ActionParams, name: String): Option[String] =
    params.get(Some(name)).map(spc.evaluateValueTemplate).flatMap(_.trimAllToOpt)

  def booleanParamByNameUseAvt(params: ActionParams, name: String, default: Boolean): Boolean =
    params.get(Some(name)).map(spc.evaluateValueTemplate).flatMap(_.trimAllToOpt).map(_ == "true").getOrElse(default)

  def paramByNameOrDefaultUseAvt(params: ActionParams, name: String): Option[String] =
    params.get(Some(name)).map(spc.evaluateValueTemplate).flatMap(_.trimAllToOpt).orElse(params.get(None))

  def requiredParamByNameUseAvt(params: ActionParams, action: String, name: String): String =
    params.get(Some(name)).map(spc.evaluateValueTemplate).flatMap(_.trimAllToOpt).getOrElse(missingArgument(action, name))

  // TODO: Obtain action name automatically.
  private def missingArgument(action: String, name: String): Nothing =
    throw new IllegalArgumentException(s"$action: `$name` parameter is required")
}