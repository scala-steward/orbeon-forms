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
package org.orbeon.oxf.xforms.action.actions

import org.orbeon.dom.QName
import org.orbeon.oxf.util.IndentedLogger
import org.orbeon.oxf.xforms.action.{DynamicActionContext, XFormsAction}
import org.orbeon.oxf.xforms.event.EventCollector.ErrorEventCollector
import org.orbeon.oxf.xforms.event.events.{XFormsRebuildEvent, XFormsRecalculateEvent}
import org.orbeon.oxf.xforms.event.{Dispatch, EventCollector, XFormsEvent}
import org.orbeon.oxf.xforms.model.{DefaultsStrategy, XFormsModel}
import org.orbeon.xforms.XFormsNames.*


trait RRRFunctions {
  def setFlag(model: XFormsModel, applyDefaults: Boolean): Unit
  def createEvent(model: XFormsModel): XFormsEvent
}

trait XFormsRebuildFunctions extends RRRFunctions {
  def setFlag(model: XFormsModel, applyDefaults: Boolean): Unit = model.deferredActionContext.markStructuralChange(DefaultsStrategy.None, None)
  def createEvent(model: XFormsModel): XFormsEvent = new XFormsRebuildEvent(model)
}

// Make `<xf:recalculate>` do the same thing as `<xf:revalidate>`
// See:
// - https://github.com/orbeon/orbeon-forms/issues/1650
// - https://github.com/orbeon/orbeon-forms/issues/4506
trait XFormsRecalculateRevalidateFunctions extends RRRFunctions {
  def setFlag(model: XFormsModel, applyDefaults: Boolean): Unit =
    model.deferredActionContext.markRecalculateRevalidate(
      defaultsStrategy = if (applyDefaults) DefaultsStrategy.All else DefaultsStrategy.None,
      instanceIdOpt    = None
    )
  def createEvent(model: XFormsModel): XFormsEvent = new XFormsRecalculateEvent(model)
}

// Concrete action classes
class XFormsRebuildAction     extends RRRAction with XFormsRebuildFunctions
class XFormsRecalculateAction extends RRRAction with XFormsRecalculateRevalidateFunctions
class XFormsRevalidateAction  extends RRRAction with XFormsRecalculateRevalidateFunctions

// Common functionality
trait RRRAction extends XFormsAction with RRRFunctions {

  override def execute(context: DynamicActionContext)(implicit logger: IndentedLogger): Unit = {

    val interpreter = context.interpreter
    val modelOpt    = interpreter.actionXPathContext.getCurrentBindingContext.modelOpt

    def resolve(qName: QName) =
      (Option(interpreter.resolveAVT(context.analysis, qName)) getOrElse "false").toBoolean

    modelOpt foreach { model =>
      val deferred      = resolve(XXFORMS_DEFERRED_QNAME)
      val applyDefaults = resolve(XXFORMS_DEFAULTS_QNAME)

      RRRAction.execute(this, model, context.collector, deferred, applyDefaults)
    }
  }
}

object RRRAction {

  private def execute(
    functions    : RRRFunctions,
    model        : XFormsModel,
    collector    : ErrorEventCollector,
    deferred     : Boolean = false,
    applyDefaults: Boolean = false,
  ): Unit = {
    // Set the flag in any case
    functions.setFlag(model, applyDefaults)

    // Perform the action immediately if needed
    // NOTE: XForms 1.1 and 2.0 say that no event should be dispatched in this case. It's a bit unclear what the
    // purpose of these events is anyway.
    if (! deferred)
      Dispatch.dispatchEvent(functions.createEvent(model), collector)
  }

  private object ConcreteRebuildFunctions     extends XFormsRebuildFunctions
  private object ConcreteRecalculateFunctions extends XFormsRecalculateRevalidateFunctions

  def rebuild(model: XFormsModel, deferred: Boolean = false): Unit =
    execute(ConcreteRebuildFunctions, model, EventCollector.Throw, deferred, applyDefaults = false)

  def recalculate(model: XFormsModel, deferred: Boolean = false, applyDefaults: Boolean = false): Unit =
    execute(ConcreteRecalculateFunctions, model, EventCollector.Throw, deferred, applyDefaults)
}