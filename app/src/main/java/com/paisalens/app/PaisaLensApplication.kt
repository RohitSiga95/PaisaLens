package com.paisalens.app

import android.app.Application
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.local.UserPreferences
import com.paisalens.app.data.parser.TransactionSmsParser
import com.paisalens.app.data.repository.TransactionRepository
import com.paisalens.app.security.SensitiveDataCipher
import com.paisalens.app.sms.AccountAvailabilitySmsParser

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
        repository = TransactionRepository(
            context = this,
            database = PaisaLensDatabase(this),
            cipher = SensitiveDataCipher(),
        )
    }
}
