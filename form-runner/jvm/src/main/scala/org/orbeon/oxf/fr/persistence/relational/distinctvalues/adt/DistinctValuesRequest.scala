/**
 * Copyright (C) 2023 Orbeon, Inc.
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
package org.orbeon.oxf.fr.persistence.relational.distinctvalues.adt

import org.orbeon.oxf.externalcontext.Credentials
import org.orbeon.oxf.fr.permission.Operation
import org.orbeon.oxf.fr.persistence.relational.Provider
import org.orbeon.oxf.fr.persistence.relational.search.adt.SearchRequestCommon
import org.orbeon.oxf.fr.{AppForm, FormDefinitionVersion}


case class DistinctValuesRequest(
  provider           : Provider,
  appForm            : AppForm,
  version            : FormDefinitionVersion,
  credentials        : Option[Credentials],
  anyOfOperations    : Option[Set[Operation]],
  isInternalAdminUser: Boolean,
  controlPaths       : List[String],
  metadata           : List[Metadata]
) extends SearchRequestCommon
