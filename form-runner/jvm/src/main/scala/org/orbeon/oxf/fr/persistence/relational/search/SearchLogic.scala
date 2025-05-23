/**
 * Copyright (C) 2016 Orbeon, Inc.
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
package org.orbeon.oxf.fr.persistence.relational.search

import org.orbeon.oxf.externalcontext.{ExternalContext, Organization, UserAndGroup}
import org.orbeon.oxf.fr.SearchVersion
import org.orbeon.oxf.fr.Version.OrbeonFormDefinitionVersion
import org.orbeon.oxf.fr.permission.PermissionsAuthorization.CheckWithDataUser
import org.orbeon.oxf.fr.permission.*
import org.orbeon.oxf.fr.persistence.PersistenceMetadataSupport
import org.orbeon.oxf.fr.persistence.relational.Statement.*
import org.orbeon.oxf.fr.persistence.relational.rest.{OrganizationId, OrganizationSupport}
import org.orbeon.oxf.fr.persistence.relational.search.adt.Metadata.LastModified
import org.orbeon.oxf.fr.persistence.relational.search.adt.*
import org.orbeon.oxf.fr.persistence.relational.search.part.*
import org.orbeon.oxf.fr.persistence.relational.{Provider, RelationalUtils}
import org.orbeon.oxf.util.CollectionUtils.*
import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.oxf.util.IndentedLogger
import org.orbeon.oxf.util.Logging.*
import org.orbeon.oxf.util.StringUtils.*
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.SimplePath.{Document as _, *}

import java.sql.Connection
import scala.collection.mutable


object SearchLogic {

  // TODO: The methods below are used by the search API, but also by the distinct values API. Should we leave them
  //  here, as they're more closely associated with the search API, or move them elsewhere (e.g. in the parent package),
  //  in which case some classes/methods should be renamed (SearchVersion, SearchPermissions, doSearch, etc.)?

  def searchVersion(httpRequest: ExternalContext.Request): SearchVersion =
    SearchVersion(httpRequest.getFirstHeaderIgnoreCase(OrbeonFormDefinitionVersion))

  def anyOfOperations(rootElement: NodeInfo): Option[Set[Operation]] =
    rootElement.child("operations").headOption.map(_.attValue("any-of").splitTo[Set]().map(Operation.withName))

  private def computePermissions(
    request       : SearchRequestCommon
  )(implicit
    indentedLogger: IndentedLogger
  ): SearchPermissions = {

    val searchOperations = request.anyOfOperations.getOrElse(SearchOps.SearchOperations)

    // The persistence proxy should pre-process and post-process the information, see:
    // https://github.com/orbeon/orbeon-forms/issues/5741
    val formPermissions =
      PersistenceMetadataSupport.readFormPermissionsMaybeWithAdminSupport(
        request.isInternalAdminUser,
        request.appForm,
        request.version
      )

    SearchPermissions(
      formPermissions,
      authorizedBasedOnRoleOptimistic  = PermissionsAuthorization.authorizedBasedOnRole(formPermissions, request.credentials, searchOperations, optimistic = true),
      authorizedBasedOnRolePessimistic = PermissionsAuthorization.authorizedBasedOnRole(formPermissions, request.credentials, searchOperations, optimistic = false),
      authorizedIfUsername             = PermissionsAuthorization.hasPermissionCond(formPermissions, Condition.Owner, searchOperations).flatOption(request.credentials.map(_.userAndGroup.username)),
      authorizedIfGroup                = PermissionsAuthorization.hasPermissionCond(formPermissions, Condition.Group, searchOperations).flatOption(request.credentials.map(_.userAndGroup.groupname)).flatten,
      authorizedIfOrganizationMatch    = SearchOps.authorizedIfOrganizationMatch(formPermissions, request.credentials)
    )
  }

  def runBodyIfHasSomePermissions[T, R <: SearchRequestCommon](
    request           : R,
    queries           : List[Query],
    freeTextSearch    : Option[String],
    noPermissionValue : T,
    connectionOpt     : Option[Connection]
  )(
    body              : (Connection, List[StatementPart], SearchPermissions) => T
  )(implicit
    externalContext   : ExternalContext,
    indentedLogger    : IndentedLogger
  ): T = {
    val permissions      = computePermissions(request)
    val hasNoPermissions =
      ! permissions.authorizedBasedOnRoleOptimistic     &&
      permissions.authorizedIfUsername         .isEmpty &&
      permissions.authorizedIfGroup            .isEmpty &&
      permissions.authorizedIfOrganizationMatch.isEmpty

    if (hasNoPermissions)
      // There is no chance we can access any data, no need to run any SQL
      noPermissionValue
    else {
      val commonStatementPart = commonPart(request.appForm, request.version, queries, freeTextSearch)
      val bodyPartial         = body(_, List(commonStatementPart, permissionsPart(permissions)), permissions)
      connectionOpt match {
        case Some(connection) => bodyPartial(connection)
        case None             => RelationalUtils.withConnection(bodyPartial)
      }
    }
  }

  def doSearch(
    request        : SearchRequest,
    connectionOpt  : Option[Connection]
  )(implicit
    externalContext: ExternalContext,
    indentedLogger : IndentedLogger
  ): (List[Document], Int) =
    SearchLogic.runBodyIfHasSomePermissions(
      request           = request,
      queries           = request.queries,
      freeTextSearch    = request.freeTextSearch,
      noPermissionValue = (List[Document](), 0),
      connectionOpt     = connectionOpt
    ) {
      (connection: Connection, commonAndPermissionsParts: List[StatementPart], permissions: SearchPermissions) =>

        val statementParts = commonAndPermissionsParts ++ metadataPart(request).toList ++ List(
          columnFilterPart  (request),
          draftsPart        (request),
          freeTextFilterPart(request)
        )
        val innerSQL       = buildQuery(statementParts)

        val searchCount = {
          val sql =
            s"""SELECT count(*)
               |  FROM (
               |       $innerSQL
               |       ) a
             """.stripMargin

          debug(s"search total query\n$sql")
          executeQuery(connection, sql, statementParts) { rs =>
            rs.next()
            rs.getInt(1)
          }
        }

        // To order the results by control value, we need to join with orbeon_i_control_text
        val (orderByJoin, orderByStatementPartOpt) = request.orderQuery match {
          case ControlQuery(controlPath, _, _) => (
            "LEFT JOIN orbeon_i_control_text t ON t.data_id = s.data_id AND t.control = ?",
            Some(StatementPart("", List[Setter]((ps, i) => ps.setString(i, controlPath))))
          )
          case _                               => ("", None)
        }

        // We'll refer to the order by column as "sort_column"
        val orderByTableAlias = request.orderQuery match {
          case _: ControlQuery => "t" // Control value => comes from orbeon_i_control_text t
          case _               => "c" // Form metadata => comes from orbeon_i_current c
        }
        val orderByAliasing   = s"$orderByTableAlias.${request.orderQuery.sqlColumn} AS sort_column"

        // First order by clause (specified by request), with CAST if necessary
        val firstOrderByColumn         = "d.sort_column"
        val firstOrderByColumnWithCast = request.orderQuery match {
          case  _: ControlQuery =>
            // DB2, Oracle, and SQL Server use CLOB/NTEXT data types for control values, which cannot be used in ORDER
            // BY clauses, so we cast them to VARCHAR.
            request.provider match {
              case _         => firstOrderByColumn
            }
          case _                =>
            firstOrderByColumn
        }
        val firstOrderByClause         = s"$firstOrderByColumnWithCast ${request.orderQuery.orderDirection.get.sql}"

        // Second order by clause (we order by last_modified_time DESC as well, if it makes sense)
        val secondOrderByClause = request.orderQuery match {
          case MetadataQuery(LastModified, _, _) => ""
          case _                                 => ", d.last_modified_time DESC"
        }

        val orderByClauses = firstOrderByClause + secondOrderByClause

        // Build SQL and create statement
        val sql = {
          val startOffsetZeroBased = (request.pageNumber - 1) * request.pageSize
          val rowNumSQL            = Provider.rowNumSQL(
            provider       = request.provider,
            connection     = connection,
            orderBy        = orderByClauses,
          )
          val rowNumCol            = rowNumSQL.col
          val rowNumOrderBy        = rowNumSQL.orderBy
          val rowNumTable          = rowNumSQL.table match {
            case Some(table) => table + ","
            case None        => ""
          }

          /*
          TODO: Is the INNER JOIN below really necessary? We end up doing it on orbeon_i_current only, i.e.
                  SELECT c.*
                  FROM   (SELECT DISTINCT c.data_id
                          FROM   orbeon_i_current c
                          WHERE  c.app = ?
                                 AND c.form = ?
                                 AND c.form_version = ?) s
                         INNER JOIN orbeon_i_current c
                                 ON c.data_id = s.data_id
           */

          // Use `LEFT JOIN` instead of regular join, in case the form doesn't have any control marked
          // to be indexed, in which case there won't be anything for it in `orbeon_i_control_text`.
          s"""SELECT
             |    c.*,
             |    t.control,
             |    t.pos,
             |    t.val
             |FROM
             |    (
             |        SELECT
             |            d.*,
             |            $rowNumCol
             |        FROM
             |            (
             |                SELECT
             |                    c.*, $orderByAliasing
             |                FROM
             |                    $rowNumTable
             |                    (
             |                        $innerSQL
             |                    ) s
             |                INNER JOIN
             |                    orbeon_i_current c
             |                    ON c.data_id = s.data_id
             |                $orderByJoin
             |            ) d
             |        $rowNumOrderBy
             |    ) c
             | LEFT JOIN
             |    orbeon_i_control_text t
             |    ON t.data_id = c.data_id
             | WHERE
             |    row_num
             |        BETWEEN ${startOffsetZeroBased + 1}
             |        AND     ${startOffsetZeroBased + request.pageSize}
             | ORDER BY row_num
             |""".stripMargin
        }
        debug(s"search items query\n$sql")

        val allStatementParts            = statementParts ::: orderByStatementPartOpt.toList
        val rawDocumentMetadataAndValues = executeQuery(connection, sql, allStatementParts) { documentsResultSet =>

          Iterator.iterateWhile(
            cond = documentsResultSet.next(),
            elem = (
                DocumentMetadata(
                  documentId       = documentsResultSet.getString                 ("document_id"),
                  draft            = documentsResultSet.getString                 ("draft") == "Y",
                  createdTime      = documentsResultSet.getTimestamp              ("created"),
                  lastModifiedTime = documentsResultSet.getTimestamp              ("last_modified_time"),
                  createdBy        = UserAndGroup.fromStrings(documentsResultSet.getString("username"),         documentsResultSet.getString("groupname")),
                  lastModifiedBy   = UserAndGroup.fromStrings(documentsResultSet.getString("last_modified_by"), ""),
                  workflowStage    = Option(documentsResultSet.getString          ("stage")),
                  organizationId   = RelationalUtils.getIntOpt(documentsResultSet, "organization_id")
                ),
                DocumentValue(
                  control          = documentsResultSet.getString                 ("control"),
                  pos              = documentsResultSet.getInt                    ("pos"),
                  value            = documentsResultSet.getString                 ("val")
                )
            )
          ).toList
        }

        // The order of the document metadata in the SQL results is already correct, take it as a reference for the final order
        val orderedDocumentMetadata          = rawDocumentMetadataAndValues.map(_._1).distinct
        val documentValuesByDocumentMetadata = rawDocumentMetadataAndValues.groupBy(_._1).view.mapValues(_.map(_._2))
        val documentMetadataAndValues        = orderedDocumentMetadata.map { documentMetadata =>
          // Keep document metadata order and group all values together
          documentMetadata -> documentValuesByDocumentMetadata(documentMetadata)
        }

        // Compute possible operations for each document
        val organizationsCache = mutable.Map[Int, Organization]()
        val documents = documentMetadataAndValues.map{ case (metadata, values) =>
            def readFromDatabase(id: Int) = OrganizationSupport.read(connection, OrganizationId(id)).get
            val organization              = metadata.organizationId.map(id => organizationsCache.getOrElseUpdate(id, readFromDatabase(id)))
            val check                     = CheckWithDataUser(metadata.createdBy, organization)
            val operations                = PermissionsAuthorization.authorizedOperations(permissions.formPermissions, request.credentials, check)
            Document(metadata, Operations.serialize(operations, normalized = true).mkString(" "), values)
          }
        (documents, searchCount)
    }
}
