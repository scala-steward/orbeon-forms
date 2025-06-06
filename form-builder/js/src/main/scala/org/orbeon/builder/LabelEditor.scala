/**
 * Copyright (C) 2017 Orbeon, Inc.
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
package org.orbeon.builder

import autowire.*
import io.udash.wrappers.jquery.JQuery
import org.orbeon.builder.rpc.FormBuilderRpcApi
import org.orbeon.jquery.Offset
import org.orbeon.web.DomEventNames
import org.orbeon.web.DomSupport.*
import org.orbeon.xforms.facade.*
import org.orbeon.xforms.rpc.RpcClient
import org.orbeon.xforms.{$, AjaxClient, AjaxEvent}
import org.scalajs.dom
import org.scalajs.dom.html
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.*

import scala.scalajs.js


object LabelEditor {

  locally {

    val SectionTitleSelector = ".fr-section-title:first"
    val SectionLabelSelector = ".fr-section-label:first .btn-link, .fr-section-label:first .xforms-output-output"

    var labelInputOpt: js.UndefOr[JQuery] = js.undefined

    // On click on a trigger inside `.fb-section-grid-editor,` send section id as a property along with the event
    AjaxClient.beforeSendingEvent.add(
      (eventWithProperties: (AjaxEvent, js.Function1[js.Dictionary[js.Any], Unit])) => {

        val (event, addProperties) = eventWithProperties

        event.targetIdOpt foreach { eventTargetId =>

          val eventName        = event.eventName
          val targetEl         = dom.document.getElementById(eventTargetId)
          val inSectionEditor  = targetEl.closestOpt(".fb-section-grid-editor").isDefined

          if (eventName == DomEventNames.DOMActivate && inSectionEditor)
            addProperties(js.Dictionary(
              "section-id" -> SectionGridEditor.currentSectionGridOpt.get.el.attr("id").get
            ))
        }
      }
    )

    def sendNewLabelValue(): Unit = {

      val newLabelValue    = labelInputOpt.get.value().asInstanceOf[String]
      val labelInputOffset = Position.adjustedOffset(labelInputOpt.get)
      val section          = Position.findInCache(BlockCache.sectionGridCache, labelInputOffset.top, labelInputOffset.left).get
      val sectionId        = section.el.attr("id").get

      section.el.find(SectionLabelSelector).text(newLabelValue)

      RpcClient[FormBuilderRpcApi].sectionUpdateLabel(sectionId, newLabelValue).call()

      labelInputOpt.get.hide()
    }

    def showLabelEditor(clickInterceptor: JQuery): Unit =
      AjaxClient.allEventsProcessedF("showLabelEditor") foreach { _ =>

        // Clear interceptor click hint, if any
        clickInterceptor.text("")

        // Create single input element, if we don't have one already
        val labelInput = labelInputOpt.getOrElse {
          val labelInput = $("<input class='fb-edit-section-label'/>")
          $(".fb-main").append(labelInput)
          labelInput.get().foreach(_.addEventListener("blur", (_: dom.Event) => { if (labelInput.is(":visible")) sendNewLabelValue() }))
          labelInput.get().foreach(_.addEventListener(DomEventNames.KeyPress, (e: dom.KeyboardEvent) => {
            if (e.code == "Enter") {
              // Avoid "enter" from being dispatched to other control that might get the focus
              e.preventDefault()
              sendNewLabelValue()
            }
          }))
          // We close the editor on Ajax response as processing the response can modify the form in ways that make the
          // label editor irrelevant, badly positioned, etc. We do this unless the response is empty, which is typically
          // the case for heartbeat responses.
          AjaxClient.ajaxResponseProcessed.add { ajaxResponseDetails =>
            val emptyAjaxResponse = ajaxResponseDetails.responseXML.documentElement.childElementCount == 0
            if (! emptyAjaxResponse)
              labelInput.hide()
          }
          labelInputOpt = labelInput
          labelInput
        }

        val interceptorOffset = Position.adjustedOffset(clickInterceptor)

        // From the section title, get the anchor element, which contains the title
        val labelAnchor = {
            val section = Position.findInCache(BlockCache.sectionGridCache, interceptorOffset.top, interceptorOffset.left).get
            section.el.find(SectionLabelSelector)
        }

        // Set placeholder, done every time to account for a value change when changing current language
        locally {
          val placeholderOutput = SectionGridEditor.sectionGridEditorContainer.children(".fb-type-section-title-label")
          val placeholderValue  = Controls.getCurrentValue(placeholderOutput.get(0).get.asInstanceOf[dom.html.Element])
          labelInput.attr("placeholder", placeholderValue.get)
        }

        // Populate and show input
        labelInput.value(labelAnchor.text())
        labelInput.show()

        // Position and size input
        val inputOffset = Offset(
          top = interceptorOffset.top -
            // Interceptor offset is normalized, so we need to remove the scrollTop when setting the offset
            Position.scrollTop() +
            // Vertically center input inside click interceptor
            (clickInterceptor.height() - labelInput.outerHeight().getOrElse(0d)) / 2,
          left = interceptorOffset.left
        )
        Offset.offset(labelInput, inputOffset)
        Offset.offset(labelInput, inputOffset) // Workaround for issue on Chrome, see https://github.com/orbeon/orbeon-forms/issues/572
        labelInput.width(clickInterceptor.width() - 10)
        labelInput.trigger("focus")
      }

    // Update highlight of section title, as a hint users can click to edit
    def updateHighlight(
      updateClass      : (String, JQuery) => Unit,
      clickInterceptor : JQuery
    )                  : Unit = {

      val offset  = Position.adjustedOffset(clickInterceptor)
      val section = Position.findInCache(BlockCache.sectionGridCache, offset.top, offset.left).get
      val sectionTitle = section.el.find(SectionTitleSelector)
      updateClass("hover", sectionTitle)
    }

    // Show textual indication user can click on empty section title
    def showClickHintIfTitleEmpty(clickInterceptor: JQuery): Unit = {
      val interceptorOffset = Position.adjustedOffset(clickInterceptor)
      val section = Position.findInCache(BlockCache.sectionGridCache, interceptorOffset.top, interceptorOffset.left).get
      val labelAnchor = section.el.find(SectionLabelSelector)
      if (labelAnchor.text() == "") {
        val outputWithHintMessage = SectionGridEditor.sectionGridEditorContainer.children(".fb-enter-section-title-label")
        val hintMessage = Controls.getCurrentValue(outputWithHintMessage.get(0).get.asInstanceOf[dom.html.Element]).get
        clickInterceptor.text(hintMessage)
      }
    }

    // Create and position click interceptors
    locally {

      // This will contain at least as many interceptors as there are sections
      var labelClickInterceptors: List[JQuery] = Nil

      Position.onOffsetMayHaveChanged(() => {

        val sections = BlockCache.sectionGridCache.elems collect {
          case block if block.el.is(BlockCache.SectionSelector) => block.el
        }

        // Create interceptor divs, so we have enough to cover all the sections
        locally {

          val newInterceptors =
            List.fill(sections.size - labelClickInterceptors.size) {
              val container = dom.document.createElementT("div")
              container.className = "fb-section-label-editor-click-interceptor"
              container.addEventListener(
                "click",
                (e: dom.Event) => { showLabelEditor($(e.target.asInstanceOf[dom.Element])) }
              )
              container.addEventListener(
                "mouseover",
                (e: dom.Event) => {
                  updateHighlight(
                    (cssClass: String, el: JQuery) => { el.addClass(cssClass); () },
                    $(e.target)
                  )
                  showClickHintIfTitleEmpty($(e.target))
                }
              )

              container.addEventListener("mouseout", (e: dom.Event) => {
                updateHighlight(
                  (cssClass: String, el: JQuery) => { el.removeClass(cssClass); () },
                  $(e.target)
                )
                e.target.asInstanceOf[dom.Element].textContent = ""
              })

              $(container)
            }

          $(".fb-main").append(newInterceptors*)

          labelClickInterceptors :::= newInterceptors
        }

        // Hide interceptors we don't need
        for (interceptor <- labelClickInterceptors)
          interceptor.hide()

        // Position interceptor for each section
        for ((section, interceptor) <- sections.iterator.zip(labelClickInterceptors.iterator)) {

          val sectionTitle = section.find(SectionTitleSelector)
          val sectionLabel = section.find(SectionLabelSelector)

          // Show, as this might be an interceptor that was previously hidden, and is now reused
          interceptor.show()

          // Start at the label, but extend all the way to the right to the end of the title
          val labelOffset = sectionLabel.offset()
          val titleOffset = sectionTitle.offset()
          val interceptorOffset = io.udash.wrappers.jquery.Offset(
            top  = titleOffset.top,
            left = labelOffset.left
          )
          interceptor.offset(interceptorOffset)
          interceptor.height(sectionTitle.height())
          interceptor.width(sectionTitle.width() - (Offset(sectionLabel).left - Offset(sectionTitle).left))
        }
      })
    }
  }
}
