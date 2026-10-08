package com.example.gamebooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import kotlin.concurrent.thread

class FrameGenService : Service() {

    companion object {
        const val ACTION_START = "fg.start"
        const val ACTION_STOP = "fg.stop"
        @Volatile var running = false
        @Volatile var listener: ((Boolean, Int, Int) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var glView: GLSurfaceView? = null
    private var origBlock: String? = null
    private var touchPatched = false
    private lateinit var wm: WindowManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startFg()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startCapture(code, data)
        } catch (e: Exception) {
            toast("Frame Gen gagal: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("fg", "Frame Gen", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, FrameGenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "fg")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Frame Gen aktif")
            .setContentText("Ketuk Stop untuk menghentikan")
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete), "Stop", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(42, n)
        }
    }

    private fun startCapture(code: Int, data: Intent) {
        FgConfig.load(getSharedPreferences("gb", MODE_PRIVATE))
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val sw = (dm.widthPixels * FgConfig.scale / 100) and 1.inv()
        val sh = (dm.heightPixels * FgConfig.scale / 100) and 1.inv()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)
        projection = mp

        // Supaya sentuhan tembus ke game (Android 12+ memblokir overlay "untrusted")
        thread {
            val m = State.mode
            if (m == Shell.Mode.NONE) {
                toast("Tanpa root/Shizuku: sentuhan ke game mungkin diblokir Android 12+")
            } else {
                origBlock = Shell.run(m, "settings get global block_untrusted_touches").trim()
                Shell.run(m, "settings put global block_untrusted_touches 0")
                touchPatched = true
            }
        }

        val renderer = FrameGenRenderer(
            sw, sh,
            { surface -> handler.post { attachSurface(surface, sw, sh, dm.densityDpi) } },
            { s, o -> listener?.invoke(true, s, o) }
        )
        val v = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val lp = WindowManager.LayoutParams(
            dm.widthPixels, dm.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        glView = v
        running = true
        listener?.invoke(true, 0, 0)
        handler.postDelayed({ FloatingService.instance?.bringToFront() }, 400)
    }

    private fun attachSurface(surface: Surface, w: Int, h: Int, dpi: Int) {
        val mp = projection ?: return
        val existing = vd
        if (existing == null) {
            vd = mp.createVirtualDisplay(
                "fg", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
        } else {
            existing.surface = surface
        }
    }

    private fun toast(s: String) = handler.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        super.onDestroy()
        try { glView?.let { wm.removeView(it) } } catch (_: Exception) {}
        glView = null
        try { vd?.release() } catch (_: Exception) {}
        vd = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        if (touchPatched) {
            val m = State.mode
            val orig = origBlock
            thread {
                if (orig.isNullOrEmpty() || orig == "null") {
                    Shell.run(m, "settings delete global block_untrusted_touches")
                } else {
                    Shell.run(m, "settings put global block_untrusted_touches $orig")
                }
            }
        }
        running = false
        listener?.invoke(false, 0, 0)
    }
}
