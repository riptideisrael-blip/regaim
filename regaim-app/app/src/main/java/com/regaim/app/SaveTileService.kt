package com.regaim.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A "save moment" button in the phone's quick-settings pull-down:
 * one swipe and one tap, even from the lock screen.
 */
class SaveTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (Status.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (Status.running) L.t("מקליט", "Recording") else L.t("כבוי", "Off")
            updateTile()
        }
    }

    // Intent overload is used only on SDK < 34; SDK 34+ uses PendingIntent.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (Status.running) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_SAVE_NOW))
        } else {
            val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(open)
            }
        }
    }
}
