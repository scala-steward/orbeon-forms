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
package org.orbeon.oxf.xforms.function

import org.orbeon.oxf.xforms.analysis.ElementAnalysisTreeXPathAnalyzer
import org.orbeon.oxf.xforms.function.XFormsFunction.getPathMapContext
import org.orbeon.oxf.xml.DependsOnContextItem
import org.orbeon.saxon.expr.{PathMap, XPathContext}

/**
 * XForms 1.1 context() function.
 *
 * "7.10.4 The context() Function [...] This function returns the in-scope evaluation context node of the
 * nearest ancestor element of the node containing the XPath expression that invokes this function. The nearest
 * ancestor element may have been created dynamically as part of the run-time expansion of repeated content as
 * described in Section 4.7 Resolving ID References in XForms."
 */
class Context extends XFormsFunction with MatchSimpleAnalysis with DependsOnContextItem {

  override def evaluateItem(xpathContext: XPathContext) =
    bindingContext.contextItem

  override def addToPathMap(pathMap: PathMap, pathMapNodeSet: PathMap.PathMapNodeSet): PathMap.PathMapNodeSet =
    matchSimpleAnalysis(pathMap, getPathMapContext(pathMap).context)
}