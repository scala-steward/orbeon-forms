/**
  * Copyright (C) 2013 Orbeon, Inc.
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
package org.orbeon.oxf.fr.persistence.relational.rest

import org.orbeon.oxf.externalcontext.ExternalContext
import org.orbeon.oxf.fr.persistence.relational.*
import org.orbeon.oxf.util.CoreUtils.*

import java.sql.{PreparedStatement, Timestamp}


object SqlSupport {

  def tableName(request: CrudRequest, master: Boolean = false): String =
    Seq(
      Some("orbeon_form"),
      request.forForm                   option "_definition",
      request.forData                   option "_data",
      request.forAttachment && ! master option "_attach"
    ).flatten.mkString

  // List of columns that identify a row
  def idColumns(req: CrudRequest): List[String] =
    List(
      Some("app"),
      Some("form"),
      req.forForm       option "form_version",
      req.forData       option "document_id",
      req.forData       option "draft",
      req.forAttachment option "file_name"
    ).flatten

  def joinColumns(cols: Seq[String], t1: String, t2: String): String =
    cols.map(c => s"$t1.$c = $t2.$c").mkString(" AND ")

  private type ParamSetterFunc = (PreparedStatement, Int) => Unit

  private def param[T](setter: PreparedStatement => (Int, T) => Unit, value: => T): ParamSetterFunc = {
    (ps: PreparedStatement, i: Int) => setter(ps)(i, value)
  }

  sealed abstract class ColValue

  case class StaticColValue (
    value: String
  ) extends ColValue

  case class DynamicColValue (
    placeholder            : String,
    paramSetter            : ParamSetterFunc
  ) extends ColValue

  case class Col(
    name                   : String,
    value                  : ColValue
  )

  def insertCols(
    req                    : CrudRequest,
    reqBodyOpt             : Option[RequestReader.Body],
    delete                 : Boolean,
    versionToSet           : Int,
    currentTimestamp       : Timestamp,
    currentUserOrganization: => Option[OrganizationId]
  )(implicit
    externalContext: ExternalContext
  ): List[Col]  = {

    val xmlCol = Provider.xmlColUpdate(req.provider)
    val xmlVal = Provider.xmlValUpdate(req.provider)
    val isFormDefinition = req.forForm && ! req.forAttachment
    val organizationToSet = req.forData match {
      case false => None
      case true  => req.existingRow match {
        case Some(row) => row.organization
        case None      => currentUserOrganization.map(_.underlying)
      }
    }

    val (xmlOpt, metadataOpt) =
      if (! delete && ! req.forAttachment) {
        RequestReader.dataAndMetadataAsString(reqBodyOpt, req.provider, metadata = !req.forData)
      } else {
        (None, None)
      }

    (
      Provider.idColGetter(req.provider) match {
        case Some(getter) if req.forDataNotAttachment =>
          List(
            Col(
              name          = "id",
              value         = StaticColValue(getter)
            )
          )
        case _ => Nil
      }
    ) ::: List(
      Col(
        name          = "created",
        value         = DynamicColValue(
          placeholder = "?",
          paramSetter = param(_.setTimestamp, req.existingRow.map(_.createdTime).map(Timestamp.from).getOrElse(currentTimestamp))
        )
      ),
      Col(
        name          = "last_modified_time",
        value         = DynamicColValue(
          placeholder = "?",
          paramSetter = param(_.setTimestamp, currentTimestamp)
        )
      ),
      Col(
        name          = "last_modified_by",
        value         = DynamicColValue(
          placeholder = "?",
          paramSetter = param(_.setString, req.username.orNull)
        )
      ),
      Col(
        name          = "app",
        value         = DynamicColValue(
          placeholder  = "?",
          paramSetter  = param(_.setString, req.appForm.app)
        )
      ),
      Col(
        name          = "form",
        value         = DynamicColValue(
          placeholder = "?",
          paramSetter = param(_.setString, req.appForm.form)
        )
      ),
      Col(
        name          = "form_version",
        value         = DynamicColValue(
          placeholder = "?",
          paramSetter = param(_.setInt, versionToSet)
        )
      )
    ) ::: (
      req.forData && req.dataPart.get.stage.isDefined list
        Col(
          name          = "stage",
          value         = DynamicColValue(
            placeholder = "?",
            paramSetter = param(_.setString, req.dataPart.get.stage.get)
          )
        )
    ) ::: (
      req.forData list
        Col(
          name          = "document_id",
          value         = DynamicColValue(
            placeholder = "?",
            paramSetter = param(_.setString, req.dataPart.get.documentId)
          )
        )
    ) ::: List(
      Col(
        name          = "deleted",
        value         = DynamicColValue(
          placeholder = "?",
          paramSetter = param(_.setString, if (delete) "Y" else "N")
        )
      )
    ) ::: (
      req.forData list
        Col(
          name          = "draft",
          value         = DynamicColValue(
            placeholder = "?",
            paramSetter = param(_.setString, if (req.dataPart.get.isDraft) "Y" else "N")
          )
        )
    ) ::: (
      req.forAttachment list
        Col(
          name          = "file_name",
          value         = DynamicColValue(
            placeholder = "?",
            paramSetter = param(_.setString, req.filename.get)
          )
        )
    ) ::: (
      req.forAttachment list
        Col(
          name          = "file_content",
          value         = DynamicColValue(
            placeholder = "?",
            paramSetter = param(_.setBytes, RequestReader.bytes(reqBodyOpt).orNull)
          )
        )
    ) ::: (
      req.forAttachment list
        Col(
          name          = "hash_algorithm",
          value         = DynamicColValue(
            placeholder = "?",
            paramSetter = param(_.setString, req.hashAlgorithm.orNull)
          )
        )
    ) ::: (
      req.forAttachment list
        Col(
          name          = "hash_value",
          value         = DynamicColValue(
            placeholder = "?",
            paramSetter = param(_.setString, req.hashValue.orNull)
          )
        )
    ) ::: (
      isFormDefinition list
        Col(
          name          = "form_metadata",
          value         = DynamicColValue(
            placeholder = "?",
            paramSetter = param(_.setString, metadataOpt.orNull)
          )
        )
    ) ::: (
      req.forData list
        Col(
          name          = "username",
          value         = DynamicColValue(
            placeholder = "?" ,
            paramSetter = param(_.setString, req.existingRow.flatMap(_.createdBy).map(_.username).getOrElse(req.username.orNull))
          )
        )
    ) ::: (
      req.forData list
        Col(
          name          = "groupname",
          value         = DynamicColValue(
            placeholder = "?",
            paramSetter = param(_.setString, req.existingRow.flatMap(_.createdBy).flatMap(_.groupname).getOrElse(req.groupname.orNull))
          )
        )
    ) ::: (
      organizationToSet.isDefined list
        Col(
          name          = "organization_id",
          value         = DynamicColValue(
            placeholder = "?",
            paramSetter = param(_.setInt, organizationToSet.get)
          )
        )
    ) ::: (
      ! req.forAttachment list
        Col(
          name          = xmlCol,
          value         = DynamicColValue(
            placeholder = xmlVal,
            paramSetter = param(_.setString, xmlOpt.orNull)
          )
        )
    ) ::: Nil
  }
}
