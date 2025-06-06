/**
 * Copyright (C) 2020 Orbeon, Inc.
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
package org.orbeon.oxf.util

import cats.effect.IO
import org.orbeon.connection.*
import org.orbeon.connection.ConnectionContextSupport.ConnectionContexts
import org.orbeon.io.UriScheme
import org.orbeon.oxf.externalcontext.{ExternalContext, SafeRequestContext}
import org.orbeon.oxf.http.Headers.*
import org.orbeon.oxf.http.HttpMethod.{GET, HttpMethodsWithRequestBody, POST}
import org.orbeon.oxf.http.{BasicCredentials, HttpMethod, StatusCode}
import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.oxf.util.Logging.debug

import java.net.URI
import scala.jdk.CollectionConverters.*


trait ResourceResolver {
  def resolve(
    method : HttpMethod,
    url    : URI,
    content: Option[StreamedContent],
    headers: Map[String, List[String]]
  )(implicit
    logger : IndentedLogger
  ): Option[ConnectionResult]
}

trait ConnectionTrait {

  def connectNow(
    method          : HttpMethod,
    url             : URI,
    credentials     : Option[BasicCredentials],
    content         : Option[StreamedContent],
    headers         : Map[String, List[String]],
    loadState       : Boolean,
    saveState       : Boolean,
    logBody         : Boolean
  )(implicit
    logger          : IndentedLogger,
    externalContext : ExternalContext,
    resourceResolver: Option[ResourceResolver]
  ): ConnectionResult

  def connectAsync(
    method          : HttpMethod,
    url             : URI,
    credentials     : Option[BasicCredentials],
    content         : Option[AsyncStreamedContent],
    headers         : Map[String, List[String]],
    loadState       : Boolean,
    logBody         : Boolean
  )(implicit
    logger          : IndentedLogger,
    safeRequestCtx  : SafeRequestContext,
    connectionCtx   : ConnectionContexts,
    resourceResolver: Option[ResourceResolver]
  ): IO[AsyncConnectionResult]

  def isInternalPath(path: String): Boolean

  def findInternalUrl(
    normalizedUrl: URI,
    filter       : String => Boolean,
    servicePrefix: String
  ): Option[String]

  def buildConnectionHeadersCapitalizedIfNeeded(
    url             : URI, // scheme can be `null`; should we force a scheme, for example `http:/my/service`?
    hasCredentials  : Boolean,
    customHeaders   : Map[String, List[String]],
    headersToForward: Set[String],
    cookiesToForward: List[String],
    getHeader       : String => Option[List[String]]
  )(implicit
    logger          : IndentedLogger,
    safeRequestCtx  : SafeRequestContext
  ): Map[String, List[String]]

  def headersToForwardFromProperty: Set[String]
  def cookiesToForwardFromProperty: List[String]

  def getHeaderFromRequest(request: ExternalContext.Request): String => Option[List[String]] =
    Option(request) match {
      case Some(request) => name => request.getHeaderValuesMap.asScala.get(name) map (_.toList)
      case None          => _    => None
    }

  protected def sessionCookieHeaderCapitalized(
    cookiesToForward: List[String]
  )(implicit
    safeRequestCtx  : SafeRequestContext,
    logger          : IndentedLogger
  ): Option[(String, List[String])]

  def notFound(url: URI): ConnectionResult =
    ConnectionResult(
      url                = url.toString,
      statusCode         = StatusCode.NotFound,
      headers            = Map.empty,
      content            = StreamedContent.Empty,
      dontHandleResponse = false,
    )

  def methodNotAllowed(url: URI): ConnectionResult =
    ConnectionResult(
      url                = url.toString,
      statusCode         = StatusCode.MethodNotAllowed,
      headers            = Map.empty,
      content            = StreamedContent.Empty,
      dontHandleResponse = false
    )

  private def buildSOAPHeadersCapitalizedIfNeeded(
    method                   : HttpMethod,
    mediatypeMaybeWithCharset: Option[String],
    encoding                 : String
  )(implicit
    logger                   : IndentedLogger
  ): List[(String, List[String])] = {

    require(encoding ne null)

    import org.orbeon.oxf.util.ContentTypes.*

    val contentTypeMediaType = mediatypeMaybeWithCharset flatMap getContentTypeMediaType

    // "If the submission mediatype contains a charset MIME parameter, then it is appended to the application/soap+xml
    // MIME type. Otherwise, a charset MIME parameter with same value as the encoding attribute (or its default) is
    // appended to the application/soap+xml MIME type." and "the charset MIME parameter is appended . The charset
    // parameter value from the mediatype attribute is used if it is specified. Otherwise, the value of the encoding
    // attribute (or its default) is used."

    def charsetOrDefault(charset: Option[String]) =
      charset.orElse(Option(encoding))

    val newHeaders =
      method match {
        case GET if contentTypeMediaType contains SoapContentType =>
          // Set an Accept header

          val acceptHeader =
            makeContentTypeCharset(SoapContentType, charsetOrDefault(mediatypeMaybeWithCharset flatMap getContentTypeCharset))

          // Accept header with optional charset
          List(Accept -> List(acceptHeader))

        case POST if contentTypeMediaType contains SoapContentType =>
          // Set Content-Type and optionally SOAPAction headers

          val parameters            = mediatypeMaybeWithCharset map getContentTypeParameters getOrElse Map.empty
          val overriddenContentType = makeContentTypeCharset(XmlTextContentType, charsetOrDefault(parameters.get(ContentTypes.CharsetParameter)))
          val actionParameter       = parameters.get(ContentTypes.ActionParameter)

          // Content-Type with optional charset and SOAPAction header if any
          List(ContentType -> List(overriddenContentType)) ++ (actionParameter map (a => SOAPAction -> List(a)))
        case _ =>
          // Not a SOAP submission
          Nil
      }

    if (newHeaders.nonEmpty)
      debug("adding SOAP headers", newHeaders map { case (k, v) => k -> v.head })

    newHeaders
  }

  def buildConnectionHeadersCapitalizedWithSOAPIfNeeded(
    url             : URI,
    method          : HttpMethod,
    hasCredentials  : Boolean,
    mediatypeOpt    : Option[String],
    encodingForSOAP : String,
    customHeaders   : Map[String, List[String]],
    headersToForward: Set[String],
    getHeader       : String => Option[List[String]]
  )(implicit
    logger          : IndentedLogger,
    safeRequestCtx  : SafeRequestContext
  ): Map[String, List[String]] =
    if ((url.getScheme eq null) || UriScheme.SchemesWithHeaders(UriScheme.withName(url.getScheme))) {

      // "If a header element defines the Content-Type header, then this setting overrides a Content-type set by the
      // mediatype attribute"
      val headersWithContentTypeIfNeeded =
        mediatypeOpt match {
          case Some(mediatype) if HttpMethodsWithRequestBody(method) && firstItemIgnoreCase(customHeaders, ContentType).isEmpty =>
            customHeaders + (ContentType -> List(mediatype))
          case _ =>
            customHeaders
        }

      // Also make sure that if a header element defines Content-Type, this overrides the mediatype attribute
      def soapMediatypeWithContentType =
        firstItemIgnoreCase(headersWithContentTypeIfNeeded, ContentTypeLower) orElse mediatypeOpt

      // NOTE: SOAP processing overrides Content-Type in the case of a POST
      // So we have: @serialization -> @mediatype ->  xf:header -> SOAP
      val connectionHeadersCapitalized =
        buildConnectionHeadersCapitalized(
          url.normalize,
          hasCredentials,
          headersWithContentTypeIfNeeded,
          headersToForward,
          cookiesToForwardFromProperty,
          getHeader
        )

      val soapHeadersCapitalized =
        buildSOAPHeadersCapitalizedIfNeeded(
          method,
          soapMediatypeWithContentType,
          encodingForSOAP
        )

      connectionHeadersCapitalized ++ soapHeadersCapitalized
    } else
      EmptyHeaders

  /**
   * Build connection headers to send given:
   *
   * - the incoming request if present
   * - a list of headers names and values to set
   * - whether explicit credentials are available (disables forwarding of session cookies and Authorization header)
   * - a list of headers to forward
   */
  protected def buildConnectionHeadersCapitalized(
    normalizedUrl           : URI,
    hasCredentials          : Boolean,
    customHeadersCapitalized: Map[String, List[String]],
    headersToForward        : Set[String],
    cookiesToForward        : List[String],
    getHeader               : String => Option[List[String]]
  )(implicit
    logger                  : IndentedLogger,
    safeRequestCtx          : SafeRequestContext
  ): Map[String, List[String]] = {

    // 1. Caller-specified list of headers to forward based on a space-separated list of header names
    val headersToForwardCapitalized =
      getHeadersToForwardCapitalized(hasCredentials, headersToForward, getHeader)

    // 2. Explicit caller-specified header name/values

    // 3. Forward cookies for session handling only if no credentials have been explicitly set
    val newCookieHeaderCapitalized =
      if (! hasCredentials)
        sessionCookieHeaderCapitalized(cookiesToForward)
      else
        None

    // 4. Authorization token only for internal connections
    // https://github.com/orbeon/orbeon-forms/issues/4388
    val tokenHeaderCapitalized =
      findInternalUrl(normalizedUrl, _ => true, safeRequestCtx.servicePrefix)
        .isDefined
        .flatList(buildTokenHeaderCapitalized.toList)

    // Don't forward headers for which a value is explicitly passed by the caller, so start with headersToForward
    // New cookie header, if present, overrides any existing cookies
    headersToForwardCapitalized.toMap ++ customHeadersCapitalized ++ newCookieHeaderCapitalized ++ tokenHeaderCapitalized
  }

  protected def buildTokenHeaderCapitalized(implicit
    logger        : IndentedLogger,
    safeRequestCtx: SafeRequestContext
  ): Option[(String, List[String])]

  // From header names and a getter for header values, find the list of headers to forward
  private def getHeadersToForwardCapitalized(
    hasCredentials         : Boolean, // exclude `Authorization` header when true
    headerNamesCapitalized : Set[String],
    getHeader              : String => Option[List[String]]
  )(implicit
    logger                 : IndentedLogger
  ): List[(String, List[String])] = {

    // NOTE: Forwarding the `Cookie` header may yield unpredictable results.

    def canForwardHeader(nameLower: String) = {
      // Only forward Authorization header if there is no credentials provided
      val canForward = nameLower != AuthorizationLower || ! hasCredentials

      if (! canForward)
        debug("not forwarding Authorization header because credentials are present")

      canForward
    }

    for {
      nameCapitalized <- headerNamesCapitalized.toList
      nameLower       = nameCapitalized.toLowerCase
      values          <- getHeader(nameLower)
      if canForwardHeader(nameLower)
    } yield {
      debug("forwarding header", List(
        "name"  -> nameCapitalized,
        "value" -> (values mkString " ")
        )
      )
      nameCapitalized -> values
    }
  }
}