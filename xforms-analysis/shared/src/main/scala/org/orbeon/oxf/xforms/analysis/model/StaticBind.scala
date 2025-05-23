package org.orbeon.oxf.xforms.analysis.model

import org.orbeon.datatypes.ExtendedLocationData
import org.orbeon.dom.*
import org.orbeon.oxf.util.Whitespace.*
import org.orbeon.oxf.util.{IndentedLogger, StaticXPath}
import org.orbeon.oxf.xforms.analysis.*
import org.orbeon.oxf.xforms.analysis.model.StaticBind.*
import org.orbeon.oxf.xml.dom.Extensions.*
import org.orbeon.saxon.functions.FunctionLibrary
import org.orbeon.xforms.analysis.model.ValidationLevel
import org.orbeon.xforms.xbl.Scope
import org.orbeon.xml.NamespaceMapping


// Represent a static `<xf:bind>` element
class StaticBind(
  index                          : Int,
  element                        : Element,
  parent                         : Option[ElementAnalysis],
  preceding                      : Option[ElementAnalysis],
  staticId                       : String,
  prefixedId                     : String,
  namespaceMapping               : NamespaceMapping,
  scope                          : Scope,
  containerScope                 : Scope
)(
  val nameOpt                    : Option[String],
  val typeMIPOpt                 : Option[TypeMIP], // `Type` MIP is special as it is not an XPath expression
  val nonPreserveWhitespaceMIPOpt: Option[WhitespaceMIP],
  val mipNameToXPathMIP          : Iterable[(MipName.XPath, List[XPathMIP])],
  val customMipNameToXPathMIP    : Map[MipName.Custom, List[XPathMIP]],
  val bindTree                   : BindTree
) extends ElementAnalysis(
  index,
  element,
  parent,
  preceding,
  staticId,
  prefixedId,
  namespaceMapping,
  scope,
  containerScope
) with WithChildrenTrait {

  staticBind =>

  val dataType: Option[QName] =
    typeMIPOpt.map(_.datatype)

  // All XPath MIPs
  val allMipNamesToXPathMIP: Map[MipName.XPath, List[XPathMIP]] = Map.empty[MipName.XPath, List[XPathMIP]] ++ customMipNameToXPathMIP ++ mipNameToXPathMIP

  // All constraint XPath MIPs grouped by level
  val constraintsByLevel: Map[ValidationLevel, List[XPathMIP]] = {
    val levelWithMIP =
      for {
        mips <- allMipNamesToXPathMIP.get(MipName.Constraint).toList
        mip  <- mips
      } yield
        mip

    levelWithMIP groupBy (_.level)
  }

  val hasCalculateComputedMIPs = mipNameToXPathMIP exists { case (_, mips) => mips exists (_.isCalculateComputedMIP)}
  val hasValidateMIPs          = typeMIPOpt.nonEmpty || (mipNameToXPathMIP exists { case (_, mips) => mips exists (_.isValidateMIP) })
  val hasCustomMIPs            = customMipNameToXPathMIP.nonEmpty
  val hasMIPs                  = hasCalculateComputedMIPs || hasValidateMIPs || hasCustomMIPs || nonPreserveWhitespaceMIPOpt.isDefined

  // Update `BindTree` during construction
  locally {

    // Globally remember binds ids
    bindTree.bindIds += staticId
    bindTree.bindsById += staticId -> staticBind

    // Remember variables mappings
    nameOpt foreach { name =>
      bindTree.bindsByName += name -> staticBind
    }

    // Globally remember if we have seen these categories of binds
    bindTree.hasDefaultValueBind      ||= hasXPathMIP(MipName.Default)
    bindTree.hasCalculateBind         ||= hasXPathMIP(MipName.Calculate)
    bindTree.hasRequiredBind          ||= hasXPathMIP(MipName.Required)

    bindTree.hasTypeBind              ||= typeMIPOpt.nonEmpty
    bindTree.hasConstraintBind        ||= constraintsByLevel.nonEmpty

    bindTree.mustRecalculate          ||= hasCalculateComputedMIPs || hasCustomMIPs || nonPreserveWhitespaceMIPOpt.isDefined
    bindTree.mustRevalidate           ||= hasValidateMIPs

    bindTree.hasNonPreserveWhitespace ||= nonPreserveWhitespaceMIPOpt.isDefined
  }

  // Used by `BindVariableResolver`
  def parentBind: Option[StaticBind] = parent collect {
    case parentBind: StaticBind => parentBind
  }

  private val ancestorBinds: List[StaticBind] = parentBind match {
    case Some(parent) => parent :: parent.ancestorBinds
    case None         => Nil
  }

  // Used by `BindVariableResolver`
  val ancestorOrSelfBinds: List[StaticBind] = staticBind :: ancestorBinds

  lazy val childrenBinds: List[StaticBind] = children.iterator collect { case b: StaticBind => b } toList
  def childrenBindsIt: Iterator[StaticBind] = childrenBinds.iterator

  def hasDefaultOrCalculateBind: Boolean =
    getXPathMIPs(MipName.Default).nonEmpty ||
    getXPathMIPs(MipName.Calculate).nonEmpty

//  def addBind(bindElement: Element, precedingId: Option[String]): Unit =
//    _children = _children :+ new StaticBind(bindTree, bindElement, staticBind, None)// NOTE: preceding not handled for now

//  def removeBind(bind: StaticBind): Unit = {
//    bindTree.bindIds -= bind.staticId
//    bindTree.bindsById -= bind.staticId
//    bind.nameOpt foreach { name =>
//      bindTree.bindsByName -= name
//    }
//
//    _children = _children filterNot (_ eq bind)
//
//    part.unmapScopeIds(bind)
//  }

  // Used by PathMapXPathDependencies
  def getXPathMIPs(mip: MipName.XPath): List[XPathMIP] = allMipNamesToXPathMIP.getOrElse(mip, Nil)

  // TODO: Support multiple relevant, readonly, and required MIPs.
  def firstXPathMipByName(mip: MipName.XPath): Option[XPathMIP] = getXPathMIPs(mip).headOption
  def hasXPathMIP  (mip: MipName.XPath): Boolean          = firstXPathMipByName(mip).nonEmpty

  // Used by `PartXBLAnalysis.unmapScopeIds()`
  def iterateNestedIds: Iterator[String] =
    for {
      elem <- element.elements.iterator
      id   <- elem.idOpt
    } yield
      id

  // TODO: Use this instead of `iterateNestedIds` as we don't want to use `element.elements` in general.
  def iterateNestedIds2: Iterator[String] =
    children.iterator map (_.staticId)

  override def freeTransientState(): Unit = {

    super.freeTransientState()

    for (mips <- allMipNamesToXPathMIP.values; mip <- mips)
      mip.analysis.freeTransientState()

    for (child <- children)
      child.freeTransientState()
  }
}

