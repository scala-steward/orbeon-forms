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
package org.orbeon.oxf.xforms.function.xxforms

import org.orbeon.oxf.xforms.model.InstanceData
import org.orbeon.saxon.expr.XPathContext
import org.orbeon.saxon.value.StringValue
import org.orbeon.scaxon.Implicits.*


/**
 * xxf:custom-mip($item as item()*, $mip-name as xs:string) as xs:string
 */
class XXFormsCustomMIP extends XXFormsMIPFunction {

  override def evaluateItem(xpathContext: XPathContext): StringValue =
    // NOTE: Custom MIPs are registered with a qualified name string. It would be better to use actual QNames
    // so that the prefix is not involved. The limitation for now is that you have to use the same prefix as
    // the one used on the binds. See also https://github.com/orbeon/orbeon-forms/issues/3721.
    InstanceData.findCustomMip(
      binding = argument(0).iterate(xpathContext).next(),
      qName   = getQNameFromExpression(argument(1))(xpathContext)
    )
}
