package dev.ruleblend.app.settings

import dev.ruleblend.core.integration.AgentAdapter

internal enum class AgentCatalogFilter { ALL, DETECTED, ATTENTION }

internal data class AgentCatalogPage(
    val agents: List<AgentAdapter>,
    val total: Int,
    val page: Int,
    val pages: Int,
    val first: Int,
    val last: Int,
)

/** Keep catalog order within each detection group; searching and paging never hide an assistant globally. */
internal fun agentCatalogPage(
    agents: List<AgentAdapter>,
    detected: Set<String>,
    attention: Set<String>,
    query: String,
    filter: AgentCatalogFilter,
    requestedPage: Int,
    pageSize: Int = 8,
): AgentCatalogPage {
    require(pageSize > 0)
    val filtered = agents.filter { agent ->
        agent.name.contains(query.trim(), ignoreCase = true) && when (filter) {
            AgentCatalogFilter.ALL -> true
            AgentCatalogFilter.DETECTED -> agent.id in detected
            AgentCatalogFilter.ATTENTION -> agent.id in detected && agent.id in attention
        }
    }.sortedByDescending { it.id in detected }
    val pages = maxOf(1, (filtered.size + pageSize - 1) / pageSize)
    val page = requestedPage.coerceIn(1, pages)
    val offset = (page - 1) * pageSize
    val visible = filtered.drop(offset).take(pageSize)
    return AgentCatalogPage(visible, filtered.size, page, pages, if (visible.isEmpty()) 0 else offset + 1, offset + visible.size)
}
