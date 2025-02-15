/**
  * Copyright (C) 2017 Orbeon, Inc.
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
package org.orbeon.jquery

import io.udash.wrappers.jquery.JQuery

import scala.scalajs.js


// TODO: facade no longer needed, should directly use `io.udash.wrappers.jquery.Offset`
case class Offset(left: Double, top: Double)

object Offset {

  def apply(v: JQuery): Offset = {
    val dyn = v.offset()
    Offset(dyn.left, dyn.top)
  }

  def offset(el: JQuery, offset: Offset): Unit =
    el.offset(io.udash.wrappers.jquery.Offset(
      left = offset.left,
      top  = offset.top
    ))
}