/**
 * Copyright (C) 2007 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.processor.handlers.xhtml

import java.{lang => jl}
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.xforms.XFormsUtils
import org.orbeon.oxf.xforms.analysis.ElementAnalysis
import org.orbeon.oxf.xforms.analysis.controls.LHHA.Label
import org.orbeon.oxf.xforms.analysis.controls.{ComponentControl, LHHA, StaticLHHASupport}
import org.orbeon.oxf.xforms.control.XFormsControl
import org.orbeon.oxf.xforms.processor.handlers.{HandlerContext, XFormsBaseHandler}
import org.orbeon.oxf.xml._
import org.orbeon.xforms.XFormsId
import org.xml.sax.{Attributes, Locator}


class XXFormsComponentHandler(
  uri             : String,
  localname       : String,
  qName           : String,
  localAtts       : Attributes,
  elementAnalysis : ElementAnalysis,
  handlerContext  : HandlerContext
) extends
  XFormsControlLifecyleHandler(
    uri,
    localname,
    qName,
    localAtts,
    elementAnalysis,
    handlerContext,
    repeating  = false,
    forwarding = false
  ) {

  private lazy val staticControl =
    handlerContext.getPartAnalysis.getControlAnalysis(getPrefixedId).asInstanceOf[ComponentControl]

  override def getContainingElementName =
    staticControl.commonBinding.containerElementName

  protected override def getContainingElementQName =
    XMLUtils.buildQName(handlerContext.findXHTMLPrefix, staticControl.commonBinding.containerElementName)

  protected override def addCustomClasses(classes: jl.StringBuilder, control: XFormsControl): Unit = {
    if (classes.length != 0)
      classes.append(' ')

    classes.append(staticControl.commonBinding.cssClasses)
  }

  protected def handleControlStart(): Unit = {

    val prefixedId = getPrefixedId
    val controller = handlerContext.controller

    handlerContext.pushComponentContext(prefixedId)

    // Process shadow content
    staticControl.bindingOpt foreach { binding =>
      XXFormsComponentHandler.processShadowTree(controller, binding.templateTree)
    }
  }

  protected override def handleControlEnd(): Unit =
    handlerContext.popComponentContext()

  protected override def handleLabel() =
    if (staticControl.commonBinding.standardLhhaAsSet(LHHA.Label)) // also implied: label is local (from `XFormsControlLifecyleHandler`)
      handleLabelHintHelpAlert(
        lhhaAnalysis             = getStaticLHHA(getPrefixedId, LHHA.Label),
        targetControlEffectiveId = getEffectiveId,
        forEffectiveId           = getForEffectiveId(getEffectiveId),
        lhha                     = LHHA.Label,
        requestedElementNameOpt  = (staticControl.commonBinding.labelFor.isEmpty || XFormsBaseHandler.isStaticReadonly(currentControl)) option "span",
        controlOrNull            = currentControl,
        isExternal               = false
      )

  protected override def handleAlert() = if (staticControl.commonBinding.standardLhhaAsSet(LHHA.Alert)) super.handleAlert()
  protected override def handleHint()  = if (staticControl.commonBinding.standardLhhaAsSet(LHHA.Hint))  super.handleHint()
  protected override def handleHelp()  = if (staticControl.commonBinding.standardLhhaAsSet(LHHA.Help))  super.handleHelp()

  // If there is a label-for, use that, otherwise don't use @for as we are not pointing to an HTML form control
  // NOTE: Used by `handleLabel()` if there is a local LHHA, and by `findTargetControlForEffectiveId`.
  override def getForEffectiveId(effectiveId: String): String = {

    import shapeless._
    import syntax.typeable._

    val result =
      for {
        staticLhhaSupport                 <- elementAnalysis.narrowTo[StaticLHHASupport]
        staticLabel                       <- staticLhhaSupport.lhh(Label)
        effectiveTargetControlOrPrefixedI <- staticLabel.effectiveTargetControlOrPrefixedIdOpt
        currentControlSuffix              = XFormsId.getEffectiveIdSuffixWithSeparator(currentControl.getEffectiveId)
      } yield
        effectiveTargetControlOrPrefixedI match {
          case Left(effectiveTargetControl) =>

            // Push/pop component context so that handler resolution works
            handlerContext.pushComponentContext(getPrefixedId)
            try
              XFormsLHHAHandler.findTargetControlForEffectiveId(
                handlerContext,
                effectiveTargetControl,
                effectiveTargetControl.prefixedId + currentControlSuffix
              )
            finally
              handlerContext.popComponentContext()

          case Right(targetPrefixedId) =>
            Some(targetPrefixedId + currentControlSuffix)
        }

    result.flatten.map(XFormsUtils.namespaceId(containingDocument, _)).orNull
  }
}

object XXFormsComponentHandler {

  def processShadowTree[Ctx](controller: ElementHandlerController[Ctx], templateTree: SAXStore): Unit = {
    // Tell the controller we are providing a new body
    controller.startBody()

    // Forward shadow content to handler
    // TODO: Handle inclusion/namespaces with XIncludeProcessor instead of custom code.
    templateTree.replay(new EmbeddedDocumentXMLReceiver(controller) {

      var level = 0

      override def startElement(uri: String, localname: String, qName: String, attributes: Attributes): Unit = {

        if (level != 0)
          super.startElement(uri, localname, qName, attributes)

        level += 1
      }

      override def endElement(uri: String, localname: String, qName: String): Unit = {

        level -= 1

        if (level != 0)
          super.endElement(uri, localname, qName)
      }

      override def setDocumentLocator(locator: Locator): Unit = {
        // NOP for now. In the future, we should push/pop the locator on ElementHandlerController
      }
    })

    // Tell the controller we are done with the new body
    controller.endBody()
  }
}