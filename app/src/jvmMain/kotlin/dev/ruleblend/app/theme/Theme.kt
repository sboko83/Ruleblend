package dev.ruleblend.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ruleblend.app.generated.resources.Res
import dev.ruleblend.app.generated.resources.book_plus
import dev.ruleblend.app.generated.resources.coverage
import dev.ruleblend.app.generated.resources.download
import dev.ruleblend.app.generated.resources.edit
import dev.ruleblend.app.generated.resources.eye
import dev.ruleblend.app.generated.resources.eye_off
import dev.ruleblend.app.generated.resources.file_text
import dev.ruleblend.app.generated.resources.home
import dev.ruleblend.app.generated.resources.library
import dev.ruleblend.app.generated.resources.link
import dev.ruleblend.app.generated.resources.place
import dev.ruleblend.app.generated.resources.refresh
import dev.ruleblend.app.generated.resources.rotate_ccw
import dev.ruleblend.app.generated.resources.search
import dev.ruleblend.app.generated.resources.settings
import dev.ruleblend.app.generated.resources.sidebar_expand
import dev.ruleblend.app.generated.resources.sidebar_toggle
import dev.ruleblend.app.generated.resources.translate
import dev.ruleblend.app.generated.resources.compare
import dev.ruleblend.app.generated.resources.trash
import dev.ruleblend.app.generated.resources.unlink
import dev.ruleblend.app.generated.resources.warning
import dev.ruleblend.core.config.ThemeMode
import org.jetbrains.compose.resources.DrawableResource

/** Semantic colors from the design2 contract that Material's color scheme does not name. */
data class RuleblendExtraColors(
    val rail: Color,
    /** Label and icon of the current sidebar destination, on [ColorScheme.primaryContainer]. */
    val railSelectedContent: Color,
    val faint: Color,
    val accentBackground: Color,
    val success: Color,
    val successBackground: Color,
    /** Neutral accent for something that is neither good nor bad, such as the MCP object type. */
    val info: Color,
    val infoBackground: Color,
    /** Coral identity of subagents, independent of status and MCP colors. */
    val subagent: Color,
    val subagentBackground: Color,
    val warning: Color,
    val warningBackground: Color,
    val danger: Color,
    val dangerBackground: Color,
    /**
     * The mats of a status surface: the band a card sits on, one per state of what it holds. Kept
     * apart from the badge backgrounds above because a mat is read as a field of colour behind a
     * card rather than as a chip on a surface — it has to stand off both the card and the window,
     * which the paler badge fills do not.
     */
    val matManaged: Color,
    val matUpdate: Color,
    val matConflict: Color,
    val matNeutral: Color,
    /**
     * The one filled button of a view — its main move. Brighter and bluer than [primary], which
     * tints selection and links: the main move has to outshine the accents around it, not match them.
     */
    val actionPrimary: Color,
    val onActionPrimary: Color,
) {
    // Compatibility aliases for controls that predate design2. They can migrate by component.
    val windowBar: Color get() = rail
    val badgeOkBg: Color get() = successBackground
    val badgeOkFg: Color get() = success
    // An update waiting is a state of the content, not a piece of information: it wears the warning
    // colour, one step below a conflict, so the three sync states read as one scale rather than
    // three unrelated hues.
    val badgeUpdBg: Color get() = warningBackground
    val badgeUpdFg: Color get() = warning
    val badgeModBg: Color get() = dangerBackground
    val badgeModFg: Color get() = danger
    /** Nothing of Ruleblend's is in there: the absence of a state, not a bad one. */
    val unmanaged: Color get() = faint
}

/** Layout constants shared by every design2 surface. */
data class RuleblendDimensions(
    val sidebarExpandedWidth: Dp,
    /** Wide enough for a square destination: [sidebarItemHeight] plus [sidebarPadding] on each side. */
    val sidebarCollapsedWidth: Dp,
    /** Inset of the sidebar content from its edges, horizontally and at the bottom. */
    val sidebarPadding: Dp,
    val sidebarHeaderHeight: Dp,
    val sidebarHeaderTopPadding: Dp,
    /** Space between the header and the first destination. */
    val sidebarSectionGap: Dp,
    val sidebarItemHeight: Dp,
    val sidebarItemGap: Dp,
    /** Inset of the icon from the start of an expanded destination. */
    val sidebarItemPadding: Dp,
    val sidebarLabelGap: Dp,
    val sidebarItemCornerRadius: Dp,
    val sidebarIconSize: Dp,
    val sidebarBrandSize: Dp,
    val sidebarBrandCornerRadius: Dp,
    val sidebarToggleSize: Dp,
    val sidebarIndicatorWidth: Dp,
    val sidebarIndicatorHeight: Dp,
    /** Extra inset of the footer divider beyond [sidebarPadding]. */
    val sidebarDividerInset: Dp,
    /** Space between the footer divider and Settings. */
    val sidebarFooterGap: Dp,
    val topBarHeight: Dp,
    val contentPadding: Dp,
    val sectionGap: Dp,
    val controlHeight: Dp,
    val iconSize: Dp,
    val cornerRadius: Dp,
    /**
     * Corners of an object's status surface and of the notice above a run of them. Tighter than
     * [cornerRadius]: these are wide, low bands, and a panel's rounding on them reads as a pill.
     */
    val surfaceCornerRadius: Dp,
    /** Corners of the card a status surface carries — one step inside the surface's own. */
    val cardCornerRadius: Dp,
)

