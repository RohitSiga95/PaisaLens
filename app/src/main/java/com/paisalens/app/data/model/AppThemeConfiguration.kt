package com.paisalens.app.data.model

/**
 * A complete, persistable description of the app's visual theme.
 *
 * The enum [storageId] values are part of the on-device preference format. Keep them stable even
 * if a display label or enum constant is renamed in a future release.
 */
data class AppThemeConfiguration(
    val style: AppThemeStyle = AppThemeStyle.MATERIAL,
    val mode: AppThemeMode = AppThemeMode.DARK,
    val palette: AppThemePalette = AppThemePalette.PAISALENS,
) {
    /** Resolves the effective appearance. AMOLED is intentionally always pure-black/dark. */
    fun isDark(systemInDarkTheme: Boolean): Boolean = when {
        style == AppThemeStyle.AMOLED -> true
        mode == AppThemeMode.SYSTEM -> systemInDarkTheme
        mode == AppThemeMode.DARK -> true
        else -> false
    }
}

enum class AppThemeStyle(
    val storageId: String,
    val label: String,
    val description: String,
) {
    MATERIAL(
        storageId = "material",
        label = "Material",
        description = "Clean Material surfaces with balanced contrast",
    ),
    AMOLED(
        storageId = "amoled",
        label = "AMOLED black",
        description = "Pure-black surfaces designed for OLED displays",
    ),
    GRADIENT(
        storageId = "gradient",
        label = "Gradient",
        description = "Layered color blends with expressive depth",
    ),
    ;

    companion object {
        fun fromStorageId(value: String?): AppThemeStyle =
            entries.firstOrNull { it.storageId == value } ?: MATERIAL
    }
}

enum class AppThemeMode(
    val storageId: String,
    val label: String,
    val description: String,
) {
    SYSTEM(
        storageId = "system",
        label = "System",
        description = "Follow the phone's light or dark appearance",
    ),
    LIGHT(
        storageId = "light",
        label = "Light",
        description = "Use a bright appearance throughout the app",
    ),
    DARK(
        storageId = "dark",
        label = "Dark",
        description = "Use a low-light appearance throughout the app",
    ),
    ;

    companion object {
        fun fromStorageId(value: String?, fallback: AppThemeMode = DARK): AppThemeMode =
            entries.firstOrNull { it.storageId == value } ?: fallback
    }
}

enum class AppThemePalette(
    val storageId: String,
    val label: String,
    val description: String,
    val primaryArgb: Long,
    val secondaryArgb: Long,
    val tertiaryArgb: Long,
) {
    PAISALENS(
        "paisalens",
        "Indigo & mint",
        "The signature PaisaLens palette",
        0xFF7784FF,
        0xFF21D19F,
        0xFFB7C0FF,
    ),
    OCEAN(
        "ocean",
        "Ocean",
        "Deep blue with clear aqua highlights",
        0xFF2775FF,
        0xFF22D3EE,
        0xFF7DD3FC,
    ),
    EMERALD(
        "emerald",
        "Emerald",
        "Rich greens with fresh mint accents",
        0xFF10B981,
        0xFF5EEAD4,
        0xFFA7F3D0,
    ),
    SUNSET(
        "sunset",
        "Sunset",
        "Warm orange fading into vivid magenta",
        0xFFF97316,
        0xFFEC4899,
        0xFFFBBF24,
    ),
    ROSE(
        "rose",
        "Rose",
        "Polished pink with soft coral warmth",
        0xFFF43F5E,
        0xFFFB7185,
        0xFFFDA4AF,
    ),
    VIOLET(
        "violet",
        "Violet",
        "Bold purple with electric lavender",
        0xFF8B5CF6,
        0xFFC084FC,
        0xFFE879F9,
    ),
    AMBER(
        "amber",
        "Amber",
        "Golden warmth with a confident orange accent",
        0xFFF59E0B,
        0xFFFB923C,
        0xFFFDE68A,
    ),
    TEAL(
        "teal",
        "Teal",
        "Calm blue-green with crisp cyan details",
        0xFF0D9488,
        0xFF2DD4BF,
        0xFF67E8F9,
    ),
    SKY(
        "sky",
        "Sky",
        "Airy blue with bright cloud-like highlights",
        0xFF0EA5E9,
        0xFF38BDF8,
        0xFFBAE6FD,
    ),
    MONOCHROME(
        "monochrome",
        "Monochrome",
        "Neutral graphite with clean silver contrast",
        0xFF64748B,
        0xFFCBD5E1,
        0xFF94A3B8,
    ),
    COBALT(
        "cobalt",
        "Cobalt",
        "Strong royal blue with an icy accent",
        0xFF2563EB,
        0xFF818CF8,
        0xFF93C5FD,
    ),
    CORAL(
        "coral",
        "Coral",
        "Friendly coral balanced by warm peach",
        0xFFFF6B6B,
        0xFFFF9F7F,
        0xFFFFC6A8,
    ),
    FOREST(
        "forest",
        "Forest",
        "Grounded green with a lively lime highlight",
        0xFF15803D,
        0xFF65A30D,
        0xFF86EFAC,
    ),
    AURORA(
        "aurora",
        "Aurora",
        "Northern-light cyan flowing into violet",
        0xFF06B6D4,
        0xFF7C3AED,
        0xFF34D399,
    ),
    ;

    val swatches: List<Long>
        get() = listOf(primaryArgb, secondaryArgb, tertiaryArgb)

    companion object {
        fun fromStorageId(value: String?): AppThemePalette =
            entries.firstOrNull { it.storageId == value } ?: PAISALENS
    }
}
