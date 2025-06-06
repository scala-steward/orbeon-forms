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
package org.orbeon.oxf.fb

import cats.syntax.option.*
import org.orbeon.dom.{Namespace, QName}
import org.orbeon.oxf.fr.FormRunner.*
import org.orbeon.oxf.fr.XMLNames.*
import org.orbeon.oxf.fr.{FormRunnerCommonConstraint, Names}
import org.orbeon.oxf.util.CoreUtils.*
import org.orbeon.oxf.util.StringUtils.*
import org.orbeon.oxf.xforms.NodeInfoFactory
import org.orbeon.oxf.xforms.action.XFormsAPI.*
import org.orbeon.oxf.xforms.analysis.controls.LHHA
import org.orbeon.oxf.xforms.analysis.controls.LHHAAnalysis.*
import org.orbeon.oxf.xforms.analysis.model.{MipName, Types}
import org.orbeon.oxf.xforms.function.xxforms.{ExcludedDatesValidation, ValidationFunctionNames}
import org.orbeon.oxf.xforms.xbl.BindingDescriptor
import org.orbeon.oxf.xml.SaxonUtils.parseQName
import org.orbeon.oxf.xml.{XMLConstants, XMLUtils}
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.Implicits.*
import org.orbeon.scaxon.NodeConversions.*
import org.orbeon.scaxon.SimplePath.*
import org.orbeon.xforms.Namespaces
import org.orbeon.xforms.XFormsNames.*
import org.orbeon.xforms.analysis.model.ValidationLevel

import scala.xml as sx


trait AlertsAndConstraintsOps extends ControlOps {

  self: GridOps => // funky dependency, to resolve at some point

  private val OldAlertRefMatcher = """\$form-resources/([^/]+)/(\w+)(?:\[(\d+)\])?""".r
  private val NewAlertRefMatcher = """xxf:r\('([^.]+)\.(\w+)(?:\.(\d+))?'\)""".r

  val OldStandardAlertRef = """$fr-resources/detail/labels/alert"""

  def readValidationsAsXML(controlName: String)(implicit ctx: FormBuilderDocContext): List[NodeInfo] =
    RequiredValidation.fromForm(controlName)    ::
    DatatypeValidation.fromForm(controlName)    ::
    ConstraintValidation.fromForm(controlName)  map
    (v => elemToNodeInfo(v.toXML))

  def writeAlertsAndValidationsAsXML(
    controlName      : String,
    newAppearance    : String,
    defaultAlertElem : NodeInfo,
    validationElems  : List[NodeInfo]
  )(implicit
    ctx              : FormBuilderDocContext
  ): Unit = {

    // Current resolutions, which could be lifted in the future:
    //
    // - writes are destructive: they remove all xf:alert, alert resources, and validations for the control
    // - we don't allow editing the validation id, but we preserve it when possible

    // Extract from XML
    val allValidations = {
      val idsIterator = nextTmpIds(token = Names.Validation, count = validationElems.size).iterator
      validationElems map (v => v -> (v attValue "type")) flatMap {
        case (e, MipName.Required.name) => Some(RequiredValidation.fromXml(e, idsIterator))
        case (e, "datatype")            => Some(DatatypeValidation.fromXml(e, idsIterator, controlName))
        case (e, _)                     => ConstraintValidation.fromXmlOpt(e, idsIterator)
      }
    }

    val defaultAlert = AlertDetails.fromXML(defaultAlertElem, None)

    // We expect exactly one "required" validation
    allValidations collectFirst {
      case v: RequiredValidation => v
    } foreach { v =>
      writeValidations(
        controlName,
        MipName.Required,
        List(v)
      )
    }

    // We expect exactly one "datatype" validation
    allValidations collectFirst {
      case v: DatatypeValidation => v
    } foreach { v =>

      v.renameControlIfNeeded(controlName, newAppearance.trimAllToOpt)

      writeValidations(
        controlName,
        MipName.Type,
        List(v)
      )
    }

    // Several "constraint" validations are supported
    writeValidations(
      controlName,
      MipName.Constraint,
      allValidations collect { case v: ConstraintValidation => v }
    )

    writeAlerts(
      controlName,
      allValidations,
      defaultAlert
    )
  }

