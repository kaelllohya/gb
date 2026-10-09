package com.example.gamebooster

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlin.concurrent.thread

private fun TileService.openApp() {
    val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(i)
    }
}

/** Tile 1: boost / restore game yang sedang dipilih. */
class BoostTile : TileService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() = update()

    private fun update() {
        val t = qsTile ?: return
        t.state = if (State.boostedPkg != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Game Boost"
        t.updateTile()
    }

    override fun onClick() {
        val p = getSharedPreferences("gb", MODE_PRIVATE)
        val pkg = State.activePkg ?: p.getString("last", null)
        if (pkg == null) {
            Toast.makeText(this, "Pilih game dulu di app Game Booster", Toast.LENGTH_LONG).show()
            openApp()
            return
        }
        thread {
            if (State.boostedPkg != null) Booster.deactivate(this) { } else Booster.activate(this, pkg, false) { }
            handler.post {
                update()
                Toast.makeText(this, if (State.boostedPkg != null) "Boost aktif" else "Dikembalikan normal", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Tile 2: nyalakan / matikan panel floating (bubble + HUD + auto-boost). */
class PanelTile : TileService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() = update()

    private fun update() {
        val t = qsTile ?: return
        t.state = if (FloatingService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Panel Game"
        t.updateTile()
    }

    override fun onClick() {
        if (FloatingService.instance != null) {
            stopService(Intent(this, FloatingService::class.java))
        } else if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingService::class.java))
        } else {
            Toast.makeText(this, "Izinkan overlay dulu di app Game Booster", Toast.LENGTH_LONG).show()
            openApp()
            return
        }
        handler.postDelayed({ update() }, 500)
    }
}
