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
package org.orbeon.oxf.fr

import org.orbeon.dom
import org.orbeon.dom.Element
import org.orbeon.dom.saxon.DocumentWrapper
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.fr.process.FormRunnerActionsCommon
import org.orbeon.oxf.pipeline.api.PipelineContext
import org.orbeon.oxf.processor.SimpleProcessor
import org.orbeon.oxf.properties.{Properties, PropertySet}
import org.orbeon.oxf.util.StringUtils.*
import org.orbeon.oxf.util.{CoreCrossPlatformSupport, XPath}
import org.orbeon.oxf.xml.dom.Support
import org.orbeon.oxf.xml.{TransformerUtils, XMLReceiver}
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.NodeInfoConversions.unsafeUnwrapElement
import org.orbeon.scaxon.SimplePath.*

// Processor to replace or add resources based on properties and form metadata.
//
// A property looks like: oxf.fr.resource.*.*.en.detail.labels.save
//
// At the moment, only detail/messages/* resources are extracted from the form metadata.
//
// NOTE: We used to do this in XSLT, but when it came to implement *adding* missing resources, the level of complexity
// increased too much and readability would have suffered so we rewrote in Scala.
class ResourcesPatcher extends SimpleProcessor  {

  def generateData(pipelineContext: PipelineContext, xmlReceiver: XMLReceiver): Unit = {

    // Read inputs
    val resourcesDocument    = readInputAsOrbeonDom(pipelineContext, "resources")
    val formMetadataDocument = readInputAsOrbeonDom(pipelineContext, "data")
    val instanceElem         = new DocumentWrapper(readInputAsOrbeonDom(pipelineContext, "instance"), null, XPath.GlobalConfiguration) / *

    val appForm = AppForm(
      instanceElem / "app"  stringValue,
      instanceElem / "form" stringValue
    )

    val langs = CoreCrossPlatformSupport.externalContext.getRequest.getFirstParamAsString("langs") map (_.splitTo[Set](","))

    // Transform and write out the document
    ResourcesPatcher.transform(resourcesDocument, formMetadataDocument, appForm, langs)(Properties.instance.getPropertySet)
    TransformerUtils.writeOrbeonDom(resourcesDocument, xmlReceiver)
  }
}

object ResourcesPatcher {

  private val Prefix   = "oxf.fr.resource"
  private val WildCard = "*"

  def transform(
    resourcesDocument    : dom.Document,
    formMetadataDocument : dom.Document,
    appForm              : AppForm,
    langsOpt             : Option[Set[String]] = None
  )(implicit
    properties           : PropertySet
  ): Unit = {

    // Start by filtering out unwanted languages if specified
    langsOpt foreach { langs =>
      resourcesDocument.getRootElement.elements.filterNot(e => langs(e.attributeValue(XMLNames.XMLLangQName))) foreach
        (_.detach())
    }

    def resourceElems: Seq[NodeInfo] =
      new DocumentWrapper(resourcesDocument, null, XPath.GlobalConfiguration).rootElement / "resource"

    val originalResourceElems = resourceElems

    // All languages remaining in the resources document
    val allLanguages = originalResourceElems attValue XMLNames.XMLLangQName

    // Retrieve resources from properties and form metadata, and expand wildcards into concrete languages
    val propertyResources = resourcesWithConcreteLanguage(allLanguages, resourcesFromProperties(properties, appForm))
    val metadataResources = resourcesWithConcreteLanguage(allLanguages, resourcesFromMetadata(formMetadataDocument))

    // Merge resources from properties and form metadata, giving higher priority to form metadata
    val mergedExtraResources  = mergedResources(resourcesGroups = Seq(metadataResources, propertyResources))

    // At the moment, wildcards in form metadata have a higher priority than specific languages in properties; this can
    // be changed if needed (by merging the resources first and then only expanding wildcards into specific languages)

    // Languages missing in the resources document, but present in the properties and/or form metadata
    val extraResourceLanguages = mergedExtraResources.map(_.lang).toSet
    val missingLanguagesInResources = (extraResourceLanguages -- allLanguages.toSet).intersect(langsOpt.getOrElse(extraResourceLanguages))

    addMissingLanguages(resourcesDocument, appForm, missingLanguagesInResources)

    val updatedResourceElems = resourceElems

    def resourceElemsForLang(lang: String) =
      updatedResourceElems filter (_.attValueOpt(XMLNames.XMLLangQName) contains lang) map unsafeUnwrapElement

    // Update or create elements and set values
    for {
      Resource(lang, path, value, isHtml) <- mergedExtraResources
      rootForLang                         <- resourceElemsForLang(lang)
    } locally {
      val elem = Support.ensurePath(rootForLang, path map dom.QName.apply)
      elem.attributeOpt("todo") foreach elem.remove
      elem.setText(value)
      if (isHtml) {
        // Only add attribute if true, false by default (see FormRunnerActionsCommon.isMessageInHtml)
        elem.addAttribute("html", "true")
      }
    }

    def hasTodo(e: NodeInfo) =
      e.attValueOpt("todo") contains "true"

    def isBracketed(s: String) =
      s.startsWith("[") && s.endsWith("]")

    // Add missing brackets for resources marked with "todo"
    for {
      e <- updatedResourceElems descendant *
      if ! e.hasChildElement && hasTodo(e) && ! isBracketed(e.stringValue)
      elem = unsafeUnwrapElement(e)
    } locally {
      elem.attributeOpt("todo") foreach elem.remove
      elem.setText(s"[${elem.getText}]")
    }
  }

