package com.ethiotelecom.ussd

import android.app.Application
import com.ethiotelecom.ussd.network.ConfigRepository
import com.ethiotelecom.ussd.utils.PreferenceManager

class UssdApplication : Application() {

    lateinit var configRepository: ConfigRepository
        private set

    lateinit var preferenceManager: PreferenceManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceManager  = PreferenceManager(this)
        configRepository   = ConfigRepository(this, preferenceManager)
    }

    companion object {
        lateinit var instance: UssdApplication
            private set
    }
}
