/**
 * Copyright (C) 2015 Orbeon, Inc.
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
package org.orbeon.oxf.xforms.model

import org.orbeon.oxf.xforms.xbl.XBLContainer

sealed trait DefaultsStrategy

object DefaultsStrategy {

  sealed trait Some extends DefaultsStrategy

  object None    extends DefaultsStrategy
  object Flagged extends DefaultsStrategy with Some
  object All     extends DefaultsStrategy with Some
}

// A single instance of this class is associated with each XFormsModel. It keeps track of rebuild and recalculate /
// revalidate requirements at a very coarse level.
class DeferredActionContext(container: XBLContainer) {

  private def winningStrategy(st1: DefaultsStrategy, st2: DefaultsStrategy) =
    if (st1 == DefaultsStrategy.All || st2 == DefaultsStrategy.All)
      DefaultsStrategy.All
    else if (st1 == DefaultsStrategy.Flagged || st2 == DefaultsStrategy.Flagged)
      DefaultsStrategy.Flagged
    else
      DefaultsStrategy.None

  private var _rebuild = false
  def rebuild: Boolean = _rebuild

  private var _recalculateRevalidate = false
  def recalculateRevalidate: Boolean = _recalculateRevalidate

  private var _defaultsStrategy: DefaultsStrategy = DefaultsStrategy.None
  def defaultsStrategy: DefaultsStrategy = _defaultsStrategy

  private var _flaggedInstances = Set.empty[String]
  def flaggedInstances: Set[String] = _flaggedInstances

  def resetRebuild(): Unit = _rebuild = false

  def markRecalculateRevalidate(defaultsStrategy: DefaultsStrategy, instanceIdOpt: Option[String]): Unit = {
    _recalculateRevalidate = true
    _defaultsStrategy      = winningStrategy(_defaultsStrategy, defaultsStrategy)

    if (defaultsStrategy == DefaultsStrategy.Flagged)
      _flaggedInstances ++= instanceIdOpt
  }

  def resetRecalculateRevalidate(): Unit = {
    _recalculateRevalidate = false
    _defaultsStrategy      = DefaultsStrategy.None
    _flaggedInstances      = Set.empty
  }

  def markStructuralChange(defaultsStrategy: DefaultsStrategy, instanceIdOpt: Option[String]): Unit = {

    _rebuild = true
    markRecalculateRevalidate(defaultsStrategy, instanceIdOpt)

    container.requireRefresh()
  }

  def markValueChange(isCalculate: Boolean): Unit = {
    // "XForms Actions that change only the value of an instance node results in setting the flags for
    // recalculate, revalidate, and refresh to true and making no change to the flag for rebuild".

    // Only set recalculate when we are not currently performing a recalculate (avoid infinite loop)
    if (! isCalculate)
      markRecalculateRevalidate(DefaultsStrategy.None, None)

    container.requireRefresh()
  }
}