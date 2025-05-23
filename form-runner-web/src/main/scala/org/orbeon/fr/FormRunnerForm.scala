package org.orbeon.fr

import org.orbeon.oxf.fr.ControlOps
import org.orbeon.web.DomSupport.*
import org.orbeon.xforms
import org.orbeon.xforms.*
import org.scalajs.dom
import org.scalajs.dom.html
import org.scalajs.dom.html.Element

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.{JSRichFutureNonThenable, JSRichIterableOnce, JSRichOption}
import scala.scalajs.js.|


// Form Runner-specific facade as we don't want to expose internal `xforms.Form` members
class FormRunnerForm(private val form: xforms.Form) extends js.Object {

  def addCallback(name: String, fn: js.Function): Unit =
    form.addCallback(name, fn)

  def removeCallback(name: String, fn: js.Function): Unit =
    form.removeCallback(name, fn)

  def isFormDataSafe(): Boolean =
    form.formDataSafe

  def activateProcessButton(buttonName: String): Unit = {

    def fromProcessButton =
      dom.document.querySelectorOpt(s".fr-buttons .xbl-fr-process-button .fr-$buttonName-button button")

    def fromDropTrigger =
      dom.document.querySelectorOpt(s".fr-buttons .xbl-fr-drop-trigger button.fr-$buttonName-button, .fr-buttons .xbl-fr-drop-trigger li a.fr-$buttonName-button")

    fromProcessButton
      .orElse(fromDropTrigger)
      .foreach(_.click())
  }

  def findControlsByName(controlName: String): js.Array[Element] =
    $(form.elem)
      .find(s".xforms-control[id *= '$controlName-control'], .xbl-component[id *= '$controlName-control']")
      .toArray collect {
      // The result must be an `html.Element` already
      case e: html.Element => e
    } filter {
      // Check the id matches the requested name
      e => (e.id ne null) && (ControlOps.controlNameFromIdOpt(XFormsId.getStaticIdFromId(e.id)) contains controlName)
    } toJSArray

  // - returns `undefined` if
  //   - the control is not found
  //   - the index is not in range
  //   - the control is an XBL component which doesn't support the JavaScript lifecycle
  // - returns a `Promise` that resolves when the server has processed the value change
  def setControlValue(
    controlName : String,
    controlValue: String | Int | Boolean,
    index       : js.UndefOr[Int] = js.undefined
  ): js.UndefOr[js.Promise[Unit]] =
    findControlMaybeNested(controlName, index)
      .map(DocumentAPI.setValue(_, controlValue.toString, form.elem, waitForServer = true))
      .orUndefined

  // - returns `undefined` if
  //   - the control is not found
  //   - the index is not in range
  //   - the control is not a trigger or input control
  // - returns a `Promise` that resolves when the server has processed the activation
  def activateControl(
    controlName: String,
    index      : js.UndefOr[Int] = js.undefined
  ): js.UndefOr[js.Promise[Unit]] =
    findControlsByName(controlName).lift(index.getOrElse(0)).map { e =>

      val elemToClickOpt =
        if (e.classList.contains("xforms-trigger"))
          e.querySelectorOpt("button")
        else if (e.classList.contains("xforms-input"))
          e.querySelectorOpt("input")
        else
          None

      elemToClickOpt.foreach(_.click())
      AjaxClient.allEventsProcessedF("activateControl").toJSPromise
    }
    .orUndefined

  // - returns `undefined` if
  //   - the control is not found
  //   - the index is not in range
  //   - the control doesn't support returning a value
  // - TODO: Array[String] for multiple selection controls
  def getControlValue(controlName: String, index: js.UndefOr[Int] = js.undefined): js.UndefOr[String] =
    findControlMaybeNested(controlName, index)
      .flatMap(DocumentAPI.getValue(_, form.elem).toOption)
      .orUndefined

  private def findControlMaybeNested(
    controlName: String,
    index      : js.UndefOr[Int] = js.undefined
  ): Option[Element] =
    findControlsByName(controlName).lift(index.getOrElse(0)).flatMap {
      case e if e.classList.contains("xbl-fr-dropdown-select1") =>
        e.querySelectorOpt(".xforms-select1")
      case e if XFormsXbl.isJavaScriptLifecycle(e) =>
        Some(e)
      case e if XFormsXbl.isComponent(e) =>
        // NOP, as the server will reject the value anyway in this case
        None
      case e =>
        Some(e)
    }
}
