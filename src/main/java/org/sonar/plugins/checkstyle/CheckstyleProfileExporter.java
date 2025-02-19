////////////////////////////////////////////////////////////////////////////////
// checkstyle: Checks Java source code for adherence to a set of rules.
// Copyright (C) 2001-2023 the original author or authors.
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 3 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
////////////////////////////////////////////////////////////////////////////////

package org.sonar.plugins.checkstyle;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.ExtensionPoint;
import org.sonar.api.batch.ScannerSide;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.config.Configuration;
import org.sonar.api.profiles.ProfileExporter;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.rules.ActiveRule;
import org.sonar.plugins.checkstyle.rule.ActiveRuleWrapper;
import org.sonar.plugins.checkstyle.rule.ActiveRuleWrapperScannerImpl;
import org.sonar.plugins.checkstyle.rule.ActiveRuleWrapperServerImpl;

import com.google.common.annotations.VisibleForTesting;

@ExtensionPoint
@ScannerSide
public class CheckstyleProfileExporter extends ProfileExporter {

    public static final String DOCTYPE_DECLARATION =
        "<!DOCTYPE module PUBLIC \"-//Checkstyle//DTD Checkstyle Configuration 1.3//EN\" "
        + "\"https://checkstyle.org/dtds/configuration_1_3.dtd\">";
    private static final String CLOSE_MODULE = "</module>";

    private final Configuration configuration;

    public CheckstyleProfileExporter(Configuration configuration) {
        super(CheckstyleConstants.REPOSITORY_KEY, CheckstyleConstants.PLUGIN_NAME);
        this.configuration = configuration;
        setSupportedLanguages(CheckstyleConstants.JAVA_KEY);
        setMimeType("application/xml");
    }

    @Override
    public void exportProfile(RulesProfile profile, Writer writer) {
        try {
            final List<ActiveRule> activeRules = profile
                    .getActiveRulesByRepository(CheckstyleConstants.REPOSITORY_KEY);
            if (activeRules != null) {
                final List<ActiveRuleWrapper> activeRuleWrappers = new ArrayList<>();
                for (ActiveRule activeRule : activeRules) {
                    activeRuleWrappers.add(new ActiveRuleWrapperServerImpl(activeRule));
                }
                final Map<String, List<ActiveRuleWrapper>> activeRulesByConfigKey =
                        arrangeByConfigKey(activeRuleWrappers);
                generateXml(writer, activeRulesByConfigKey);
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("Fail to export the profile " + profile, ex);
        }
    }

    /**
     * Exports the active rules to the specified writer.
     *
     * @param activeRules The rules to export.
     * @param writer The destination of the export.
     * @throws IllegalStateException if the rules failed to export.
     */
    public void exportProfile(ActiveRules activeRules, Writer writer) {
        try {
            final List<ActiveRuleWrapper> activeRuleWrappers = new ArrayList<>();
            for (org.sonar.api.batch.rule.ActiveRule activeRule : activeRules
                    .findByRepository(CheckstyleConstants.REPOSITORY_KEY)) {
                activeRuleWrappers.add(new ActiveRuleWrapperScannerImpl(activeRule));
            }
            final Map<String, List<ActiveRuleWrapper>> activeRulesByConfigKey =
                    arrangeByConfigKey(activeRuleWrappers);
            generateXml(writer, activeRulesByConfigKey);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Fail to export active rules.", ex);
        }

    }

    private void generateXml(Writer writer, Map<String,
            List<ActiveRuleWrapper>> activeRulesByConfigKey) throws IOException {
        appendXmlHeader(writer);
        appendTabWidth(writer);
        appendCustomFilters(writer);
        appendCheckerModules(writer, activeRulesByConfigKey);
        appendTreeWalker(writer, activeRulesByConfigKey);
        appendXmlFooter(writer);
    }

    private static void appendXmlHeader(Writer writer) throws IOException {
        writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + DOCTYPE_DECLARATION
                + "<!-- Generated by Sonar -->" + "<module name=\"Checker\">");
    }

    private void appendCustomFilters(Writer writer) throws IOException {
        final String filtersXml = configuration.get(CheckstyleConstants.CHECKER_FILTERS_KEY)
                .orElse(null);
        writer.append(filtersXml);
    }

    private void appendTabWidth(Writer writer) throws IOException {
        final String tabWidth = configuration.get(CheckstyleConstants.CHECKER_TAB_WIDTH)
                .orElse(null);
        appendModuleProperty(writer, "tabWidth", tabWidth);
    }

