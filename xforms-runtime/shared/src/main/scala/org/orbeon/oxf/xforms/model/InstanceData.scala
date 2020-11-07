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
package org.orbeon.oxf.xforms.model

import java.{lang => jl, util => ju}

import org.orbeon.datatypes.LocationData
import org.orbeon.dom._
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.util.StaticXPath
import org.orbeon.oxf.util.StaticXPath.VirtualNodeType
import org.orbeon.scaxon.NodeInfoConversions.unwrapNode
import org.orbeon.oxf.xforms.analysis.model.ModelDefs
import org.orbeon.oxf.xml.XMLConstants
import org.orbeon.oxf.xml.dom.Extensions._
import org.orbeon.saxon.om

import scala.jdk.CollectionConverters._
import scala.util.control.Breaks.{break, breakable}


/**
 * Instances of this class are used to annotate XForms instance nodes with MIPs and other information.
 *
 * Previously, all element and attribute nodes in every XForms instance were annotated with instances of this class.
 * Annotations are now done lazily when needed in order to reduce the number of objects created. This has a positive
 * impact on memory usage and garbage collection. This is also why most methods in this class are static.
 *
 * Since 2010-12, this now points back to bind nodes, which store bind MIPs directly.
 */
object InstanceData {

  def addBindNode(nodeInfo: om.NodeInfo, bindNode: BindNode): Unit = {
    val instanceData = getOrCreateInstanceData(nodeInfo, forUpdate = false)
    if (instanceData ne ReadonlyLocalInstanceData) {
      // only register ourselves if we are not a readonly node
      if (instanceData.bindNodes eq null)
        instanceData.bindNodes = ju.Collections.singletonList(bindNode)
      else if (instanceData.bindNodes.size == 1) {
        val oldBindNode = instanceData.bindNodes.get(0)
        instanceData.bindNodes = new ju.ArrayList[BindNode](4) // hoping that situations where many binds point to same node are rare
        instanceData.bindNodes.add(oldBindNode)
        instanceData.bindNodes.add(bindNode)
      }
      else instanceData.bindNodes.add(bindNode)
    }
  }

  private val ReadonlyLocalInstanceData: InstanceData = new InstanceData {
    override def getRequired: Boolean = ModelDefs.DEFAULT_REQUIRED
    override def getValid: Boolean = ModelDefs.DEFAULT_VALID
    override def getSchemaOrBindType: QName = null
    override def getInvalidBindIds: String = null
    override def getLocationData: LocationData = null
  }

  def setTransientAnnotation(nodeInfo: om.NodeInfo, name: String, value: String): Unit = {
    val instanceData = getOrCreateInstanceData(nodeInfo, forUpdate = true)
    instanceData.setTransientAnnotation(name, value)
  }

  def getTransientAnnotation(node: Node, name: String): String = {
    val existingInstanceData = getLocalInstanceData(node)
    if (existingInstanceData eq null)
      null
    else
      existingInstanceData.getTransientAnnotation(name)
  }

  def collectAllClientCustomMIPs(nodeInfo: om.NodeInfo): Option[Predef.Map[String, String]] = {
    val existingInstanceData = getLocalInstanceData(nodeInfo, forUpdate = false)
    Option(existingInstanceData) map (_.collectAllClientCustomMIPs)
  }

  def findCustomMip(nodeInfo: om.NodeInfo, mipName: String): Option[String] = {
    val existingInstanceData = getLocalInstanceData(nodeInfo, forUpdate = false)
    Option(existingInstanceData) flatMap (_.findCustomMip(mipName))
  }

  def getInheritedRelevant(nodeInfo: om.NodeInfo): Boolean =
    if (nodeInfo.isInstanceOf[VirtualNodeType])
      getInheritedRelevant(unwrapNode(nodeInfo).getOrElse(throw new IllegalArgumentException))
    else if (nodeInfo ne null)
      ModelDefs.DEFAULT_RELEVANT
    else
      throw new OXFException("Cannot get relevant Model Item Property on null object.")

  def getInheritedRelevant(node: Node): Boolean = { // Iterate this node and its parents. The node is non-relevant if it or any ancestor is non-relevant.
    var currentNode = node
    while (currentNode ne null) {
      val currentInstanceData = getLocalInstanceData(currentNode)
      val currentRelevant =
        if (currentInstanceData eq null)
          ModelDefs.DEFAULT_RELEVANT
        else
          currentInstanceData.getLocalRelevant
      if (! currentRelevant)
        return false
      currentNode = currentNode.getParent
    }
    true
  }

