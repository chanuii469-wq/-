package com.adshield.android

import android.app.Application
import com.adshield.android.filter.FilterManager
import com.adshield.android.filter.FilterUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AdShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Stats.load()
        AppRules.load()
        FilterManager.init(this)
        appScope.launch { FilterManager.rebuild() }
        if (FilterManager.needsInitialDownload()) FilterUpdateWorker.runOnce(this)
        FilterUpdateWorker.schedule(this)
    }

    companion object {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
