package org.orbeon.oxf.fr.persistence.relational.rest

import org.orbeon.oxf.controller.XmlNativeRoute
import org.orbeon.oxf.externalcontext.{ExternalContext, UserAndGroup}
import org.orbeon.oxf.fr.persistence.PersistenceMetadataSupport
import org.orbeon.oxf.fr.persistence.api.PersistenceApi
import org.orbeon.oxf.fr.persistence.relational.Statement.*
import org.orbeon.oxf.fr.persistence.relational.{Provider, RelationalUtils}
import org.orbeon.oxf.http.{HttpMethod, HttpStatusCodeException, StatusCode}
import org.orbeon.oxf.pipeline.api.PipelineContext
import org.orbeon.oxf.processor.RegexpMatcher.MatchResult
import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.oxf.util.Logging.*
import org.orbeon.oxf.util.StringUtils.*
import org.orbeon.oxf.util.{ContentTypes, DateUtils, IndentedLogger}
import org.orbeon.oxf.xml.DeferredXMLReceiver

import scala.util.matching.Regex


object HistoryRoute extends XmlNativeRoute {

  import History.*

  def process(
    matchResult: MatchResult
  )(implicit
    pc         : PipelineContext,
    ec         : ExternalContext
  ): Unit = {

    val httpRequest  = ec.getRequest
    val httpResponse = ec.getResponse

    implicit val indentedLogger: IndentedLogger = RelationalUtils.newIndentedLogger

    try {
      require(httpRequest.getMethod == HttpMethod.GET)

      httpRequest.getRequestPath match {
        case ServicePathRe(provider, app, form, documentId, filenameOrNull) =>
          implicit val receiver: DeferredXMLReceiver = getResponseXmlReceiverSetContentType
          returnHistory(
            Request(httpRequest.getFirstParamAsString, provider),
            app,
            form,
            documentId,
            Option(filenameOrNull),
            PersistenceMetadataSupport.isInternalAdminUser(httpRequest.getFirstParamAsString)
          )
        case _ =>
          httpResponse.setStatus(StatusCode.NotFound)
      }
    } catch {
      case e: HttpStatusCodeException =>
        httpResponse.setStatus(e.code)
    }
  }
}

private object History {

  case class Request(
    pageSize  : Int,
    pageNumber: Int,
    provider  : Provider
  )

  case object Request {
    def apply(getParam: String => Option[String], providerString: String): Request =
      Request(
        pageSize   = RelationalUtils.parsePositiveIntParamOrThrow(getParam(PersistenceApi.PageSizeParam),  10),
        pageNumber = RelationalUtils.parsePositiveIntParamOrThrow(getParam(PersistenceApi.PageNumberParam), 1),
        provider   = Provider.withName(providerString)
      )
  }

  val ServicePathRe: Regex = "/fr/service/([^/]+)/history/([^/]+)/([^/]+)/([^/^.]+)(?:/([^/]+))?".r

