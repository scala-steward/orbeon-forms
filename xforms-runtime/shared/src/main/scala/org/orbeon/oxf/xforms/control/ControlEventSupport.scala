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
package org.orbeon.oxf.xforms.control

import org.orbeon.oxf.util.Logging.*
import org.orbeon.oxf.xforms.*
import org.orbeon.oxf.xforms.analysis.controls.ViewTrait
import org.orbeon.oxf.xforms.control.Controls.AncestorOrSelfIterator
import org.orbeon.oxf.xforms.control.controls.XFormsRepeatIterationControl
import org.orbeon.oxf.xforms.event.EventCollector.ErrorEventCollector
import org.orbeon.oxf.xforms.event.*
import org.orbeon.oxf.xforms.event.events.*


trait ControlEventSupport extends ListenersTrait {

  self: XFormsControl =>

  def performDefaultAction(event: XFormsEvent, collector: ErrorEventCollector): Unit = event match {
    case _ @ (_: XXFormsRepeatActivateEvent | _: XFormsFocusEvent) =>
      // Try to update xf:repeat indexes based on this

      // Find current path through ancestor xf:repeat elements, if any
      val repeatIterationsToModify =
        new AncestorOrSelfIterator(self) collect
          { case ri: XFormsRepeatIterationControl if ! ri.isCurrentIteration => ri.effectiveId }

      // NOTE: It would be nice to review whether it makes sense to re-obtain controls by id in the code below. Is
      // there a use case for it? Events can be dispatched via setIndex(), which means that repeats and relevance
      // can change. But is there a better way?
      if (repeatIterationsToModify.nonEmpty) {
        val controls = containingDocument.controls
        // Find all repeat iterations and controls again
        for (repeatIterationEffectiveId <- repeatIterationsToModify) {
          val repeatIterationControl =
            containingDocument.getControlByEffectiveId(repeatIterationEffectiveId).asInstanceOf[XFormsRepeatIterationControl]
          val newRepeatIndex = repeatIterationControl.iterationIndex

          debug("xf:repeat: setting index upon focus change", List("new index" -> newRepeatIndex.toString))(controls.indentedLogger)

          repeatIterationControl.repeat.setIndex(newRepeatIndex, collector)
        }
      }

      // Focus on current control if possible
      event match {
        case focusEvent: XFormsFocusEvent =>

          // Try to update hidden `xf:case` controls
          // NOTE: We don't allow this behavior when events come from the client in ClientEvents
          // NOTE: See note above on re-obtaining controls by id. Do we need to do this here as well?
          Focus.ancestorOrSelfHiddenCases(this.parent) foreach (_.toggle(collector))

          val includes = focusEvent.includes
          val excludes = focusEvent.excludes

          def satisfiesIncludesAndExcludes(c: XFormsControl): Boolean = {
            val qName = c.staticControl.element.getQName
            (includes.isEmpty || includes.contains(qName)) && ! excludes.contains(qName)
          }

          directlyFocusableControls      find
            satisfiesIncludesAndExcludes foreach
            Focus.focusWithEvents

        case _ =>
      }

    case _: XFormsHelpEvent =>
      containingDocument.setClientHelpEffectiveControlId(effectiveId)
    case ev: XXFormsXPathErrorEvent =>
      XFormsError.handleNonFatalXPathError(container, ev.throwableOpt, ev.combinedMessage)
    case ev: XXFormsBindingErrorEvent =>
      XFormsError.handleNonFatalBindingError(this, Option(ev.locationData), ev.reasonOpt)
    case ev: XXFormsActionErrorEvent =>
      XFormsError.handleNonFatalActionError(this, ev.throwableOpt)
    case _ =>
  }

  def performTargetAction(event: XFormsEvent, collector: ErrorEventCollector): Unit = ()

  // Check whether this concrete control supports receiving the external event specified
  final def allowExternalEvent(eventName: String): Boolean = staticControl match {
    case viewTrait: ViewTrait => viewTrait.externalEvents(eventName)
    case _ => false
  }

  // TODO LATER: should probably return true because most controls could then dispatch relevance events
  def supportsRefreshEvents: Boolean = false

  // Dispatch creation events
  def dispatchCreationEvents(collector: ErrorEventCollector): Unit = {
    commitCurrentUIState()
    Dispatch.dispatchEvent(new XFormsEnabledEvent(this), collector)
  }

  // Dispatch change events (between the control becoming enabled and disabled)
  def dispatchChangeEvents(collector: ErrorEventCollector): Unit = ()

  // Dispatch destruction events
  def dispatchDestructionEvents(collector: ErrorEventCollector): Unit = {
    // Don't test for relevance here
    // - in iteration removal case, control is still relevant
    // - in refresh case, control is non-relevant
    Dispatch.dispatchEvent(new XFormsDisabledEvent(this), collector)
  }

  final def parentEventObserver: XFormsEventTarget = Option(parent).orNull
}
