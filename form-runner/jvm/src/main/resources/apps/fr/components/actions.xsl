<!--
  Copyright (C) 2013 Orbeon, Inc.

  This program is free software; you can redistribute it and/or modify it under the terms of the
  GNU Lesser General Public License as published by the Free Software Foundation; either version
  2.1 of the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  See the GNU Lesser General Public License for more details.

  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  -->
<xsl:stylesheet
    version="2.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:xf="http://www.w3.org/2002/xforms"
    xmlns:xxf="http://orbeon.org/oxf/xml/xforms"
    xmlns:ev="http://www.w3.org/2001/xml-events"
    xmlns:fr="http://orbeon.org/oxf/xml/form-runner"
    xmlns:xh="http://www.w3.org/1999/xhtml"
    xmlns:xbl="http://www.w3.org/ns/xbl"
    xmlns:p="http://www.orbeon.com/oxf/pipeline"
    xmlns:frf="java:org.orbeon.oxf.fr.FormRunner">

    <xsl:import href="oxf:/oxf/xslt/utils/copy-modes.xsl"/>
    <xsl:import href="actions-common.xsl"/>
    <xsl:import href="synchronize-repeated-content.xsl"/>

    <xsl:function name="fr:action-bindings" as="element(xf:action)*">
        <xsl:param name="model" as="element(xf:model)"/>
        <xsl:sequence select="$model/xf:action[ends-with(@id, '-binding')]"/>
    </xsl:function>

    <xsl:function name="fr:itemset-actions-elements" as="element(xf:action)*">
        <xsl:param name="action-binding" as="element(xf:action)"/>
        <xsl:sequence select="$action-binding//xf:action[p:has-class('fr-itemset-action')]"/>
    </xsl:function>

    <xsl:function name="fr:action-bindings-2018.2" as="element(fr:action)*">
        <xsl:param name="model" as="element(xf:model)"/>
        <xsl:sequence select="$model/fr:action[@version = '2018.2']"/>
    </xsl:function>

    <xsl:function name="fr:itemset-actions-elements-2018.2" as="element(fr:control-setitems)*">
        <xsl:param name="action-binding" as="element(fr:action)"/>
        <xsl:sequence select="$action-binding//fr:control-setitems"/>
    </xsl:function>

    <xsl:function name="fr:has-known-action-event" as="xs:boolean">
        <xsl:param name="elem" as="element()"/>
        <xsl:sequence
            select="
                p:split(($elem/@event, $elem/@ev:event)[1]) = (
                    $controls-xforms-action-names,
                    $form-load-fr-action-names,    (: new names :)
                    'xforms-ready',                (: probably no longer needed :)
                    'xforms-model-construct-done'  (: probably no longer needed :)

                )
        "/>
    </xsl:function>

    <xsl:function name="fr:has-action-call-event" as="xs:boolean">
        <xsl:param name="elem" as="element()"/>
        <xsl:sequence
            select="($elem/@event, $elem/@ev:event)[1] =
                concat('fr-call-user-', frf:actionNameFromBindingId($elem/parent::xf:action/@id), '-action')"/>
    </xsl:function>

    <xsl:function name="fr:has-action" as="xs:boolean">
        <xsl:param name="model"       as="element(xf:model)"/>
        <xsl:param name="action-name" as="xs:string"/>
        <xsl:sequence
            select="
                exists(
                    $model/
                    fr:action-bindings(.)/
                    xf:action[
                        p:split((@event, @ev:event)[1]) = $action-name
                    ]
                )
        "/>
    </xsl:function>

    <!-- Common implementation of itemset actions (one per model) -->
    <xsl:function name="fr:itemset-action-common-impl" as="element(xf:action)">
        <xsl:param name="model-id" as="xs:string"/>

        <xf:action event="fr-call-itemset-action">

            <xf:var name="action-source"      value="event('action-source')"      as="xs:string"/>
            <xf:var name="control-name"       value="event('control-name')"       as="xs:string"/>
            <xf:var name="new-itemset-id"     value="event('new-itemset-id')"     as="xs:string"/>
            <xf:var name="new-itemset-holder" value="event('new-itemset-holder')" as="element(itemset)"/>
            <xf:var name="at"                 value="event('at')"                 as="xs:string?"/>

            <xf:var
                name="resolved-data-holders"
                value="
                    if (empty($at)) then
                        frf:resolveTargetRelativeToActionSource($action-source, $control-name, false(), ())
                    else
                        frf:resolveTargetRelativeToActionSourceFromBinds('{$model-id}', $control-name)[
                            if ($at = 'end') then
                                last()
                            else if ($at = 'start') then
                                1
                            else if ($at castable as xs:integer) then
                                xs:integer($at)
                            else
                                error()
                        ]
            "/>

            <xf:var
                name="choices-count"
                value="count($new-itemset-holder/choices)"/>

            <!--
                If all itemsets are identical, only keep the first one. This avoids duplication when the itemsets
                are not localized. See also: https://github.com/orbeon/orbeon-forms/issues/1191
            -->
            <xf:var
                name="keep-only-one"
                value="
                    $choices-count = 1 or
                    deep-equal(
                        for $ignore in 2 to $choices-count return $new-itemset-holder/choices[1],
                        $new-itemset-holder/choices[position() ge 2]
                    )
                "/>

            <xf:delete
                if="$keep-only-one"
                ref="$new-itemset-holder/choices[position() ge 2]"/>

            <xf:action if="not($keep-only-one)">
                <xf:action iterate="1 to $choices-count">
                    <xf:var name="p" value="."/>
                    <xf:insert
                        context="$new-itemset-holder/choices[$p]"
                        origin="xxf:instance('fr-form-resources')/resource[$p]/@xml:lang"/>
                </xf:action>
            </xf:action>

            <!-- Ensure existence of `<fr:metadata>` -->
            <xf:insert
                if="empty(instance('fr-form-instance')/fr:metadata)"
                context="instance('fr-form-instance')"
                ref="*"
                origin="xf:element('fr:metadata')"/>

            <!-- Insert new `<itemset>` -->
            <xf:insert
                context="instance('fr-form-instance')/fr:metadata"
                ref="*"
                origin="$new-itemset-holder"/>

            <!-- All targeted holders add indirection to itemset -->
            <xf:insert
                iterate="$resolved-data-holders"
                context="."
                origin="xf:attribute('fr:itemsetid', $new-itemset-id)"/>

            <!-- Set map for new itemsets -->
            <xf:var
                name="itemsetmap-node"
                value="frf:findItemsetMapNode($action-source, $control-name, instance('fr-form-instance'))"/>

            <xf:insert
                context="$itemsetmap-node"
                origin="xf:attribute('fr:itemsetmap', frf:addToItemsetMap(@fr:itemsetmap, $control-name, $new-itemset-id))"/>

            <!-- Clear information on descendants -->
            <xf:action iterate="$itemsetmap-node//*[exists(@fr:itemsetmap)]">
                <xf:setvalue
                    ref="."
                    value="frf:removeFromItemsetMap(@fr:itemsetmap, $control-name)"/>
            </xf:action>

            <!-- Garbage-collect unused itemsets and metadata -->
            <xf:action type="xpath">
                frf:garbageCollectMetadataItemsets(instance('fr-form-instance'))
            </xf:action>

            <!-- Filter item values that are out of range -->
            <!-- See: https://github.com/orbeon/orbeon-forms/issues/1019 -->
            <!-- NOTE: We guess whether the control is a select or select1 based on the element name. One exception is
                 autocomplete, which is also a single selection control. -->
            <xf:action>
                <xf:var
                    name="element-name"
                    value="local-name(xxf:control-element(concat($control-name, '-control')))"/>
                <xf:var
                    name="possible-values"
                    value="$new-itemset-holder/choices[1]/item/value/string()"/>
                <xf:action if="frf:isMultipleSelectionControl($element-name)">
                    <xf:action iterate="$resolved-data-holders">
                        <xf:var name="bind" value="."/>
                        <xf:setvalue
                            ref="$bind"
                            value="string-join(xxf:split($bind)[. = $possible-values], ' ')"/>
                    </xf:action>
                </xf:action>
                <xf:action
                    if="not($element-name = 'open-select1') and (
                            frf:isSingleSelectionControl($element-name) or
                            $element-name = 'autocomplete'
                        )">
                    <xf:action iterate="$resolved-data-holders">
                        <xf:var name="bind" value="."/>
                        <xf:setvalue
                            if="not(string($bind) = $possible-values)"
                            ref="$bind"/>
                    </xf:action>
                </xf:action>
            </xf:action>
        </xf:action>
    </xsl:function>

    <!-- Return one dataset instance per unique dataset name appearing in the given model's actions -->
    <xsl:function name="fr:common-dataset-actions-impl" as="element(xf:instance)*">
        <xsl:param name="model" as="element(xf:model)"/>

        <xsl:variable
            name="action-bindings"
            select="$model/fr:action-bindings(.)"/>

        <xsl:variable
            name="dataset-names"
            select="
                $action-bindings//
                    xf:action[
                        p:has-class('fr-save-to-dataset-action')
                    ]/
                    xf:var[@name = 'dataset-name']/
                    string()
                "/>

        <xsl:variable
            name="actions-20182"
            select="$model/fr:action-bindings-2018.2(.)"/>

        <xsl:variable
            name="dataset-names-20182"
            select="$actions-20182//(fr:dataset-write | fr:dataset-clear)/@name/string()"/>

        <xsl:variable
            name="existing-dataset-instances"
            select="$model/xf:instance/@id[starts-with(., 'fr-dataset-')]/substring-after(., 'fr-dataset-')"/>

        <xsl:for-each select="distinct-values(($dataset-names, $dataset-names-20182))[not(. = $existing-dataset-instances)]">
            <xf:instance id="fr-dataset-{.}"><_/></xf:instance>
        </xsl:for-each>

    </xsl:function>

    <xsl:function name="fr:common-service-actions-impl" as="element(xf:action)*">
        <xsl:param name="model" as="element(xf:model)"/>

        <xsl:variable
            name="action-bindings"
            select="$model/fr:action-bindings(.)"/>

        <xsl:variable
            name="service-names"
            select="
                $action-bindings//
                    xf:send/@submission[
                        ends-with(., '-submission')
                    ]/substring-before(., '-submission')
                "/>

        <xsl:variable
            name="actions-20182"
            select="$model/fr:action-bindings-2018.2(.)"/>

        <xsl:variable
            name="service-names-20182"
            select="$actions-20182//fr:service-call/@service/string()"/>

        <xsl:variable
            name="all-service-names"
            select="distinct-values(($service-names, $service-names-20182))"/>

        <xsl:if test="exists($all-service-names)">
            <xf:action
                event="xforms-submit-error"
                observer="{string-join(for $n in $all-service-names return concat($n, '-submission'), ' ')}"
                type="xpath">
                if (exists(event('fr-action-id'))) then xxf:remove-document-attributes(event('fr-action-id')) else (),
                fr:run-process-by-name('oxf.fr.detail.process', 'action-service-error')
            </xf:action>
        </xsl:if>

        <xsl:if test="exists($actions-20182)">
            <xf:action event="xxforms-action-error" target="#observer" propagate="stop">
                <xf:message level="xxf:log-error" value="concat('Error: ', xxf:trim(event('message')))"/>
                <xf:message level="xxf:log-error" value="event('element')"/>
                <xf:action type="xpath">
                    if (exists(event('fr-action-id'))) then xxf:remove-document-attributes(event('fr-action-id')) else (),
                    fr:run-process-by-name('oxf.fr.detail.process', 'action-action-error')
                </xf:action>
            </xf:action>
        </xsl:if>

    </xsl:function>

    <!--
        Update actions so that the implementation of the actions is up to date. Forms generated with Form Builder
        prior to this change embed a specific implementation of the actions. Forms generated after this change
        do not contain an implementation of the actions and only the parameters are present. See also:

         - annotate.xpl
         - form-to-xbl.xsl
         - https://github.com/orbeon/orbeon-forms/issues/1019
         - https://github.com/orbeon/orbeon-forms/issues/1190
         - https://github.com/orbeon/orbeon-forms/issues/1465
         - https://github.com/orbeon/orbeon-forms/issues/1105
     -->

    <!-- Gather top-level model as well as models within section templates -->
    <xsl:variable
        name="xbl-models"
        select="/xh:html/xh:head/xbl:xbl/xbl:binding/xbl:implementation/xf:model"/>

    <xsl:variable
        name="candidate-action-models"
        select="$fr-form-model, $xbl-models"/>

    <xsl:variable
        name="xbl-models-ids"
        select="$xbl-models/generate-id()"/>

    <xsl:variable
        name="candidate-action-models-ids"
        select="$fr-form-model-id, $xbl-models-ids"/>

    <xsl:variable
        name="fr-service-instance-ids"
        select="
            $candidate-action-models/
                xf:instance[
                    @id = (
                        'fr-service-request-instance',
                        'fr-service-response-instance'
                    )
                ]/generate-id()"/>

    <xsl:variable
        name="action-bindings"
        select="$candidate-action-models/fr:action-bindings(.)"/>

    <xsl:variable
        name="action-bindings-ids"
        select="$action-bindings/generate-id()"/>

    <xsl:variable
        name="action-bindings-2018.2"
        select="$candidate-action-models/fr:action-bindings-2018.2(.)"/>

    <xsl:variable
        name="action-bindings-ids-2018.2"
        select="$action-bindings-2018.2/generate-id()"/>

    <xsl:variable
        name="service-instances"
        select="
            $candidate-action-models/
                xf:instance[
                    p:has-class('fr-service') or p:has-class('fr-database-service')
                ]"/>

    <xsl:variable
        name="service-instance-ids"
        select="$service-instances/generate-id()"/>

    <xsl:variable
        name="service-submissions-ids"
        select="
            $candidate-action-models/
                xf:submission[
                    p:has-class('fr-service') or p:has-class('fr-database-service')
                ]/generate-id()"/>

    <xsl:variable
        name="models-with-actions-model-ids"
        select="
            distinct-values(
                ($action-bindings, $action-bindings-2018.2)/
                    (ancestor::xf:model[1])/
                    generate-id()
            )"/>

    <xsl:variable
        name="itemset-actions-elements"
        select="$action-bindings/fr:itemset-actions-elements(.)"/>

    <xsl:variable
        name="itemset-actions-elements-2018.2"
        select="$action-bindings-2018.2/fr:itemset-actions-elements-2018.2(.)"/>

    <xsl:variable
        name="models-with-itemset-actions-models-ids"
        select="
            distinct-values(
                ($itemset-actions-elements, $itemset-actions-elements-2018.2)/
                    (ancestor::xf:model[1])/
                    generate-id()
            )"/>

    <xsl:variable
        name="fr-form-model-vars"
        select="$fr-form-model/xf:var/@name/string()"/>

    <!-- Remove service `xf:instance`s (they will be placed in `fr-actions-model`) and existing Form Runner service instances if any -->
    <xsl:template
        match="
            /xh:html/xh:head//
                xf:model[
                    generate-id() = $candidate-action-models-ids
                ]/xf:instance[
                    generate-id() = ($fr-service-instance-ids, $service-instance-ids)
                ]"/>

    <!-- Match and modify service `xf:submission`s -->
    <xsl:template
        match="
            /xh:html/xh:head//
                xf:model[
                    generate-id() = $candidate-action-models-ids
                ]/xf:submission[
                    generate-id() = $service-submissions-ids
                ]">
        <xsl:variable
            name="add-request-parameters-to-resource"
            select="@serialization = 'application/x-www-form-urlencoded' and @method = 'get'"/>
        <xsl:variable
            name="library-name"
            select="ancestor::xbl:binding[1]/frf:findAppFromSectionTemplateUri(namespace-uri-for-prefix('component', .))"/>
        <xf:var name="{concat(@id, '-resource')}">
            <xsl:value-of select="frf:replaceVarReferencesWithFunctionCallsFromString(@resource, @resource, true(), $library-name, $fr-form-model-vars)"/>
        </xf:var>
        <xsl:variable name="resource" select="concat('$', @id, '-resource')"/>
        <xsl:copy>
            <xsl:copy-of select="@* except (@resource | @ref | @replace | @instance | @xxf:instance | @serialization)"/>
            <xsl:choose>
                <!-- Don't rely on XForms URL parameter serialization, as we want to support parameters where the
                     name is provided through an attribute, e.g. `<param name="my-name">my-value</param>`, this in
                     addition to the "default" parameters where the name is in the element name, e.g.
                     `<my-name>my-value</my-value>`. -->
                <xsl:when test="$add-request-parameters-to-resource">
                    <xsl:attribute name="serialization" select="'none'"/>
                    <xsl:attribute name="resource">{
                        let
                            $resource-without-params := xxf:evaluate-avt(<xsl:value-of select="$resource"/>),
                            $parameters-separator    := if (contains($resource-without-params, '?')) then '&amp;' else '?',
                            $parameters-list         :=
                                for $param-element in xxf:instance('fr-service-request-instance')/*
                                return
                                    let
                                        $param-value := $param-element/string(),
                                        $param-name  :=
                                            if (exists($param-element/@name))
                                            then $param-element/@name
                                            else $param-element/local-name()
                                    return
                                        concat(encode-for-uri($param-name), '=', encode-for-uri($param-value)),
                            $parameters-string := string-join($parameters-list, '&amp;')
                        return
                            concat(
                                $resource-without-params,
                                $parameters-separator,
                                $parameters-string
                            )
                    }</xsl:attribute>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:copy-of select="@serialization"/>
                    <xsl:attribute name="resource">{xxf:evaluate-avt(<xsl:value-of select="$resource"/>)}</xsl:attribute>
                    <xsl:attribute name="ref">xxf:instance('fr-service-request-instance')</xsl:attribute>
                </xsl:otherwise>
            </xsl:choose>
            <xsl:attribute name="targetref">xxf:instance('fr-service-response-instance')</xsl:attribute>
            <xsl:choose>
                <!-- See https://github.com/orbeon/orbeon-forms/issues/3945 -->
                <xsl:when test="@replace = 'xxf:binary'">
                    <xsl:copy-of select="@replace"/>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:attribute name="replace">instance</xsl:attribute>
                </xsl:otherwise>
            </xsl:choose>
            <!--
                An attribute on the submission (so the service) takes precedence over the event.
                As of 2024.1.x, this is not exposed through the "HTTP Service Editor" dialog, so if there
                is an attribute, it's because it has been put there by hand. The idea, though, is that
                until now in the JVM environment all service calls are 'synchronous', but we could mark a
                service explicitly as 'asynchronous'. In the browser environment, all service calls must
                be 'asynchronous'.

                NOTE: Another way would be to entirely disable synchronous calls in the JS environment at the
                XForms level. But are there cases where we still want synchronous calls on `xf:submission`, such as
                when loading resources? For now, we handle services below.
            -->
            <xsl:choose>
                <xsl:when test="@mode = 'synchronous'">
                    <!--
                        This takes precedence over the event, but we force 'asynchronous' anyway in the JS environment.
                        This said, having an explicit 'synchronous' should not happen, see the above comment.
                    -->
                    <xsl:attribute name="mode">{
                    if (fr:is-browser-environment()) then 'asynchronous' else 'synchronous'
                    }</xsl:attribute>
                </xsl:when>
                <xsl:when test="empty(@mode)">
                    <!--
                        Nothing was specified, so we honor the event if it exists, except that in the JS environment
                        we always use 'asynchronous' (see the above comment). If there is no event, we default to
                        'synchronous' in the JVM environment, and to 'asynchronous' in the JS environment.
                    -->
                    <xsl:attribute name="mode">{
                    (
                        if (fr:is-browser-environment()) then 'asynchronous' else (),
                        'asynchronous'[exists(event('fr-async')[. = 'true'])],
                        'synchronous'[exists(event('fr-async')[. = 'false'])],
                        'synchronous'
                    )[1]
                    }</xsl:attribute>
                </xsl:when>
                <xsl:otherwise>
                    <!--
                        This could be 'asynchronous' or an AVT. But we shouldn't support an AVT here in the form definition.
                        So we should be 'asynchronous'. In this case, this takes precedence, so we leave the value as is.
                    -->
                </xsl:otherwise>
            </xsl:choose>
            <!-- For now attribute on submission takes precedence over event. We could easily reverse that. -->
            <xsl:if test="empty(@xxf:response-must-await)">
                <xsl:attribute name="xxf:response-must-await">{
                    (
                        event('fr-response-must-await'),
                        'forever'
                    )[1]
                }</xsl:attribute>
            </xsl:if>
            <!-- https://github.com/orbeon/orbeon-forms/issues/4606 -->
            <xsl:apply-templates select="xf:header"/>
        </xsl:copy>
    </xsl:template>

    <!-- Match and modify action `xf:action`s -->
    <xsl:template
        match="
            /xh:html/xh:head//
                xf:model[
                    generate-id() = $candidate-action-models-ids
                ]/xf:action[
                    generate-id() = $action-bindings-ids
                ]">
        <xsl:copy>
            <xsl:copy-of select="@*"/>

            <xsl:variable name="model-id"     select="../@id/string()"/>
            <xsl:variable name="library-name" select="ancestor::xbl:binding[1]/frf:findAppFromSectionTemplateUri(namespace-uri-for-prefix('component', .))"/>

            <!-- Main event handler to start the action -->
            <xsl:for-each select="xf:action[fr:has-known-action-event(.) or fr:has-action-call-event(.)][1]">

                <xsl:variable name="action"                   select="."/>
                <xsl:variable name="iterate-control-name-var" select="$action/xf:var[@name = 'iterate-control-name']"/>

                <xsl:copy>
                    <xsl:copy-of select="$action/@*"/>

                    <xsl:if test="exists($action/@if)">
                        <xsl:attribute
                            name="if"
                            select="frf:replaceVarReferencesWithFunctionCallsFromString($action/@if, $action/@if, false(), $library-name, $fr-form-model-vars)"/>
                    </xsl:if>

                    <!-- Create action id compatible with `actions-20182.xsl` -->
                    <xf:var
                        name="current-action-id"
                        value="concat('fr-action-', secure:randomHexId())" xmlns:secure="java:org.orbeon.oxf.util.SecureUtils"/>

                    <!-- 1. Choose to iterate or not on `$iterate-control-name`.
                         2. Also store the absolute id of the action source in the request.
                            If the action source is passed from the listener via 'action-source' we just use it (also
                            case of section templates). -->
                    <xsl:choose>
                        <xsl:when test="exists($iterate-control-name-var)">
                            <xsl:copy-of select="$iterate-control-name-var"/>
                            <xf:action iterate="frf:findRepeatedControlsForTarget(event('action-source'), event('xxf:absolute-targetid'))[1], $iterate-control-name)">
                                <xf:action type="xpath">xxf:set-document-attribute($current-action-id, 'action-source', string(.))</xf:action>
                            </xf:action>
                        </xsl:when>
                        <xsl:otherwise>
                            <xf:action type="xpath">xxf:set-document-attribute($current-action-id, 'action-source', (event('action-source'), event('xxf:absolute-targetid'))[1])</xf:action>
                        </xsl:otherwise>
                    </xsl:choose>
                    <xsl:for-each select="$action/* except $iterate-control-name-var">
                        <xsl:copy>
                            <xsl:copy-of select="@* | node()"/>
                            <!-- We should only have nested `<xf:send>` children elements, right? -->
                            <xsl:if test="exists(self::xf:send)">
                                <xf:property name="fr-action-id" value="$current-action-id" xxf:tunnel="true"/>
                            </xsl:if>
                        </xsl:copy>
                    </xsl:for-each>
                </xsl:copy>
            </xsl:for-each>

            <!-- Request actions -->
            <xsl:for-each select="xf:action[@*:event = 'xforms-submit'][1]">
                <xsl:copy>
                    <xsl:copy-of select="@*"/>
                    <xf:var
                        name="request-instance-name"
                        value="{((*:variable | *:var)[@name = 'request-instance-name']/(@value | @select))[1]}"/>
                    <xf:insert
                        ref="xxf:instance('fr-service-request-instance')"
                        origin="xf:parse(xxf:instance($request-instance-name))"/>

                    <xf:var
                        name="current-action-id"
                        value="event('fr-action-id')"/>

                    <xsl:variable
                        name="request-actions"
                        select=".//xf:action[p:classes() = $request-action-classes]"/>

                    <xsl:if test="exists($request-actions)">
                        <xf:action context="xxf:instance('fr-service-request-instance')">
                            <xsl:for-each select="$request-actions">
                                <xsl:copy>
                                    <xsl:copy-of select="@*"/>
                                    <!-- Create variable with value -->
                                    <xsl:if test="p:has-class('fr-set-service-value-action') or p:has-class('fr-set-database-service-value-action')">
                                        <xsl:choose>
                                            <xsl:when test="exists(*:var[@name = 'control-name'])">
                                                <xsl:copy-of select="(*:variable | *:var)[@name = 'control-name']"/>
                                                <xsl:variable name="library-name" select="(*:variable | *:var)[@name = 'library-name']"/>
                                                <xf:var
                                                    name="value"
                                                    value="
                                                        frf:resolveTargetRelativeToActionSource(
                                                            xxf:get-document-attribute($current-action-id, 'action-source'),
                                                            $control-name,
                                                            true(),
                                                            {if (exists($library-name)) then $library-name/@value else '()'}
                                                        )"/>
                                            </xsl:when>
                                            <!-- https://github.com/orbeon/orbeon-forms/issues/4955 -->
                                            <xsl:when test="exists(*:var[@name = 'source-path'])">
                                                <xf:var
                                                    name="value"
                                                    context="instance('fr-form-instance')"
                                                    value="{
                                                        frf:replaceVarReferencesWithFunctionCallsFromString(
                                                            *:var[@name = 'source-path']/@value,
                                                            *:var[@name = 'source-path']/@value,
                                                            false(),
                                                            $library-name,
                                                            $fr-form-model-vars
                                                        )
                                                    }"/>
                                            </xsl:when>
                                            <xsl:otherwise/>
                                        </xsl:choose>
                                    </xsl:if>
                                    <xsl:choose>
                                        <xsl:when test="p:has-class('fr-set-service-value-action')">
                                            <xsl:if test="exists(*:var[@name = 'path'])">
                                                <xsl:copy-of select="*:var[@name = 'path']"/>
                                                <xf:setvalue ref="$path" value="$value"/>
                                            </xsl:if>
                                            <xsl:if test="exists(*:var[@name = 'parameter-name'])">
                                                <xsl:copy-of select="*:var[@name = 'parameter-name']"/>
                                                <xf:setvalue
                                                    ref="(
                                                        /params/param[@name        = $parameter-name],
                                                        //*          [local-name() = $parameter-name]
                                                    )[1]"
                                                    value="$value"/>
                                            </xsl:if>
                                        </xsl:when>
                                        <xsl:when test="p:has-class('fr-set-database-service-value-action')">
                                            <!-- Keep parameters but override implementation -->
                                            <xsl:apply-templates select="(*:variable | *:var)[@name = 'parameter']"/>
                                            <!-- Set value and escape single quotes -->
                                            <xf:setvalue
                                                xmlns:sql="http://orbeon.org/oxf/xml/sql"
                                                ref="/sql:config/sql:query/sql:param[xs:integer($parameter)]/(@value | @select)[1]"
                                                value="
                                                    concat(
                                                        '''',
                                                        replace(
                                                            string($value),
                                                            '''',
                                                            ''''''
                                                        ),
                                                        ''''
                                                    )"/>
                                        </xsl:when>
                                        <xsl:otherwise/>
                                    </xsl:choose>
                                </xsl:copy>
                            </xsl:for-each>
                        </xf:action>
                    </xsl:if>
                </xsl:copy>
            </xsl:for-each>

            <!-- Response actions -->
            <xsl:for-each select="xf:action[@*:event = 'xforms-submit-done'][1]">
                <xsl:copy>
                    <xsl:copy-of select="@* except @context"/>
                    <xsl:attribute name="context">xxf:instance('fr-service-response-instance')</xsl:attribute>

                    <xsl:variable
                        name="response-actions"
                        select=".//xf:action[p:classes() = $response-action-classes]"/>

                    <!-- Rebuild, for the `frf:resolveTargetRelativeToActionSource()` to return nodes in the current
                         `fr-form-instance`, if that instance was just replaced by a submission loading the
                         auto-saved or manually-saved document. -->
                    <!-- https://github.com/orbeon/orbeon-forms/issues/4178 -->

                    <!-- 2024-08-15: Force-`<xf:rebuild>` unneeded if there was an instance replacement (structural change).
                         https://github.com/orbeon/orbeon-forms/issues/1660 -->
                    <xf:recalculate model="{$model-id}" xxf:deferred="true"/>

                    <xf:var
                        name="current-action-id"
                        value="event('fr-action-id')"/>

                    <xsl:if test="exists($response-actions)">
                        <xsl:for-each select="$response-actions">
                            <xsl:copy>
                                <xsl:copy-of select="@*"/>

                                <xsl:choose>
                                    <xsl:when test="p:has-class('fr-set-control-value-action')">
                                        <!-- Keep parameters but override implementation -->
                                        <xsl:for-each select="(*:variable | *:var)[@name = 'control-name']">
                                            <xf:var
                                                name="{@name}"
                                                value="{(@value | @select)[1]}"/>
                                        </xsl:for-each>

                                        <xsl:for-each select="(*:variable | *:var)[@name = 'control-value']">
                                            <xf:var
                                                name="{@name}"
                                                value="{frf:replaceVarReferencesWithFunctionCallsFromString((@value | @select)[1], (@value | @select)[1], false(), $library-name, $fr-form-model-vars)}"/>
                                        </xsl:for-each>

                                        <!-- Set values (we choose to set all targets returned) -->
                                        <xf:action
                                            iterate="
                                                frf:resolveTargetRelativeToActionSource(
                                                    xxf:get-document-attribute($current-action-id, 'action-source'),
                                                    $control-name,
                                                    true(),
                                                    ()
                                                )">
                                            <xf:setvalue
                                                ref="."
                                                value="$control-value"/>
                                            <!-- https://github.com/orbeon/orbeon-forms/issues/5768 -->
                                            <xf:delete
                                                ref=".//@fr:tmp-file"/>
                                        </xf:action>
                                    </xsl:when>
                                    <xsl:when test="p:has-class('fr-itemset-action')">
                                        <!-- Keep parameters but override implementation -->
                                        <xsl:for-each select="(*:variable | *:var)[@name = 'control-name']">
                                            <xf:var
                                                name="{@name}"
                                                value="{(@value | @select)[1]}"/>
                                        </xsl:for-each>

                                        <xsl:variable
                                            name="resource-items-value"
                                            select="(*:variable | *:var)[@name = 'response-items']/(@value | @select)[1]"/>

                                        <xf:var
                                            xmlns:secure="java:org.orbeon.oxf.util.SecureUtils"
                                            name="new-itemset-id"
                                            value="concat('fr', secure:randomHexId())"/>

                                        <!-- New `<itemset>` outer element (not inserted into the instance yet) -->
                                        <xf:var
                                            name="new-itemset-holder"
                                            value="xf:element('itemset', xf:attribute('id', $new-itemset-id))"/>

                                        <!-- Create `<choices>` elements under the new `<itemset>` outer element -->
                                        <xf:action iterate="xxf:instance('fr-form-resources')/resource">

                                            <xf:var name="fr-lang" value="@xml:lang"/>

                                            <!-- Re-evaluate `$response-items` at each iteration because that can depend on `$fr-lang` -->
                                            <xf:var
                                                name="response-items"
                                                context="xxf:instance('fr-service-response-instance')"
                                                value="{frf:replaceVarReferencesWithFunctionCallsFromString($resource-items-value, $resource-items-value, false(), $library-name, $fr-form-model-vars)}"/>

                                            <xf:insert
                                                context="$new-itemset-holder"
                                                ref="*"
                                                origin="xf:element('choices')"/>

                                            <xf:var
                                                name="new-choices-holder"
                                                value="$new-itemset-holder/choices[last()]"/>

                                            <!-- Should use a version of `XFormsItemUtils.evaluateItemset()`
                                                 See https://github.com/orbeon/orbeon-forms/issues/3125 -->
                                            <xsl:variable
                                                name="item-label"
                                                select=".//(*:variable | *:var)[@name = ('item-label')]/(@value | @select)[1]"/>
                                            <xsl:variable
                                                name="item-value"
                                                select=".//(*:variable | *:var)[@name = ('item-value')]/(@value | @select)[1]"/>
                                            <xsl:variable
                                                name="item-hint"
                                                select=".//(*:variable | *:var)[@name = ('item-hint')]/(@value | @select)[1]"/>
                                            <xf:action iterate="$response-items">

                                                <xf:var name="item-label" value="{frf:replaceVarReferencesWithFunctionCallsFromString($item-label, $item-label, false(), $library-name, $fr-form-model-vars)}"/>
                                                <xf:var name="item-value" value="{frf:replaceVarReferencesWithFunctionCallsFromString($item-value, $item-value, false(), $library-name, $fr-form-model-vars)}"/>

                                                <xsl:choose>
                                                    <xsl:when test="exists($item-hint) and $item-hint != ''">
                                                        <xf:var name="item-hint"    value="{frf:replaceVarReferencesWithFunctionCallsFromString($item-hint, $item-hint, false(), $library-name, $fr-form-model-vars)}"/>
                                                        <xf:var name="element-hint" value="xf:element('hint', xs:string($item-hint))"/>
                                                    </xsl:when>
                                                    <xsl:otherwise>
                                                        <xf:var name="element-hint" value="()"/>
                                                    </xsl:otherwise>
                                                </xsl:choose>
                                                <xf:insert
                                                    context="$new-choices-holder"
                                                    ref="*"
                                                    origin="
                                                        xf:element(
                                                            'item',
                                                            (
                                                                xf:element('label', xs:string($item-label)),
                                                                xf:element('value', xs:string($item-value)),
                                                                $element-hint
                                                            )
                                                        )"/>
                                            </xf:action>
                                        </xf:action>

                                        <!-- Delegate the rest to common implementation. We should not duplicate much of the code above either, but
                                             the problem is the evaluation of `response-items`, 'item-label', and 'item-value', which must take place
                                             in a context where variables are available, so we cannot use `saxon:evaluate()`. -->
                                        <xf:dispatch name="fr-call-itemset-action" targetid="fr-form-instance">
                                            <xf:property name="action-source"      value="xxf:get-document-attribute($current-action-id, 'action-source')"/>
                                            <xf:property name="control-name"       value="$control-name"/>
                                            <xf:property name="new-itemset-id"     value="$new-itemset-id"/>
                                            <xf:property name="new-itemset-holder" value="$new-itemset-holder"/>
                                        </xf:dispatch>
                                    </xsl:when>
                                    <xsl:when test="p:has-class('fr-save-to-dataset-action')">
                                        <xf:var
                                            name="response"
                                            value="."/>
                                        <xf:insert
                                            context="xxf:instance('fr-service-response-instance')"
                                            ref="instance('fr-dataset-{xf:var[@name = 'dataset-name']}')"
                                            origin="$response"/>
                                    </xsl:when>
                                    <xsl:otherwise/>
                                </xsl:choose>
                            </xsl:copy>
                        </xsl:for-each>
                    </xsl:if>
                    <!-- Cleanup response, which is no longer needed -->
                    <xf:insert ref="." origin="xf:element('response')"/>
                </xsl:copy>
            </xsl:for-each>
        </xsl:copy>
    </xsl:template>

    <!-- Match itemsets of controls which can be the target of an itemset action -->
    <xsl:template match="xf:itemset" mode="within-grid">
        <xsl:param name="itemset-action-control-names" tunnel="yes"/>
        <xsl:param name="library-name" as="xs:string?" tunnel="yes"/>
        <xsl:choose>
            <xsl:when test="../@fr:itemsetid-mode = 'always' or (frf:controlNameFromId(../@id) = $itemset-action-control-names and not(../@fr:itemsetid-mode = 'never'))">
                <xsl:copy>
                    <!-- Update `@ref` attribute to disable the default itemset if an `@fr:itemsetid` attribute is present -->
                    <xsl:if test="exists(@ref)">
                        <xsl:attribute
                            name="ref"
                            select="
                                concat(
                                    'if (empty(@fr:itemsetid)) then ',
                                    if (exists(fr:filter/fr:expr)) then
                                        concat(
                                            @ref,
                                            '[boolean(',
                                            frf:replaceVarReferencesWithFunctionCallsFromString(fr:filter/fr:expr, fr:filter/fr:expr, false(), $library-name, $fr-form-model-vars),
                                            ')]'
                                        )
                                    else
                                        @ref,
                                    ' else ()'
                                 )"/>
                    </xsl:if>
                    <xsl:apply-templates select="@* except @ref | node() except fr:filter"/>
                </xsl:copy>
                <!-- Add new itemset which kicks in if an `@fr:itemsetid` attribute is present -->
                <xf:itemset ref="id(@fr:itemsetid)[1]/(choices[@xml:lang = xxf:lang()], choices)[1]/item">
                    <xf:label ref="label">
                        <!-- Keep mediatype in case it is present so that service can return HTML labels -->
                        <xsl:copy-of select="xf:label/@mediatype"/>
                    </xf:label>
                    <xf:value ref="value"/>
                    <xf:hint ref="hint"/>
                </xf:itemset>
            </xsl:when>
            <xsl:when test="exists(fr:filter/fr:expr)">
                <!-- Only check for the filter -->
                <xsl:copy>
                    <xsl:if test="exists(@ref)">
                        <xsl:attribute
                            name="ref"
                            select="
                                concat(
                                    @ref,
                                    '[boolean(',
                                    frf:replaceVarReferencesWithFunctionCallsFromString(fr:filter/fr:expr, fr:filter/fr:expr, false(), $library-name, $fr-form-model-vars),
                                    ')]'
                                )"/>
                    </xsl:if>
                    <xsl:apply-templates select="@* except @ref | node() except fr:filter"/>
                </xsl:copy>
            </xsl:when>
            <xsl:otherwise>
                <xsl:next-match/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <!-- Match models with at least one action -->
    <!-- This does NOT match the main model handled in components.xsl -->
    <xsl:template match="/xh:html/xh:head//xf:model[generate-id() = $models-with-actions-model-ids]">

        <xsl:variable name="model" select="."/>

        <!-- NOTE: This would look better with XPath 3.1 maps and arrays. -->
        <xsl:variable
            name="has-actions"
            as="xs:boolean+"
            select="
                for $fr-action-name in $form-load-fr-action-names
                    return fr:has-action($model, $fr-action-name)
        "/>

        <!-- If there are any "form load" actions, insert model before main model. This will dispatch the appropriate
             events to the main model as needed. -->
        <xsl:if test="$has-actions = true()">
            <xf:model>
                <xsl:for-each select="1 to count($form-load-xforms-action-names)">
                    <xsl:variable name="p" select="."/>
                    <xsl:if test="$has-actions[$p]">
                        <xf:action event="{$form-load-xforms-action-names[$p]}" observer="{$model/@id}">
                            <xf:dispatch name="{$form-load-fr-action-names[$p]}" targetid="{$model/@id}"/>
                        </xf:action>
                    </xsl:if>
                </xsl:for-each>
            </xf:model>
        </xsl:if>

        <xf:model id="fr-actions-model">
            <xsl:call-template name="action-common-impl">
                <xsl:with-param name="model" select="$model"/>
            </xsl:call-template>
        </xf:model>

        <xsl:copy>
            <xsl:apply-templates select="@* | node()"/>
            <xsl:if test="generate-id() = $models-with-itemset-actions-models-ids">
                <xsl:copy-of select="fr:itemset-action-common-impl($model/@id/string())"/>
            </xsl:if>
            <xsl:copy-of select="fr:common-dataset-actions-impl($model)"/>
            <xsl:copy-of select="fr:common-service-actions-impl($model)"/>
        </xsl:copy>
    </xsl:template>

    <!-- Insert service instances -->
    <xsl:template name="action-common-impl">
        <xsl:param name="model" as="element(xf:model)"/>

        <xf:instance id="fr-service-request-instance"  xxf:exclude-result-prefixes="#all"><request/></xf:instance>
        <xf:instance id="fr-service-response-instance" xxf:exclude-result-prefixes="#all"><response/></xf:instance>

        <xsl:copy-of select="$model/xf:instance[p:has-class('fr-service') or p:has-class('fr-database-service')]"/>

        <!-- For "continue action" confirmation dialog -->
        <xf:action event="fr-positive" target="#observer">
            <xf:dispatch
                name="{{substring-after(event('context'), '|')}}"
                targetid="{$model/@id}">
                <xf:property name="fr-action-id" value="substring-before(event('context'), '|')"/>
            </xf:dispatch>
        </xf:action>

        <xf:action event="fr-negative" target="#observer" type="xpath">
            xxf:remove-document-attributes(substring-before(event('context'), '|'))
        </xf:action>

    </xsl:template>

</xsl:stylesheet>