  def getLocalRelevant(node: Node): Boolean = {
    val currentInstanceData = getLocalInstanceData(node)
    if (currentInstanceData eq null)
      ModelDefs.DEFAULT_RELEVANT
    else
      currentInstanceData.getLocalRelevant
  }

  def getRequired(nodeInfo: om.NodeInfo): Boolean = {
    val existingInstanceData = getLocalInstanceData(nodeInfo, forUpdate = false)
    if (existingInstanceData eq null)
      ModelDefs.DEFAULT_REQUIRED
    else
      existingInstanceData.getRequired
  }

  def getRequired(node: Node): Boolean = {
    val existingInstanceData = getLocalInstanceData(node)
    if (existingInstanceData eq null)
      ModelDefs.DEFAULT_REQUIRED
    else
      existingInstanceData.getRequired
  }

  def getInheritedReadonly(nodeInfo: om.NodeInfo): Boolean =
    if (nodeInfo.isInstanceOf[VirtualNodeType])
      getInheritedReadonly(unwrapNode(nodeInfo).getOrElse(throw new IllegalArgumentException))
    else if (nodeInfo ne null)
      true // Default for non-mutable nodes is to be read-only
    else
      throw new OXFException("Cannot get readonly Model Item Property on null object.")

  def getInheritedReadonly(node: Node): Boolean = { // Iterate this node and its parents. The node is readonly if it or any ancestor is readonly.
    var currentNode = node
    while (currentNode ne null) {
      val currentInstanceData = getLocalInstanceData(currentNode)
      val currentReadonly =
        if (currentInstanceData eq null)
          ModelDefs.DEFAULT_READONLY
        else
          currentInstanceData.getLocalReadonly
      if (currentReadonly)
        return true
      currentNode = currentNode.getParent
    }
    false
  }

  def getValid(nodeInfo: om.NodeInfo): Boolean = {
    val existingInstanceData = getLocalInstanceData(nodeInfo, forUpdate = false)
    if (existingInstanceData eq null)
      ModelDefs.DEFAULT_VALID
    else
      existingInstanceData.getValid
  }

  def getValid(node: Node): Boolean = {
    val existingInstanceData = getLocalInstanceData(node)
    if (existingInstanceData eq null)
      ModelDefs.DEFAULT_VALID
    else
      existingInstanceData.getValid
  }

  def setBindType(nodeInfo: om.NodeInfo, `type`: QName): Unit =
    getOrCreateInstanceData(nodeInfo, forUpdate = true).bindType = `type`

  def setSchemaType(node: Node, `type`: QName): Unit =
    getOrCreateInstanceData(node).schemaType = `type`

  def getType(nodeInfo: om.NodeInfo): QName = {

    // Try schema or bind type
    val existingInstanceData = getLocalInstanceData(nodeInfo, forUpdate = false)
    if (existingInstanceData ne null) {
      val schemaOrBindType = existingInstanceData.getSchemaOrBindType
      if (schemaOrBindType ne null)
        return schemaOrBindType
    }

    // No type was assigned by schema or MIP, try xsi:type
    if (nodeInfo.getNodeKind == org.w3c.dom.Node.ELEMENT_NODE) {
      // Check for xsi:type attribute
      // NOTE: Saxon 9 has new code to resolve such QNames
      val typeQName = nodeInfo.getAttributeValue(om.StandardNames.XSI_TYPE)
      if (typeQName ne null)
        try {
          val checker = nodeInfo.getConfiguration.getNameChecker
          val parts = checker.getQNameParts(typeQName)

          // No prefix
          if (parts(0) == "")
            return QName.apply(parts(1))

          // There is a prefix, resolve it
          val namespaceNodes = nodeInfo.iterateAxis(StaticXPath.NamespaceAxisType)
          breakable {
            while (true) {
              val currentNamespaceNode = namespaceNodes.next.asInstanceOf[om.NodeInfo]
              if (currentNamespaceNode eq null)
                break()
              val prefix = currentNamespaceNode.getLocalPart
              if (prefix == parts(0))
                return QName.apply(parts(1), "", currentNamespaceNode.getStringValue)
            }
          }
        } catch {
          case e: Exception =>
            throw new OXFException(e)
        }
    }
    null
  }

