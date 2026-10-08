package com.example.gamebooster

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class FloatingService : Service() {

    companion object {
        @Volatile var instance: FloatingService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("gb", MODE_PRIVATE) }
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var tvFps: TextView
    private lateinit var tvGame: TextView
    private lateinit var btnFg: Button
    private lateinit var btnMult: Button
    private lateinit var btnMode: Button
    private lateinit var btnScale: Button
    private var attached = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        FgConfig.load(prefs)
        build()
        if (State.mode == Shell.Mode.NONE) thread { State.mode = Shell.detect() }
        FrameGenService.listener = { run, s, o -> handler.post { updateStats(run, s, o) } }
        updateStats(FrameGenService.running, 0, 0)
    }

    private fun bg(color: Int, radius: Float = 16f, oval: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        minHeight = 0
        minimumHeight = dp(36)
        setOnClickListener { onClick() }
    }

    private fun build() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = TextView(this).apply {
            text = "⚡"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg(0xFF0A84FF.toInt(), oval = true)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xEE202020.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(250), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        panel.addView(TextView(this).apply {
            text = "Game Booster"
            textSize = 16f
            setTextColor(Color.WHITE)
        })
        tvGame = TextView(this).apply { textSize = 12f; setTextColor(0xFFBBBBBB.toInt()) }
        tvFps = TextView(this).apply { textSize = 12f; setTextColor(0xFF7CFC00.toInt()) }
        panel.addView(tvGame)
        panel.addView(tvFps)

        panel.addView(btn("⚡ Boost sekarang") { doBoost() })
        btnFg = btn("") { toggleFg() }
        panel.addView(btnFg)
        btnMult = btn("") {
            FgConfig.mult = if (FgConfig.mult >= 4) 2 else FgConfig.mult + 1
            FgConfig.save(prefs); refreshLabels()
        }
        panel.addView(btnMult)
        btnMode = btn("") {
            FgConfig.mode = if (FgConfig.mode == 1) 0 else 1
            FgConfig.save(prefs); refreshLabels()
        }
        panel.addView(btnMode)
        btnScale = btn("") {
            FgConfig.scale = when (FgConfig.scale) { 50 -> 75; 75 -> 100; else -> 50 }
            FgConfig.save(prefs); refreshLabels()
        }
        panel.addView(btnScale)
        panel.addView(btn("Buka app Game Booster") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        panel.addView(btn("Tutup panel") {
            startService(Intent(this, FrameGenService::class.java).setAction(FrameGenService.ACTION_STOP))
            stopSelf()
        })

        root.addView(bubble)
        root.addView(panel)

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(160) }

        var sx = 0; var sy = 0; var dx = 0f; var dy = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; dx = e.rawX; dy = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt(); val my = (e.rawY - dy).toInt()
                    if (Math.abs(mx) > dp(6) || Math.abs(my) > dp(6)) moved = true
                    if (moved) { lp.x = sx + mx; lp.y = sy + my; wm.updateViewLayout(root, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    refreshLabels()
                }
            }
            true
        }

        wm.addView(root, lp)
        attached = true
        refreshLabels()
    }

    fun bringToFront() {
        if (!attached) return
        try {
            wm.removeView(root)
            wm.addView(root, lp)
        } catch (_: Exception) {}
    }

    private fun gameLabel(): String {
        val pkg = prefs.getString("last", null) ?: return "Game: (pilih di app utama)"
        return try {
            "Game: " + packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
        } catch (e: Exception) { "Game: $pkg" }
    }

    private fun refreshLabels() {
        tvGame.text = gameLabel()
        btnFg.text = if (FrameGenService.running) "Frame Gen: ON (ketuk untuk stop)" else "Frame Gen: OFF (ketuk untuk start)"
        btnMult.text = "Pengali FPS: ${FgConfig.mult}x"
        btnMode.text = if (FgConfig.mode == 1) "Mode: Motion (kualitas)" else "Mode: Blend (ringan)"
        btnScale.text = "Resolusi capture: ${FgConfig.scale}% (berlaku saat start)"
    }

    private fun updateStats(running: Boolean, src: Int, out: Int) {
        tvFps.text = if (running) "FPS sumber: $src → tampil: $out" else "Frame Gen: mati"
        refreshLabels()
    }

    private fun toast(s: String) = handler.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun doBoost() {
        val pkg = prefs.getString("last", null)
        if (pkg == null) { toast("Pilih game dulu di app utama"); return }
        if (State.mode == Shell.Mode.NONE) { toast("Belum ada akses root/Shizuku"); return }
        toast("Boosting...")
        thread {
            Boost.execute(State.mode, pkg, packageName, prefs) { }
            toast("Boost selesai")
        }
    }

    private fun toggleFg() {
        if (FrameGenService.running) {
            startService(Intent(this, FrameGenService::class.java).setAction(FrameGenService.ACTION_STOP))
        } else {
            if (Build.VERSION.SDK_INT < 34) {
                toast("Android <14: capture layar penuh bisa menimbulkan efek cermin")
            }
            panel.visibility = View.GONE
            startActivity(Intent(this, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FrameGenService.listener = null
        try { if (attached) wm.removeView(root) } catch (_: Exception) {}
        attached = false
        instance = null
    }
}
