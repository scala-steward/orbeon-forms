/**
 *  Copyright (C) 2007 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.model

import org.orbeon.oxf.xforms.*
import org.orbeon.oxf.xforms.analysis.{ElementAnalysis, EventHandler}
import org.orbeon.oxf.xforms.event.{EventCollector, XFormsEventHandler, XFormsEventTarget}
import org.orbeon.oxf.xforms.submission.XFormsModelSubmission
import org.orbeon.xforms.XFormsId
import org.orbeon.xforms.runtime.XFormsObject


class XFormsModelAction(parent: XFormsEventTarget, eventHandler: EventHandler)
  extends XFormsEventHandler
  with XFormsObject {

  val effectiveId                      = XFormsId.getRelatedEffectiveId(parent.effectiveId, eventHandler.staticId)
  def container                        = parent.container
  def containingDocument               = parent.containingDocument
  def modelOpt: Option[XFormsModel]    = parent.modelOpt
  def elementAnalysis: ElementAnalysis = eventHandler

  // This is called by `Dispatch`
  def bindingContext: BindingContext =
    parent match {
      case model: XFormsModel =>
        // Use the model's inner context
        model.getDefaultEvaluationContext
      case submission: XFormsModelSubmission =>
        // Evaluate the binding of the submission element based on the model's inner context
        // NOTE: When the submission actually starts processing, the binding will be re-evaluated
        val contextStack = new XFormsContextStack(submission.container, submission.model.getDefaultEvaluationContext)
        contextStack.pushBinding(
          submission.staticSubmission.element,
          submission.effectiveId,
          submission.model.getResolutionScope,
          submission,
          EventCollector.ToReview
        )
        contextStack.getCurrentBindingContext
      case _ =>
        // We know we are either nested directly within the model, or within a submission
        throw new IllegalStateException
    }
}