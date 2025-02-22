/**
  * Copyright (C) 2019 Orbeon, Inc.
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
package org.orbeon.xbl

import org.orbeon.date.{DateFormat, IsoDate, IsoTime, TimeFormat}


case class DateExternalValue(
  isoOrUnrecognizedValue: IsoDate Either String,
  format                : DateFormat,
  excludedDates         : List[String],
  weekStart             : Option[Int]
) {
  def stringValue: String =
    isoOrUnrecognizedValue.fold(IsoDate.formatDate(_, format), identity)
}

case class TimeExternalValue(
  isoOrUnrecognizedValue: IsoTime Either String,
  format                : TimeFormat
) {
  def stringValue: String =
    isoOrUnrecognizedValue.fold(IsoTime.formatTime(_, format), identity)
}