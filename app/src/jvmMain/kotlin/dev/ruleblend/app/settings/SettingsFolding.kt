package dev.ruleblend.app.settings

import androidx.compose.runtime.Composable
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.theme.SegmentedControl

/**
 * What is open the moment a screen is entered. One place for every such answer: the question is the
 * same whichever list it is asked about, and a person who wants lists closed wants them all closed.
 * These are view defaults — nothing here is written to a managed file.
 */
@Composable
internal fun FoldingSection(model: SettingsModel, narrow: Boolean) {
    val strings = LocalStrings.current
    SettingsSectionBlock(SettingsSection.FOLDING, strings.setSectionFolding, strings.setFoldingSum) {
        SettingsRow(strings.setGroupsExpansion, strings.setGroupsExpansionHint, narrow) {
            SegmentedControl(
                options = listOf(strings.setGroupsExpanded, strings.setGroupsCollapsed),
                selected = if (model.groupsExpandedByDefault) 0 else 1,
                onSelect = { model.changeGroupsExpandedByDefault(it == 0) },
                testTagFor = { if (it == 0) "settings-groups-expanded" else "settings-groups-collapsed" },
            )
        }
        RowDivider()
        SettingsRow(strings.setPlaceLibraryExpansion, strings.setPlaceLibraryExpansionHint, narrow) {
            SegmentedControl(
                options = listOf(strings.setGroupsExpanded, strings.setGroupsCollapsed),
                selected = if (model.placeLibraryExpandedByDefault) 0 else 1,
                onSelect = { model.changePlaceLibraryExpandedByDefault(it == 0) },
                testTagFor = { if (it == 0) "settings-place-library-expanded" else "settings-place-library-collapsed" },
            )
        }
        RowDivider()
        SettingsRow(strings.setPlaceRulesExpansion, strings.setPlaceRulesExpansionHint, narrow) {
            SegmentedControl(
                options = listOf(strings.setGroupsExpanded, strings.setGroupsCollapsed),
                selected = if (model.placeRulesExpandedByDefault) 0 else 1,
                onSelect = { model.changePlaceRulesExpandedByDefault(it == 0) },
                testTagFor = { if (it == 0) "settings-place-rules-expanded" else "settings-place-rules-collapsed" },
            )
        }
        RowDivider()
        SettingsRow(strings.setPlaceSkillsExpansion, strings.setPlaceSkillsExpansionHint, narrow) {
            SegmentedControl(
                options = listOf(strings.setGroupsExpanded, strings.setGroupsCollapsed),
                selected = if (model.placeSkillsExpandedByDefault) 0 else 1,
                onSelect = { model.changePlaceSkillsExpandedByDefault(it == 0) },
                testTagFor = { if (it == 0) "settings-place-skills-expanded" else "settings-place-skills-collapsed" },
            )
        }
        RowDivider()
        SettingsRow(strings.setPlaceSubagentsExpansion, strings.setPlaceSubagentsExpansionHint, narrow) {
            SegmentedControl(
                options = listOf(strings.setGroupsExpanded, strings.setGroupsCollapsed),
                selected = if (model.placeSubagentsExpandedByDefault) 0 else 1,
                onSelect = { model.changePlaceSubagentsExpandedByDefault(it == 0) },
                testTagFor = { if (it == 0) "settings-place-subagents-expanded" else "settings-place-subagents-collapsed" },
            )
        }
        RowDivider()
        SettingsRow(strings.setPlaceMcpExpansion, strings.setPlaceMcpExpansionHint, narrow) {
            SegmentedControl(
                options = listOf(strings.setGroupsExpanded, strings.setGroupsCollapsed),
                selected = if (model.placeMcpExpandedByDefault) 0 else 1,
                onSelect = { model.changePlaceMcpExpandedByDefault(it == 0) },
                testTagFor = { if (it == 0) "settings-place-mcp-expanded" else "settings-place-mcp-collapsed" },
            )
        }
    }
}

// ---------------------------------------------------------------- General
