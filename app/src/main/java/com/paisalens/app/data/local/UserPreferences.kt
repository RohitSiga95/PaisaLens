package com.paisalens.app.data.local

import android.content.Context
import com.paisalens.app.data.model.AppThemeConfiguration
import com.paisalens.app.data.model.AppThemeMode
import com.paisalens.app.data.model.AppThemePalette
import com.paisalens.app.data.model.AppThemeStyle
import com.paisalens.app.data.model.HomeLayoutConfiguration
import com.paisalens.app.data.model.HomeModule
import com.paisalens.app.data.model.NotificationDigestConfiguration
import com.paisalens.app.data.model.NotificationDigestFrequency
import com.paisalens.app.notification.PrivateDigestScheduler
import com.paisalens.app.notification.PrivateDigestNotifier
import java.time.DayOfWeek
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserPreferences(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = context.getSharedPreferences("paisalens.preferences", Context.MODE_PRIVATE)

    private val _themeConfiguration = MutableStateFlow(readThemeConfiguration())
    val themeConfiguration: StateFlow<AppThemeConfiguration> = _themeConfiguration.asStateFlow()

    private val _homeLayout = MutableStateFlow(readHomeLayout())
    val homeLayout: StateFlow<HomeLayoutConfiguration> = _homeLayout.asStateFlow()

    private val _notificationDigest = MutableStateFlow(readNotificationDigest())
    val notificationDigest: StateFlow<NotificationDigestConfiguration> = _notificationDigest.asStateFlow()

    var onboardingComplete: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING, false)
        set(value) = preferences.edit().putBoolean(KEY_ONBOARDING, value).apply()

    var darkMode: Boolean
        get() = when {
            themeConfiguration.value.style == AppThemeStyle.AMOLED -> true
            themeConfiguration.value.mode == AppThemeMode.DARK -> true
            themeConfiguration.value.mode == AppThemeMode.LIGHT -> false
            else -> preferences.getBoolean(KEY_DARK_MODE, true)
        }
        set(value) {
            preferences.edit().putBoolean(KEY_DARK_MODE, value).apply()
            setThemeMode(if (value) AppThemeMode.DARK else AppThemeMode.LIGHT)
        }

    var lastScanAt: Long
        get() = preferences.getLong(KEY_LAST_SCAN, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_SCAN, value).apply()

    var lastBackupCreatedAt: Long
        get() = preferences.getLong(KEY_LAST_BACKUP_CREATED, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_BACKUP_CREATED, value).apply()

    var lastBackupVerifiedAt: Long
        get() = preferences.getLong(KEY_LAST_BACKUP_VERIFIED, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_BACKUP_VERIFIED, value).apply()

    var appLockEnabled: Boolean
        get() = preferences.getBoolean(KEY_APP_LOCK, false)
        set(value) = preferences.edit().putBoolean(KEY_APP_LOCK, value).apply()

    var widgetAmountsVisible: Boolean
        get() = preferences.getBoolean(KEY_WIDGET_AMOUNTS, false)
        set(value) = preferences.edit().putBoolean(KEY_WIDGET_AMOUNTS, value).apply()

    var travelModeEnabled: Boolean
        get() = preferences.getBoolean(KEY_TRAVEL_MODE, false)
        set(value) = preferences.edit().putBoolean(KEY_TRAVEL_MODE, value).apply()

    var baseCurrency: String
        get() = preferences.getString(KEY_BASE_CURRENCY, "INR") ?: "INR"
        set(value) = preferences.edit().putString(KEY_BASE_CURRENCY, value).apply()

    fun setThemeConfiguration(configuration: AppThemeConfiguration) {
        preferences.edit()
            .putString(KEY_THEME_STYLE, configuration.style.storageId)
            .putString(KEY_THEME_MODE, configuration.mode.storageId)
            .putString(KEY_THEME_PALETTE, configuration.palette.storageId)
            .also { editor ->
                when (configuration.mode) {
                    AppThemeMode.DARK -> editor.putBoolean(KEY_DARK_MODE, true)
                    AppThemeMode.LIGHT -> editor.putBoolean(KEY_DARK_MODE, false)
                    AppThemeMode.SYSTEM -> Unit
                }
            }
            .apply()
        _themeConfiguration.value = configuration
    }

    fun setThemeStyle(style: AppThemeStyle) {
        setThemeConfiguration(themeConfiguration.value.copy(style = style))
    }

    fun setThemeMode(mode: AppThemeMode) {
        setThemeConfiguration(themeConfiguration.value.copy(mode = mode))
    }

    fun setThemePalette(palette: AppThemePalette) {
        setThemeConfiguration(themeConfiguration.value.copy(palette = palette))
    }

    fun setHomeLayout(configuration: HomeLayoutConfiguration) {
        val normalized = configuration.normalized()
        preferences.edit()
            .putString(KEY_HOME_LAYOUT, normalized.toStorageString())
            .apply()
        _homeLayout.value = normalized
    }

    fun setHomeModuleVisible(module: HomeModule, visible: Boolean) {
        setHomeLayout(homeLayout.value.withVisibility(module, visible))
    }

    fun moveHomeModule(module: HomeModule, toIndex: Int) {
        setHomeLayout(homeLayout.value.move(module, toIndex))
    }

    fun setNotificationDigest(configuration: NotificationDigestConfiguration) {
        val normalized = configuration.normalized()
        preferences.edit()
            .putBoolean(KEY_DIGEST_ENABLED, normalized.enabled)
            .putString(KEY_DIGEST_FREQUENCY, normalized.frequency.storageId)
            .putInt(KEY_DIGEST_HOUR, normalized.hour)
            .putString(KEY_DIGEST_WEEKDAY, normalized.weekday.name)
            .putBoolean(KEY_DIGEST_SHOW_AMOUNTS, normalized.showAmounts)
            .apply()
        _notificationDigest.value = normalized
        PrivateDigestScheduler.sync(applicationContext, normalized)
        if (!normalized.enabled) PrivateDigestNotifier.cancel(applicationContext)
    }

    fun setNotificationDigestEnabled(enabled: Boolean) {
        setNotificationDigest(notificationDigest.value.copy(enabled = enabled))
    }

    fun setNotificationDigestFrequency(frequency: NotificationDigestFrequency) {
        setNotificationDigest(notificationDigest.value.copy(frequency = frequency))
    }

    fun setNotificationDigestHour(hour: Int) {
        setNotificationDigest(notificationDigest.value.copy(hour = hour))
    }

    fun setNotificationDigestWeekday(weekday: DayOfWeek) {
        setNotificationDigest(notificationDigest.value.copy(weekday = weekday))
    }

    fun setNotificationDigestShowAmounts(showAmounts: Boolean) {
        setNotificationDigest(notificationDigest.value.copy(showAmounts = showAmounts))
    }

    fun clear() {
        preferences.edit().clear().apply()
        _themeConfiguration.value = AppThemeConfiguration()
        _homeLayout.value = HomeLayoutConfiguration()
        _notificationDigest.value = NotificationDigestConfiguration()
        PrivateDigestScheduler.cancel(applicationContext)
        PrivateDigestNotifier.cancel(applicationContext)
    }

    private fun readThemeConfiguration(): AppThemeConfiguration {
        val legacyMode = if (preferences.getBoolean(KEY_DARK_MODE, true)) {
            AppThemeMode.DARK
        } else {
            AppThemeMode.LIGHT
        }
        return AppThemeConfiguration(
            style = AppThemeStyle.fromStorageId(preferences.getString(KEY_THEME_STYLE, null)),
            mode = if (preferences.contains(KEY_THEME_MODE)) {
                AppThemeMode.fromStorageId(preferences.getString(KEY_THEME_MODE, null))
            } else {
                legacyMode
            },
            palette = AppThemePalette.fromStorageId(preferences.getString(KEY_THEME_PALETTE, null)),
        )
    }

    private fun readHomeLayout(): HomeLayoutConfiguration = HomeLayoutConfiguration.fromStorageString(
        safeString(KEY_HOME_LAYOUT),
    )

    private fun readNotificationDigest(): NotificationDigestConfiguration = NotificationDigestConfiguration(
        enabled = safeBoolean(KEY_DIGEST_ENABLED, false),
        frequency = NotificationDigestFrequency.fromStorageId(
            safeString(KEY_DIGEST_FREQUENCY),
        ),
        hour = safeInt(KEY_DIGEST_HOUR, NotificationDigestConfiguration.DEFAULT_HOUR),
        weekday = NotificationDigestConfiguration.safeWeekday(
            safeString(KEY_DIGEST_WEEKDAY),
        ),
        showAmounts = safeBoolean(KEY_DIGEST_SHOW_AMOUNTS, false),
    ).normalized()

    private fun safeString(key: String): String? = runCatching {
        preferences.getString(key, null)
    }.getOrNull()

    private fun safeBoolean(key: String, default: Boolean): Boolean = runCatching {
        preferences.getBoolean(key, default)
    }.getOrDefault(default)

    private fun safeInt(key: String, default: Int): Int = runCatching {
        preferences.getInt(key, default)
    }.getOrDefault(default)

    private companion object {
        const val KEY_ONBOARDING = "onboarding_complete"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_LAST_SCAN = "last_scan_at"
        const val KEY_LAST_BACKUP_CREATED = "last_backup_created_at"
        const val KEY_LAST_BACKUP_VERIFIED = "last_backup_verified_at"
        const val KEY_APP_LOCK = "app_lock_enabled"
        const val KEY_WIDGET_AMOUNTS = "widget_amounts_visible"
        const val KEY_TRAVEL_MODE = "travel_mode_enabled"
        const val KEY_BASE_CURRENCY = "base_currency"
        const val KEY_THEME_STYLE = "theme_style_v1"
        const val KEY_THEME_MODE = "theme_mode_v1"
        const val KEY_THEME_PALETTE = "theme_palette_v1"
        const val KEY_HOME_LAYOUT = "home_layout_v1"
        const val KEY_DIGEST_ENABLED = "notification_digest_enabled_v1"
        const val KEY_DIGEST_FREQUENCY = "notification_digest_frequency_v1"
        const val KEY_DIGEST_HOUR = "notification_digest_hour_v1"
        const val KEY_DIGEST_WEEKDAY = "notification_digest_weekday_v1"
        const val KEY_DIGEST_SHOW_AMOUNTS = "notification_digest_show_amounts_v1"
    }
}