/** Icons are semantic theme tokens so another theme can replace the complete visual language. */
data class RuleblendIconSet(
    val home: DrawableResource,
    val place: DrawableResource,
    val library: DrawableResource,
    val coverage: DrawableResource,
    val settings: DrawableResource,
    val search: DrawableResource,
    val refresh: DrawableResource,
    val warning: DrawableResource,
    val file: DrawableResource,
    val translate: DrawableResource,
    val compare: DrawableResource,
    val edit: DrawableResource,
    val delete: DrawableResource,
    val release: DrawableResource,
    /** Takes a foreign copy under the library object of its name. */
    val takeOwnership: DrawableResource,
    /** Copies a foreign copy into the library as a new object. */
    val saveToLibrary: DrawableResource,
    val restore: DrawableResource,
    val hide: DrawableResource,
    val unhide: DrawableResource,
    val install: DrawableResource,
    val sidebarCollapse: DrawableResource,
    val sidebarExpand: DrawableResource,
)

private val LightExtraColors = RuleblendExtraColors(
    rail = Color(0xFFECECF0),
    railSelectedContent = Color(0xFF4545C2),
    faint = Color(0xFFA0A2AC),
    accentBackground = Color(0xFFEEEEFC),
    success = Color(0xFF20B86A),
    successBackground = Color(0xFFE6F6ED),
    info = Color(0xFF2D7FF9),
    infoBackground = Color(0xFFE8F1FE),
    subagent = Color(0xFFB64537),
    subagentBackground = Color(0xFFFCEAE5),
    warning = Color(0xFFD6A243),
    warningBackground = Color(0xFFFBF3E0),
    danger = Color(0xFFD64545),
    dangerBackground = Color(0xFFFDEAEA),
    matManaged = Color(0xFFD8EDE2),
    matUpdate = Color(0xFFF4E7C6),
    matConflict = Color(0xFFF7D8D8),
    matNeutral = Color(0xFFE2E2E8),
    actionPrimary = Color(0xFF4C64F7),
    onActionPrimary = Color.White,
)

private val DarkExtraColors = RuleblendExtraColors(
    rail = Color(0xFF131417),
    railSelectedContent = Color(0xFFD5D2FF),
    faint = Color(0xFF92939E),
    accentBackground = Color(0xFF26264A),
    success = Color(0xFF36D982),
    successBackground = Color(0xFF153324),
    info = Color(0xFF5AA2FF),
    infoBackground = Color(0xFF10233D),
    subagent = Color(0xFFFF9685),
    subagentBackground = Color(0xFF3B2422),
    warning = Color(0xFFE0B45C),
    warningBackground = Color(0xFF33290F),
    danger = Color(0xFFE06C6C),
    dangerBackground = Color(0xFF3A1414),
    matManaged = Color(0xFF1D4230),
    matUpdate = Color(0xFF45360F),
    matConflict = Color(0xFF4A1C1C),
    matNeutral = Color(0xFF2A2C34),
    actionPrimary = Color(0xFF4C64F7),
    onActionPrimary = Color.White,
)

private val LightColors = lightColorScheme(
    background = Color(0xFFF6F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECECF0),
    onSurfaceVariant = Color(0xFF6E7079),
    outline = Color(0xFFE2E2E8),
    outlineVariant = Color(0xFFE2E2E8),
    onSurface = Color(0xFF1C1D22),
    onBackground = Color(0xFF1C1D22),
    primary = Color(0xFF5B5BD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEEEFC),
    onPrimaryContainer = Color(0xFF5B5BD6),
    secondaryContainer = Color(0xFFEEEEFC),
    onSecondaryContainer = Color(0xFF5B5BD6),
    error = Color(0xFFD64545),
    errorContainer = Color(0xFFFDEAEA),
    onError = Color.White,
    onErrorContainer = Color(0xFFD64545),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF17181B),
    surface = Color(0xFF1E2027),
    surfaceVariant = Color(0xFF131417),
    onSurfaceVariant = Color(0xFFA5A6B1),
    outline = Color(0xFF2A2C34),
    outlineVariant = Color(0xFF2A2C34),
    onSurface = Color(0xFFE5E5EA),
    onBackground = Color(0xFFE5E5EA),
    primary = Color(0xFF7C7CE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF26264A),
    onPrimaryContainer = Color(0xFF7C7CE0),
    secondaryContainer = Color(0xFF26264A),
    onSecondaryContainer = Color(0xFF7C7CE0),
    error = Color(0xFFE06C6C),
    errorContainer = Color(0xFF3A1414),
    onError = Color.White,
    onErrorContainer = Color(0xFFE06C6C),
)

