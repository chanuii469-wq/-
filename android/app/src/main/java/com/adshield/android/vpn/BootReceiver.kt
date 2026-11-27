package com.adshield.android.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.adshield.android.Prefs

/** 재부팅/업데이트 후 자동으로 다시 켜기 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.autoStart && Prefs.wasRunning && VpnService.prepare(ctx) == null) {
            AdBlockVpnService.start(ctx)
        }
    }
}
