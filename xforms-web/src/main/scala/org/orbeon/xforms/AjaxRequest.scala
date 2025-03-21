/**
 * Copyright (C) 2019 Orbeon, Inc.
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
package org.orbeon.xforms

import java.lang as jl
import cats.data.NonEmptyList
import org.orbeon.oxf.util.MarkupUtils.*
import org.orbeon.xforms
import org.orbeon.xforms.rpc.{WireAjaxEvent, WireAjaxEventWithTarget}
import shapeless.syntax.typeable.*


object AjaxRequest {

  private val Indent: String = " " * 4

  // NOTE: Later we can switch this to an automatically-generated protocol
  def buildXmlRequest(
    currentForm      : xforms.Form,
    eventsToSend     : NonEmptyList[WireAjaxEvent],
    sequenceNumberOpt: Option[Int]
  ): String = {

    val requestDocumentString = new jl.StringBuilder

    def newLine(): Unit = requestDocumentString.append('\n')
    def indent(l: Int): Unit = for (_ <- 0 to l) requestDocumentString.append(Indent)

    // Start request
    requestDocumentString.append("""<xxf:event-request xmlns:xxf="http://orbeon.org/oxf/xml/xforms">""")
    newLine()

    // Add form UUID
    indent(1)
    requestDocumentString.append("<xxf:uuid>")
    requestDocumentString.append(currentForm.uuid)
    requestDocumentString.append("</xxf:uuid>")
    newLine()

    // Still send the element name even if empty as this is what the schema and server-side code expects
    indent(1)
    requestDocumentString.append("<xxf:sequence>")
    sequenceNumberOpt foreach requestDocumentString.append
    requestDocumentString.append("</xxf:sequence>")
    newLine()

    // Keep track of the events we have handled, so we can later remove them from the queue

    // Start action
    indent(1)
    requestDocumentString.append("<xxf:action>")
    newLine()

    // Add events
    eventsToSend.toList foreach { event =>

      // Create `<xxf:event>` element
      indent(2)
      requestDocumentString.append("<xxf:event")
      requestDocumentString.append(s""" name="${event.eventName}"""")

      event.narrowTo[WireAjaxEventWithTarget] foreach { e =>
        requestDocumentString.append(s""" source-control-id="${currentForm.deNamespaceIdIfNeeded(e.targetId)}"""")
      }

      requestDocumentString.append(">")

      if (event.properties.nonEmpty) {
        // Only add properties when we don't have a value (in the future, the value should be
        // sent in a sub-element, so both a value and properties can be sent for the same event)
        newLine()
        event.properties foreach { case (key, value) =>

          val stringValue = value // support number and boolean

          indent(3)
          requestDocumentString.append(s"""<xxf:property name="${key.escapeXmlForAttribute}">""")
          requestDocumentString.append(stringValue.escapeXmlMinimal.filterOutInvalidXmlCharacters)
          requestDocumentString.append("</xxf:property>")
          newLine()
        }
        indent(2)
      }
      requestDocumentString.append("</xxf:event>")
      newLine()
    }

    // End action
    indent(1)
    requestDocumentString.append("</xxf:action>")
    newLine()

    // End request
    requestDocumentString.append("</xxf:event-request>")

    requestDocumentString.toString
  }
}