  private def writeValidations(
    controlName : String,
    mip         : MipName.Validate,
    validations : List[Validation])(implicit
    ctx         : FormBuilderDocContext
  ): Unit = {

    val bindElem = findBindByName(controlName).get

    val existingAttributeValidations = mipAtts (bindElem, mip)
    val existingElementValidations   = mipElems(bindElem, mip)

    val (_, mipElemQName) = mipToFBMIPQNames(mip)

    validations match {
      case Nil =>
        delete(existingAttributeValidations ++ existingElementValidations)
      case List(Validation(_, ValidationLevel.ErrorLevel, value, None)) =>

        // Single validation without custom alert: set @fb:mipAttName and remove all nested elements
        // See also: https://github.com/orbeon/orbeon-forms/issues/1829
        // NOTE: We could optimize further by taking this branch if there is no type or required validation.
        writeAndNormalizeMip(controlName.some, mip, value, iteration = false)
        delete(existingElementValidations)
      case _ =>
        val nestedValidations =
          validations flatMap { validation =>
            val Validation(idOpt, level, value, _) = validation
            value.trimAllToOpt match {
              case Some(nonEmptyValue) =>

                val prefix = mipElemQName.namespace.uri match {
                  case XMLNames.FB => XMLNames.FBPrefix // also covers the case of `xxf:default` (Form Builder names here)
                  case XF          => "xf" // case of `xf:type`, `xf:required`
                }

                val dummyMIPElem =
                  <xf:dummy
                    id={idOpt.orNull}
                    level={if (level != ValidationLevel.ErrorLevel) level.entryName else null}
                    value={if (mip != MipName.Type) nonEmptyValue else null}
                    xmlns:xf={XF}
                    xmlns:fb={XMLNames.FB}>{
                    if (mip == MipName.Type) // https://github.com/orbeon/orbeon-forms/issues/6252
                      normalizeMipValue(
                        mip          = MipName.Type,
                        mipValue     = nonEmptyValue,
                        hasCalculate = throw new IllegalArgumentException, // unused here as we pass `mip == Type`
                        isTypeString = isTypeStringUpdateNsIfNeeded(bindElem, _)
                      ).orNull
                    else
                      null
                  }</xf:dummy>

                List(dummyMIPElem.copy(prefix = prefix, label = mipElemQName.localName): NodeInfo)
              case None =>
                Nil
            }
          }

        delete(existingAttributeValidations ++ existingElementValidations)
        insertElementsImposeOrder(into = bindElem, origin = nestedValidations, MipName.AllMipNamesInOrder)
    }
  }

  // Write resources and alerts for those that have resources
  // If the default alert has resources, write it as well
  private def writeAlerts(
    controlName  : String,
    validations  : List[Validation],
    defaultAlert : AlertDetails)(implicit
    ctx          : FormBuilderDocContext
  ): Unit = {

    val alertsWithResources = {

      val alertsForValidations =
        validations collect
          { case Validation(_, _, _, Some(alert)) => alert }

      val nonGlobalDefaultAlert =
        ! defaultAlert.global list defaultAlert

      alertsForValidations ::: nonGlobalDefaultAlert
    }

    val messagesByLangForAllLangs = {

      def messagesForAllLangs(a: AlertDetails): collection.Seq[(String, String)] = {
        val messagesMap = a.messages.toMap
        allLangs(resourcesRoot) map { lang => lang -> messagesMap.getOrElse(lang, "") }
      }

      val messagesByLang =
        alertsWithResources
          .flatMap(messagesForAllLangs)
          .groupBy(_._1)
          .map { case (lang, values) => lang -> values.map(_._2) }

      // Make sure we have a default for all languages if there are no alerts or if some languages are missing
      // from the alerts. We do want to update all languages on write, including removing unneeded <alert>
      // elements.
      val defaultMessages = allLangs(resourcesRoot) map (_ -> Nil)

      defaultMessages.toMap ++ messagesByLang toList
    }

    setControlResourcesWithLang(controlName, "alert", messagesByLangForAllLangs)

    // Write alerts
    val newAlertElements =
      ensureCleanLHHAElements(
        controlName = controlName,
        lhhaName    = LHHA.Alert.entryName,
        count       = alertsWithResources.size,
        replace     = true,
        keepParams  = false
      )

    // Insert validation attribute as needed
    newAlertElements zip alertsWithResources foreach {
      case (alertElem, AlertDetails(Some(forValidationId), _, _, params)) =>
        insert(into = alertElem, origin = NodeInfoFactory.attributeInfo(VALIDATION_QNAME, forValidationId))
        params foreach { param =>
          insert(into = alertElem, origin = param)
        }
      case _ => // no attributes to insert if this is not an alert linked to a validation
    }

    // Write global default alert if needed
    if (defaultAlert.global) {
      val newGlobalAlert = ensureCleanLHHAElements(controlName, LHHA.Alert.entryName, count = 1, replace = false, keepParams = false).head
      setvalue(newGlobalAlert /@ "ref", OldStandardAlertRef)
    }
  }

