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
import org.orbeon.saxon.`type`.{BuiltInAtomicType, BuiltInType}
import org.orbeon.saxon.expr.*
import org.orbeon.saxon.functions.DeepEqual
import org.orbeon.saxon.instruct.NumberInstruction
import org.orbeon.saxon.om.*
import org.orbeon.saxon.pattern.{NameTest, NodeKindTest}
import org.orbeon.saxon.sort.{CodepointCollator, GenericAtomicComparer}
import org.orbeon.saxon.sxpath.XPathEvaluator
import org.orbeon.saxon.value.*
import org.orbeon.saxon.{ArrayFunctions, MapFunctions, om}
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
      (e.iterateSubExpressions.asScala.asInstanceOf[Iterator[Expression]] flatMap iterateExpressionTree)

  def iterateExternalVariableReferences(expr: Expression): Iterator[String] =
    iterateExpressionTree(expr) collect {
      case vr: VariableReference if ! vr.isInstanceOf[LocalVariableReference] =>
        vr.getBinding.getVariableQName.getLocalName
    }

  def containsFnWithParam(expr: Expression, fnName: StructuredQName, arity: Int, oldName: String, argPos: Int): Boolean =
    iterateFunctions(expr, fnName) exists {
      case fn if fn.getArguments.size == arity =>
        fn.getArguments.apply(argPos) match {
          case s: StringLiteral if s.getStringValue == oldName => true
          case _                                               => false
        }
      case _ => false
    }

  def parseQName(lexicalQName: String): (String, String) = {
    val checker = Name10Checker.getInstance
    val parts   = checker.getQNameParts(lexicalQName)

    (parts(0), parts(1))
  }

  def makeNCName(name: String, keepFirstIfPossible: Boolean): String = {

    require(name.nonAllBlank, "name must not be blank or empty")

    val name10Checker = Name10Checker.getInstance
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
    (valueRepr1, valueRepr2) match {
      // Ideally we wouldn't support null here (XFormsVariableControl passes null)
      case (null,             null)            => true
      case (null,             _)               => false
      case (_,                null)            => false
      case (v1: Value, v2: Value)              => compareValues(v1, v2)
      case (v1: NodeInfo, v2: NodeInfo)        => v1 == v2
      // 2014-08-18: Checked Saxon class hierarchy
      // Saxon type hierarchy is closed (ValueRepresentation = NodeInfo | Value)
      case _                                   => throw new IllegalStateException
    }

  private def compareValues(value1: Value, value2: Value): Boolean = {
    val iter1 = Implicits.asScalaIterator(value1.iterate)
    val iter2 = Implicits.asScalaIterator(value2.iterate)

    iter1.zipAll(iter2, null, null) forall (compareItems _).tupled
  }

  def compareItemSeqs(nodeset1: Iterable[Item], nodeset2: Iterable[Item]): Boolean =
    nodeset1.size == nodeset2.size &&
      (nodeset1.iterator.zip(nodeset2.iterator) forall (compareItems _).tupled)

  def compareItems(item1: Item, item2: Item): Boolean =
    (item1, item2) match {
      // We probably shouldn't support null at all here!
      case (null,             null)            => true
      case (null,             _)               => false
      case (_,                null)            => false
      // StringValue.equals() throws (Saxon equality requires a collation)
      case (v1: StringValue,  v2: StringValue) => v1.codepointEquals(v2)
      case (v1: StringValue,  v2 )             => false
      case (v1,               v2: StringValue) => false
      // AtomicValue.equals() may throw (Saxon changes the standard equals() contract)
      case (v1: AtomicValue,  v2: AtomicValue) => v1 == v2
      case (v1,               v2: AtomicValue) => false
      case (v1: AtomicValue,  v2)              => false
      // NodeInfo
      case (v1: NodeInfo,     v2: NodeInfo)    => v1 == v2
      // 2014-08-18: Checked Saxon class hierarchy
      // Saxon type hierarchy is closed (Item = NodeInfo | AtomicValue)
      case _                                   => throw new IllegalStateException
    }

  protected def findNodePosition(node: NodeInfo): Int = {

    val nodeTestForSameNode =
      node.getFingerprint match {
        case -1 => NodeKindTest.makeNodeKindTest(node.getNodeKind)
        case _  => new NameTest(node)
      }

    val precedingAxis =
      node.iterateAxis(Axis.PRECEDING_SIBLING, nodeTestForSameNode)

    var i: Int = 1
    while (precedingAxis.next ne null) {
      i += 1
    }
    i
  }

  def convertJavaObjectToSaxonObject(o: Any): ValueRepresentationType =
    o match {
      case v: ValueRepresentationType => v
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
    def filterWhitespaceNodes(item: om.Item) = item match {
      case n: om.NodeInfo => ! Whitespace.isWhite(n.getStringValueCS)
      case _ => true
    }

    def asSequenceIterator(i: collection.Iterator[Item]): SequenceIterator = new SequenceIterator {

      private var currentItem: Item = _
      private var _position = 0

      def next(): Item = {
        if (i.hasNext) {
          currentItem = i.next()
          _position += 1
        } else {
          currentItem = null
          _position = -1
        }

        currentItem
      }

      def current    : Item             = currentItem
      def position   : Int              = _position
      def close()    : Unit             = ()
      def getAnother : SequenceIterator = throw new IllegalStateException // won't be used by the callees
      def getProperties                 = 0
    }

    DeepEqual.deepEquals(
      asSequenceIterator(if (excludeWhitespaceTextNodes) it1 filter filterWhitespaceNodes else it1),
      asSequenceIterator(if (excludeWhitespaceTextNodes) it2 filter filterWhitespaceNodes else it2),
      new GenericAtomicComparer(CodepointCollator.getInstance, config.getConversionContext),
      config,
      DeepEqual.INCLUDE_PREFIXES                  |
        DeepEqual.INCLUDE_COMMENTS                |
        DeepEqual.COMPARE_STRING_VALUES           |
        DeepEqual.INCLUDE_PROCESSING_INSTRUCTIONS |
        (if (excludeWhitespaceTextNodes) DeepEqual.EXCLUDE_WHITESPACE_TEXT_NODES else 0)
    )
  }

  def getStructuredQNameLocalPart(qName: om.StructuredQName): String = qName.getLocalName
  def getStructuredQNameURI      (qName: om.StructuredQName): String = qName.getNamespaceURI

  def itemIterator(i: om.Item): SequenceIterator = SingletonIterator.makeIterator(i)
  def listIterator(s: collection.Seq[om.Item]): SequenceIterator = new ListIterator(s.asJava)
  def emptyIterator: SequenceIterator = EmptyIterator.getInstance
  def valueAsIterator(v: ValueRepresentationType): SequenceIterator = Value.asIterator(v)

  def selectID(node: NodeInfo, id: String): Option[NodeInfo] =
    Option(node.getDocumentRoot.selectID(id))

  def newMapItem(map: Map[AtomicValue, ValueRepresentationType]): Item =
    MapFunctions.createValue(map)

  def newArrayItem(v: Vector[ValueRepresentationType]): Item =
    ArrayFunctions.createValue(v)

  def hasXPathNumberer(lang: String): Boolean =
    NumberInstruction.makeNumberer(lang, null, null).getClass.getName.endsWith("Numberer_" + lang)

  def isValidNCName(name: String): Boolean =
    Name10Checker.getInstance.isValidNCName(name)

  def isValidNmtoken(name: String): Boolean =
    Name10Checker.getInstance.isValidNmtoken(name)

  val ChildAxisInfo: AxisType = Axis.CHILD
  val AttributeAxisInfo: AxisType = Axis.ATTRIBUTE

  def getInternalPathForDisplayPath(namespaces: Map[String, String], path: String): String = {

    // Special case of empty path
    if (path.isEmpty)
      return path

    val pool = GlobalNamePool

    {
      for (token <- path split '/') yield {
        if (token.startsWith("instance(")) {
          // instance(...)
          token
        } else {
          val (optionalAt, qName) = if (token.startsWith("@")) ("@", token.substring(1)) else ("", token)

          optionalAt + {

            val prefix = XMLUtils.prefixFromQName(qName)
            val localname = XMLUtils.localNameFromQName(qName)

            // Get number from pool based on QName
            pool.allocate(prefix, namespaces(prefix), localname)
          }
        }
      }
    } mkString "/"
  }

  def attCompare(boundNodeOpt: Option[om.NodeInfo], att: om.NodeInfo): Boolean =
    boundNodeOpt exists (_.getAttributeValue(att.getFingerprint) == att.getStringValue)

  def xsiType(elem: om.NodeInfo): Option[QName] = {
    // NOTE: Saxon 9 has new code to resolve such QNames
    val typeQName = elem.getAttributeValue(om.StandardNames.XSI_TYPE)
    if (typeQName ne null) {
      val checker = elem.getConfiguration.getNameChecker
      val parts = checker.getQNameParts(typeQName)

      // No prefix
      if (parts(0).isEmpty)
        return QName(parts(1)).some

      // There is a prefix, resolve it
      val namespaceNodes = elem.iterateAxis(NamespaceAxisType)
      breakable {
        while (true) {
          val currentNamespaceNode = namespaceNodes.next.asInstanceOf[om.NodeInfo]
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

    val requiredTypeFingerprint = om.StandardNames.getFingerprint(XMLConstants.XSD_URI, newTypeLocalName)
    require(requiredTypeFingerprint != -1)

    value.convertPrimitive(
      BuiltInType.getSchemaType(requiredTypeFingerprint).asInstanceOf[BuiltInAtomicType],
      true,
      new XPathContextMajor(value, new XPathEvaluator(config).getExecutable)
    ) match {
        case v: AtomicValue => v.some
        case _              => None
    }
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
      if (ancestorOrSelf.size > 1) // first is the root element, which we skip as that corresponds to instance('...')
        ancestorOrSelf.tail map { node =>
          node.getNodeKind match {
            case ELEMENT_NODE   => node.getFingerprint
            case ATTRIBUTE_NODE => "@" + node.getFingerprint
          }
        }
      else
        Nil

    pathElements mkString "/"
  }
}
