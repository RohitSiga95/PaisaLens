package com.paisalens.app

import android.app.Application
import android.content.res.Configuration
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.local.UserPreferences
import com.paisalens.app.data.parser.TransactionSmsParser
import com.paisalens.app.data.repository.TransactionRepository
import com.paisalens.app.security.SensitiveDataCipher
import com.paisalens.app.sms.AccountAvailabilitySmsParser
import com.paisalens.app.data.model.AppThemeMode
import com.paisalens.app.widget.PaisaLensWidgetProvider
import com.paisalens.app.notification.PrivateDigestNotifier
import com.paisalens.app.notification.PrivateDigestScheduler

class PaisaLensApplication : Application() {
    lateinit var repository: TransactionRepository
        private set
    lateinit var preferences: UserPreferences
        private set
    lateinit var parser: TransactionSmsParser
        private set
    lateinit var availabilityParser: AccountAvailabilitySmsParser
        private set

    override fun onCreate() {
        super.onCreate()
        parser = TransactionSmsParser()
        availabilityParser = AccountAvailabilitySmsParser()
        preferences = UserPreferences(this)
        PrivateDigestNotifier.ensureChannel(this)
        // Restore after a force-stop/relaunch without replacing an inexact delivery
        // already queued by Android for today.
        PrivateDigestScheduler.ensure(this, preferences.notificationDigest.value)
        repository = TransactionRepository(
            context = this,
            database = PaisaLensDatabase(this),
            cipher = SensitiveDataCipher(),
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::preferences.isInitialized && preferences.themeConfiguration.value.mode == AppThemeMode.SYSTEM) {
            PaisaLensWidgetProvider.scheduleUpdateAll(this, delayMillis = 150L)
        }
    }
}