  sealed trait Validation {
    def idOpt       : Option[String]
    def level       : ValidationLevel
    def stringValue : String
    def alert       : Option[AlertDetails]

    def toXML(implicit ctx: FormBuilderDocContext): sx.Elem
  }

  object Validation {

    def unapply(v: Validation): Option[(Option[String], ValidationLevel, String, Option[AlertDetails])] =
      Some((v.idOpt, v.level, v.stringValue, v.alert))

    def levelFromXML(validationElem: NodeInfo): ValidationLevel =
      ValidationLevel.LevelByName(validationElem attValue "level")
  }

  // Required is either a simple boolean or a custom XPath expression
  case class RequiredValidation(
    idOpt    : Option[String],
    required : Either[Boolean, String],
    alert    : Option[AlertDetails]
  ) extends Validation {

    import RequiredValidation.*

    def level      : ValidationLevel = ValidationLevel.ErrorLevel
    def stringValue: String          = eitherToXPath(required)

    def toXML(implicit ctx: FormBuilderDocContext): sx.Elem =
      <validation type={MipName.Required.name} level={level.entryName} default-alert={alert.isEmpty.toString}>
        <required>{eitherToXPath(required)}</required>
        {alertOrPlaceholder(alert)}
      </validation>
  }

  object RequiredValidation {

    private val DefaultRequireValidation = RequiredValidation(None, Left(false), None)

    def fromForm(controlName: String)(implicit ctx: FormBuilderDocContext): RequiredValidation =
      findMIPs(controlName, MipName.Required).headOption map {
        case (idOpt, _, value, alertOpt) =>
          RequiredValidation(idOpt, xpathOptToEither(Some(value)), alertOpt)
      } getOrElse
        DefaultRequireValidation

    def fromXml(validationElem: NodeInfo, newIds: Iterator[String])(implicit ctx: FormBuilderDocContext): RequiredValidation = {
      require(validationElem /@ "type" === MipName.Required.name)

      val validationIdOpt = validationElem.id.trimAllToOpt orElse Some(newIds.next())
      val required        = validationElem / MipName.Required.name stringValue

      RequiredValidation(
        validationIdOpt,
        xpathOptToEither(required.trimAllToOpt),
        AlertDetails.fromValidationXML(validationElem, validationIdOpt)
      )
    }

    private def xpathOptToEither(opt: Option[String]): Either[Boolean, String] =
      opt match {
        case Some("true()")         => Left(true)
        case Some("false()") | None => Left(false)    // normalize missing MIP to false()
        case Some(xpath)            => Right(xpath)
      }

    private def eitherToXPath(required: Either[Boolean, String]) =
      required match {
        case Left(true)   => "true()"
        case Left(false)  => "false()"
        case Right(xpath) => xpath
      }
  }