object StaticBind {

  def getBindTree(parent: Option[ElementAnalysis]): BindTree =
    parent match {
      case Some(bindTree: BindTree)     => bindTree
      case Some(staticBind: StaticBind) => staticBind.bindTree
      case _                            => throw new IllegalStateException
    }

  // Represent an individual MIP on an <xf:bind> element
  sealed trait MIP {
    def id    : String
    def name  : MipName
    def level : ValidationLevel

    // Make these lazy vals to avoid early initialization issues
    lazy val isCalculateComputedMIP : Boolean = MipName.CalculateMipNames(name)
    lazy val isValidateMIP          : Boolean = MipName.ValidateMipNames(name)
  }

  // Represent an XPath MIP
  class XPathMIP(
    override val id       : String,
    override val name     : MipName.XPath,
    override val level    : ValidationLevel,
    val compiledExpression: StaticXPath.CompiledExpression,
    var analysis          : XPathAnalysis
  ) extends MIP

  object XPathMIP {

    def apply(
      id              : String,
      name            : MipName.XPath,
      level           : ValidationLevel,
      expression      : String,
      namespaceMapping: NamespaceMapping,
      locationData    : ExtendedLocationData,
      functionLibrary : FunctionLibrary
    )(implicit
      indentedLogger  : IndentedLogger
    ): XPathMIP = {

      val compiledExpression: StaticXPath.CompiledExpression = {
        val booleanOrStringExpression =
          if (MipName.BooleanXPathMipNames(name))
            StaticXPath.makeBooleanExpression(expression)
          else
            StaticXPath.makeStringExpression(expression)

        StaticXPath.compileExpression(
          xpathString      = booleanOrStringExpression,
          namespaceMapping = namespaceMapping,
          locationData     = locationData,
          functionLibrary  = functionLibrary,
          avt              = false
        )
      }

      new XPathMIP(
        id,
        name,
        level,
        compiledExpression,
        new NegativeAnalysis(expression) // default to `NegativeAnalysis`, `analyzeXPath()` can change that
      )
    }
  }

  // The type MIP is not an XPath expression
  class TypeMIP(
    override val id       : String,
             val datatype : QName
  ) extends MIP {
    override val name     : MipName         = MipName.Type
    override val level    : ValidationLevel = ValidationLevel.ErrorLevel
  }

  class WhitespaceMIP(
    override val id       : String,
    val policy            : Policy
  ) extends MIP {
    override val name     : MipName         = MipName.Whitespace
    override val level    : ValidationLevel = ValidationLevel.ErrorLevel
  }
}