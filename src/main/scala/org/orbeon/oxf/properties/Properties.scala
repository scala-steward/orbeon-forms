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
package org.orbeon.oxf.properties

import cats.Eval
import cats.syntax.option.*
import org.log4s.Logger
import org.orbeon.dom.QName
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.pipeline.InitUtils.withPipelineContext
import org.orbeon.oxf.processor.{DOMSerializer, ProcessorImpl}
import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.oxf.util.{LoggerFactory, PipelineUtils}

import java.util as ju
import java.util.concurrent.Semaphore
import scala.jdk.CollectionConverters.*


/**
 * This class provides access to global, configurable properties, as well as to processor-specific properties. This is
 * an example of properties file:
 *
 * <properties xmlns:xs="http://www.w3.org/2001/XMLSchema"
 * xmlns:oxf="http://www.orbeon.com/oxf/processors">
 *
 * <property as="xs:integer" name="oxf.cache.size" value="200"/>
 * <property as="xs:string"  processor-name="oxf:page-flow" name="instance-passing" value="redirect"/>
 *
 * </properties>
 */
object Properties {

  val logger: Logger = LoggerFactory.createLogger("org.orbeon.properties")

  private val DefaultPropertiesUri = "oxf:/properties.xml"
  private val ReloadDelay          = 5 * 1000

  private def newEval: Eval[Properties] =
    Eval.later {
      new Properties |!> (_.update())
    }

  private var _instance: Eval[Properties] = newEval

  private var propertiesURI = DefaultPropertiesUri
  private val initializingSemaphore = new Semaphore(1)

  // Set URI of the resource we will read the properties from and initialize them
  def init(propertiesURI: String): Unit = {
    this.propertiesURI = propertiesURI
    instance // force evaluation at this time
  }

  // Global `Properties`
  def instance: Properties = _instance.value

  // Invalidate all properties (for testing)
  def invalidate(): Unit =
    _instance = newEval

  def withAcquiredPropertiesOrSkip[T](thunk: => T): Option[T] =
    if (initializingSemaphore.tryAcquire()) {
      try
        Some(thunk)
      finally
        initializingSemaphore.release()
    } else {
      None
    }
}

class Properties private {
  /**
   * The property store.
   */
  private var propertyStore: Option[PropertyStore] = None

  // Used for refresh
  private lazy val urlGenerator = PipelineUtils.createURLGenerator(Properties.propertiesURI, true) // enable XInclude too
  private lazy val domSerializer = {
    // Create mini-pipeline to read properties if needed
    val serializer = new DOMSerializer
    PipelineUtils.connect(urlGenerator, ProcessorImpl.OUTPUT_DATA, serializer, ProcessorImpl.INPUT_DATA)
    serializer
  }

  private var lastUpdate = Long.MinValue

  /**
   * Make sure we have the latest properties, and if we don't (resource changed), reload them.
   */
  private def update(): Unit =
    Properties.withAcquiredPropertiesOrSkip {

      val current = System.currentTimeMillis
      if (lastUpdate + Properties.ReloadDelay >= current)
        return

      withPipelineContext { pipelineContext =>

        urlGenerator.reset(pipelineContext)
        domSerializer.reset(pipelineContext)

        // Find whether we can skip reloading
        if (propertyStore.isDefined && domSerializer.findInputLastModified(pipelineContext) <= lastUpdate) {
          Properties.logger.debug("Not reloading properties because they have not changed.")
          lastUpdate = current
          return
        }
        Properties.logger.debug("Reloading properties because timestamp indicates they may have changed.")

        // Read updated properties document
        val document = domSerializer.runGetDocument(pipelineContext)
        if (document == null || document.content.isEmpty)
          throw new OXFException("Failure to initialize Orbeon Forms properties")

        val newSequence = propertyStore.map(_.sequence + 1).getOrElse(0)

        propertyStore = PropertyStore.parse(document, newSequence).some
        lastUpdate = current
      }
    }

  def getPropertySet: PropertySet =
    propertyStore match {
      case Some(propertyStore) =>
        update()
        propertyStore.getGlobalPropertySet
      case None =>
        null
    }

  def getPropertySetOrThrow: PropertySet = {
    val ps = getPropertySet
    if (ps eq null)
      throw new OXFException("property set not found")
    ps
  }

  def getPropertySet(processorName: QName): PropertySet =
    propertyStore match {
      case Some(propertyStore) =>
        update()
        propertyStore.getProcessorPropertySet(processorName)
      case None =>
        null
    }

  def keySetJava: ju.Set[?] =
    propertyStore match {
      case Some(propertyStore) =>
        propertyStore.getGlobalPropertySet.keySet.asJava
      case None =>
        null
    }
}