  case class DatatypeValidation(
    idOpt    : Option[String],
    datatype : Either[(QName, Boolean), QName],
    alert    : Option[AlertDetails]
  ) extends Validation {

    val datatypeQName: QName = datatype.fold(_._1, identity)

    def level      : ValidationLevel = ValidationLevel.ErrorLevel
    def stringValue: String          = XMLUtils.buildQName(datatypeQName.namespace.prefix, datatypeQName.localName)

    // Rename control element if needed when the datatype changes
    def renameControlIfNeeded(
      controlName     : String,
      newAppearanceOpt: Option[String]
    )(implicit
      ctx             : FormBuilderDocContext
    ): Unit = {
      import ctx.bindingIndex
      val newDatatype = datatypeQName
      for {
        controlElem <- findControlByName(controlName)
        oldDatatype = DatatypeValidation.fromForm(controlName).datatypeQName
        oldAtts     = BindingDescriptor.getAtts(controlElem)
        (newElemName, newAppearanceAttOpt) <-
          FormBuilder.newElementName(
            controlElem.uriQualifiedName,
            oldDatatype,
            oldAtts,
            newDatatype,
            newAppearanceOpt
          )
      } locally {
        // Q: If binding changes, what about instance and bind templates? Should also be updated? Not a
        // concrete case as of now, but can happen depending on which bindings are available.
        val newControlElem = rename(controlElem, newElemName)
        toggleAttribute(newControlElem, APPEARANCE_QNAME, newAppearanceAttOpt)
      }
    }

    def toXML(implicit ctx: FormBuilderDocContext): sx.Elem = {

      val (builtinTypeString, builtinTypeRequired, schemaTypeString) =
        datatype match {
          case Left((qName, required)) => (qName.localName, required.toString, "")
          case Right(qName)            => ("", "", qName.qualifiedName)
        }

      <validation type="datatype" id={idOpt.orNull} level={level.entryName} default-alert={alert.isEmpty.toString}>
        <builtin-type>{builtinTypeString}</builtin-type>
        <builtin-type-required>{builtinTypeRequired}</builtin-type-required>
        <schema-type>{schemaTypeString}</schema-type>
        {alertOrPlaceholder(alert)}
      </validation>
    }
  }

  object DatatypeValidation {

    // `xs:string` is special since the empty string is still a valid `xs:string`, so `xs:string` is the same as
    // `xf:string`. We shouldn't ever have to write `xf:string`. (XForms 2.0 even removes those `xf:*` types as
    // they are not needed anymore, since `required` has precedence.) Here, we decide to return `xs:string` as the
    // default datatype (`DefaultDataTypeValidation`), but we always mark it as not implying requiredness. The UI
    // will show it as required only if the `required` validation implies it. However, for other `xs:*` types, we
    // imply requiredness unless there is a `required` validation that implies otherwise.
    private val DefaultDataTypeValidation =
      DatatypeValidation(None, Left(XMLConstants.XS_STRING_QNAME -> false), None)

    // Create from a control name
    def fromForm(controlName: String)(implicit ctx: FormBuilderDocContext): DatatypeValidation = {

      val bind = findBindByName(controlName).get // require the bind

      def builtinOrSchemaType(typ: String): Either[(QName, Boolean), QName] = {
        val qName = bind.resolveQName(typ)
        if (Namespaces.isForBuiltinType(qName))
          Left(qName -> (qName != XMLConstants.XS_STRING_QNAME && qName.namespace.uri == XS)) // see `DefaultDataTypeValidation`
        else
          Right(qName)
      }

      findMIPs(controlName, MipName.Type).headOption map {
        case (idOpt, _, value, alertOpt) =>
          // If the value is blank, use `xs:string` as the default
          // https://github.com/orbeon/orbeon-forms/issues/6252
          DatatypeValidation(
           idOpt,
           value.trimAllToOpt.map(builtinOrSchemaType).getOrElse(DefaultDataTypeValidation.datatype), // see `DefaultDataTypeValidation`
           alertOpt
        )
      } getOrElse
        DefaultDataTypeValidation
    }

