<xsl:stylesheet
    version="2.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:xf="http://www.w3.org/2002/xforms"
    xmlns:xxf="http://orbeon.org/oxf/xml/xforms"
    xmlns:fr="http://orbeon.org/oxf/xml/form-runner"
    xmlns:xh="http://www.w3.org/1999/xhtml"
    xmlns:xbl="http://www.w3.org/ns/xbl"
    xmlns:frf="java:org.orbeon.oxf.fr.FormRunner"
    xmlns:p="http://www.orbeon.com/oxf/pipeline"
    xmlns:map="http://www.w3.org/2005/xpath-functions/map"
    xmlns:array="http://www.w3.org/2005/xpath-functions/array">

    <xsl:import href="functions.xsl"/>

    <xsl:variable
        name="controls-roots-for-pdf-appearance"
        select="
            if ($is-pdf-mode and not($use-pdf-template)) then
                (
                    $body,
                    /xh:html/xh:head/xbl:xbl/xbl:binding[p:has-class('fr-section-component')]/xbl:template
                )
            else
                ()"/>

    <!-- For now only support `xf:select1[appearance ~= dropdown]` and `xf:select1[appearance ~= search]`. The databound
         controls (`fr:databound-select1` and `fr:databound-select1-search`) don't currently have an `xf:select1`
         representation. -->
    <xsl:variable
        name="controls-to-check-for-pdf-appearance"
        select="$controls-roots-for-pdf-appearance//(
            fr:dropdown-select1         | (: output as `fr:dropdown-select1` or `xf:select1[appearance ~= dropdown]` :)
            fr:dropdown-select1-search  | (: output as `xf:select1[appearance ~= search]`                            :)
            xf:select1[
                @appearance = ('dropdown', 'search')
            ][
                exists(@fr:pdf-appearance) or
                exists(
                    map:get(
                        $select1-pdf-appearances,
                        fr:direct-name-for-select1-element(.)
                    )
                )
            ]
        )"/>

    <xsl:variable
        name="controls-to-check-for-pdf-appearance-ids"
        select="$controls-to-check-for-pdf-appearance/generate-id()"/>

    <!-- Convert `xf:input` of type `date` and `time` to `fr:date` and `fr:time` -->
    <xsl:template
        mode="within-grid"
        match="xf:input[@bind]">
        <xsl:param name="binds-root" tunnel="yes"/>
        <xsl:variable name="bind-id" select="@bind"/>
        <xsl:choose>
            <xsl:when test="
                exists(
                    $binds-root//xf:bind[
                        @id = $bind-id and (
                            (@type, xf:type) = ('xf:date', 'xs:date')
                        )
                    ]
                )">
                <fr:date>
                    <xsl:choose>
                        <xsl:when test="$is-static-readonly and ($is-pdf-mode or not($date-native-picker = 'always'))">
                            <xsl:attribute name="fr:static-readonly">true</xsl:attribute>
                        </xsl:when>
                        <xsl:when test="$use-pdf-template">
                            <xsl:attribute name="fr:pdf-template">true</xsl:attribute>
                        </xsl:when>
                    </xsl:choose>
                    <xsl:apply-templates select="@* | node()" mode="#current"/>
                    <!-- See other comment further "Q: Do we really need this?" -->
                    <xsl:if test="empty(xf:alert)">
                        <xf:alert ref="xxf:r('detail.labels.alert', '|fr-fr-resources|')"/>
                    </xsl:if>
                </fr:date>
            </xsl:when>
            <xsl:when test="
                exists(
                    $binds-root//xf:bind[
                        @id = $bind-id and
                        (@type, xf:type) = ('xf:time', 'xs:time')
                    ]
                )">
                <fr:time>
                    <xsl:choose>
                        <xsl:when test="$is-static-readonly and ($is-pdf-mode or not($time-native-picker = 'always'))">
                            <xsl:attribute name="fr:static-readonly">true</xsl:attribute>
                        </xsl:when>
                        <xsl:when test="$use-pdf-template">
                            <xsl:attribute name="fr:pdf-template">true</xsl:attribute>
                        </xsl:when>
                    </xsl:choose>
                    <xsl:apply-templates select="@* | node()" mode="#current"/>
                    <!-- See other comment further "Q: Do we really need this?" -->
                    <xsl:if test="empty(xf:alert)">
                        <xf:alert ref="xxf:r('detail.labels.alert', '|fr-fr-resources|')"/>
                    </xsl:if>
                </fr:time>
            </xsl:when>
            <xsl:otherwise>
                <xsl:next-match/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <!-- NOTE: The `xf:label` rule below matches `select1` controls and if it matches this one won't match. But we shouldn't have
         common cases where the `xf:label` is missing as Form Builder adds that element. -->
    <xsl:template
        match="
            fr:*      [generate-id() = $controls-to-check-for-pdf-appearance-ids] |
            xf:select1[generate-id() = $controls-to-check-for-pdf-appearance-ids]"
        mode="within-grid">
        <!-- For now this only applies to controls that have an `xf:select1` binding -->
        <xsl:element name="xf:select1">
            <xsl:choose>
                <xsl:when test="$is-static-readonly">
                    <xsl:attribute name="fr:static-readonly">true</xsl:attribute>
                </xsl:when>
                <xsl:when test="$use-pdf-template">
                    <xsl:attribute name="fr:pdf-template">true</xsl:attribute>
                </xsl:when>
            </xsl:choose>
            <xsl:apply-templates select="@* except (@appearance, @fr:pdf-appearance)" mode="#current"/>
            <xsl:attribute name="appearance" select="(@fr:pdf-appearance, map:get($select1-pdf-appearances, fr:direct-name-for-select1-element(.)))[1]"/>
            <xsl:apply-templates select="node()" mode="#current"/>
        </xsl:element>
    </xsl:template>

    <!--
        This should not conflict with other rules, which apply to:

        1. PDF modes and
        2. dynamic selection controls

        We add an alert, and we need to target:

        - controls with static items but that are NOT open selection
        - Boolean selection controls
        - controls that can be the target of itemset actions, INCLUDING open selection

        The combination of those is included in `$choice-validation-selection-control-names`.

        See https://github.com/orbeon/orbeon-forms/issues/6008

        Set priority a little higher, see https://github.com/orbeon/orbeon-forms/issues/6709.
     -->
    <xsl:template
        mode="within-grid"
        match="*[$validate-selection-controls-choices and frf:isMaybeSelectionControl(.)]"
        priority="10"
    >
        <xsl:param name="choice-validation-selection-control-names" tunnel="yes"/>
        <xsl:variable name="is-multiple" select="frf:isMultipleSelectionControl(local-name(.))"/>
        <xsl:choose>
            <xsl:when test="frf:controlNameFromId(@id) = $choice-validation-selection-control-names">
                <xsl:copy>
                    <xsl:choose>
                        <xsl:when test="$is-static-readonly">
                            <xsl:attribute name="fr:static-readonly">true</xsl:attribute>
                        </xsl:when>
                        <xsl:when test="$use-pdf-template">
                            <xsl:attribute name="fr:pdf-template">true</xsl:attribute>
                        </xsl:when>
                    </xsl:choose>
                    <xsl:apply-templates select="@* | node()" mode="#current"/>
                        <xf:alert
                            ref="
                                xxf:format-message(
                                    xxf:r(
                                        'detail.labels.alert-out-of-range{'-multiple'[$is-multiple]}',
                                        '|fr-fr-resources|'
                                    ),
                                    (
                                        string(.)
                                    )
                                )"
                            validation="{frf:controlNameFromId(@id)}-choice-constraint"/>
                </xsl:copy>
                <xf:trigger
                    class="fr-clear-out-of-range"
                    appearance="xxf:mini"
                    ref=".[xxf:visited('{@id}') and xxf:failed-validations(xxf:binding('{@id}')) = concat(frf:controlNameFromId('{@id}'), '-choice-constraint')]">
                    <xf:label ref="xxf:r('detail.labels.clear-out-of-range', '|fr-fr-resources|')"/>
                    <xf:action event="DOMActivate">
                        <xsl:choose>
                            <xsl:when test="$is-multiple">
                                <xf:setvalue
                                    ref="xxf:binding('{@id}')"
                                    value="
                                        let $itemset-values := xxf:itemset('{@id}', 'xml')//value/string()
                                        return
                                            string-join(
                                                xxf:split(string(.))[
                                                    . = $itemset-values
                                                ],
                                                ' '
                                            )"/>
                            </xsl:when>
                            <xsl:otherwise>
                                <xf:setvalue
                                    ref="xxf:binding('{@id}')"/>
                            </xsl:otherwise>
                        </xsl:choose>
                    </xf:action>
                </xf:trigger>
            </xsl:when>
            <xsl:otherwise>
                <xsl:next-match/>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <xsl:template
        match="xf:output[exists(xf:label) and empty(@appearance)]"
        mode="within-grid">
        <xsl:copy>
            <xsl:choose>
                <xsl:when test="$is-static-readonly">
                    <xsl:attribute name="fr:static-readonly">true</xsl:attribute>
                </xsl:when>
                <xsl:when test="$use-pdf-template">
                    <xsl:attribute name="fr:pdf-template">true</xsl:attribute>
                </xsl:when>
            </xsl:choose>
            <xsl:for-each select="$calculated-value-appearance[. != 'full']"><!-- `full` is the default so don't bother adding the attribute in this case -->
                <xsl:attribute name="appearance" select="."/>
            </xsl:for-each>
            <xsl:apply-templates select="@* | node()" mode="#current"/>
        </xsl:copy>
    </xsl:template>

    <!-- See also `fb.ControlOps` for the renaming part -->
    <xsl:template
        match="fr:number[exists(@prefix | @suffix)] | fr:currency[exists(@prefix | @suffix)]"
        mode="within-grid">
        <xsl:param name="library-name" as="xs:string?" tunnel="yes"/>
        <xsl:copy>
            <xsl:choose>
                <xsl:when test="$is-static-readonly">
                    <xsl:attribute name="fr:static-readonly">true</xsl:attribute>
                </xsl:when>
                <xsl:when test="$use-pdf-template">
                    <xsl:attribute name="fr:pdf-template">true</xsl:attribute>
                </xsl:when>
            </xsl:choose>
            <xsl:for-each select="@prefix | @suffix">
                <xsl:attribute name="{name(.)}" select="frf:replaceVarReferencesWithFunctionCallsFromString(., ., true(), $library-name, ())"/>
            </xsl:for-each>
            <xsl:apply-templates select="@* except (@prefix | @suffix) | node()" mode="#current"/>
        </xsl:copy>
    </xsl:template>

    <!-- See also `fb.ControlOps` for the renaming part -->
    <xsl:template
        match="fr:databound-select1 | fr:databound-select1-search"
        mode="within-grid">
        <xsl:param name="library-name" as="xs:string?" tunnel="yes"/>
        <xsl:copy>
            <xsl:choose>
                <xsl:when test="$is-static-readonly">
                    <xsl:attribute name="fr:static-readonly">true</xsl:attribute>
                </xsl:when>
                <xsl:when test="$use-pdf-template">
                    <xsl:attribute name="fr:pdf-template">true</xsl:attribute>
                </xsl:when>
            </xsl:choose>
            <xsl:if test="exists(@resource | @selection)">
                <!-- As calls below can generate `frf:controlVariableValue()` -->
                <xsl:namespace name="frf" select="'java:org.orbeon.oxf.fr.FormRunner'"/>
            </xsl:if>
            <xsl:for-each select="@resource | @selection">
                <xsl:attribute name="{name(.)}" select="frf:replaceVarReferencesWithFunctionCallsFromString(., ., true(), $library-name, ('fr-search-value', 'fr-search-page'))"/>
            </xsl:for-each>
            <xsl:apply-templates select="@* except (@resource | @selection) | node() except (xf:itemset, xf:item, xf:choices)" mode="#current"/>
            <xsl:apply-templates select="xf:itemset | xf:item | xf:choices" mode="within-databound-itemset"/>
        </xsl:copy>
    </xsl:template>

    <!-- See also `fb.ControlOps` for the renaming part -->
    <xsl:template
        match="@ref | @value | @label | @hint"
        mode="within-databound-itemset">
        <xsl:param name="library-name" as="xs:string?" tunnel="yes"/>
        <xsl:attribute name="{name(.)}" select="frf:replaceVarReferencesWithFunctionCallsFromString(., ., false(), $library-name, ())"/>
    </xsl:template>

    <xsl:template
        match="@class"
        mode="within-grid">
        <xsl:param name="library-name" as="xs:string?" tunnel="yes"/>
        <xsl:attribute name="class" select="frf:replaceVarReferencesWithFunctionCallsFromString(., ., true(), $library-name, ())"/>
    </xsl:template>

    <!-- Process dynamic LHHA with parameters -->
    <xsl:template
        match="
            *[
                (
                    self::xf:label               or
                    self::xf:help                or
                    self::xf:hint                or
                    self::xf:alert               or
                    self::fr:text                or
                    self::fr:short-label         or
                    self::fr:iteration-label     or
                    self::fr:add-iteration-label
                ) and
                exists(fr:param)
            ]
        "
        mode="within-controls within-grid">

        <xsl:param name="library-name" as="xs:string?" tunnel="yes"/>

        <xsl:copy>
            <xsl:apply-templates select="@*" mode="#current"/>
            <xsl:variable name="xxf-r-path" select="
                (: For instance: `$form-resources/control/alert[2]` → `control.alert.1` :)
                let
                    $without-prefix := substring-after(@ref, '$form-resources/'),
                    $dot            := replace($without-prefix, '/', '.'),
                    $indexed        := replace($dot, '\[(\d+)\]', '.$1'),
                    $split-on-dot   := tokenize($indexed, '\.'),
                    $adjust-index   := for $part in $split-on-dot return
                                       if ($part castable as xs:integer)
                                       then string(xs:integer($part) - 1)
                                       else $part,
                    $merge          := string-join($adjust-index, '.')
                return $merge
            "/>
            <xsl:attribute
                name="ref"
                select="
                    concat(
                        'xxf:r(''',
                            $xxf-r-path,
                            ''',''fr-form-resources'',',
                            fr:build-template-param-map(
                                fr:param,
                                $library-name,
                                false(),                  (: for-pdf :)
                                @mediatype = 'text/html', (: for-text-html-mediatype :)
                                ()
                            ),
                        ')'
                    )"/>
        </xsl:copy>
    </xsl:template>

    <xsl:template match="fr:attachment/xf:hint[exists(@ref) and empty(text())]" mode="within-grid">
        <xsl:param name="binds-root" tunnel="yes"/>
        <xsl:copy>
            <xsl:apply-templates select="@* except (@ref, @fr:automatic)" mode="#current"/>
            <xsl:variable name="control" select=".."/>
            <xsl:variable name="bind-id" select="$control/@bind"/>
            <xsl:variable name="bind"    select="$binds-root//xf:bind[@id = $bind-id]"/>
            <xsl:variable name="xpaths"  select="$bind/@constraint, $bind/xf:constraint/@value"/>

            <xsl:variable
                name="automatic-hint-enabled"
                as="xs:boolean"
                select="
                    @fr:automatic = 'true' or
                    (
                        not(@fr:automatic = 'false') and
                        (
                            $fr-form-metadata/automatic-hints = 'true' or (
                                not($fr-form-metadata/automatic-hints = 'false') and
                                p:property(string-join(('oxf.fr.detail.hint.automatic', $app, $form), '.')) = true()
                            )
                        )
                    )"/>

            <!-- XPath for the automatic hint, or empty string if it shouldn't be used  -->
            <xsl:variable
                name="automatic-hint-xpath"
                as="xs:string"
                select="
                    if   ($automatic-hint-enabled)
                    then frf:knownConstraintsToAutomaticHint($xpaths)
                    else ''"/>

            <!-- Generate `@ref` possibly combining form author provided hint with automatic hint -->
            <xsl:attribute
                name="ref"
                select="
                    if   (p:non-blank($automatic-hint-xpath))
                    then
                        concat(
                            'string-join((',
                            $automatic-hint-xpath,
                            ', ',
                            @ref,
                            '), '' '')'
                        )
                    else @ref"/>
        </xsl:copy>
    </xsl:template>

    <!-- Annotate remaining (most) controls with `fr:static-readonly`/`fr:pdf-template` if needed -->
    <xsl:template
        mode="within-grid"
        match="*[parent::fr:c or parent::xh:td]"
        priority="-10">
        <xsl:copy>
            <xsl:choose>
                <xsl:when test="
                    $is-static-readonly and (
                        $is-pdf-mode or (
                            (: `view` mode :)
                            not(
                                (exists(self::fr:date) and (@native-picker/string(), $date-native-picker)[1] = 'always') or
                                (exists(self::fr:time) and (@native-picker/string(), $time-native-picker)[1] = 'always')
                            )
                        )
                    )
                ">
                    <xsl:attribute name="fr:static-readonly">true</xsl:attribute>
                </xsl:when>
                <xsl:when test="$use-pdf-template">
                    <xsl:attribute name="fr:pdf-template">true</xsl:attribute>
                </xsl:when>
            </xsl:choose>
            <xsl:apply-templates select="@* | node()" mode="#current"/>
        </xsl:copy>
    </xsl:template>

    <!-- Add a default xf:alert for those fields which don't have one. Only do this within grids and dialogs. -->
    <!-- Q: Do we really need this? -->
    <!-- NOTE: Lower priority so that `xf:input[@bind]` rules match. -->
    <xsl:template
        mode="within-grid"
        match="xf:*[local-name() = ('input', 'textarea', 'select', 'select1', 'upload', 'secret') and not(xf:alert)]"
        priority="-20">
        <xsl:copy>
            <xsl:choose>
                <xsl:when test="$is-static-readonly">
                    <xsl:attribute name="fr:static-readonly">true</xsl:attribute>
                </xsl:when>
                <xsl:when test="$use-pdf-template">
                    <xsl:attribute name="fr:pdf-template">true</xsl:attribute>
                </xsl:when>
            </xsl:choose>
            <xsl:apply-templates select="@* | node()" mode="#current"/>
            <xf:alert ref="xxf:r('detail.labels.alert', '|fr-fr-resources|')"/>
        </xsl:copy>
    </xsl:template>

</xsl:stylesheet>
