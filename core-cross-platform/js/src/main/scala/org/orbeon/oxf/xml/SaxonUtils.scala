/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xml

import cats.syntax.option.*
import org.orbeon.dom.QName
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.util.StaticXPath.*
import org.orbeon.oxf.util.StringUtils.*
import org.orbeon.saxon.expr.parser.ExpressionTool
import org.orbeon.saxon.expr.sort.{CodepointCollator, GenericAtomicComparer}
import org.orbeon.saxon.expr.{EarlyEvaluationContext, Expression, StringLiteral}
import org.orbeon.saxon.functions.DeepEqual
import org.orbeon.saxon.ma.arrays.ImmutableArrayItem
import org.orbeon.saxon.ma.map.HashTrieMap
import org.orbeon.saxon.ma.parray.ImmList
import org.orbeon.saxon.model.{AtomicType, BuiltInType, Converter, Type}
import org.orbeon.saxon.om
import org.orbeon.saxon.om.*
import org.orbeon.saxon.pattern.{NameTest, NodeKindTest}
import org.orbeon.saxon.tree.iter.{EmptyIterator, ListIterator, SingletonIterator}
import org.orbeon.saxon.value.*
import org.orbeon.scaxon.Implicits
import org.w3c.dom.Node.*

import java.net.URI
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.Breaks.{break, breakable}


object SaxonUtils extends SaxonUtilsTrait {

  def effectiveBooleanValue(iterator: SequenceIterator): Boolean =
    ExpressionTool.effectiveBooleanValue(iterator)

  def iterateExpressionTree(e: Expression): Iterator[Expression] =
    Iterator.single(e) ++
      (e.operands.iterator.asScala flatMap (o => iterateExpressionTree(o.getChildExpression)))

  def iterateExternalVariableReferences(expr: Expression): Iterator[String] = {
    iterateExpressionTree(expr) collect {
      case vr: OrbeonVariableReference =>
        vr.name.getLocalPart
    }
  }

  def containsFnWithParam(expr: Expression, fnName: StructuredQName, arity: Int, oldName: String, argPos: Int): Boolean =
    iterateFunctions(expr, fnName) exists {
      case fn if fn.getArguments.size == arity =>
        fn.getArguments(argPos) match {
          case s: StringLiteral if s.getStringValue == oldName => true
          case _                                               => false
        }
      case _ => false
    }

  def parseQName(lexicalQName: String): (String, String) = {
    val checker = NameChecker
    val parts   = checker.getQNameParts(lexicalQName)

    (parts(0), parts(1))
  }

  def makeNCName(name: String, keepFirstIfPossible: Boolean): String = {

    require(name.nonAllBlank, "name must not be blank or empty")

    val name10Checker = NameChecker
    if (name10Checker.isValidNCName(name)) {
      name
    } else {
      val sb = new StringBuilder
      val start = name.charAt(0)

      if (name10Checker.isNCNameStartChar(start))
        sb.append(start)
      else if (keepFirstIfPossible && name10Checker.isNCNameChar(start)) {
        sb.append('_')
        sb.append(start)
      } else
        sb.append('_')

      for (i <- 1 until name.length) {
        val ch = name.charAt(i)
        sb.append(if (name10Checker.isNCNameChar(ch)) ch else '_')
      }
      sb.toString
    }
  }

  def compareValueRepresentations(valueRepr1: ValueRepresentationType, valueRepr2: ValueRepresentationType): Boolean =
    (
      // Ideally we wouldn't support `null` here but `XFormsVariableControl` can pass `null`
      if (valueRepr1 ne null) valueRepr1 else EmptySequence,
      if (valueRepr2 ne null) valueRepr2 else EmptySequence
    ) match {
      case (EmptySequence,      EmptySequence)      => true // NOTE: `EmptySequence` is not an item.
      case (EmptySequence,      _)                  => false
      case (_,                  EmptySequence)      => false
      case (v1: Item,           v2: Item)           => compareItems(v1, v2)
      case ( _: Item,           _)                  => false
      case (_,                  _ : Item)           => false
      case (v1: SequenceExtent, v2: SequenceExtent) => compareSequenceExtents(v1, v2)
      case (_ : SequenceExtent, _)                  => false
      case (_,                  _: SequenceExtent)  => false
      case _                                        => throw new IllegalStateException
    }

  def compareItemSeqs(nodeset1: Iterable[Item], nodeset2: Iterable[Item]): Boolean =
    nodeset1.size == nodeset2.size &&
      (nodeset1.iterator.zip(nodeset2.iterator) forall (compareItems _).tupled)