  def getType(node: Node): QName = {
    val existingInstanceData = getLocalInstanceData(node)
    if (existingInstanceData ne null) {
      val schemaOrBindType = existingInstanceData.getSchemaOrBindType
      if (schemaOrBindType ne null)
        return schemaOrBindType
    }
    node match {
      case elem: Element =>
        elem.resolveAttValueQName(XMLConstants.XSI_TYPE_QNAME, unprefixedIsNoNamespace = false).orNull // TODO: should pass true?
      case _ =>
        null
    }
  }

  def getInvalidBindIds(nodeInfo: om.NodeInfo): String = {
    val existingInstanceData = getLocalInstanceData(nodeInfo, forUpdate = false)
    if (existingInstanceData eq null)
      null
    else
      existingInstanceData.getInvalidBindIds
  }

  def setRequireDefaultValue(node: Node): Unit =
    getOrCreateInstanceData(node).requireDefaultValue = true

  def clearRequireDefaultValue(node: Node): Unit = {
    val instanceData = getLocalInstanceData(node)
    if (instanceData ne null)
      instanceData.requireDefaultValue = false
  }

  def getRequireDefaultValue(nodeInfo: om.NodeInfo): Boolean =
    getLocalInstanceData(nodeInfo, forUpdate = false).requireDefaultValue

  def removeInstanceData(node: Node): Unit =
    node match {
      case elem: Element  => elem.setData(null)
      case att: Attribute => att.setData(null)
      case _ =>
    }

  def addSchemaError(node: Node): Unit = {
    // Get or create InstanceData
    val instanceData = getOrCreateInstanceData(node)
    // Remember that the value is invalid
    instanceData.schemaInvalid = true
  }

  def clearStateForRebuild(nodeInfo: om.NodeInfo): Unit = {
    val existingInstanceData = getLocalInstanceData(nodeInfo, forUpdate = false) // not really an update since for read-only nothing changes
    if (existingInstanceData ne null) {
      existingInstanceData.bindNodes = null
      existingInstanceData.bindType = null
      existingInstanceData.schemaType = null
      existingInstanceData.schemaInvalid = false
      existingInstanceData.transientAnnotations = null
      // NOTE: Do not clear requireInitialData which must survive a rebuild
    }
  }

  def clearSchemaState(nodeInfo: om.NodeInfo): Unit = {
    val existingInstanceData = getLocalInstanceData(nodeInfo, forUpdate = false)
    if (existingInstanceData ne null) {
      existingInstanceData.schemaType = null
      existingInstanceData.schemaInvalid = false
    }
  }

  private def getOrCreateInstanceData(nodeInfo: om.NodeInfo, forUpdate: Boolean): InstanceData = {
    val existingInstanceData = getLocalInstanceData(nodeInfo, forUpdate)
    if (existingInstanceData ne null)
      existingInstanceData
    else
      createNewInstanceData(nodeInfo)
  }

  private def getOrCreateInstanceData(node: Node): InstanceData = {
    val existingInstanceData = getLocalInstanceData(node)
    if (existingInstanceData ne null)
      existingInstanceData
    else
      createNewInstanceData(node)
  }

  def getLocalInstanceData(nodeInfo: om.NodeInfo, forUpdate: Boolean): InstanceData =
    if (nodeInfo.isInstanceOf[VirtualNodeType])
      getLocalInstanceData(unwrapNode(nodeInfo).getOrElse(throw new IllegalArgumentException))
    else if ((nodeInfo ne null) && ! forUpdate)
      ReadonlyLocalInstanceData
    else if ((nodeInfo ne null) && forUpdate)
      throw new OXFException("Cannot update MIP information on non-VirtualNode NodeInfo.")
    else
      throw new OXFException("Null NodeInfo found.")

  def getLocalInstanceData(node: Node): InstanceData = {
    // Find data annotation on node
    val data =
      node match {
        case elem: Element  => elem.getData
        case att: Attribute => att.getData
        case doc: Document  => doc.getRootElement.getData // We can't store data on the `Document` object. Use root element instead.
        case _ => return null
      }
    // Make sure we return InstanceData and not something else
    data match {
      case data: InstanceData => data
      case _                  => null
    }
  }

  private def createNewInstanceData(nodeInfo: om.NodeInfo): InstanceData =
    if (nodeInfo.isInstanceOf[VirtualNodeType])
      createNewInstanceData(unwrapNode(nodeInfo).getOrElse(throw new IllegalArgumentException))
    else
      throw new OXFException("Cannot create InstanceData on non-VirtualNode NodeInfo.")