    def datatypeQNameFromStrings(
      controlName   : String,
      builtinTypeOpt: Option[(String, Boolean)],
      schemaTypeOpt : Option[String]
    )(implicit
      ctx            : FormBuilderDocContext
    ): Either[(QName, Boolean), QName] = {

      val bind = findBindByName(controlName).get

      def builtinTypeQName(builtinType: (String, Boolean)): (QName, Boolean) = {

        val (builtinTypeString, builtinTypeRequired) = builtinType

        // If a builtin type, we just have a local name
        val nsURI = Types.uriForBuiltinTypeName(builtinTypeString, builtinTypeRequired)

        // Namespace mapping must be in scope
        val prefix = bind.nonEmptyPrefixesForURI(nsURI).min

        QName(builtinTypeString, Namespace(prefix, nsURI)) -> builtinTypeRequired
      }

      def schemaTypeQName(schemaType: String): QName = {

        // Schema type OTOH comes with a prefix if needed
        val localname = parseQName(schemaType)._2
        val namespace = valueNamespaceMappingScopeIfNeeded(bind, schemaType) map
          { case (prefix, uri) => Namespace(prefix, uri) } getOrElse
          Namespace.EmptyNamespace
        QName(localname, namespace)
      }

      schemaTypeOpt.map(schemaTypeQName).map(Right.apply)
        .orElse(builtinTypeOpt.map(builtinTypeQName).map(Left.apply))
        .getOrElse(throw new IllegalArgumentException)
    }

    def fromXml(
      validationElem : NodeInfo,
      newIds         : Iterator[String],
      controlName    : String
    )(implicit
      ctx            : FormBuilderDocContext
    ): DatatypeValidation = {

      require(validationElem.attValueOpt("type").contains("datatype"))

      val validationIdOpt = validationElem.id.trimAllToOpt orElse Some(newIds.next())

      val datatype: Either[(QName, Boolean), QName] = {

        val builtinTypeStringOpt = (validationElem elemValue "builtin-type").trimAllToOpt
        val builtinTypeRequired  = (validationElem elemValue "builtin-type-required").trimAllToOpt contains "true"
        val schemaTypeOpt        = (validationElem elemValue "schema-type").trimAllToOpt

        datatypeQNameFromStrings(
          controlName    = controlName,
          builtinTypeOpt = builtinTypeStringOpt.map(builtinTypeString => (builtinTypeString, builtinTypeRequired)),
          schemaTypeOpt  = schemaTypeOpt,
        )
      }

      DatatypeValidation(
        validationIdOpt,
        datatype,
        AlertDetails.fromValidationXML(validationElem, validationIdOpt)
      )
    }
  }

  case class ConstraintValidation(
    idOpt      : Option[String],
    level      : ValidationLevel,
    expression : String,
    alert      : Option[AlertDetails]
  ) extends Validation {

    def stringValue: String = expression

    def toXML(implicit ctx: FormBuilderDocContext): sx.Elem = {

      // NOTE: We use the namespaces in scope on the model, not the bind containing the constraint. This is
      // a simplification and implies a constraint that there are no new namespace declarations on binds
      // compared to the model.
      val analyzed = FormRunnerCommonConstraint.analyzeKnownConstraint(
        expression,
        ctx.formBuilderModel.getOrElse(throw new IllegalStateException).staticModel.namespaceMapping,
        inScopeContainingDocument.functionLibrary
      )

      <validation
        type={analyzed map (_._1) getOrElse "formula"}
        id={idOpt getOrElse ""}
        level={level.entryName}
        default-alert={alert.isEmpty.toString}>
        <constraint
          expression={if (analyzed.isEmpty) expression else ""}
          argument={analyzed flatMap (_._2) getOrElse ""}
        />
        {alertOrPlaceholder(alert)}
      </validation>
    }
  }

  object ConstraintValidation {

    def fromForm(controlName: String)(implicit ctx: FormBuilderDocContext): List[ConstraintValidation] =
      findMIPs(controlName, MipName.Constraint) map {
        case (idOpt, level, value, alertOpt) =>
          ConstraintValidation(idOpt, level, value, alertOpt)
      }

