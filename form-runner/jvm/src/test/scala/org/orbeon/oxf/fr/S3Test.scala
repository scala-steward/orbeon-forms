/**
 * Copyright (C) 2025 Orbeon, Inc.
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
package org.orbeon.oxf.fr

import cats.implicits.catsSyntaxOptionId
import org.apache.commons.io.FilenameUtils
import org.orbeon.oxf.fr.Names.ResourcesModel
import org.orbeon.oxf.fr.s3.{S3, S3Config}
import org.orbeon.oxf.test.{DocumentTestBase, ResourceManagerSupport}
import org.orbeon.oxf.xforms.action.XFormsAPI
import org.orbeon.oxf.xforms.action.XFormsAPI.topLevelModel
import org.scalatest.Tag
import org.scalatest.funspec.AnyFunSpecLike
import software.amazon.awssdk.services.s3.S3Client

import java.util.UUID


// There's apparently no way to tag a "describe" section globally, so we need to tag all "it" statements individually
object S3Tag extends Tag("S3")

object S3Test {
  def withTestS3ConfigAndPath(
    configName: String,
    pathOpt   : Option[String] = None)(
    body      : S3Config => String => Unit
  ): Unit = {

    implicit val s3Config: S3Config = S3Config.fromProperties(configName).get

    S3.withS3Client { implicit s3Client =>
      // Use S3 path used as argument or generate random S3 path
      val s3Path = pathOpt.getOrElse(UUID.randomUUID().toString)

      def emptyPath(): Unit = S3.objects(s3Config.bucket, prefix = s3Path).get foreach { s3Object =>
        S3.deleteObject(s3Config.bucket, s3Object.key).get
      }

      // Create the test bucket if it doesn't exist yet
      if (! S3.bucketExists(s3Config.bucket).get) {
        S3.createBucket(s3Config.bucket).get
      }

      // Make sure the test path is empty
      emptyPath()

      try {
        body(s3Config)(s3Path)
      } finally {
        // Delete all objects in the test path
        emptyPath()
      }
    }
  }
}

class S3Test
  extends DocumentTestBase
    with ResourceManagerSupport
    with AnyFunSpecLike
    with FormRunnerSupport {

  describe("Form Runner S3 storage") {

    describe("#6751: Store all attachments sent by email in AWS S3") {

      def testWithS3Config(configName: String)(body: S3Config => String => S3Client => Unit): Unit =
        withTestExternalContext { _ =>
          // It's important to use a new document for each test, as URLs to dynamic resources for rendered formats
          // (e.g. PDF) are stored in a document instance, but dynamic resources are tracked in XFormsAssetServerRoute
          // in the session attributes, and a new session is used for each test.
          val (processorService, docOpt, _) = runFormRunner("issue", "6751", "new", initialize = true)

          withFormRunnerDocument(processorService, doc = docOpt.get) {
            S3Test.withTestS3ConfigAndPath(configName) { implicit s3Config => s3Path =>
              S3.withS3Client { implicit s3Client =>
                body(s3Config)(s3Path)(s3Client)
              }
            }
          }
        }

      def sendEmail(s3Store: Boolean, s3PathOpt: Option[String], param: (String, String)*): Unit = {
        // Disable actual email sending, just test the S3 storage
        val baseParams = Map[Option[String], String]("send-email".some -> "false")

        // Include date/time in path so that different templates are stored in different sub-paths
        val s3Params = Map[Option[String], String](
          "s3-store".some  -> "true",
          "s3-config".some -> "test-s3-config"
        ) ++ s3PathOpt.map(s3Path => "s3-path".some -> s"concat('$s3Path', '/', current-dateTime())").toMap

        process.SimpleProcess.trySendEmail(
          baseParams ++
          (if (s3Store) s3Params else Map.empty) ++
          param.toMap.map(kv => kv._1.some -> kv._2)
        )
      }

      it("must leave S3 bucket untouched if S3 storage disabled", S3Tag) {
        testWithS3Config(configName = "test-s3-config") { implicit s3Config => s3Path => implicit s3Client =>
          sendEmail(s3Store = false, s3PathOpt = None)
          assert(S3.objects(s3Config.bucket, prefix = s3Path).get.isEmpty)
        }
      }

      it("must store expected S3 objects when email templates are selected by name", S3Tag) {
        val expectedTemplates = List(
          "0" -> List("0"),
          "1" -> List("1"),
          "2" -> List("2"),
          "3" -> List("3"),
          "4" -> List("4"),
          "5" -> List("5"),
          "6" -> List(),    // Excluded because of "enable if true" XPath expression (static)
          "7" -> List("7"),
          "8" -> List(),    // Excluded because of language
          "9" -> List()     // Excluded because of "enable if true" XPath expression (dynamic)
        )

        for {
          (templateName, expectedTemplateNames) <- expectedTemplates
          // Try with no match param (defaults to "first) and match=first
          matchParam                            <- List(None, Some("first"))
        } {
          testWithS3Config(configName = "test-s3-config") { implicit s3Config => s3Path => implicit s3Client =>
            // Select email template by name and match first template only
            val params = List("template" -> templateName) ++ matchParam.map("match" -> _).toList

            sendEmail(s3Store = true, s3PathOpt = s3Path.some, params *)

            val actualTemplateNames = checkS3PathAgainstTemplateDefinitions(s3Path).map(_.name)

            assert(
              expectedTemplateNames == actualTemplateNames,
              s"Unexpected stored templates: expected=${expectedTemplateNames.mkString(", ")}, actual=${actualTemplateNames.mkString(", ")}"
            )
          }
        }
      }

      it("must store expected S3 objects when all email templates are selected", S3Tag) {
        testWithS3Config(configName = "test-s3-config") { implicit s3Config => s3Path => implicit s3Client =>
          // Select all email templates (current language is the default one, i.e. "en")
          sendEmail(s3Store = true, s3PathOpt = s3Path.some, "match" -> "all")

          val expectedTemplateNames = List("0", "1", "2", "3", "4", "5", "7")
          val actualTemplateNames   = checkS3PathAgainstTemplateDefinitions(s3Path).map(_.name)

          assert(
            expectedTemplateNames == actualTemplateNames,
            s"Unexpected stored templates: expected=${expectedTemplateNames.mkString(", ")}, actual=${actualTemplateNames.mkString(", ")}"
          )
        }
      }

      it("must store expected S3 objects when non-default language is selected", S3Tag) {
        testWithS3Config(configName = "test-s3-config") { implicit s3Config => s3Path => implicit s3Client =>
          // Select a non-default language
          setLang("fr")

          // Select all email templates (current language is "fr")
          sendEmail(s3Store = true, s3PathOpt = s3Path.some, "match" -> "all")

          // We should select all templates that are not statically disabled
          val expectedTemplateNames = List("0", "1", "2", "3", "4", "5", "8")
          val actualTemplateNames   = checkS3PathAgainstTemplateDefinitions(s3Path).map(_.name)

          assert(
            expectedTemplateNames == actualTemplateNames,
            s"Unexpected stored templates: expected=${expectedTemplateNames.mkString(", ")}, actual=${actualTemplateNames.mkString(", ")}"
          )
        }
      }

      it("must store expected S3 objects when dynamic 'enable if true' expression specified", S3Tag) {
        val expectedTemplates = List(
          "disabled" -> List(),
          "enabled"  -> List("9")
        )

        testWithS3Config(configName = "test-s3-config") { implicit s3Config => s3Path => implicit s3Client =>

          for ((controlValue, expectedTemplateNames) <- expectedTemplates) {
            // Set the control value to enable or disable the template
            setControlValue("section-1-section≡grid-1-grid≡control-text-control", controlValue)

            sendEmail(s3Store = true, s3PathOpt = s3Path.some, "template" -> "9")

            val actualTemplateNames   = checkS3PathAgainstTemplateDefinitions(s3Path).map(_.name)

            assert(
              actualTemplateNames == expectedTemplateNames,
              s"Unexpected stored templates: expected=${expectedTemplateNames.mkString(", ")}, actual=${actualTemplateNames.mkString(", ")}"
            )
          }
        }
      }
    }
  }

  case class Template(
    name        : String,
    langOpt     : Option[String],
    staticEnable: Option[Boolean],
    pdf         : Option[Boolean],
    xml         : Option[Boolean],
    files       : Set[String]
  )

  // Static definitions of the email templates found in the test form definition. We'll use the attached files to
  // match the email template used, as we have currently no way to include the email template name anywhere in the
  // email content.
  protected val templates = List(
    Template("0", langOpt = None     , staticEnable = None      , pdf = None      , xml = None      , files = Set.empty),
    Template("1", langOpt = None     , staticEnable = None      , pdf = false.some, xml = false.some, files = Set("1.txt")),
    Template("2", langOpt = None     , staticEnable = None      , pdf = false.some, xml = true.some , files = Set("2.txt")),
    Template("3", langOpt = None     , staticEnable = None      , pdf = true.some , xml = false.some, files = Set("3.txt")),
    Template("4", langOpt = None     , staticEnable = None      , pdf = true.some , xml = true.some , files = Set("4.txt")),
    Template("5", langOpt = None     , staticEnable = true.some , pdf = None      , xml = None      , files = Set("1.txt", "2.txt", "3.txt", "4.txt")),
    Template("6", langOpt = None     , staticEnable = false.some, pdf = None      , xml = None      , files = Set("1.txt", "2.txt")),
    Template("7", langOpt = "en".some, staticEnable = None      , pdf = None      , xml = None      , files = Set("1.txt", "2.txt", "3.txt")),
    Template("8", langOpt = "fr".some, staticEnable = None      , pdf = None      , xml = None      , files = Set("1.txt", "3.txt", "4.txt")),
    Template("9", langOpt = None     , staticEnable = None      , pdf = None      , xml = None      , files = Set("2.txt", "3.txt", "4.txt"))
  )

  protected val DefaultAttachPdf = false // Should match oxf.fr.email.attach-pdf.issue.6751
  protected val DefaultAttachXml = true  // Should match oxf.fr.email.attach-xml.issue.6751

  protected def setLang(lang: String): Unit =
    List("lang", "fr-lang").foreach { variable =>
      XFormsAPI.setvalue(Seq(topLevelModel(ResourcesModel).get.unsafeGetVariableAsNodeInfo(variable)), lang)
    }

  protected val expectedContentTypesByExtension: Map[String, String] = Map(
    "pdf" -> "application/pdf",
    "txt" -> "text/plain",
    "xml" -> "application/xml; charset=UTF-8"
  )

  // The following method will list the S3 objects in a path and try to find matching email templates, based on the
  // files attached.
  //
  // It will also check that:
  //  - all S3 objects are not null-sized
  //  - file attachments will have the correct content (1.txt contains 1, 2.txt contains 2, etc.)
  //  - a PDF file is present if expected
  //  - an XML file is present if expected
  //  - templates which are statically disabled by an XPath expression are never used

  protected def checkS3PathAgainstTemplateDefinitions(
    s3Path  : String
  )(implicit
    s3Config: S3Config,
    s3Client: S3Client
  ): List[Template] = {

    // The objects returned will follow the format: bucket/testpath/datetime/attachment.ext
    val s3Objects = S3.objects(s3Config.bucket, prefix = s3Path).get

     s3Objects
      .map { s3Object =>
        // Check that the S3 objects contain something
        assert(
          s3Object.size() > 0L,
          s"Empty S3 object: ${s3Object.key}"
        )

        val dateTime :: filename :: Nil = s3Object.key.split("/").takeRight(2).toList

        val extension           = FilenameUtils.getExtension(filename)
        val expectedContentType = expectedContentTypesByExtension(extension)
        val actualContentType   = S3.objectMetadata(s3Config.bucket, s3Object.key).get.contentType()

        assert(
          expectedContentType == actualContentType,
          s"Unexpected content type found for $filename: expected=$expectedContentType, actual=$actualContentType"
        )

        if (extension == "txt") {
          // We have 1 in 1.txt, 2 in 2.txt, etc.
          val expectedContent = filename.stripSuffix(".txt")
          val actualContent   = S3.objectAsString(s3Config.bucket, s3Object.key).get

          assert(
            expectedContent == actualContent,
            s"Unexpected content found in $filename: expected=$expectedContent, actual=$actualContent"
          )
        }

        (dateTime, filename)
      }
      // Group by date/time
      .groupBy(_._1)
      .view
      .mapValues( _.map(_._2).sorted)
      .toList
      // Sort by date/time
      .sortBy(_._1)
      .map { case (_, filenames) =>
        val pdfFiles        = filenames.filter(_.endsWith(".pdf"))
        val xmlFiles        = filenames.filter(_.endsWith(".xml"))
        val attachmentFiles = filenames.filter(_.endsWith(".txt"))

        // Make sure we don't find any other files
        assert(
          filenames.size == pdfFiles.size + xmlFiles.size + attachmentFiles.size,
          s"Unexpected files: ${filenames.mkString(", ")}"
        )

        // Find the matching template based on the attached files
        val attachmentFilesSet = attachmentFiles.toSet
        val templateOpt        = templates.find(_.files == attachmentFilesSet)

        assert(
          templateOpt.isDefined,
          s"Could not find matching template for attachments: ${attachmentFiles.mkString(", ")}"
        )

        val template = templateOpt.get

        // We shouldn't find a template if it's statically disabled
        assert(
          ! template.staticEnable.contains(false),
          s"Found template ${template.name}, but it's statically disabled"
        )

        val expectedPdfCount = if (template.pdf.getOrElse(DefaultAttachPdf)) 1 else 0

        assert(
          expectedPdfCount == pdfFiles.size,
          s"Unexpected PDF count for template ${template.name}: expected=$expectedPdfCount, actual=${pdfFiles.size}"
        )

        val expectedXmlCount = if (template.xml.getOrElse(DefaultAttachXml)) 1 else 0

        assert(
          expectedXmlCount == xmlFiles.size,
          s"Unexpected XML count for template ${template.name}: expected=$expectedXmlCount, actual=${xmlFiles.size}"
        )

        template
      }
  }
}
