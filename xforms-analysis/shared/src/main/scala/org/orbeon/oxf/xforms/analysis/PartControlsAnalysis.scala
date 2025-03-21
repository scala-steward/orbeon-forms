/**
 *  Copyright (C) 2007 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.analysis

import org.orbeon.dom
import org.orbeon.oxf.xforms.analysis.controls.*
import org.orbeon.oxf.xforms.analysis.model.Model
import org.orbeon.saxon.om

import scala.collection.mutable


trait PartControlsAnalysis extends TransientState {

  self =>

  def findControlAnalysis(prefixedId: String): Option[ElementAnalysis]

  def wrapElement(elem: dom.Element): om.NodeInfo

  def getControlAnalysis(prefixedId: String): ElementAnalysis =
    controlAnalysisMap.get(prefixedId).orNull

  val controlAnalysisMap: mutable.LinkedHashMap[String, ElementAnalysis] =
    mutable.LinkedHashMap[String, ElementAnalysis]()

  val controlTypes: mutable.HashMap[String, mutable.LinkedHashMap[String, ElementAnalysis]] =
    mutable.HashMap[String, mutable.LinkedHashMap[String, ElementAnalysis]]() // `type` -> `Map` of `prefixedId` -> `ElementAnalysis`

  def iterateControlsNoModels: Iterator[ElementAnalysis] =
    for (control <- controlAnalysisMap.valuesIterator if ! control.isInstanceOf[Model] && ! control.isInstanceOf[RootControl])
      yield control

  // Special handling of attributes
  var _attributeControls: Map[String, Map[String, AttributeControl]] = Map.empty

  def indexAttributeControls(attributes: Iterable[AttributeControl]): Unit =
    if (attributes.nonEmpty) {

      case class AttributeDetails(forPrefixedId: String, attributeName: String, attributeControl: AttributeControl)

      val triples =
        for (attributeControl <- attributes)
          yield AttributeDetails(attributeControl.forPrefixedId, attributeControl.attributeName, attributeControl)

      // Nicely group the results in a two-level map
      // NOTE: We assume only one control per forPrefixedId/attributeName combination, hence the `assert`
      val newAttributes =
        triples
          .groupBy(_.forPrefixedId)
          .view
          .mapValues(_.groupBy(_.attributeName).view.mapValues(_.ensuring(_.size == 1).head.attributeControl))

      // Accumulate new attributes into existing map by combining values for a given "for id"
      // NOTE: mapValues above ok, since we accumulate the values below into new immutable Maps.
      _attributeControls = newAttributes.foldLeft(_attributeControls) {
        case (existingMap, (forId, newAttributes)) =>
          val existingAttributes = existingMap.getOrElse(forId, Map[String, AttributeControl]())
          existingMap + (forId -> (existingAttributes ++ newAttributes))
      }
    }

  private var _select1Groups: Map[String, List[SelectionControl]] = Map.empty

  def indexSelect1Groups(select1Groups: mutable.Map[String, mutable.Buffer[SelectionControl]]): Unit =
    _select1Groups = select1Groups.foldLeft(_select1Groups) {
      case (existingMap, (groupName, select1Group)) =>
        val existingSelect1Group = existingMap.getOrElse(groupName, Nil)
        existingMap + (groupName -> (existingSelect1Group ++ select1Group))
    }

  def getSelect1Groups(groupName: String): List[SelectionControl] =
    _select1Groups.getOrElse(groupName, Nil)

  def controlElement(prefixedId: String): Option[om.NodeInfo] =
    findControlAnalysis(prefixedId) map { control =>
      wrapElement(control.element)
    }

  def hasAttributeControl(prefixedForAttribute: String): Boolean =
    _attributeControls.contains(prefixedForAttribute)

  def getAttributeControl(prefixedForAttribute: String, attributeName: String): AttributeControl =
    _attributeControls.get(prefixedForAttribute).flatMap(_.get(attributeName)).orNull // TODO: `null`

  def controlsByName(controlName: String): Iterable[ElementAnalysis] =
    controlTypes.get(controlName).toList.flatMap(_.values)

  // Repeats
  def repeats: Iterable[RepeatControl] =
    controlTypes.get("repeat").map(_.values.map(_.asInstanceOf[RepeatControl])).getOrElse(Nil)

  def getRepeatHierarchyString(ns: String): String =
    controlTypes
      .get("repeat")
      .map { repeats =>
        val repeatsWithAncestors =
          for {
            repeat               <- repeats.values
            namespacedPrefixedId = s"$ns${repeat.prefixedId}"
            ancestorRepeat       = repeat.ancestorRepeatsAcrossParts.headOption
          } yield
            ancestorRepeat match {
              case Some(ancestorRepeat) => s"$namespacedPrefixedId $ns${ancestorRepeat.prefixedId}"
              case None                 => namespacedPrefixedId
            }

        repeatsWithAncestors mkString ","
      }
      .getOrElse("")

  def getTopLevelControls: List[ElementAnalysis] = List(controlTypes("root").values.head)

  override def freeTransientState(): Unit = {
    super.freeTransientState()

    for (controlAnalysis <- controlAnalysisMap.values)
      controlAnalysis.freeTransientState()
  }
}
