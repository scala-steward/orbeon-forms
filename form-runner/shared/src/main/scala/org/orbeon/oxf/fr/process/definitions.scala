package org.orbeon.oxf.fr.process

import cats.data.NonEmptyList
import cats.syntax.option.*
import enumeratum.EnumEntry.Hyphencase
import enumeratum.{Enum, EnumEntry}
import org.orbeon.oxf.fr.DataFormatVersion
import org.orbeon.oxf.util.ContentTypes
import org.orbeon.oxf.util.StringUtils.*


trait ProcessParams {
  def runningProcessId  : String
  def app               : String
  def form              : String
  def formVersion       : Int
  def document          : String
  def valid             : Boolean
  def language          : String
  def dataFormatVersion : String
  def workflowStage     : String
}

sealed trait RenderedFormat extends EnumEntry with Hyphencase
object RenderedFormat extends Enum[RenderedFormat] {

  val values = findValues

  case object Pdf                     extends RenderedFormat
  case object Tiff                    extends RenderedFormat
  case object ExcelWithNamedRanges    extends RenderedFormat
  case object XmlFormStructureAndData extends RenderedFormat

  val SupportedRenderFormatsMediatypes: Map[RenderedFormat, String] =
    Map(
      RenderedFormat.Pdf                     -> ContentTypes.PdfContentType,
      RenderedFormat.Tiff                    -> ContentTypes.TiffContentType,
      RenderedFormat.ExcelWithNamedRanges    -> ContentTypes.ExcelContentType,
      RenderedFormat.XmlFormStructureAndData -> ContentTypes.XmlContentType
    )

  private val MatchTrailingUrlRegex = "(.+)-url".r

  def findRenderedFormatWithIsUrl(token: String): Option[(RenderedFormat, Boolean)] = token match {
    case MatchTrailingUrlRegex(token) => RenderedFormat.withNameOption(token).map(_ -> true)
    case token                        => RenderedFormat.withNameOption(token).map(_ -> false)
  }
}

trait SerializationDefaults {
  def defaultContentType  : String
  def defaultSerialization: String
}

sealed trait ContentToken extends SerializationDefaults
object ContentToken {
  case object Xml extends ContentToken {
    def defaultContentType  : String = ContentTypes.XmlContentType
    def defaultSerialization: String = ContentTypes.XmlContentType
  }
  case object Json extends ContentToken {
    def defaultContentType  : String = ContentTypes.JsonContentType
    def defaultSerialization: String = ContentTypes.JsonContentType
  }
  case object Metadata extends ContentToken {
    def defaultContentType  : String = ContentTypes.XmlContentType
    def defaultSerialization: String = ContentTypes.XmlContentType
  }
  case object Attachments extends ContentToken {
    def defaultContentType  : String = ContentTypes.MultipartRelatedContentType
    def defaultSerialization: String = ContentTypes.OctetStreamContentType
  }
  case class Rendered(format: RenderedFormat, urlOnly: Boolean) extends ContentToken {
    def defaultContentType  : String = if (urlOnly) ContentTypes.XmlContentType else RenderedFormat.SupportedRenderFormatsMediatypes(format)
    def defaultSerialization: String = if (urlOnly) ContentTypes.XmlContentType else ContentTypes.OctetStreamContentType
  }

  def fromString(s: String): Set[ContentToken] =
    s.splitTo[Set]() flatMap {
      case "xml"         => ContentToken.Xml.some
      case "json"        => ContentToken.Json.some
      case "metadata"    => ContentToken.Metadata.some
      case "attachments" => ContentToken.Attachments.some
      case rendered      => RenderedFormat.findRenderedFormatWithIsUrl(rendered).map((ContentToken.Rendered.apply _).tupled)
    }

  def toString(contentToken: ContentToken): String = contentToken match {
    case ContentToken.Xml         => "xml"
    case ContentToken.Json        => "json"
    case ContentToken.Metadata    => "metadata"
    case ContentToken.Attachments => "attachments"
    case ContentToken.Rendered(format, urlOnly) =>
      if (urlOnly) s"${format.entryName}-url" else format.entryName
  }
}

sealed trait ContentToSend
object ContentToSend {
  case object NoContent extends ContentToSend
  case class Multipart(parts: NonEmptyList[ContentToken]) extends ContentToSend with SerializationDefaults {
    def defaultContentType  : String = ContentTypes.MultipartRelatedContentType
    def defaultSerialization: String = ContentTypes.OctetStreamContentType
  }
  case class Single(part: ContentToken) extends ContentToSend with SerializationDefaults {
    def defaultContentType  : String = part.defaultContentType
    def defaultSerialization: String = part.defaultSerialization
  }

  def fromContentTokens(tokens: Set[ContentToken]): ContentToSend =
    NonEmptyList.fromList(tokens.toList) match {
      case None                                                    => ContentToSend.NoContent
      case Some(nel @ NonEmptyList(ContentToken.Attachments, Nil)) => ContentToSend.Multipart(nel)
      case Some(NonEmptyList(head, Nil))                           => ContentToSend.Single(head)
      case Some(nel)                                               => ContentToSend.Multipart(nel)
    }

  def toStringOpt(contentToSend: ContentToSend): Option[String] = contentToSend match {
    case ContentToSend.NoContent          => None
    case ContentToSend.Single(part)       => ContentToken.toString(part).some
    case ContentToSend.Multipart(parts)   => parts.toList.map(ContentToken.toString).mkString(" ").some
  }
}