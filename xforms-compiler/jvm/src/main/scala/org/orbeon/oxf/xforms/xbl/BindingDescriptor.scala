/**
 * Copyright (C) 2013 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.xbl

import org.orbeon.css.CSSSelectorParser
import org.orbeon.css.CSSSelectorParser.AttributePredicate
import org.orbeon.dom.QName
import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.oxf.util.StringUtils.*
import org.orbeon.oxf.xforms.analysis.model.Types
import org.orbeon.oxf.xforms.xbl.BindingIndex.DatatypeMatch
import org.orbeon.oxf.xml.XMLConstants
import org.orbeon.oxf.xml.dom.Extensions
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.SimplePath.*
import org.orbeon.xforms.XFormsNames.{APPEARANCE_QNAME, XFORMS_STRING_QNAME}
import org.orbeon.xml.NamespaceMapping

import scala.collection.SeqView


// CSS selectors can be very complex but we only support a small subset of them for the purpose of binding controls to
// elements. Namely, we can bind:
//
// - by element name only
// - by element name and datatype (with the `xxf:type()` functional pseudo-class)
// - by element name and a single `appearance` attribute
// - by a single `appearance` attribute
// - by element name, a datatype, and a single `appearance` attribute
//
// `BindingDescriptor` is a minimal CSS selector descriptor able to hold the combinations above. It can in fact hold
// more than that, such as:
//
// - binding by datatype only
// - binding via attributes which are not `appearance` (but then excluding `appearance` support itself)
//   - `xf|textarea[mediatype = 'text/html']`
//   - `fr|attachment[multiple ~= true]`
case class BindingDescriptor(
  elementName  : Option[QName],
  datatype     : Option[QName],
  appearanceOpt: Option[AttributePredicate],
  attOpt       : Option[BindingAttributeDescriptor]
)(
  val binding  : Option[NodeInfo] // not part of the case-classiness
) {
  require(! attOpt.exists(_.name == APPEARANCE_QNAME))
}

// Represent a single attribute binding
case class BindingAttributeDescriptor(name: QName, predicate: AttributePredicate)

object BindingDescriptor {

  import CSSSelectorParser.*
  import Private.*

  def getAtts(controlElem: NodeInfo): SeqView[(QName, String)] =
    (controlElem /@ @*).view.map(attNode => (attNode.qName, attNode.stringValue))

  def updateAttAppearance(atts: Iterable[(QName, String)], newAppearanceOpt: Option[String]): List[(QName, String)] =
    newAppearanceOpt.map(APPEARANCE_QNAME ->) ++: atts.filterNot(_._1 == APPEARANCE_QNAME).toList

  val EqualOrTokenAttributePredicateFilter: AttributePredicate => Boolean = {
    case AttributePredicate.Equal(_) | AttributePredicate.Token(_) => true
    case _ => false
  }

  trait AppearanceExtractor {

    // Scala 3: trait parameters
    def getAtts: Iterable[(QName, String)]
    def getFilterAppearance: AttributePredicate => Boolean

    def unapply(appearanceOpt: Option[AttributePredicate]): Option[String] =
      appearanceOpt
        .filter(getFilterAppearance)
        .flatMap { predicate =>
          getAtts.collectFirst {
            case (attName, attValue)
              if attName == APPEARANCE_QNAME && attValueMatches(predicate, attValue)
                => attValue
          }
        }
  }

  trait FirstAttExtractor {

    def getAtts: Iterable[(QName, String)] // Scala 3: trait parameter

    def unapply(attDescOpt: Option[BindingAttributeDescriptor]): Option[BindingAttributeDescriptor] =
      attDescOpt.flatMap { attDesc =>
        getAtts.collectFirst {
          case (attName, attValue)
            if attName == attDesc.name && attValueMatches(attDesc.predicate, attValue)
              => attDesc
        }
      }
  }

  // Custom extractor to extract the `appearance` attribute and other attributes. Assumptions:
  // - at most one `appearance` attribute
  // - at most one other attribute
  // - at most one datatype with `:xxf-type()`
  // - order of attribute filters doesn't matter
  private trait FilterExtractor {

    def getNs: NamespaceMapping

    def unapply(attFilters: List[Filter]): Option[(Option[AttributePredicate], Option[BindingAttributeDescriptor], Option[QName])] = {

      val appearanceOpt =
        attFilters.collectFirst {
          case Filter.Attribute(attTypeSelector, attPredicate)
            if qNameFromElementSelector(Some(attTypeSelector), getNs).contains(APPEARANCE_QNAME) =>
              attPredicate
        }

      val otherOpt =
        attFilters.collectFirst {
          case Filter.Attribute(attTypeSelector, attPredicate)
            if ! qNameFromElementSelector(Some(attTypeSelector), getNs).contains(APPEARANCE_QNAME) =>
              BindingAttributeDescriptor(
                attTypeSelector.toQName(getNs),
                attPredicate
              )
        }

      val datatypeOpt =
        attFilters.collectFirst {
          case FunctionalPseudoClassFilter("xxf-type", List(Expr.Str(datatype))) =>
            qNameFromString(datatype, getNs)
        }
        .flatten

      if (appearanceOpt.isDefined || otherOpt.isDefined || datatypeOpt.isDefined)
        Some((appearanceOpt, otherOpt, datatypeOpt))
      else
        None
    }
  }

  // If found return the binding and a Boolean flag indicating if the match was by name only.
  def findMostSpecificBinding[T](
    qName           : QName,
    datatypeMatch   : DatatypeMatch,
    atts            : Iterable[(QName, String)],
    filterAppearance: AttributePredicate => Boolean = _ => true
  )(implicit
    index           : BindingIndex[T]
  ): Option[(T, Boolean)] = {

    object AppearanceExtractor extends AppearanceExtractor {
      def getAtts: Iterable[(QName, String)] = atts
      def getFilterAppearance: AttributePredicate => Boolean = filterAppearance
    }

    // Depending on `DatatypeMatch`, match on the datatype. The extractor, if it matches, returns a weight which can
    // be 0 or 1. The idea is that if the binding doesn't specify a datatype, and we passed a datatype in, the binding
    // does match, but with a priority less than if the binding specifies a datatype, and it matches the datatype
    // passed.
    object TypeExtractor {

      val (datatype1Opt, datatype2Opt) =
        datatypeMatch match {
          case DatatypeMatch.Exclude         => (None, None)
          case DatatypeMatch.Match(datatype) => (Some(datatype), Some(Types.getVariationTypeOrKeep(datatype)))
        }

      def unapply(bindingDatatypeOpt: Option[QName]): Option[Int] =
        datatypeMatch match {
          case DatatypeMatch.Exclude | DatatypeMatch.Match(_)
            if bindingDatatypeOpt.isEmpty =>
              Some(0)
          case DatatypeMatch.Match(_)
            if bindingDatatypeOpt == datatype1Opt || bindingDatatypeOpt == datatype2Opt =>
              Some(1)
          case _ =>
              None
        }
    }

    object FirstAttExtractor extends FirstAttExtractor {
      def getAtts: Iterable[(QName, String)] = atts
    }

    // This and `ScoreOrdering` assign a score to a `BindingDescriptor` given the incoming parameters. The idea is that
    // the more stuff matches, the higher the score. We keep track separately of whether the element name matched, vs.
    // the other matches.
    //
    // Priorities:
    //
    //     foo:xxf-type('xs:date')[bar ~= baz][appearance ~= qux]                  >
    //     foo:xxf-type('xs:date')[bar ~= baz], foo[bar ~= baz][appearance ~= qux] >
    //     [bar ~= baz][appearance ~= qux]                                         >
    //     foo:xxf-type('xs:date'), foo[bar ~= baz]                                >
    //     [bar ~= baz]                                                            >
    //     foo
    //
    // There can be matches with the same priority, in which case we take the first we find. We currently don't have
    // a deterministic way to break ties, so that kind of matches should be avoided in XBL selectors.
    //
    // It doesn't look like the spec specifies that:
    //
    //     [bar ~= baz] > [bar]
    //
    // But that would be reasonable. We don't handle this yet.
    //
    // See also: https://drafts.csswg.org/selectors-4/#specificity
    def computeScore(bd: BindingDescriptor): Option[Score] = {
      val nameMatch    =
        bd.elementName.contains(qName)
      val otherMatches =
        bd match {
          case BindingDescriptor(_, TypeExtractor(w), AppearanceExtractor(_), FirstAttExtractor(_)) => Some(2 + w)
          case BindingDescriptor(_, TypeExtractor(w), AppearanceExtractor(_), None)                 => Some(1 + w)
          case BindingDescriptor(_, TypeExtractor(w), None, FirstAttExtractor(_))                   => Some(1 + w)
          case BindingDescriptor(_, TypeExtractor(w), None, None)                                   => Some(0 + w)
          case _                                                                                    => None
        }

      (bd.elementName.isEmpty || nameMatch) && otherMatches.isDefined option
        Score(
          nameMatch    = nameMatch,
          otherMatches = otherMatches.get
        )
    }

    def scoreBindings: Option[(Score, T)] = {
      var bestScoreOpt: Option[(Score, T)] = None
      index.iterateDescriptors2.foreach { bd =>
        computeScore(bd._1).foreach { newScore =>
          if (bestScoreOpt.isEmpty || bestScoreOpt.exists(s => ScoreOrdering.compare(newScore, s._1) == 1)) {
            bestScoreOpt = Some(newScore, bd._2)
          }
        }
      }
      bestScoreOpt
    }

    scoreBindings.map(s => s._2 -> (s._1.nameMatch && s._1.otherMatches == 0))
  }

  def findMostSpecificBindingUseEqualOrToken(
    searchElemName: QName,
    datatypeMatch : DatatypeMatch,
    searchAtts    : Iterable[(QName, String)],
  )(implicit
    index         : BindingIndex[BindingDescriptor]
  ): Option[BindingDescriptor] =
    findMostSpecificBinding(
      qName            = searchElemName,
      datatypeMatch    = datatypeMatch,
      atts             = searchAtts,
      filterAppearance = EqualOrTokenAttributePredicateFilter
    ).map(_._1)

  // Find the virtual name and appearance for the control given its element name, datatype, and appearances.
  // See `BindingDescriptorTest` for examples.
  //
  // The virtual name is the name the control would have if we natively supported datatype bindings. We don't
  // support them because datatypes can change dynamically at runtime and that is a big change, see:
  // https://github.com/orbeon/orbeon-forms/issues/1248
  def findVirtualNameAndAppearance(
    searchElemName: QName,
    searchDatatype: QName,
    searchAtts    : Iterable[(QName, String)],
  )(implicit
    index            : BindingIndex[BindingDescriptor]
  ): (QName, Option[String]) = {

    val mostSpecificDescriptorOpt =
      findMostSpecificBindingUseEqualOrToken(searchElemName, DatatypeMatch.Exclude, searchAtts)

    def fromRelatedDescriptors: Option[(QName, Option[String])] =
      for {
        // Find descriptor as would be found at form compilation time, except with `Equal`/`Token` attribute predicates
        // only.
        descriptor <- mostSpecificDescriptorOpt
        _ = assert(descriptor.datatype.isEmpty)
        // See comments in https://github.com/orbeon/orbeon-forms/issues/2479
        if descriptor.attOpt.isEmpty && descriptor.appearanceOpt.isEmpty // only a direct binding can be an alias for another related binding
        relatedDescriptors = findRelatedDescriptors(descriptor).filterNot(_ == descriptor)
        BindingDescriptor(elemNameOpt, _, appearanceOpt, _) <- findRelatedVaryNameAndAppearance(searchDatatype, searchAtts, relatedDescriptors)
        elemName <- elemNameOpt
      } yield
        (
          elemName,
          appearanceOpt.collect {
            case AttributePredicate.Equal(value) => value
            case AttributePredicate.Token(value) => value
          }
        )

    def fromMostSpecificDescriptor: Option[(QName, Option[String])] =
      for {
        descriptor <- mostSpecificDescriptorOpt
        elemName   <- descriptor.elementName
      } yield
        (
          elemName,
          descriptor.appearanceOpt.collect {
            case AttributePredicate.Equal(value) => value
            case AttributePredicate.Token(value) => value
          }
        )

    def fromSearchParams: (QName, Option[String]) =
      (
        searchElemName,
        searchAtts
          .collectFirst { case (APPEARANCE_QNAME, appearance) => appearance.tokenizeToSet.headOption }
          .flatten
      )

    fromRelatedDescriptors
      .orElse(fromMostSpecificDescriptor)
      .getOrElse(fromSearchParams)
  }

  // Example: `fr|number`, `xf|textarea`
  def directBindingPF(
    ns     : NamespaceMapping,
    binding: Option[NodeInfo]
  ): PartialFunction[Selector, BindingDescriptor] = {
    case
      Selector(
        ElementWithFiltersSelector(
          Some(TypeSelector(NsType.Specific(prefix), localname)),
          Nil
        ),
        Nil
      ) =>
        BindingDescriptor(
          elementName =  Some(QName(localname, prefix, ns.mapping(prefix))),
          datatype      = None,
          appearanceOpt = None,
          attOpt        = None
        )(binding)
  }

  // Examples:
  //
  // - `xf:select1[appearance ~= full]`
  // - `[appearance ~= character-counter]`
  // - `xf|textarea[mediatype = 'text/html']`
  // - `fr|attachment[multiple ~= true]`
  // - `xf:select1[appearance ~= full][selection = open]`
  def attributeBindingPF(
    ns     : NamespaceMapping,
    binding: Option[NodeInfo],
    includeBindingsWithDatatype: Boolean
  ): PartialFunction[Selector, BindingDescriptor] = {

    object FilterExtractor extends FilterExtractor {
      def getNs: NamespaceMapping = ns
    }

    {
      case
        Selector(
          ElementWithFiltersSelector(
            elemTypeSelectorOpt,
            FilterExtractor(appearanceOpt, otherOpt, datatypeOpt),
          ),
          Nil
        ) if includeBindingsWithDatatype || datatypeOpt.isEmpty =>
        BindingDescriptor(
          elementName   = qNameFromElementSelector(elemTypeSelectorOpt, ns),
          datatype      = datatypeOpt,
          appearanceOpt = appearanceOpt,
          attOpt        = otherOpt
        )(binding)
    }
  }

  def getAllRelevantDescriptors(bindings: Iterable[NodeInfo]): Iterable[BindingDescriptor] =
    getAllSelectorsWithPF(
      bindings,
      (ns, binding) =>
        directBindingPF              (ns, Some(binding)) orElse
        datatypeBindingPF            (ns, Some(binding)) orElse
        attributeBindingPF           (ns, Some(binding), includeBindingsWithDatatype = true) orElse
        datatypeAndAttributeBindingPF(ns, Some(binding))
    )

  def buildIndexFromBindingDescriptors(
    descriptors: Iterable[BindingDescriptor]
  ): BindingIndex[BindingDescriptor] =
    descriptors.foldLeft(BindingIndex[BindingDescriptor](Nil, Nil, Nil)) { case (index, binding) =>
      BindingIndex.indexBindingDescriptor(index, binding, binding, includeBindingsWithDatatype = true)
    }

  def findRelatedDescriptors(
    searchDescriptor: BindingDescriptor
  )(implicit
    index           : BindingIndex[BindingDescriptor]
  ): Iterable[BindingDescriptor] =
    index.iterateRelatedDescriptors(searchDescriptor).toList

  private object Private {

    // The `xs:string` and `xf:string` types are default types and considered the same as no type specification
    private val StringQNames: Set[QName] = Set(XMLConstants.XS_STRING_QNAME, XFORMS_STRING_QNAME)

    case class Score(nameMatch: Boolean, otherMatches: Int)

    object ScoreOrdering extends Ordering[Score] {
      override def compare(x: Score, y: Score): Int =
        if (x.otherMatches > y.otherMatches)
          1
        else if (x.otherMatches < y.otherMatches)
          -1
        else if (x.nameMatch && ! y.nameMatch)
          1
        else if (! x.nameMatch && y.nameMatch)
          -1
        else
          0
    }

    def attValueMatches(attPredicate: AttributePredicate, attValue: String): Boolean = attPredicate match {
      case AttributePredicate.Exist           => true
      case AttributePredicate.Equal   (value) => attValue == value
      case AttributePredicate.Token   (value) => attValue.tokenizeToSet.contains(value)
      case AttributePredicate.Lang    (value) => attValue == value || attValue.startsWith(value + '-')
      case AttributePredicate.Start   (value) => value != "" && attValue.startsWith(value)
      case AttributePredicate.End     (value) => value != "" && attValue.endsWith(value)
      case AttributePredicate.Contains(value) => value != "" && attValue.contains(value)
    }

    def qNameFromElementSelector(selectorOpt: Option[SimpleElementSelector], ns: NamespaceMapping): Option[QName] =
      selectorOpt collect {
        case TypeSelector(NsType.Specific(prefix),      localname) => QName(localname, prefix, ns.mapping(prefix))
        case TypeSelector(NsType.Default | NsType.None, localname) => QName(localname)
      }

    def qNameFromString(qualifiedName: String, ns: NamespaceMapping): Option[QName] =
      qualifiedName
        .trimAllToOpt
        .flatMap(Extensions.resolveQName(ns.mapping.get, _, unprefixedIsNoNamespace = true))
        .filterNot(StringQNames)

    // Used only by `getAllRelevantDescriptors`
    // Example: `xf|input:xxf-type("xs:decimal")`
    def datatypeBindingPF(
      ns     : NamespaceMapping,
      binding: Option[NodeInfo]
    ): PartialFunction[Selector, BindingDescriptor] = {
      case
        Selector(
          ElementWithFiltersSelector(
            Some(TypeSelector(NsType.Specific(prefix), localname)),
            List(FunctionalPseudoClassFilter("xxf-type", List(Expr.Str(datatype))))
          ),
          Nil
        ) =>
          BindingDescriptor(
            elementName   = Some(QName(localname, prefix, ns.mapping(prefix))),
            datatype      = qNameFromString(datatype, ns),
            appearanceOpt = None,
            attOpt        = None
          )(binding)
    }

    // Used only by `getAllRelevantDescriptors`
    // Example: `xf|input:xxf-type('xs:date')[appearance ~= dropdowns]`
    def datatypeAndAttributeBindingPF(
      ns     : NamespaceMapping,
      binding: Option[NodeInfo]
    ): PartialFunction[Selector, BindingDescriptor] = {
      case
        Selector(
          ElementWithFiltersSelector(
            elemTypeSelectorOpt,
            List(
              FunctionalPseudoClassFilter("xxf-type", List(Expr.Str(datatype))), // Q: are we sure the functional pseudo-class is always first?
              Filter.Attribute(attTypeSelector, attPredicate)
            )
          ),
          Nil
        ) =>
          val isForAppearance = qNameFromElementSelector(Some(attTypeSelector), ns).contains(APPEARANCE_QNAME)
          BindingDescriptor(
            elementName   = qNameFromElementSelector(elemTypeSelectorOpt, ns),
            datatype      = qNameFromString(datatype, ns),
            appearanceOpt = isForAppearance option attPredicate,
            attOpt        = ! isForAppearance option
              BindingAttributeDescriptor(
                attTypeSelector.toQName(ns),
                attPredicate
              )
          )(binding)
    }

    def getAllSelectorsWithPF(
      bindings : Iterable[NodeInfo],
      collector: (NamespaceMapping, NodeInfo) => PartialFunction[Selector, BindingDescriptor]
    ): Iterable[BindingDescriptor] = {

      def getBindingSelectorsAndNamespaces(bindingElem: NodeInfo): (String, NamespaceMapping) =
        (bindingElem attValue "element", NamespaceMapping(bindingElem.namespaceMappings.toMap))

      def descriptorsForSelectors(selectors: String, ns: NamespaceMapping, binding: NodeInfo): List[BindingDescriptor] =
        CSSSelectorParser.parseSelectors(selectors) collect collector(ns, binding)

      for {
        binding         <- bindings
        (selectors, ns) = getBindingSelectorsAndNamespaces(binding)
        descriptor      <- descriptorsForSelectors(selectors, ns, binding)
      } yield
        descriptor
    }

    // Rules:
    //
    // - Always return a descriptor which has an element name (so exclude things like just `[foo = bar]`).
    // - If the descriptor has and attribute filter, it must match based on attributes.
    // - Try to pick, among the related descriptors, the most specific one.
    //
    // In practice, for related bindings, we only expect to have:
    //
    // - a direct name: `fr|number`
    // - and
    //   - one or more selectors with types: `xf|input:xxf-type('xs:decimal')`, `xf|input:xxf-type('xs:integer')`
    //   - or `xf|input:xxf-type('xs:date')[appearance ~= dropdowns]`
    //   - or similar with attribute and appearance
    //
    def findRelatedVaryNameAndAppearance(
      searchDatatype    : QName,
      atts              : Iterable[(QName, String)],
      relatedDescriptors: Iterable[BindingDescriptor]
    ): Option[BindingDescriptor] = {

      val Datatype1 = searchDatatype
      val Datatype2 = Types.getVariationTypeOrKeep(searchDatatype)

      object FirstAttExtractor extends FirstAttExtractor {
        def getAtts: Iterable[(QName, String)] = atts
      }

      def findWithNameDatatypeAppearanceAndAtt: Option[BindingDescriptor] =
        relatedDescriptors.collectFirst {
          case
            descriptor @ BindingDescriptor(
              Some(_),
              Some(Datatype1 | Datatype2),
              Some(_),
              FirstAttExtractor(_)
            ) => descriptor
        }

      def findWithNameDatatypeAndAtt: Option[BindingDescriptor] =
        relatedDescriptors.collectFirst {
          case
            descriptor @ BindingDescriptor(
              Some(_),
              Some(Datatype1 | Datatype2),
              None,
              FirstAttExtractor(_)
            ) => descriptor
        }

      def findWithNameDatatypeAndAppearance: Option[BindingDescriptor] =
        relatedDescriptors.collectFirst {
          case
            descriptor @ BindingDescriptor(
              Some(_),
              Some(Datatype1 | Datatype2),
              Some(_),
              None
            ) => descriptor
        }

      def findWithNameAndDatatype: Option[BindingDescriptor] =
        relatedDescriptors.collectFirst {
          case
            descriptor @ BindingDescriptor(
              Some(_),
              Some(Datatype1 | Datatype2),
              None,
              None
            ) => descriptor
        }

      def findWithNameAppearanceAndAtt: Option[BindingDescriptor] =
        relatedDescriptors.collectFirst {
          case
            descriptor @ BindingDescriptor(
              Some(_),
              None,
              Some(_),
              FirstAttExtractor(_)
            ) => descriptor
        }

      def findWithNameAndAtt: Option[BindingDescriptor] =
        relatedDescriptors.collectFirst {
          case
            descriptor @ BindingDescriptor(
              Some(_),
              None,
              None,
              FirstAttExtractor(_)
            ) => descriptor
        }

      def findWithNameAndAppearance: Option[BindingDescriptor] =
        relatedDescriptors.collectFirst {
          case
            descriptor @ BindingDescriptor(
              Some(_),
              None,
              Some(_),
              None
            ) => descriptor
        }

      findWithNameDatatypeAppearanceAndAtt orElse
        findWithNameDatatypeAndAtt         orElse
        findWithNameDatatypeAndAppearance  orElse
        findWithNameAndDatatype            orElse
        findWithNameAppearanceAndAtt       orElse
        findWithNameAndAtt                 orElse
        findWithNameAndAppearance
    }
  }
}
