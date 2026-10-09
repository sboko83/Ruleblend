package dev.ruleblend.app.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.SectionHeaderStyle
import dev.ruleblend.app.theme.ToggleChip
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.McpConfigError
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.model.RESERVED_MCP_SERVER_NAME
import dev.ruleblend.core.model.validationErrors

/** One editable key-value row; kept ordered so typing does not reshuffle the list. */
private class KvRow(key: String = "", value: String = "") {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
}

/**
 * Form state for both transports at once, so switching back and forth loses nothing typed. Only
 * the active transport's fields are serialized into the draft.
 */
private class McpFormFields(config: McpServerConfig?) {
    var http by mutableStateOf(config is McpServerConfig.Http)
    var command by mutableStateOf((config as? McpServerConfig.Stdio)?.command ?: "")
    var url by mutableStateOf((config as? McpServerConfig.Http)?.url ?: "")
    val args = mutableStateListOf<String>().apply {
        addAll((config as? McpServerConfig.Stdio)?.args.orEmpty())
    }
    val env = rows((config as? McpServerConfig.Stdio)?.env)
    val headers = rows((config as? McpServerConfig.Http)?.headers)

    private fun rows(map: Map<String, String>?) =
        mutableStateListOf<KvRow>().apply { map?.forEach { (k, v) -> add(KvRow(k, v)) } }

    /** Rows with a blank key and a blank value are placeholders, not content. */
    private fun kvMap(rows: List<KvRow>): Map<String, String> =
        rows.filterNot { it.key.isBlank() && it.value.isBlank() }.associate { it.key to it.value }

    fun toConfig(): McpServerConfig =
        if (http) McpServerConfig.Http(url = url, headers = kvMap(headers))
        else McpServerConfig.Stdio(command = command, args = args.filter { it.isNotBlank() }, env = kvMap(env))

    /** Form-level problems the canonical config cannot express, e.g. two rows sharing a key. */
    fun duplicateKeys(): Boolean = (if (http) headers else env)
        .map { it.key }.filter { it.isNotBlank() }.let { keys -> keys.size != keys.distinct().size }
}

/** Validity of the MCP draft as it stands; gates the Save button. */
fun mcpDraftValid(draft: Block): Boolean {
    if (draft.id == RESERVED_MCP_SERVER_NAME) return false
    if (draft.content.isBlank()) return false
    val config = McpConfigCodec.parse(draft.content).getOrNull() ?: return false
    return config.validationErrors().isEmpty()
}

/**
 * Structured editor for a [dev.ruleblend.core.model.BlockType.MCP] block. Every change serializes
 * canonically back into the draft content, so dirtiness and versioning behave exactly like rules.
 */
@Composable
fun McpConfigForm(
    draft: Block,
    onEdit: ((Block) -> Block) -> Unit,
    modifier: Modifier = Modifier,
    onFormValidChange: (Boolean) -> Unit = {},
) {
    val strings = LocalStrings.current
    val parsed = remember(draft.id, draft.content) { McpConfigCodec.parse(draft.content) }
    if (draft.content.isNotBlank() && parsed.isFailure) {
        BrokenConfig(draft, onEdit, modifier)
        return
    }
    val fields = remember(draft.id) { McpFormFields(parsed.getOrNull()) }
    val sync = { onEdit { it.copy(content = McpConfigCodec.serialize(fields.toConfig())) } }
    val formValid = !fields.duplicateKeys() && fields.toConfig().validationErrors().isEmpty()
    LaunchedEffect(draft.id, formValid) { onFormValidChange(formValid) }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            FieldLabel(strings.mcpTransport)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip(strings.mcpTransportStdio, on = !fields.http, onClick = { fields.http = false; sync() }, modifier = Modifier.testTag("mcp-stdio"))
                ToggleChip(strings.mcpTransportHttp, on = fields.http, onClick = { fields.http = true; sync() }, modifier = Modifier.testTag("mcp-http"))
            }
        }

        if (fields.http) {
            Column {
                FieldLabel(strings.mcpUrl)
                FormField(fields.url, { fields.url = it; sync() }, Modifier.testTag("mcp-url"))
            }
            KvSection(strings.mcpHeaders, "headers", fields.headers, sync, strings)
        } else {
            Column {
                FieldLabel(strings.mcpCommand)
                FormField(fields.command, { fields.command = it; sync() }, Modifier.testTag("mcp-command"))
            }
            Column {
                FieldLabel(strings.mcpArgs)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fields.args.forEachIndexed { index, arg ->
                        RowWithRemove(onRemove = { fields.args.removeAt(index); sync() }) {
                            FormField(arg, { fields.args[index] = it; sync() }, Modifier.weight(1f).testTag("mcp-arg-$index"))
                        }
                    }
                    AddRowButton(strings, "mcp-add-arg") { fields.args.add("") }
                }
            }
            KvSection(strings.mcpEnv, "env", fields.env, sync, strings)
        }

        val errors = formErrors(draft, fields, strings)
        errors.forEach { error ->
            Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            strings.mcpServerNameHint(draft.id),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formErrors(draft: Block, fields: McpFormFields, strings: Strings): List<String> = buildList {
    if (draft.id == RESERVED_MCP_SERVER_NAME) add(strings.mcpErrReservedName(RESERVED_MCP_SERVER_NAME))
    fields.toConfig().validationErrors().forEach { error ->
        add(
            when (error) {
                McpConfigError.COMMAND_REQUIRED -> strings.mcpErrCommandRequired
                McpConfigError.URL_INVALID -> strings.mcpErrUrlInvalid
                McpConfigError.BLANK_KEY -> strings.mcpErrBlankKey
            },
        )
    }
    if (fields.duplicateKeys()) add(strings.mcpErrDuplicateKey)
}

@Composable
private fun KvSection(label: String, kind: String, rows: MutableList<KvRow>, sync: () -> Unit, strings: Strings) {
    Column {
        FieldLabel(label)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEachIndexed { index, row ->
                RowWithRemove(onRemove = { rows.removeAt(index); sync() }) {
                    FormField(row.key, { row.key = it; sync() }, Modifier.weight(1f).testTag("mcp-$kind-key-$index"))
                    FormField(row.value, { row.value = it; sync() }, Modifier.weight(2f).testTag("mcp-$kind-value-$index"))
                }
            }
            AddRowButton(strings, "mcp-add-$kind") { rows.add(KvRow()) }
        }
    }
}

@Composable
private fun RowWithRemove(onRemove: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
        CompactOutlinedButton(onClick = onRemove) { Text("−") }
    }
}

@Composable
private fun AddRowButton(strings: Strings, tag: String, onClick: () -> Unit) {
    // The new row starts blank and is not synced into the draft until something is typed into it.
    Text(
        strings.mcpAddRow,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag(tag).clickable(onClick = onClick).padding(vertical = 2.dp),
    )
}

@Composable
private fun FormField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    RuleblendOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
        colors = ruleblendFieldColors(),
        contentPadding = RuleblendFieldContentPadding,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Escape hatch for a hand-edited or imported body the codec cannot read. */
@Composable
private fun BrokenConfig(draft: Block, onEdit: ((Block) -> Block) -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(strings.mcpBrokenConfig, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        RuleblendOutlinedTextField(
            value = draft.content,
            onValueChange = {},
            readOnly = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            colors = ruleblendFieldColors(),
            contentPadding = RuleblendFieldContentPadding,
            modifier = Modifier.fillMaxWidth(),
        )
        CompactOutlinedButton(onClick = { onEdit { it.copy(content = "") } }) { Text(strings.mcpResetConfig) }
    }
}