  def returnHistory(
    request            : Request,
    app                : String,
    form               : String,
    documentId         : String,
    filenameOpt        : Option[String],
    isInternalAdminUser: Boolean // 2024-07-18: Unused, see https://github.com/orbeon/orbeon-forms/issues/6416
  )(implicit
    receiver           : DeferredXMLReceiver,
    externalContext    : ExternalContext,
    indentedLogger     : IndentedLogger
  ): Unit = {
    RelationalUtils.withConnection { connection =>

      val startOffsetZeroBased = (request.pageNumber - 1) * request.pageSize
      val rowNumSQL            = Provider.rowNumSQL(
        provider       = request.provider,
        connection     = connection,
        orderBy        = "d.last_modified_time DESC"
      )
      val rowNumCol            = rowNumSQL.col
      val rowNumOrderBy        = rowNumSQL.orderBy
      val rowNumTable          = rowNumSQL.table match {
        case Some(table) => table + ","
        case None        => ""
      }

      val tableName =
        filenameOpt match {
          case None                 => "orbeon_form_data"
          case Some(NonAllBlank(_)) => "orbeon_form_data_attach" // TODO: use constants for table names
          case Some(_)              => throw HttpStatusCodeException(StatusCode.BadRequest)
        }

      val hasStage = filenameOpt.isDefined

      val innerSQL =
        s"""|SELECT  t.last_modified_time, t.last_modified_by, t.created
            |        , t.username, t.groupname, t.organization_id
            |        ${if (hasStage) ", t.stage" else ""}
            |        , t.form_version, t.deleted
            |FROM    $tableName t
            |WHERE   t.app  = ?
            |        and t.form = ?
            |        and t.document_id = ?
            |        and t.draft = ?
            |        ${if (filenameOpt.isDefined) "and t.file_name = ?" else ""}
            |""".stripMargin

      // Boilerplate for cross-database paging, see also `SearchLogic`
      val sql =
        s"""SELECT
           |    c.*
           |FROM
           |    (
           |        SELECT
           |            d.*,
           |            $rowNumCol
           |        FROM
           |             $rowNumTable
           |             (
           |                 $innerSQL
           |             ) d
           |        $rowNumOrderBy
           |    ) c
           | WHERE
           |    row_num
           |        BETWEEN ${startOffsetZeroBased + 1}
           |        AND     ${startOffsetZeroBased + request.pageSize}
           |""".stripMargin

      val setters =
        List[Setter](
          (ps, i) => ps.setString(i, app),
          (ps, i) => ps.setString(i, form),
          (ps, i) => ps.setString(i, documentId),
          (ps, i) => ps.setString(i, "N"),
        ) :::
        filenameOpt.toList.map { filename =>
          ((ps, i) => ps.setString(i, filename)): Setter
        }

      val (searchTotal, minLastModifiedTime, maxLastModifiedTime) = {

        val sql =
          s"""SELECT count(*) total,
             |       min(a.last_modified_time) min_last_modified_time,
             |       max(a.last_modified_time) max_last_modified_time
             |  FROM (
             |       $innerSQL
             |       ) a
           """.stripMargin

        debug(s"search total query:\n$sql")
        executeQuery(connection, sql, List(StatementPart("", setters))) { rs =>
          rs.next()

          (
            rs.getInt("total"),
            Option(rs.getTimestamp("min_last_modified_time")).map(_.toInstant),
            Option(rs.getTimestamp("max_last_modified_time")).map(_.toInstant)
          )
        }
      }

      executeQuery(connection, sql, List(StatementPart("", setters))) { rs =>

        import org.orbeon.oxf.xml.XMLReceiverSupport.*

        var position = 0

        withDocument {
          withElement(
            "documents",
            atts =
              List(
                "application-name"       -> app,
                "form-name"              -> form,
                "document-id"            -> documentId,
                "total"                  -> searchTotal.toString
              ) :::
              (searchTotal > 0).flatList(
                List(
                  "min-last-modified-time" -> minLastModifiedTime.map(DateUtils.formatIsoDateTimeUtc).getOrElse(throw new IllegalArgumentException),
                  "max-last-modified-time" -> maxLastModifiedTime.map(DateUtils.formatIsoDateTimeUtc).getOrElse(throw new IllegalArgumentException)
                )
              ) :::
              List(
                "page-size"              -> request.pageSize.toString,
                "page-number"            -> request.pageNumber.toString,
              )
          ) {
            while(rs.next()) {

              if (position == 0) {
                receiver.addAttribute("", "form-version", "form-version", rs.getString("form_version"))
                receiver.addAttribute("", "created-time", "created-time", DateUtils.formatIsoDateTimeUtc(rs.getTimestamp("created").toInstant))
                receiver.addAttribute("", "created-username", "created-username", "TODO") // xxx
              }

              val userAndGroup = UserAndGroup.fromStrings(rs.getString("username"), rs.getString("groupname"))
              val organization = OrganizationSupport.readFromResultSet(connection, rs).map(_._2)

              element(
                "document",
                atts =
                  (hasStage list ("stage" -> rs.getString("stage").trimAllToEmpty)) :::
                  List(
                    "modified-time"     -> DateUtils.formatIsoDateTimeUtc(rs.getTimestamp("last_modified_time").toInstant),
                    "modified-username" -> rs.getString("last_modified_by").trimAllToEmpty,
                    "owner-username"    -> userAndGroup.map(_.username).getOrElse(""),
                    "owner-group"       -> userAndGroup.flatMap(_.groupname).getOrElse(""),
                    "deleted"           -> (rs.getString("deleted") == "Y").toString,
                  )
              )

              position += 1
            }
          }
        }
      }
    }
  }
}