    def fromXmlOpt(validationElem: NodeInfo, newIds: Iterator[String])(implicit ctx: FormBuilderDocContext): Option[ConstraintValidation] = {

      def normalizedAttOpt(attName: String) =
        (validationElem child MipName.Constraint.name attValue attName headOption) flatMap trimAllToOpt

      val constraintExpressionOpt = validationElem attValue "type" match {
        case "formula"                                     => normalizedAttOpt("expression")
        case vn @ ValidationFunctionNames.UploadMediatypes => Some(s"xxf:$vn('${normalizedAttOpt("argument") getOrElse ""}')") // quote
        case vn @ ExcludedDatesValidation.PropertyName     => Some(s"xxf:$vn((${normalizedAttOpt("argument") getOrElse ""}))") // parens
        case vn                                            => Some(s"xxf:$vn(${normalizedAttOpt("argument") getOrElse ""})")   // as is
      }

      constraintExpressionOpt map { expr =>

        val level           = Validation.levelFromXML(validationElem)
        val validationIdOpt = validationElem.id.trimAllToOpt orElse Some(newIds.next())

        ConstraintValidation(
          validationIdOpt,
          level,
          expr,
          AlertDetails.fromValidationXML(validationElem, validationIdOpt)
        )
      }
    }
  }

  case class AlertDetails(forValidationId: Option[String], messages: List[(String, String)], global: Boolean, params: List[NodeInfo]) {

    require(! (global && forValidationId.isDefined))

    def default: Boolean = forValidationId.isEmpty

    // XML representation used by Form Builder
    def toXML: sx.Elem = {
      <alert global={global.toString}>{
        val messageElem = messages.collect {
          case (lang, message) =>
            <message lang={lang} value={message}/>
        }
        val paramsElems = params map nodeInfoToElem
        messageElem ++ paramsElems
      }</alert>
    }
  }

  private def isGlobalAlertRef(refAtt: String): Boolean =
    refAtt == OldStandardAlertRef

  // - If the attribute matches a non-global alert path, return `Some`, otherwise `None`.
  // - If there is an explicit index such as `[1]`, then return a nested `Some` index, otherwise `None.
  // - The index, if any, is 0-based.
  private def findZeroBasedIndexFromAlertRef(refAtt: String, resourceName: String): Option[Option[Int]] = {

    def normalizeIndex(index: String) =
      Option(index) map (_.toInt - 1)

    refAtt match {
      case OldAlertRefMatcher(_, `resourceName`, index) => Some(normalizeIndex(index))
      case NewAlertRefMatcher(_, `resourceName`, index) => Some(normalizeIndex(index))
      case _                                            => None
    }
  }

  // Same as `findZeroBasedIndexFromAlertRef` but handle case of a blank value which returns `Some(None)`.
  def findZeroBasedIndexFromAlertRefHandleBlankRef(refAtt: String, resourceName: String): Option[Option[Int]] =
    findZeroBasedIndexFromAlertRef(refAtt, resourceName) orElse (refAtt.isAllBlank option None)

  // NOTE: The index is 0-based.
  def buildResourcePointer(controlName: String, lhhaName: String, indexOpt: Option[Int]) =
    s"$$form-resources/$controlName/$lhhaName${indexOpt map (i => s"[${i + 1}]") getOrElse ""}"

  object AlertDetails {

