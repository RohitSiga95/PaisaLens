package com.paisalens.app

import android.app.Application
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.local.UserPreferences
import com.paisalens.app.data.parser.TransactionSmsParser
import com.paisalens.app.data.repository.TransactionRepository
import com.paisalens.app.security.SensitiveDataCipher

class PaisaLensApplication : Application() {
    lateinit var repository: TransactionRepository
        private set
    lateinit var preferences: UserPreferences
        private set
    lateinit var parser: TransactionSmsParser
        private set

    override fun onCreate() {
        super.onCreate()
        parser = TransactionSmsParser()
        preferences = UserPreferences(this)
        repository = TransactionRepository(
            database = PaisaLensDatabase(this),
            cipher = SensitiveDataCipher(),
        )
    }
}
