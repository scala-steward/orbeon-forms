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

import org.orbeon.oxf.fr.Names.*
import org.orbeon.oxf.fr.process.ProcessInterpreter.*
import org.orbeon.oxf.xforms.CallbackInvocation
import org.orbeon.oxf.xforms.action.XFormsAPI.*
import org.orbeon.saxon.om
import org.orbeon.scaxon.Implicits.*


trait XFormsActions {

  self: ProcessInterpreter =>

  def AllowedXFormsActions: Map[String, Action] = Map(
    "xf:send"     -> tryXFormsSend,
    "xf:dispatch" -> tryXFormsDispatch,
    "xf:show"     -> tryShowDialog,
    "xf:hide"     -> tryHideDialog,
    "xf:setvalue" -> trySetvalue,
    "xf:insert"   -> tryInsert,
    "xf:delete"   -> tryDelete,
    "xf:callback" -> tryCallback,
  )

  def tryXFormsSend(params: ActionParams): ActionResult =
    ActionResult.trySync {
      val submission = paramByNameOrDefault(params, "submission")
      submission foreach (sendThrowOnError(_))
    }

  private val StandardDispatchParams = Set("name", "targetid")
  private val StandardDialogParams   = Set("dialog")

  private def collectCustomProperties(params: ActionParams, standardParamNames: Set[String]): Map[String, Option[String]] = params.collect {
    case (Some(name), value) if ! standardParamNames(name) => name -> Option(evaluateValueTemplate(value))
  }

  def tryXFormsDispatch(params: ActionParams): ActionResult =
    ActionResult.trySync {
      val eventName     = paramByNameOrDefault(params, "name")
      val eventTargetId = paramByName(params, "targetid") getOrElse FormModel

      eventName foreach (dispatch(_, eventTargetId, properties = collectCustomProperties(params, StandardDispatchParams)))
    }

  def tryShowDialog(params: ActionParams): ActionResult =
    ActionResult.trySync {
      val dialogName = paramByNameOrDefault(params, "dialog")

      dialogName foreach (show(_, properties = collectCustomProperties(params, StandardDialogParams)))
    }

  def tryHideDialog(params: ActionParams): ActionResult =
    ActionResult.trySync {
      val dialogName = paramByNameOrDefault(params, "dialog")

      dialogName foreach (hide(_, properties = collectCustomProperties(params, StandardDialogParams)))
    }

  def trySetvalue(params: ActionParams): ActionResult =
    ActionResult.trySync {
      val all      = booleanParamByName(params, "all", default = false)
      val refParam = requiredParamByName(params, "setvalue", "ref")
      val refSeq   = if (all) evaluateNodes(refParam) else Seq(evaluateOne(refParam))
      refSeq.foreach {
        case nodeInfo: om.NodeInfo =>
          val valueToSet = params.get(Some("value")) match {
            case None            => ""
            case Some(valueExpr) =>
              // TODO: Use namespaces from appropriate scope.
              evaluateString(
                item = nodeInfo,
                expr = valueExpr
              )
          }
          setvalue(nodeInfo, valueToSet)
        case _ =>
          debug("setvalue: `ref` parameter did not return a node, ignoring")
      }
    }

  def tryInsert(params: ActionParams): ActionResult =
    ActionResult.trySync {

      def paramAsNodes(name: String): List[om.NodeInfo] =
        paramByName(params, name).toList flatMap (evaluateNodes(_)) collect { case n: om.NodeInfo => n }

      insert(
        origin = evaluateNodes(requiredParamByName(params, "insert", "origin")) collect { case n: om.NodeInfo => n },
        into   = paramAsNodes("into"),
        before = paramAsNodes("before"),
        after  = paramAsNodes("after")
      )
    }

  def tryDelete(params: ActionParams): ActionResult =
    ActionResult.trySync {
      val refParam = requiredParamByName(params, "delete", "ref")
      delete(ref = evaluateNodes(refParam) collect { case n: om.NodeInfo => n })
    }

  // callback(name = "foo", params = Map("bar" -> "baz"))
  def tryCallback(params: ActionParams): ActionResult =
    ActionResult.trySync {
      // TODO: callback parameters, see https://github.com/orbeon/orbeon-forms/issues/5913
      inScopeContainingDocument
        .addCallbackToRun(CallbackInvocation(requiredParamByName(params, "callback", "name"), Nil))
    }
}