  def addMissingLanguages(
    resourcesDocument : dom.Document,
    appForm           : AppForm,
    missingLanguages  : Set[String]
  ): Unit = {
    val defaultLang = FormRunnerCommon.frc.getDefaultLang(Some(appForm))

    def resourceForLang(lang: String): Option[dom.Element] =
      resourcesDocument.getRootElement.elements.find(_.attributeValue(XMLNames.XMLLangQName) == lang)

    def resourceToCopy: Element =
      resourceForLang(defaultLang)
        .orElse(resourceForLang("en"))
        .getOrElse(throw new OXFException(s"Could not find resource for default language $defaultLang or English"))

    missingLanguages.foreach { missingLanguage =>
      val newResource = resourceToCopy.deepCopy
      newResource.attribute(XMLNames.XMLLangQName).setValue(missingLanguage)
      resourcesDocument.getRootElement.add(newResource)
    }
  }

  case class ResourceKey(lang: String, path: Seq[String])
  case class Resource   (lang: String, path: Seq[String], value: String, isHtml: Boolean) {
    def key: ResourceKey = ResourceKey(lang, path)
  }

  def resourcesFromProperties(properties: PropertySet, appForm: AppForm): Seq[Resource] = {

    val propertyNames =
      properties
        .propertiesStartsWith((Prefix :: appForm.toList).mkString("."))
        .filterNot(_.endsWith(".html"))

    // In 4.6 summary/detail buttons are at the top level
    def filterPathForBackwardCompatibility(path: List[String]): List[String] = path match {
      case ("detail" | "summary") :: "buttons" :: _ => path drop 1
      case _ => path
    }

    propertyNames flatMap { propertyName =>

      val _ :: _ :: _ :: _ :: _ :: lang :: resourceTokens = propertyName.splitTo[List](".")

      // Property name with possible `*` replaced by actual app/form name
      val expandedPropertyName = Prefix :: appForm.toList ::: lang :: resourceTokens mkString "."

      // Had a case where value was null (more details would be useful)
      val value = properties.getNonBlankString(expandedPropertyName)

      // TODO: this doesn't work, as the base (i.e. suffix-less) property doesn't seem to be listed by
      //  propertiesStartsWith if a property with the .html suffix exists
      val isHtml = properties.getBoolean(expandedPropertyName + ".html", default = false)

      value.map(Resource(lang, filterPathForBackwardCompatibility(resourceTokens), _, isHtml))
    }
  }

  def resourcesFromMetadata(formMetadataDocument : dom.Document): Seq[Resource] =
    for {
      resources <- new DocumentWrapper(formMetadataDocument, null, XPath.GlobalConfiguration) / "resources"
      resource  <- resources / "resource"
      lang      =  resource attValue XMLNames.XMLLangQName
      messages  <- resource / "messages"
      message   <- messages / "message"
    } yield
      Resource(
        lang   = lang,
        path   = "detail" :: "messages" :: message.attValue("name") :: Nil,
        value  = message.getStringValue,
        isHtml = FormRunnerActionsCommon.isMessageInHtml(message)
      )

  def resourcesWithConcreteLanguage(allLanguages: Seq[String], resources: Seq[Resource]): Map[ResourceKey, Resource] = {
    // Process resources with concrete and wildcard languages separately, as we give a higher priority to concrete languages
    val (concrete, wildCard) = resources.map(r => r.key -> r).toMap.partition(_._1.lang != WildCard)

    val concreteFromWildcard = wildCard.flatMap { case (_, resource) =>
      val concreteLanguagesForPath = concrete.keys.filter(_.path == resource.path).map(_.lang).toSet

      // Add missing languages for this path (i.e. do not overwrite existing resources with concrete languages for that path)
      allLanguages.filterNot(concreteLanguagesForPath).map { concreteLanguage =>
        val resourceWithConcreteLanguage = resource.copy(lang = concreteLanguage)
        resourceWithConcreteLanguage.key -> resourceWithConcreteLanguage
      }
    }

    concrete ++ concreteFromWildcard
  }

  // Merge resources from different groups, giving higher priority to groups appearing first in the sequence
  def mergedResources(resourcesGroups: Seq[Map[ResourceKey, Resource]]): Seq[Resource] = {
    // Sort the keys to ensure that any missing resource is inserted in a consistent order (mainly useful for tests)
    val allKeys = resourcesGroups.flatMap(_.keys).distinct.sortBy(k => (k.lang, k.path.mkString("/")))

    allKeys.map { key =>
      // Keep the resource from the first group that contains it
      resourcesGroups.find(_.contains(key)).get(key)
    }
  }
}