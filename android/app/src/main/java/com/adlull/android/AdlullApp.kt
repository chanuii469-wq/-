package com.adlull.android

import android.app.Application
import com.adlull.android.filter.FilterManager
import com.adlull.android.filter.FilterUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AdlullApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Stats.load()
        AppRules.load()
        Stats.startPublisher(appScope)
        FilterManager.init(this)
        appScope.launch { FilterManager.rebuild() }
        if (FilterManager.needsInitialDownload()) FilterUpdateWorker.runOnce(this)
        FilterUpdateWorker.schedule(this)
    }

    companion object {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