private val BaseFontFamily = FontFamily.SansSerif
val MonoFontFamily: FontFamily = FontFamily.Monospace

private val RuleblendTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(
            fontFamily = BaseFontFamily,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = BaseFontFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        ),
        titleSmall = base.titleSmall.copy(
            fontFamily = BaseFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = base.bodyLarge.copy(fontFamily = BaseFontFamily, fontSize = 14.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = BaseFontFamily, fontSize = 13.sp),
        bodySmall = base.bodySmall.copy(fontFamily = BaseFontFamily, fontSize = 12.sp),
        labelLarge = base.labelLarge.copy(fontFamily = BaseFontFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = BaseFontFamily, fontSize = 12.sp),
        labelSmall = base.labelSmall.copy(
            fontFamily = BaseFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        ),
    )
}

private val RuleblendShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
)

private val DefaultDimensions = RuleblendDimensions(
    sidebarExpandedWidth = 204.dp,
    sidebarCollapsedWidth = 68.dp,
    sidebarPadding = 10.dp,
    sidebarHeaderHeight = 40.dp,
    sidebarHeaderTopPadding = 8.dp,
    sidebarSectionGap = 22.dp,
    sidebarItemHeight = 48.dp,
    sidebarItemGap = 7.dp,
    sidebarItemPadding = 10.dp,
    sidebarLabelGap = 12.dp,
    sidebarItemCornerRadius = 12.dp,
    sidebarIconSize = 18.dp,
    sidebarBrandSize = 28.dp,
    sidebarBrandCornerRadius = 8.dp,
    sidebarToggleSize = 30.dp,
    sidebarIndicatorWidth = 3.dp,
    sidebarIndicatorHeight = 28.dp,
    sidebarDividerInset = 8.dp,
    sidebarFooterGap = 12.dp,
    topBarHeight = 44.dp,
    contentPadding = 14.dp,
    sectionGap = 12.dp,
    controlHeight = 28.dp,
    iconSize = 18.dp,
    cornerRadius = 8.dp,
    surfaceCornerRadius = 6.dp,
    cardCornerRadius = 4.dp,
)

private val DefaultIcons = RuleblendIconSet(
    home = Res.drawable.home,
    place = Res.drawable.place,
    library = Res.drawable.library,
    coverage = Res.drawable.coverage,
    settings = Res.drawable.settings,
    search = Res.drawable.search,
    refresh = Res.drawable.refresh,
    warning = Res.drawable.warning,
    file = Res.drawable.file_text,
    translate = Res.drawable.translate,
    compare = Res.drawable.compare,
    edit = Res.drawable.edit,
    delete = Res.drawable.trash,
    release = Res.drawable.unlink,
    takeOwnership = Res.drawable.link,
    saveToLibrary = Res.drawable.book_plus,
    restore = Res.drawable.rotate_ccw,
    hide = Res.drawable.eye_off,
    unhide = Res.drawable.eye,
    install = Res.drawable.download,
    sidebarCollapse = Res.drawable.sidebar_toggle,
    sidebarExpand = Res.drawable.sidebar_expand,
)

/** One selectable theme owns every visual token, even when light and dark share a value today. */
private data class ThemeTokens(
    val colors: ColorScheme,
    val extraColors: RuleblendExtraColors,
    val typography: Typography,
    val shapes: Shapes,
    val dimensions: RuleblendDimensions,
    val icons: RuleblendIconSet,
)

private val LightThemeTokens = ThemeTokens(
    colors = LightColors,
    extraColors = LightExtraColors,
    typography = RuleblendTypography,
    shapes = RuleblendShapes,
    dimensions = DefaultDimensions,
    icons = DefaultIcons,
)

private val DarkThemeTokens = ThemeTokens(
    colors = DarkColors,
    extraColors = DarkExtraColors,
    typography = RuleblendTypography,
    shapes = RuleblendShapes,
    dimensions = DefaultDimensions,
    icons = DefaultIcons,
)

/** 10sp section header style; display text should be uppercased by the caller. */
val SectionHeaderStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

private val LocalRuleblendExtraColors = staticCompositionLocalOf { LightExtraColors }
private val LocalRuleblendDimensions = staticCompositionLocalOf { DefaultDimensions }
private val LocalRuleblendIcons = staticCompositionLocalOf { DefaultIcons }

object RuleblendTheme {
    val extraColors: RuleblendExtraColors
        @Composable get() = LocalRuleblendExtraColors.current

    val dimensions: RuleblendDimensions
        @Composable get() = LocalRuleblendDimensions.current

    val icons: RuleblendIconSet
        @Composable get() = LocalRuleblendIcons.current
}

@Composable
fun RuleblendTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val tokens = if (dark) DarkThemeTokens else LightThemeTokens
    CompositionLocalProvider(
        LocalRuleblendExtraColors provides tokens.extraColors,
        LocalRuleblendDimensions provides tokens.dimensions,
        LocalRuleblendIcons provides tokens.icons,
    ) {
        MaterialTheme(
            colorScheme = tokens.colors,
            typography = tokens.typography,
            shapes = tokens.shapes,
            content = content,
        )
    }
}
