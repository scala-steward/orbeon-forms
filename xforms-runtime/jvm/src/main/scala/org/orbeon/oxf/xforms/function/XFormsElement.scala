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
package org.orbeon.oxf.xforms.function

import org.orbeon.oxf.xforms.NodeInfoFactory.elementInfo
import org.orbeon.saxon.expr.XPathContext
import org.orbeon.saxon.om.{EmptyIterator, Item}
import org.orbeon.scaxon.Implicits.*

/**
 * xf:element()
 */
class XFormsElement extends XFormsFunction {

  override def evaluateItem(xpathContext: XPathContext): Item = {

    // Element QName and content sequence
    val qName   = argument.lift(0) map (getQNameFromExpression(_)(xpathContext)) get
    val content = argument.lift(1) map (_.iterate(xpathContext)) getOrElse EmptyIterator.getInstance

    elementInfo(qName, asScalaIterator(content).toList)
  }
}
