/**
 * Copyright (C) 2006 Orbeon, Inc.
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
package org.orbeon.oxf.resources.handler

import org.orbeon.oxf.http.{ApacheHttpUrlConnection, PropertiesApacheHttpClient}

import java.net.{URL, URLConnection, URLStreamHandler}

class HTTPHandler extends URLStreamHandler {
  protected def openConnection(url: URL): URLConnection =
    new ApacheHttpUrlConnection(url)(PropertiesApacheHttpClient)
}