  private def createNewInstanceData(node: Node): InstanceData =
    node match {
      case elem: Element =>
        val data = InstanceData.createNewInstanceData(elem.getData)
        elem.setData(data)
        data
      case att: Attribute =>
        val data = InstanceData.createNewInstanceData(att.getData)
        att.setData(data)
        data
      case doc: Document =>
        val element = doc.getRootElement
        val data = InstanceData.createNewInstanceData(element.getData)
        element.setData(data)
        data
      case _ => // No other node type is supported
        throw new OXFException(s"Cannot create InstanceData on node type: `${Node.nodeTypeName(node)}`")
    }

  private def createNewInstanceData(existingData: Any): InstanceData =
    existingData match {
      case data: LocationData => new InstanceData(data)
      case data: InstanceData => new InstanceData(data.getLocationData)
      case _                  => new InstanceData(null)
    }
}

class InstanceData private () {

  private var locationData: LocationData = null

  // Point back to binds that impacted this node
  private var bindNodes: ju.List[BindNode] = null

  // Types set by schema or binds
  private var bindType: QName = null
  private var schemaType: QName = null

  // Schema validity: only set by schema
  private var schemaInvalid = false

  // Whether to evaluate the default value
  private var requireDefaultValue = false

  // Annotations (used only for multipart submission as of 2010-12)
  private var transientAnnotations: ju.Map[String, String] = null

  def getBindNodes: ju.List[BindNode] =
    if (bindNodes eq null)
      ju.Collections.emptyList[BindNode]
    else
      bindNodes

  private def getLocalRelevant: Boolean = {
    if ((bindNodes ne null) && bindNodes.size > 0) {
      for (bindNode <- bindNodes.asScala) {
        if (bindNode.relevant != ModelDefs.DEFAULT_RELEVANT)
          return ! ModelDefs.DEFAULT_RELEVANT
      }
    }
    ModelDefs.DEFAULT_RELEVANT
  }

  private def getLocalReadonly: Boolean = {
    if ((bindNodes ne null) && bindNodes.size > 0) {
      for (bindNode <- bindNodes.asScala) {
        if (bindNode.readonly != ModelDefs.DEFAULT_READONLY)
          return ! ModelDefs.DEFAULT_READONLY
      }
    }
    ModelDefs.DEFAULT_READONLY
  }

  def getRequired: Boolean = {
    if ((bindNodes ne null) && bindNodes.size > 0) {
      for (bindNode <- bindNodes.asScala) {
        if (bindNode.required != ModelDefs.DEFAULT_REQUIRED)
          return ! ModelDefs.DEFAULT_REQUIRED
      }
    }
    ModelDefs.DEFAULT_REQUIRED
  }

  def getValid: Boolean = {
    if (schemaInvalid)
      return false
    if ((bindNodes ne null) && bindNodes.size > 0) {
      for (bindNode <- bindNodes.asScala) {
        if (bindNode.valid != ModelDefs.DEFAULT_VALID)
          return ! ModelDefs.DEFAULT_VALID
      }
    }
    ModelDefs.DEFAULT_VALID
  }

  private def collectAllClientCustomMIPs: Map[String, String] =
    BindNode.collectAllClientCustomMIPs(bindNodes)

  private def findCustomMip(mipName: String): Option[String] =
    BindNode.findCustomMip(bindNodes, mipName)

  def getSchemaOrBindType: QName =
    if (schemaType ne null)
      schemaType
    else
      bindType

  def getInvalidBindIds: String = {
    var sb: jl.StringBuilder = null
    if ((bindNodes ne null) && ! bindNodes.isEmpty) {
      for (bindNode <- bindNodes.asScala) {
        if (bindNode.valid != ModelDefs.DEFAULT_VALID) {
          if (sb eq null)
            sb = new jl.StringBuilder
          else if (sb.length > 0)
            sb.append(' ')
          sb.append(bindNode.parentBind.staticId)
        }
      }
    }
    if (sb eq null)
      null
    else
      sb.toString
  }

  private def setTransientAnnotation(name: String, value: String): Unit = {
    if (transientAnnotations eq null)
      transientAnnotations = new ju.HashMap[String, String]
    transientAnnotations.put(name, value)
  }

  private def getTransientAnnotation(name: String): String =
    if (transientAnnotations eq null)
      null
    else
      transientAnnotations.get(name)

  def this(locationData: LocationData) {
    this()
    this.locationData = locationData
  }

  def getLocationData: LocationData = locationData
}