    private static void appendCheckerModules(Writer writer,
            Map<String, List<ActiveRuleWrapper>> activeRulesByConfigKey) throws IOException {
        for (Map.Entry<String, List<ActiveRuleWrapper>> entry : activeRulesByConfigKey.entrySet()) {
            final String configKey = entry.getKey();
            if (!isInTreeWalker(configKey)) {
                final List<ActiveRuleWrapper> activeRules = entry.getValue();
                for (ActiveRuleWrapper activeRule : activeRules) {
                    appendModule(writer, activeRule);
                }
            }
        }
    }

    private void appendTreeWalker(Writer writer,
            Map<String, List<ActiveRuleWrapper>> activeRulesByConfigKey) throws IOException {
        writer.append("<module name=\"TreeWalker\">");
        if (isSuppressWarningsEnabled()) {
            writer.append("<module name=\"SuppressWarningsHolder\"/> ");
        }
        final List<String> ruleSet = new ArrayList<>(activeRulesByConfigKey.keySet());
        Collections.sort(ruleSet);
        for (String configKey : ruleSet) {
            if (isInTreeWalker(configKey)) {
                final List<ActiveRuleWrapper> activeRules = activeRulesByConfigKey.get(configKey);
                for (ActiveRuleWrapper activeRule : activeRules) {
                    appendModule(writer, activeRule);
                }
            }
        }
        // append Treewalker filters
        final String filtersXml = configuration
                .get(CheckstyleConstants.TREEWALKER_FILTERS_KEY)
                .orElse(null);
        writer.append(filtersXml);

        writer.append(CLOSE_MODULE);
    }

    private boolean isSuppressWarningsEnabled() {
        final String filtersXml = configuration.get(CheckstyleConstants.CHECKER_FILTERS_KEY)
                .orElse(null);
        boolean result = false;
        if (filtersXml != null) {
            result = filtersXml.contains("<module name=\"SuppressWarningsFilter\" />");
        }
        return result;
    }

    private static void appendXmlFooter(Writer writer) throws IOException {
        writer.append(CLOSE_MODULE);
    }

    @VisibleForTesting
    static boolean isInTreeWalker(String configKey) {
        return StringUtils.startsWithIgnoreCase(configKey, "Checker/TreeWalker/");
    }

    private static Map<String, List<ActiveRuleWrapper>> arrangeByConfigKey(
            Collection<ActiveRuleWrapper> activeRules) {
        final Map<String, List<ActiveRuleWrapper>> result = new HashMap<>();
        for (ActiveRuleWrapper activeRule : activeRules) {
            final String key = activeRule.getInternalKey();
            if (result.containsKey(key)) {
                final List<ActiveRuleWrapper> rules = result.get(key);
                rules.add(activeRule);
            }
            else {
                final List<ActiveRuleWrapper> rules = new ArrayList<>();
                rules.add(activeRule);
                result.put(key, rules);
            }
        }
        return result;
    }

    private static void appendModule(Writer writer, ActiveRuleWrapper activeRule)
            throws IOException {
        final String moduleName = StringUtils.substringAfterLast(activeRule.getInternalKey(), "/");
        writer.append("<module name=\"");
        StringEscapeUtils.escapeXml(writer, moduleName);
        writer.append("\">");
        if (activeRule.getTemplateRuleKey() != null) {
            appendModuleProperty(writer, "id", activeRule.getRuleKey());
        }
        appendModuleProperty(writer, "severity", activeRule.getSeverity());
        appendRuleParameters(writer, activeRule);
        writer.append(CLOSE_MODULE);
    }

    private static void appendRuleParameters(Writer writer, ActiveRuleWrapper activeRule)
            throws IOException {
        for (Map.Entry<String, String> param : activeRule.getParams().entrySet()) {
            if (StringUtils.isNotBlank(param.getValue())) {
                appendModuleProperty(writer, param.getKey(), param.getValue());
            }
        }
    }

    private static void appendModuleProperty(Writer writer, String propertyKey,
            @Nullable String propertyValue) throws IOException {
        if (StringUtils.isNotBlank(propertyValue)) {
            writer.append("<property name=\"");
            StringEscapeUtils.escapeXml(writer, propertyKey);
            writer.append("\" value=\"");
            StringEscapeUtils.escapeXml(writer, propertyValue);
            writer.append("\"/>");
        }
    }

}
