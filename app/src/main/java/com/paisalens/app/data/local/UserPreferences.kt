package com.paisalens.app.data.local

import android.content.Context

class UserPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("paisalens.preferences", Context.MODE_PRIVATE)

    var onboardingComplete: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING, false)
        set(value) = preferences.edit().putBoolean(KEY_ONBOARDING, value).apply()

    var darkMode: Boolean
        get() = preferences.getBoolean(KEY_DARK_MODE, true)
        set(value) = preferences.edit().putBoolean(KEY_DARK_MODE, value).apply()

    var lastScanAt: Long
        get() = preferences.getLong(KEY_LAST_SCAN, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_SCAN, value).apply()

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_ONBOARDING = "onboarding_complete"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_LAST_SCAN = "last_scan_at"
    }
}
