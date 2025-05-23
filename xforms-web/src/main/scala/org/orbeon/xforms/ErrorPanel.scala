/**
 * Copyright (C) 2019 Orbeon, Inc.
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
package org.orbeon.xforms

import org.orbeon.jquery.*
import org.orbeon.xforms
import org.orbeon.xforms.facade.Utils
import org.scalajs.dom
import org.scalajs.dom.ext.*
import org.scalajs.dom.html
import io.udash.wrappers.jquery.JQueryEvent

import scala.scalajs.js
import scala.scalajs.js.Dynamic.{newInstance, global as g}


object ErrorPanel {

  import Private._

  def initializeErrorPanel(formElem: html.Form): Option[js.Object] = {

    // See `error-dialog.xml` for the expected layout of the HTML

    // We support multiple error panels, try to find one with a `lang` attribute that matches the language of the form,
    // and if we can't find one, use the error panel we find
    val allErrorPanelsNode           = formElem.querySelectorAll(".xforms-error-dialogs > .xforms-error-panel")
    val allErrorPanelsElements       = allErrorPanelsNode.to(List).asInstanceOf[List[html.Element]]
    val formLang                     = dom.document.firstElementChild.getAttribute("lang")
    val panelElemWithMatchingLangOpt = allErrorPanelsElements.find(_.getAttribute("lang") == formLang)
    val panelElemOpt                 = panelElemWithMatchingLangOpt.orElse(allErrorPanelsElements.headOption)

    panelElemOpt map { panelElem =>

      val jPanelElem = $(panelElem)

      jPanelElem.removeClass(Constants.InitiallyHiddenClass)

      val panel = newInstance(g.YAHOO.widget.Panel)(panelElem, new js.Object {
        val modal               = true
        val fixedcenter         = false
        val underlay            = "shadow"
        val visible             = false
        val constraintoviewport = true
        val draggable           = true
      })

      panel.render()

      Utils.overlayUseDisplayHidden(panel)

      // When the error dialog is closed, we make sure that the "details" section is closed,
      // so it will be closed the next time the dialog is opened.
      panel.beforeHideEvent.subscribe(
        ((_: js.Any) => toggleDetails(panelElem, show = false)): js.Function
      )

      Option(panelElem.querySelector(".xforms-error-panel-show-details")).foreach(_.addEventListener(
        `type` = "click",
        listener = (_: dom.Event) => toggleDetails(panelElem, show = true)
      ))

      Option(panelElem.querySelector(".xforms-error-panel-hide-details")).foreach(_.addEventListener(
        `type` = "click",
        listener = (_: dom.Event) => toggleDetails(panelElem, show = false)
      ))

      Option(panelElem.querySelector(".xforms-error-panel-close")).foreach(_.addEventListener(
        `type` = "click",
        listener = (_: dom.Event) => panel.hide()
      ))

      Option(panelElem.querySelector(".xforms-error-panel-reload")).foreach(_.addEventListener(
        `type` = "click",
        listener = (_: dom.Event) => dom.window.location.reload()
      ))

      panel
    }
  }

  def showError(currentForm: xforms.Form, detailsOrNull: String): Unit = {

    val formErrorPanel  = currentForm.errorPanel.asInstanceOf[js.Dynamic]
    val jErrorPanelElem = $(formErrorPanel.element)

    jErrorPanelElem.css("display: block")

    Option(detailsOrNull) match {
      case Some(details) =>
        jErrorPanelElem.find(".xforms-error-panel-details").html(details)
        toggleDetails(jErrorPanelElem.get(0).get, show = true)
      case None =>
        jErrorPanelElem.find(".xforms-error-panel-details-hidden").addClass("xforms-disabled")
        jErrorPanelElem.find(".xforms-error-panel-details-shown").addClass("xforms-disabled")
    }

    formErrorPanel.show()
    Globals.lastDialogZIndex += 2
    formErrorPanel.cfg.setProperty("zIndex", Globals.lastDialogZIndex)
    formErrorPanel.center()

    // Focus within the dialog so that screen readers handle aria attributes
    jErrorPanelElem.find(".container-close").trigger("focus")
  }

  private object Private {

    def toggleDetails(errorPanelElem: dom.Element, show: Boolean): Unit = {

      val jErrorPanelElem = $(errorPanelElem)

      jErrorPanelElem.find(".xforms-error-panel-details-hidden").toggleClass("xforms-disabled", show)
      jErrorPanelElem.find(".xforms-error-panel-details-shown").toggleClass("xforms-disabled", ! show)
    }
  }
}