    // Return supported alert details for the control
    //
    // - None if the alert message can't be found or if the alert/validation combination can't be handled by FB
    // - alerts returned are either global (no validation/level specified) or for a single specific validation
    def fromForm(controlName: String)(implicit ctx: FormBuilderDocContext): Seq[AlertDetails] = {

      val controlElem                = findControlByName(controlName).get
      val alertResourcesForAllLangs  = getControlResourcesWithLang(controlName, "alert", allLangs(resourcesRoot))

      def alertFromElement(e: NodeInfo) = {

        def attValueOrNone(name: QName) = e att name map (_.stringValue) headOption

        val validationAtt = attValueOrNone(VALIDATION_QNAME)
        val levelAtt      = attValueOrNone(LEVEL_QNAME)
        val refAttOpt     = attValueOrNone(REF_QNAME)

        val alertIndexOpt = refAttOpt match {
          case Some(refAtt) => findZeroBasedIndexFromAlertRef(refAtt, LHHA.Alert.entryName).flatten orElse Some(0)
          case None         => throw new IllegalArgumentException(s"missing `${REF_QNAME.qualifiedName}` attribute")
        }

        // Try to find an existing resource for the given index if present, otherwise assume a blank value for
        // the language
        val alertsByLang = alertResourcesForAllLangs.to(List) map {
          case (lang, alerts) => lang -> (alertIndexOpt flatMap alerts.lift map (_.stringValue) getOrElse "")
        }

        val forValidations = gatherAlertValidations(validationAtt)
        val forLevels      = gatherAlertLevels(levelAtt)

        // Form Builder only handles a subset of the allowed XForms mappings for now
        def isDefault           = forValidations.isEmpty && forLevels.isEmpty
        def hasSingleValidation = forValidations.size == 1 && forLevels.isEmpty
        def canHandle           = (isDefault || hasSingleValidation) && alertsByLang.nonEmpty
        def params              = e.child("*:param").toList

        canHandle option AlertDetails(forValidations.headOption, alertsByLang, refAttOpt exists isGlobalAlertRef, params)
      }

      controlElem child "alert" flatMap alertFromElement toList
    }

    def fromXML(alertElem: NodeInfo, forValidationId: Option[String])(implicit ctx: FormBuilderDocContext): AlertDetails = {

      val messagesElems = (alertElem child "message" toList) map {
        message => (message attValue "lang", message attValue "value")
      }

      val isGlobal = (alertElem attValue "global") == "true"
      val params   = alertElem.child("*:param").toList
      AlertDetails(forValidationId, messagesElems, isGlobal, params)
    }

    def fromValidationXML(
      validationElem : NodeInfo,
      forValidationId: Option[String]
    )(implicit
      ctx            : FormBuilderDocContext
    ): Option[AlertDetails] = {

      def alertOpt: Option[AlertDetails] =
        validationElem firstChildOpt "alert" map (AlertDetails.fromXML(_, forValidationId))

      val useDefaultAlert = validationElem.attValueOpt("default-alert").contains("true")

      if (useDefaultAlert) None else alertOpt
    }
  }

  private def findMIPs(
    controlName: String,
    mip        : MipName
  )(implicit
    ctx        : FormBuilderDocContext
  ): List[(Option[String], ValidationLevel, String, Option[AlertDetails])] = {

    val bind            = findBindByName(controlName).get // require the bind
    val supportedAlerts = AlertDetails.fromForm(controlName)

    def findAlertForId(id: String) =
      supportedAlerts find (_.forValidationId.contains(id))

    def fromAttribute(a: NodeInfo) = {
      val bindId = (a parent * head).id
      (
        None, // no id because we don't want that attribute to roundtrip
        ValidationLevel.ErrorLevel,
        a.stringValue,
        findAlertForId(bindId)
      )
    }

    def fromElement(e: NodeInfo) = {
      val id = e.id
      (
        id.trimAllToOpt,
        (e attValue LEVEL_QNAME).trimAllToOpt map ValidationLevel.LevelByName getOrElse ValidationLevel.ErrorLevel,
        if (mip == MipName.Type) e.stringValue else e attValue VALUE_QNAME,
        findAlertForId(id)
      )
    }

    // Gather all validations (in fb:* except for type)
    def attributeValidations = mipAtts (bind, mip) map fromAttribute
    def elementValidations   = mipElems(bind, mip) map fromElement

    attributeValidations ++ elementValidations toList
  }

  private def mipAtts (bind: NodeInfo, mip: MipName) = bind /@ mipToFBMIPQNames(mip)._1
  private def mipElems(bind: NodeInfo, mip: MipName) = bind /  mipToFBMIPQNames(mip)._2

  private def alertOrPlaceholder(alert: Option[AlertDetails])(implicit ctx: FormBuilderDocContext) =
    alert orElse Some(AlertDetails(None, Nil, global = false, params = Nil)) map (_.toXML) get
}