  private def compareSequenceExtents(v1: SequenceExtent, v2: SequenceExtent): Boolean =
    v1.getLength == v2.getLength &&
      (v1.iterator.asScala.zip(v2.iterator.asScala) forall (compareItems _).tupled)

  def compareItems(item1: Item, item2: Item): Boolean =
    (
      if (item1 ne null) item1 else EmptySequence, // TODO: I don't think any caller passes `null`. If so, get rid of this once we test.
      if (item2 ne null) item2 else EmptySequence
    ) match {
      case (EmptySequence,             EmptySequence)             => true
      case (EmptySequence,             _)                         => false
      case (_,                         EmptySequence)             => false
      // `StringValue.equals()` throws (Saxon equality requires a collation)
      case (v1: StringValue,           v2: StringValue)           => v1.codepointEquals(v2)
      case ( _: StringValue,           _ )                        => false
      case ( _,                        _: StringValue)            => false
      case (v1: AtomicValue,           v2: AtomicValue)           => v1 == v2
      case ( _: AtomicValue,           _)                         => false
      case (_,                         _ : AtomicValue)           => false
      case (v1: NodeInfo,              v2: NodeInfo)              => v1 == v2
      case (_ : NodeInfo,              _)                         => false
      case (_,                         _ : NodeInfo)              => false
      case ( _: Function,              _ : Function)              => throw new UnsupportedOperationException
      case ( _: Function,              _)                         => throw new UnsupportedOperationException
      case (_,                         _ : Function)              => throw new UnsupportedOperationException
      case _                                                      => throw new IllegalStateException
    }

  protected def findNodePosition(node: NodeInfo): Int = {

    val nodeTestForSameNode =
      node.getNodeKind match {
        case Type.ELEMENT | Type.ATTRIBUTE | Type.PROCESSING_INSTRUCTION | Type.NAMESPACE =>
          new NameTest(node.getNodeKind, node.getURI, node.getLocalPart, node.getConfiguration.getNamePool)
        case _ =>
          NodeKindTest.makeNodeKindTest(node.getNodeKind)
      }

    val precedingAxis =
      node.iterateAxis(AxisInfo.PRECEDING_SIBLING, nodeTestForSameNode)

    var i = 1
    while (precedingAxis.next() ne null)
      i += 1
    i
  }

  def convertJavaObjectToSaxonObject(o: Any): ValueRepresentationType =
    o match {
      case v: GroundedValue       => v
      case v: String              => new StringValue(v)
      case v: java.lang.Boolean   => BooleanValue.get(v)
      case v: java.lang.Integer   => new Int64Value(v.toLong)
      case v: java.lang.Float     => new FloatValue(v)
      case v: java.lang.Double    => new DoubleValue(v)
      case v: URI                 => new AnyURIValue(v.toString)
      case _                      => throw new OXFException(s"Invalid variable type: ${o.getClass}")
    }

  def deepCompare(
    config                     : SaxonConfiguration,
    it1                        : Iterator[om.Item],
    it2                        : Iterator[om.Item],
    excludeWhitespaceTextNodes : Boolean
  ): Boolean = {

    // Do our own filtering of top-level items as Saxon's `DeepEqual` doesn't
    def filterWhitespaceNodes(item: om.Item): Boolean = item match {
      case n: om.NodeInfo => ! Whitespace.isWhite(n.getStringValueCS)
      case _ => true
    }

    DeepEqual.deepEqual(
      Implicits.asSequenceIterator(if (excludeWhitespaceTextNodes) it1 filter filterWhitespaceNodes else it1),
      Implicits.asSequenceIterator(if (excludeWhitespaceTextNodes) it2 filter filterWhitespaceNodes else it2),
      new GenericAtomicComparer(CodepointCollator.getInstance, config.getConversionContext),
      new EarlyEvaluationContext(config),
      DeepEqual.INCLUDE_PREFIXES                  |
        DeepEqual.INCLUDE_COMMENTS                |
        DeepEqual.COMPARE_STRING_VALUES           |
        DeepEqual.INCLUDE_PROCESSING_INSTRUCTIONS |
        (if (excludeWhitespaceTextNodes) DeepEqual.EXCLUDE_WHITESPACE_TEXT_NODES else 0)
    )
  }

  // These are here to abstract some differences between Saxon 9 and 10
  def getStructuredQNameLocalPart(qName: om.StructuredQName): String = qName.getLocalPart
  def getStructuredQNameURI      (qName: om.StructuredQName): String = qName.getURI

