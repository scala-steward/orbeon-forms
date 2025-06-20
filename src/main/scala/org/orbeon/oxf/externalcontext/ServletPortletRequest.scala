/**
  * Copyright (C) 2016 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.oxf.externalcontext

import org.orbeon.oxf.util.URLRewriterUtils


// Implementations shared between `ServletExternalContext` and `Portlet2ExternalContext`.
trait ServletPortletRequest extends ExternalContext.Request {

  protected def headerValuesMap: Map[String, Array[String]]

  lazy val credentials: Option[Credentials] =
    sessionOpt.flatMap(CredentialsSupport.findCredentialsInSession)

  def isUserInRole(role: String): Boolean =
    credentials exists (_.roles exists (_.roleName == role))

  def getClientContextPath(urlString: String): String =
    if (URLRewriterUtils.isPlatformPath(urlString))
      platformClientContextPath
    else
      applicationClientContextPath

  private lazy val platformClientContextPath: String =
    URLRewriterUtils.getClientContextPath(this, true)

  private lazy val applicationClientContextPath: String =
    URLRewriterUtils.getClientContextPath(this, false)

  lazy val servicePrefix: String =
    URLRewriterImpl.rewriteServiceUrl(
      this,
      "/",
      UrlRewriteMode.Absolute,
      URLRewriterUtils.getServiceBaseURI, // blank by default
    )
}
