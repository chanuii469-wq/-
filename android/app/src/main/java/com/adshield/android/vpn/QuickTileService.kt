package com.adshield.android.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.adshield.android.Stats
import com.adshield.android.ui.MainActivity

/** 빠른 설정 타일로 켜고 끄기 */
class QuickTileService : TileService() {

    override fun onStartListening() = updateTile()

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (Stats.running.value) {
            AdBlockVpnService.stop(this)
        } else if (VpnService.prepare(this) == null) {
            AdBlockVpnService.start(this)
        } else {
            // 처음엔 VPN 권한 승인이 필요하므로 앱을 연다
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_START, true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
    }

    private fun updateTile() {
        val t = qsTile ?: return
        t.state = if (Stats.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    companion object {
        fun refresh(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { requestListeningState(ctx, ComponentName(ctx, QuickTileService::class.java)) }
            }
        }
    }
}