  def itemIterator(i: om.Item): SequenceIterator = SingletonIterator.makeIterator(i)
  def listIterator(s: collection.Seq[om.Item]): SequenceIterator = new ListIterator(s.asJava)
  def emptyIterator: SequenceIterator = EmptyIterator.getInstance
  def valueAsIterator(v: ValueRepresentationType): SequenceIterator = if (v eq null) emptyIterator else v.iterate()

  def selectID(node: NodeInfo, id: String): Option[NodeInfo] =
    Option(node.getTreeInfo.selectID(id, getParent = false))

  def newMapItem(map: Map[AtomicValue, ValueRepresentationType]): Item = {
    val m = new HashTrieMap
    map foreach { case (k, v) => m.initialPut(k, v)}
    m
  }

  def newArrayItem(v: collection.Seq[ValueRepresentationType]): Item =
    new ImmutableArrayItem(ImmList.fromList(v.asJava))

  def hasXPathNumberer(lang: String): Boolean =
    GlobalConfiguration.makeNumberer(lang, null).getClass.getName.endsWith("Numberer_" + lang)

  def isValidNCName(name: String): Boolean =
    NameChecker.isValidNCName(name)

  def isValidNmtoken(name: String): Boolean =
    NameChecker.isValidNmtoken(name)

  val ChildAxisInfo: AxisType = AxisInfo.CHILD
  val AttributeAxisInfo: AxisType = AxisInfo.ATTRIBUTE

  def getInternalPathForDisplayPath(namespaces: Map[String, String], path: String): String =
    throw new NotImplementedError("getInternalPathForDisplayPath")

  def attCompare(boundNodeOpt: Option[om.NodeInfo], att: om.NodeInfo): Boolean =
    boundNodeOpt exists (_.getAttributeValue(att.getURI, att.getLocalPart) == att.getStringValue)

  def xsiType(elem: om.NodeInfo): Option[QName] = {
    val fp = om.StandardNames.XSI_TYPE
    val typeQName = elem.getAttributeValue(om.StandardNames.getURI(fp), om.StandardNames.getLocalName(fp))
    if (typeQName ne null) {
      val parts = NameChecker.getQNameParts(typeQName)

      // No prefix
      if (parts(0).isEmpty)
        return QName(parts(1)).some

      // There is a prefix, resolve it
      val namespaceNodes = elem.iterateAxis(NamespaceAxisType)
      breakable {
        while (true) {
          val currentNamespaceNode = namespaceNodes.next()
          if (currentNamespaceNode eq null)
            break()
          val prefix = currentNamespaceNode.getLocalPart
          if (prefix == parts(0))
            return QName(parts(1), "", currentNamespaceNode.getStringValue).some
        }
      }
    }
    None
  }

  def convertType(
    value               : StringValue,
    newTypeLocalName    : String,
    config              : SaxonConfiguration
  ): Try[Option[AtomicValue]] = Try {
    val targetType = BuiltInType.getSchemaTypeByLocalName(newTypeLocalName).asInstanceOf[AtomicType]
    Try(Converter.convert(value, targetType, config.getConversionRules)).toOption
  }

  def createFingerprintedPath(node: om.NodeInfo): String = {

    // Create an immutable list with ancestor-or-self nodes up to but not including the document node
    var ancestorOrSelf: List[om.NodeInfo] = Nil
    var currentNode = node
    while (currentNode != null && currentNode.getNodeKind != DOCUMENT_NODE) {
      ancestorOrSelf = currentNode :: ancestorOrSelf
      currentNode = currentNode.getParent
    }

    // Fingerprint representation of the element and attribute nodes
    val pathElements =
      if (ancestorOrSelf.size > 1) { // first is the root element, which we skip as that corresponds to instance('...')

        val namePool = node.getConfiguration.getNamePool

        ancestorOrSelf.tail map { node =>

          val codePrefix =
            node.getNodeKind match {
              case ELEMENT_NODE   => ""
              case ATTRIBUTE_NODE => "@"
            }

          // NOTE: Our `NodeWrapper` no longer supports fingerprints, so we must support them here,
          // unless we find a better path encoding.
          codePrefix + (
            if (node.hasFingerprint)
              node.getFingerprint
            else
              namePool.allocateFingerprint(node.getURI, node.getLocalPart)
            )
        }
      } else
        Nil

    pathElements mkString "/"
  }
}
