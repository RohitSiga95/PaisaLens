package com.paisalens.app.data.local

import android.content.Context
import android.net.Uri
import com.paisalens.app.data.backup.ScheduledBackupConfiguration
import com.paisalens.app.data.backup.ScheduledBackupFrequency
import com.paisalens.app.data.backup.ScheduledBackupRunResult
import com.paisalens.app.data.backup.ScheduledBackupScheduler
import com.paisalens.app.data.backup.ScheduledBackupSecretStore
import com.paisalens.app.data.backup.ScheduledBackupStatus
import com.paisalens.app.data.backup.hasScheduledBackupDestinationPermission
import com.paisalens.app.data.backup.releaseScheduledBackupDestinationPermission
import com.paisalens.app.data.model.AppThemeConfiguration
import com.paisalens.app.data.model.AppThemeMode
import com.paisalens.app.data.model.AppThemePalette
import com.paisalens.app.data.model.AppThemeStyle
import com.paisalens.app.data.model.ActionableAlertCategory
import com.paisalens.app.data.model.ActionableAlertsConfiguration
import com.paisalens.app.data.model.HomeLayoutConfiguration
import com.paisalens.app.data.model.HomeModule
import com.paisalens.app.data.model.NotificationDigestConfiguration
import com.paisalens.app.data.model.NotificationDigestFrequency
import com.paisalens.app.data.model.PrivacyModeConfiguration
import com.paisalens.app.notification.ActionableAlertNotifier
import com.paisalens.app.notification.ActionableAlertScheduler
import com.paisalens.app.notification.ActionableAlertDeliveryStore
import com.paisalens.app.notification.PrivateDigestScheduler
import com.paisalens.app.notification.PrivateDigestNotifier
import com.paisalens.app.ui.privacy.PrivacyModeRuntime
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

    private val _actionableAlerts = MutableStateFlow(readActionableAlerts())
    val actionableAlerts: StateFlow<ActionableAlertsConfiguration> = _actionableAlerts.asStateFlow()

    private val _privacyModeConfiguration = MutableStateFlow(readPrivacyModeConfiguration())
    val privacyModeConfiguration: StateFlow<PrivacyModeConfiguration> =
        _privacyModeConfiguration.asStateFlow()

    private val _privacyModeSessionOverride = MutableStateFlow<Boolean?>(null)
    val privacyModeSessionOverride: StateFlow<Boolean?> = _privacyModeSessionOverride.asStateFlow()

    private val _privacyModeActive = MutableStateFlow(_privacyModeConfiguration.value.defaultEnabled)
    val privacyModeActive: StateFlow<Boolean> = _privacyModeActive.asStateFlow()

    private val _scheduledBackup = MutableStateFlow(readScheduledBackup())
    val scheduledBackup: StateFlow<ScheduledBackupConfiguration> = _scheduledBackup.asStateFlow()

    private val _scheduledBackupStatus = MutableStateFlow(readScheduledBackupStatus())
    val scheduledBackupStatus: StateFlow<ScheduledBackupStatus> = _scheduledBackupStatus.asStateFlow()

    init {
        PrivacyModeRuntime.initialize(_privacyModeActive.value)
        ActionableAlertScheduler.ensure(applicationContext, _actionableAlerts.value)
        // Re-establish an alarm lost to force-stop as soon as the app is opened again.
        ScheduledBackupScheduler.ensure(applicationContext, _scheduledBackup.value)
    }

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

    fun setActionableAlerts(configuration: ActionableAlertsConfiguration) {
        val normalized = configuration.normalized()
        preferences.edit()
            .putBoolean(KEY_ACTIONABLE_ALERTS_ENABLED, normalized.enabled)
            .putStringSet(
                KEY_ACTIONABLE_ALERTS_CATEGORIES,
                normalized.enabledCategories.mapTo(linkedSetOf(), ActionableAlertCategory::storageId),
            )
            .putInt(KEY_ACTIONABLE_ALERTS_HOUR, normalized.evaluationHour)
            .putInt(KEY_ACTIONABLE_ALERTS_DUE_WINDOW, normalized.dueWindowDays)
            .putInt(KEY_ACTIONABLE_ALERTS_BUDGET_THRESHOLD, normalized.budgetThresholdBasisPoints)
            .putInt(KEY_ACTIONABLE_ALERTS_UTILIZATION_THRESHOLD, normalized.utilizationThresholdBasisPoints)
            .putLong(KEY_ACTIONABLE_ALERTS_LOW_BALANCE, normalized.lowBalanceThresholdMinor)
            .putBoolean(KEY_ACTIONABLE_ALERTS_SHOW_AMOUNTS, normalized.showAmounts)
            .putBoolean(KEY_ACTIONABLE_ALERTS_GENERIC_PUBLIC_TEXT, normalized.genericLockScreenText)
            .putInt(KEY_ACTIONABLE_ALERTS_REPEAT_HOURS, normalized.minimumRepeatHours)
            .apply()
        _actionableAlerts.value = normalized
        ActionableAlertScheduler.sync(applicationContext, normalized)
        if (!normalized.enabled) ActionableAlertNotifier.cancel(applicationContext)
    }

    fun setActionableAlertsEnabled(enabled: Boolean) {
        val current = actionableAlerts.value
        setActionableAlerts(
            current.copy(
                enabled = enabled,
                enabledCategories = if (enabled && current.enabledCategories.isEmpty()) {
                    ActionableAlertCategory.entries.toSet()
                } else {
                    current.enabledCategories
                },
            ),
        )
    }

    fun setPrivacyModeConfiguration(configuration: PrivacyModeConfiguration) {
        preferences.edit()
            .putBoolean(KEY_PRIVACY_MODE_DEFAULT_ENABLED, configuration.defaultEnabled)
            .putBoolean(KEY_PRIVACY_MODE_PROTECT_CAPTURE, configuration.protectScreenCapture)
            .apply()
        _privacyModeConfiguration.value = configuration
        if (_privacyModeSessionOverride.value == null) updateEffectivePrivacy(configuration.defaultEnabled)
    }

    fun setPrivacyDefaultEnabled(enabled: Boolean) {
        setPrivacyModeConfiguration(privacyModeConfiguration.value.copy(defaultEnabled = enabled))
    }

    /** Temporary eye-button state; it intentionally does not change the next-launch default. */
    fun toggleSessionPrivacy(): Boolean = setSessionPrivacy(!privacyModeActive.value)

    fun setSessionPrivacy(enabled: Boolean): Boolean {
        _privacyModeSessionOverride.value = enabled
        updateEffectivePrivacy(enabled)
        return enabled
    }

    fun clearSessionPrivacyOverride(): Boolean {
        _privacyModeSessionOverride.value = null
        val enabled = privacyModeConfiguration.value.defaultEnabled
        updateEffectivePrivacy(enabled)
        return enabled
    }

    /**
     * Saves a logical schedule. A supplied passphrase is consumed (zeroed) after it is wrapped
     * by Android Keystore; plaintext is never written to preferences.
     */
    fun setScheduledBackup(
        configuration: ScheduledBackupConfiguration,
        passphrase: CharArray? = null,
    ) {
        try {
            val normalized = configuration.normalized()
            val previousDestination = scheduledBackup.value.destinationUri
            if (normalized.enabled) {
                require(!normalized.destinationUri.isNullOrBlank()) {
                    "Choose a backup folder before enabling scheduled backups"
                }
                require(
                    hasScheduledBackupDestinationPermission(
                        applicationContext,
                        Uri.parse(normalized.destinationUri),
                    ),
                ) { "Choose the backup folder again so Android can grant persistent access" }
            }
            val secretStore = ScheduledBackupSecretStore(applicationContext)
            if (passphrase != null) {
                secretStore.store(passphrase)
            }
            if (normalized.enabled) {
                require(secretStore.hasSecret()) {
                    "Save a backup password before enabling scheduled backups"
                }
            }
            preferences.edit()
                .putBoolean(KEY_SCHEDULED_BACKUP_ENABLED, normalized.enabled)
                .putString(KEY_SCHEDULED_BACKUP_FREQUENCY, normalized.frequency.storageId)
                .putInt(KEY_SCHEDULED_BACKUP_HOUR, normalized.hour)
                .putString(KEY_SCHEDULED_BACKUP_WEEKDAY, normalized.weekday.name)
                .putInt(KEY_SCHEDULED_BACKUP_MONTH_DAY, normalized.monthDay)
                .putInt(KEY_SCHEDULED_BACKUP_RETENTION, normalized.retentionCount)
                .putString(KEY_SCHEDULED_BACKUP_DESTINATION, normalized.destinationUri)
                .apply()
            _scheduledBackup.value = normalized
            ScheduledBackupScheduler.sync(applicationContext, normalized)
            if (previousDestination != null && previousDestination != normalized.destinationUri) {
                releaseScheduledBackupDestinationPermission(
                    applicationContext,
                    Uri.parse(previousDestination),
                )
            }
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    fun setScheduledBackupDestination(uri: String?) {
        val clean = uri?.trim()?.takeIf(String::isNotBlank)
        setScheduledBackup(
            scheduledBackup.value.copy(
                destinationUri = clean,
                enabled = scheduledBackup.value.enabled && clean != null,
            ),
        )
    }

    fun clearScheduledBackupSecret() {
        ScheduledBackupSecretStore(applicationContext).clear()
        if (scheduledBackup.value.enabled) {
            setScheduledBackup(scheduledBackup.value.copy(enabled = false))
        } else {
            ScheduledBackupScheduler.cancel(applicationContext)
        }
    }

    fun hasScheduledBackupSecret(): Boolean =
        ScheduledBackupSecretStore(applicationContext).hasSecret()

    internal fun recordScheduledBackupSuccess(result: ScheduledBackupRunResult) {
        require(result.succeeded) { "Cannot record an unsuccessful backup as successful" }
        val at = result.completedAt
        preferences.edit()
            .putLong(KEY_LAST_BACKUP_CREATED, at)
            .putLong(KEY_LAST_BACKUP_VERIFIED, at)
            .putLong(KEY_SCHEDULED_BACKUP_LAST_ATTEMPT, at)
            .putLong(KEY_SCHEDULED_BACKUP_LAST_SUCCESS, at)
            .putLong(KEY_SCHEDULED_BACKUP_LAST_VERIFIED, at)
            .remove(KEY_SCHEDULED_BACKUP_LAST_FAILURE)
            .remove(KEY_SCHEDULED_BACKUP_FAILURE_MESSAGE)
            .putString(KEY_SCHEDULED_BACKUP_LAST_FILE, result.fileName)
            .putString(KEY_SCHEDULED_BACKUP_WARNING_MESSAGE, result.rotationWarning)
            .apply()
        _scheduledBackupStatus.value = ScheduledBackupStatus(
            lastAttemptAt = at,
            lastSuccessfulAt = at,
            lastVerifiedAt = at,
            lastFileName = result.fileName,
            lastWarningMessage = result.rotationWarning,
        )
    }

    internal fun recordScheduledBackupFailure(result: ScheduledBackupRunResult) {
        require(!result.succeeded) { "Cannot record a successful backup as failed" }
        val current = _scheduledBackupStatus.value
        preferences.edit()
            .putLong(KEY_SCHEDULED_BACKUP_LAST_ATTEMPT, result.completedAt)
            .putLong(KEY_SCHEDULED_BACKUP_LAST_FAILURE, result.completedAt)
            .putString(KEY_SCHEDULED_BACKUP_FAILURE_MESSAGE, result.failureMessage?.take(160))
            .apply()
        _scheduledBackupStatus.value = current.copy(
            lastAttemptAt = result.completedAt,
            lastFailureAt = result.completedAt,
            lastFailureMessage = result.failureMessage?.take(160),
        )
    }

    fun clear() {
        scheduledBackup.value.destinationUri?.let { destination ->
            releaseScheduledBackupDestinationPermission(applicationContext, Uri.parse(destination))
        }
        preferences.edit().clear().apply()
        _themeConfiguration.value = AppThemeConfiguration()
        _homeLayout.value = HomeLayoutConfiguration()
        _notificationDigest.value = NotificationDigestConfiguration()
        _actionableAlerts.value = ActionableAlertsConfiguration()
        _privacyModeConfiguration.value = PrivacyModeConfiguration()
        _privacyModeSessionOverride.value = null
        updateEffectivePrivacy(false)
        _scheduledBackup.value = ScheduledBackupConfiguration()
        _scheduledBackupStatus.value = ScheduledBackupStatus()
        PrivateDigestScheduler.cancel(applicationContext)
        PrivateDigestNotifier.cancel(applicationContext)
        ActionableAlertScheduler.cancel(applicationContext)
        ActionableAlertNotifier.cancel(applicationContext)
        ActionableAlertDeliveryStore(applicationContext).clear()
        ScheduledBackupScheduler.cancel(applicationContext)
        ScheduledBackupSecretStore(applicationContext).clear()
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

    private fun readActionableAlerts(): ActionableAlertsConfiguration {
        val storedCategories = runCatching {
            preferences.getStringSet(KEY_ACTIONABLE_ALERTS_CATEGORIES, null)
        }.getOrNull()
        val categories = storedCategories
            ?.mapNotNull(ActionableAlertCategory::fromStorageId)
            ?.toSet()
            ?: ActionableAlertCategory.entries.toSet()
        return ActionableAlertsConfiguration(
            enabled = safeBoolean(KEY_ACTIONABLE_ALERTS_ENABLED, false),
            enabledCategories = categories,
            evaluationHour = safeInt(
                KEY_ACTIONABLE_ALERTS_HOUR,
                ActionableAlertsConfiguration.DEFAULT_EVALUATION_HOUR,
            ),
            dueWindowDays = safeInt(
                KEY_ACTIONABLE_ALERTS_DUE_WINDOW,
                ActionableAlertsConfiguration.DEFAULT_DUE_WINDOW_DAYS,
            ),
            budgetThresholdBasisPoints = safeInt(
                KEY_ACTIONABLE_ALERTS_BUDGET_THRESHOLD,
                ActionableAlertsConfiguration.DEFAULT_BUDGET_THRESHOLD_BASIS_POINTS,
            ),
            utilizationThresholdBasisPoints = safeInt(
                KEY_ACTIONABLE_ALERTS_UTILIZATION_THRESHOLD,
                ActionableAlertsConfiguration.DEFAULT_UTILIZATION_THRESHOLD_BASIS_POINTS,
            ),
            lowBalanceThresholdMinor = safeLong(KEY_ACTIONABLE_ALERTS_LOW_BALANCE),
            showAmounts = safeBoolean(KEY_ACTIONABLE_ALERTS_SHOW_AMOUNTS, false),
            genericLockScreenText = safeBoolean(KEY_ACTIONABLE_ALERTS_GENERIC_PUBLIC_TEXT, true),
            minimumRepeatHours = safeInt(
                KEY_ACTIONABLE_ALERTS_REPEAT_HOURS,
                ActionableAlertsConfiguration.DEFAULT_MINIMUM_REPEAT_HOURS,
            ),
        ).normalized()
    }

    private fun readPrivacyModeConfiguration(): PrivacyModeConfiguration = PrivacyModeConfiguration(
        defaultEnabled = safeBoolean(KEY_PRIVACY_MODE_DEFAULT_ENABLED, false),
        protectScreenCapture = safeBoolean(KEY_PRIVACY_MODE_PROTECT_CAPTURE, true),
    )

    private fun updateEffectivePrivacy(enabled: Boolean) {
        _privacyModeActive.value = enabled
        PrivacyModeRuntime.update(enabled)
    }

    private fun readScheduledBackup(): ScheduledBackupConfiguration = ScheduledBackupConfiguration(
        enabled = safeBoolean(KEY_SCHEDULED_BACKUP_ENABLED, false),
        frequency = ScheduledBackupFrequency.fromStorageId(
            safeString(KEY_SCHEDULED_BACKUP_FREQUENCY),
        ),
        hour = safeInt(KEY_SCHEDULED_BACKUP_HOUR, ScheduledBackupConfiguration.DEFAULT_HOUR),
        weekday = ScheduledBackupConfiguration.safeWeekday(
            safeString(KEY_SCHEDULED_BACKUP_WEEKDAY),
        ),
        monthDay = safeInt(KEY_SCHEDULED_BACKUP_MONTH_DAY, 1),
        retentionCount = safeInt(
            KEY_SCHEDULED_BACKUP_RETENTION,
            ScheduledBackupConfiguration.DEFAULT_RETENTION_COUNT,
        ),
        destinationUri = safeString(KEY_SCHEDULED_BACKUP_DESTINATION),
    ).normalized()

    private fun readScheduledBackupStatus(): ScheduledBackupStatus = ScheduledBackupStatus(
        lastAttemptAt = safeLong(KEY_SCHEDULED_BACKUP_LAST_ATTEMPT),
        lastSuccessfulAt = safeLong(KEY_SCHEDULED_BACKUP_LAST_SUCCESS),
        lastVerifiedAt = safeLong(KEY_SCHEDULED_BACKUP_LAST_VERIFIED),
        lastFailureAt = safeLong(KEY_SCHEDULED_BACKUP_LAST_FAILURE),
        lastFailureMessage = safeString(KEY_SCHEDULED_BACKUP_FAILURE_MESSAGE),
        lastFileName = safeString(KEY_SCHEDULED_BACKUP_LAST_FILE),
        lastWarningMessage = safeString(KEY_SCHEDULED_BACKUP_WARNING_MESSAGE),
    )

    private fun safeString(key: String): String? = runCatching {
        preferences.getString(key, null)
    }.getOrNull()

    private fun safeBoolean(key: String, default: Boolean): Boolean = runCatching {
        preferences.getBoolean(key, default)
    }.getOrDefault(default)

    private fun safeInt(key: String, default: Int): Int = runCatching {
        preferences.getInt(key, default)
    }.getOrDefault(default)

    private fun safeLong(key: String, default: Long = 0L): Long = runCatching {
        preferences.getLong(key, default)
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
        const val KEY_ACTIONABLE_ALERTS_ENABLED = "actionable_alerts_enabled_v1"
        const val KEY_ACTIONABLE_ALERTS_CATEGORIES = "actionable_alerts_categories_v1"
        const val KEY_ACTIONABLE_ALERTS_HOUR = "actionable_alerts_hour_v1"
        const val KEY_ACTIONABLE_ALERTS_DUE_WINDOW = "actionable_alerts_due_window_v1"
        const val KEY_ACTIONABLE_ALERTS_BUDGET_THRESHOLD = "actionable_alerts_budget_threshold_v1"
        const val KEY_ACTIONABLE_ALERTS_UTILIZATION_THRESHOLD = "actionable_alerts_utilization_threshold_v1"
        const val KEY_ACTIONABLE_ALERTS_LOW_BALANCE = "actionable_alerts_low_balance_v1"
        const val KEY_ACTIONABLE_ALERTS_SHOW_AMOUNTS = "actionable_alerts_show_amounts_v1"
        const val KEY_ACTIONABLE_ALERTS_GENERIC_PUBLIC_TEXT = "actionable_alerts_generic_public_text_v1"
        const val KEY_ACTIONABLE_ALERTS_REPEAT_HOURS = "actionable_alerts_repeat_hours_v1"
        const val KEY_PRIVACY_MODE_DEFAULT_ENABLED = "privacy_mode_default_enabled_v1"
        const val KEY_PRIVACY_MODE_PROTECT_CAPTURE = "privacy_mode_protect_capture_v1"
        const val KEY_SCHEDULED_BACKUP_ENABLED = "scheduled_backup_enabled_v1"
        const val KEY_SCHEDULED_BACKUP_FREQUENCY = "scheduled_backup_frequency_v1"
        const val KEY_SCHEDULED_BACKUP_HOUR = "scheduled_backup_hour_v1"
        const val KEY_SCHEDULED_BACKUP_WEEKDAY = "scheduled_backup_weekday_v1"
        const val KEY_SCHEDULED_BACKUP_MONTH_DAY = "scheduled_backup_month_day_v1"
        const val KEY_SCHEDULED_BACKUP_RETENTION = "scheduled_backup_retention_v1"
        const val KEY_SCHEDULED_BACKUP_DESTINATION = "scheduled_backup_destination_v1"
        const val KEY_SCHEDULED_BACKUP_LAST_ATTEMPT = "scheduled_backup_last_attempt_v1"
        const val KEY_SCHEDULED_BACKUP_LAST_SUCCESS = "scheduled_backup_last_success_v1"
        const val KEY_SCHEDULED_BACKUP_LAST_VERIFIED = "scheduled_backup_last_verified_v1"
        const val KEY_SCHEDULED_BACKUP_LAST_FAILURE = "scheduled_backup_last_failure_v1"
        const val KEY_SCHEDULED_BACKUP_FAILURE_MESSAGE = "scheduled_backup_failure_message_v1"
        const val KEY_SCHEDULED_BACKUP_LAST_FILE = "scheduled_backup_last_file_v1"
        const val KEY_SCHEDULED_BACKUP_WARNING_MESSAGE = "scheduled_backup_warning_message_v1"
    }
}
