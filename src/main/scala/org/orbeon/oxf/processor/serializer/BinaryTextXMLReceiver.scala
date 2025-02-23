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
package org.orbeon.oxf.processor.serializer

import org.orbeon.dom.{Namespace, QName}
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.externalcontext.ExternalContext
import org.orbeon.oxf.externalcontext.ExternalContext.Response
import org.orbeon.oxf.http.{Headers, PathType}
import org.orbeon.oxf.processor.serializer.BinaryTextXMLReceiver.*
import org.orbeon.oxf.util.ContentTypes.{getContentTypeCharset, getContentTypeMediaType, makeContentTypeCharset}
import org.orbeon.oxf.util.StringUtils.*
import org.orbeon.oxf.util.{Base64XMLReceiver, ContentTypes, DateUtils, TextXMLReceiver}
import org.orbeon.oxf.xml.SaxonUtils.parseQName
import org.orbeon.oxf.xml.XMLConstants.*
import org.orbeon.oxf.xml.{XMLReceiver, XMLReceiverAdapter}
import org.xml.sax.Attributes

import java.io.*
import scala.collection.mutable


/**
 * ContentHandler able to serialize text or binary documents to an output stream.
 */
class BinaryTextXMLReceiver(
  output                    : Either[(Response, PathType), OutputStream], // one of those is required
  closeStream               : Boolean,                        // whether to close the stream upon endDocument()
  forceContentType          : Boolean,
  requestedContentType      : Option[String],
  ignoreDocumentContentType : Boolean,
  forceEncoding             : Boolean,
  requestedEncoding         : Option[String],
  ignoreDocumentEncoding    : Boolean,
  headersToForward          : List[String]

) extends XMLReceiverAdapter {

  require(! forceContentType || requestedContentType.get.nonAllBlank)
  require(! forceEncoding    || requestedEncoding.get.nonAllBlank)

  private val responsePathTypeOpt: Option[(Response, PathType)] = output.left.toOption
  val outputStream: OutputStream = responsePathTypeOpt map (_._1.getOutputStream) getOrElse output.getOrElse(throw new NoSuchElementException)

  private val prefixMappings = new mutable.HashMap[String, String]

  private var elementLevel: Int = 0
  private var writer: Writer = null
  private var outputReceiver: XMLReceiver = null

  // For Java callers
  def this(
    response                  : ExternalContext.Response,
    pathType                  : PathType,
    outputStream              : OutputStream,
    closeStream               : Boolean,
    forceContentType          : Boolean,
    requestedContentType      : String,
    ignoreDocumentContentType : Boolean,
    forceEncoding             : Boolean,
    requestedEncoding         : String,
    ignoreDocumentEncoding    : Boolean,
    headersToForward          : List[String]
  ) =
    this(
      Either.cond(response eq null, outputStream, (response, pathType)),
      closeStream,
      forceContentType,
      requestedContentType.trimAllToOpt,
      ignoreDocumentContentType,
      forceEncoding,
      requestedEncoding.trimAllToOpt,
      ignoreDocumentEncoding,
      headersToForward
    )

  // Simple constructor to write to a stream and close it
  def this(outputStream: OutputStream) =
    this(Right(outputStream), true, false, None, false, false, None, false, Nil)

  // Record definitions only before root element arrives
  override def startPrefixMapping(prefix: String, uri: String): Unit =
    if (elementLevel == 0)
      prefixMappings.put(prefix, uri)

  override def startElement(namespaceURI: String, localName: String, qName: String, attributes: Attributes): Unit = {
    elementLevel += 1

    if (elementLevel == 1) {
      // This is the root element

      // Get xsi:type attribute and determine whether the input is binary or text

      val xsiType = Option(attributes.getValue(XSI_TYPE_QNAME.namespace.uri, XSI_TYPE_QNAME.localName)) getOrElse
        (throw new OXFException("Root element must contain an xsi:type attribute"))

      val (typePrefix, typeLocalName) = parseQName(xsiType)

      val typeNamespaceURI =
        prefixMappings.getOrElse(typePrefix, throw new OXFException(s"Undeclared prefix in xsi:type: $typePrefix"))

      val isBinaryInput = QName(typeLocalName, Namespace(typePrefix, typeNamespaceURI)) match {
        case XS_BASE64BINARY_QNAME => true
        case XS_STRING_QNAME       => false
        case _                     => throw new OXFException("Type xs:string or xs:base64Binary must be specified")
      }

      // Set Last-Modified, Content-Disposition and status code when available
      responsePathTypeOpt foreach { case (response, pathType) =>

        // This will override caching settings which may have taken place before
        attributes.getValue(Headers.LastModifiedLower).trimAllToOpt foreach
          (validity => response.setPageCaching(DateUtils.parseRFC1123(validity), pathType)) // TODO: determine path type based on parameter

        attributes.getValue("filename").trimAllToOpt.foreach { fileName =>
          val isInline        = attributes.getValue("disposition-type").trimAllToOpt.contains("inline")
          val dispositionType = if (isInline) "inline" else "attachment"
          response.setHeader("Content-Disposition", s"$dispositionType; filename=$fileName")
        }

        attributes.getValue("status-code").trimAllToOpt foreach
          (statusCode => response.setStatus(statusCode.toInt))

        // Forward headers if any
        headersToForward foreach { headerName =>
          attributes.getValue(headerName).trimAllToOpt foreach { headerValue =>
            response.setHeader(headerName, headerValue)
          }
        }
      }

      // Set ContentHandler and headers depending on input type
      val contentTypeAttribute        = Option(attributes.getValue(Headers.ContentTypeLower))
      val contentDispositionAttribute = Option(attributes.getValue(Headers.ContentDispositionLower)) flatMap (_.trimAllToOpt)
      if (isBinaryInput) {
        responsePathTypeOpt foreach { case (response, _) =>
          // Get content-type and encoding

          if (! forceContentType          &&
              ! ignoreDocumentContentType &&
              ! forceEncoding             &&
              ! ignoreDocumentEncoding    &&
            contentTypeAttribute.isDefined) {
            // Simple case where we just forward what's coming in
            response.setContentType(contentTypeAttribute.get)
          } else {
            // Otherwise try some funky logic based on the configuration
            val contentType = getContentType(contentTypeAttribute, DefaultBinaryContentType)
            val encoding    = getEncoding(contentTypeAttribute, CachedSerializer.DefaultEncoding)

            // Output encoding only for text content types, defaulting to utf-8
            // NOTE: The "binary" mode doesn't mean the content is binary, it could be text as well. So we
            // output a charset when possible.
            if (ContentTypes.isTextOrJSONContentType(contentType))
              response.setContentType(makeContentTypeCharset(contentType, Some(encoding)))
            else
              response.setContentType(contentType)
          }

          contentDispositionAttribute map Headers.buildContentDispositionHeader foreach (response.setHeader _).tupled
        }

        outputReceiver = new Base64XMLReceiver(outputStream)
      } else {
        // Get content-type and encoding
        val contentType = getContentType(contentTypeAttribute, DefaultTextContentType)
        val encoding    = getEncoding(contentTypeAttribute, CachedSerializer.DefaultEncoding)

        responsePathTypeOpt foreach { case (response, _) =>
          // Always set the content type with a charset attribute (with a default)
          response.setContentType(contentType + "; charset=" + encoding)
          contentDispositionAttribute map Headers.buildContentDispositionHeader foreach (response.setHeader _).tupled
        }

        writer = new OutputStreamWriter(outputStream, encoding)
        outputReceiver = new TextXMLReceiver(writer)
      }
    }
  }

  override def endElement(namespaceURI: String, localName: String, qName: String): Unit =
    elementLevel -= 1

  override def characters(ch: Array[Char], start: Int, length: Int): Unit =
    outputReceiver.characters(ch, start, length)

  override def endDocument(): Unit = {
    if (writer ne null)
      writer.flush()
    outputStream.flush()
    if (closeStream)
      outputStream.close()
  }

  override def processingInstruction(target: String, data: String): Unit =
    parseSerializerPI(target, data) match {
      case Some(("status-code", Some(code))) =>
        responsePathTypeOpt foreach (_._1.setStatus(code.toInt))
      case Some(("flush", _)) =>
        if (writer ne null)
          writer.flush()

        outputStream.flush()
      case _ =>
        super.processingInstruction(target, data)
    }

  // Content type determination algorithm
  private def getContentType(contentTypeAttribute: Option[String], defaultContentType: String): String =
    if (forceContentType)
      requestedContentType.get
    else if (ignoreDocumentContentType)
      requestedContentType getOrElse defaultContentType
    else
      contentTypeAttribute flatMap getContentTypeMediaType getOrElse defaultContentType

  // Encoding determination algorithm
  private def getEncoding(contentTypeAttribute: Option[String], defaultEncoding: String): String =
    if (forceEncoding)
      requestedEncoding.get
    else if (ignoreDocumentEncoding)
      requestedEncoding getOrElse defaultEncoding
    else
      contentTypeAttribute flatMap getContentTypeCharset getOrElse defaultEncoding
}

object BinaryTextXMLReceiver {

  val DefaultBinaryContentType = ContentTypes.OctetStreamContentType
  val DefaultTextContentType   = ContentTypes.PlainTextContentType

  val PITargets = Set("orbeon-serializer", "oxf-serializer")

  private val StatusCodeRE = """status-code="([^"]*)"""".r

  private def parseSerializerPI(target: String, data: String): Option[(String, Option[String])] = {
    if (PITargets(target)) {
      Option(data) collect {
        case StatusCodeRE(code) => "status-code" -> Some(code)
        case "flush"            => "flush" -> None
      }
    } else
      None
  }

  def isSerializerPI(target: String, data: String): Boolean =
    parseSerializerPI(target, data).isDefined
}