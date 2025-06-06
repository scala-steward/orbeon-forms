/**
 * Copyright (C) 2012 Orbeon, Inc.
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
package org.orbeon.exception

import org.orbeon.errorified.*
import org.orbeon.oxf.common.{OrbeonLocationException, ValidationException}
import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.datatypes.{ExtendedLocationData, LocationData}
import org.orbeon.oxf.util.StringUtils.*


// Orbeon-specific exception formatter
object OrbeonFormatter extends Formatter {

  val Width = 120
  val MaxStackLength = 80

  override def getThrowableMessage(throwable: Throwable): Option[String] =
    throwable match {
      case ve: ValidationException => Option(ve.message)
      case t => Option(t.getMessage)
    }

  override def getAllLocationData(t: Throwable): List[SourceLocation] =
    OrbeonLocationException.getAllLocationData(t) flatMap sourceLocation

  // Create SourceLocation from LocationData
  private def sourceLocation(locationData: LocationData): Option[SourceLocation] = {

    val (description, params) =
      locationData match {
        case extended: ExtendedLocationData => (extended.description, extended.params)
        case _                              => (None, Nil)
      }

    val locationDataHasFileOrExpression =
      locationData.file.nonAllBlank ||
      params.exists(_._1 == "expression")

    locationDataHasFileOrExpression.option {
      SourceLocation(
        Option(locationData.file).filter(_.nonAllBlank).getOrElse("Location not available"),
        filterLineCol(locationData.line),
        filterLineCol(locationData.col),
        description,
        params.to(List)
      )
    }
  }
}
