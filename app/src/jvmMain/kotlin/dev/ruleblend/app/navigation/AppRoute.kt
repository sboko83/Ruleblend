package dev.ruleblend.app.navigation

/** Top-level destinations plus nested flows owned by one of those destinations. */
enum class AppRoute {
    HOME,
    PLACE,
    LIBRARY,
    COVERAGE,
    SETTINGS,
    RESOLVE,
    ;

    companion object {
        val primary = listOf(HOME, PLACE, LIBRARY, COVERAGE)
    }
}
