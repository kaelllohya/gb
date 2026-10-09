package com.example.gamebooster

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
    private lateinit var tBoost: TextView
    private lateinit var tFg: TextView
    private lateinit var tHud: TextView
    private lateinit var tAuto: TextView
    private lateinit var tMult: TextView
    private lateinit var tMode: TextView
    private lateinit var tScale: TextView
    private lateinit var hudView: TextView
    private lateinit var hudLp: WindowManager.LayoutParams
    @Volatile private var hudShown = false
    @Volatile private var alive = true
    @Volatile private var fgStat = ""
    private var attached = false

    // state monitor (thread terpisah)
    private var layerName: String? = null
    private var layerPkg: String? = null
    private var searchCooldown = 0
    private var zeroTicks = 0
    private var nonGameTicks = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        FgConfig.load(prefs)
        if (State.activePkg == null) {
            State.activePkg = prefs.getString("last", null)
        }
        State.activePkg?.let { FgConfig.apply(Profiles.get(prefs, it), prefs) }
        build()
        FrameGenService.listener = { run, s, o ->
            fgStat = if (run && (s > 0 || o > 0)) "$s→$o" else ""
            handler.post { updateStats(run, s, o) }
        }
        updateStats(FrameGenService.running, 0, 0)
        startMonitor()
    }

    private fun bg(color: Int, radius: Int = 16, oval: Boolean = false) =
        if (oval) Ui.oval(color) else Ui.rounded(this, color, radius)

    private fun setActive(v: TextView, on: Boolean) {
        v.background = Ui.rounded(this, if (on) Ui.ACCENT else Ui.CARD2, 14)
    }

    private fun tile(onClick: () -> Unit) = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.WHITE)
        background = Ui.rounded(this@FloatingService, Ui.CARD2, 14)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun chip(onClick: () -> Unit) = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.WHITE)
        background = Ui.rounded(this@FloatingService, Ui.CARD2, 12)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun row(h: Int, vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, x ->
            addView(x, LinearLayout.LayoutParams(0, dp(h), 1f).apply {
                if (i > 0) leftMargin = dp(8)
                topMargin = dp(8)
            })
        }
    }

    private fun curPkg(): String? = State.activePkg ?: prefs.getString("last", null)

    private fun editProfile(f: (Profile) -> Profile) {
        val pk = curPkg() ?: return
        Profiles.save(prefs, pk, f(Profiles.get(prefs, pk)))
    }

    private fun build() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = TextView(this).apply {
            text = "⚡"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.gradient(this@FloatingService, 22)
            alpha = 0.92f
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@FloatingService, 0xF2151821.toInt(), 20, Ui.CARD2, 1)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(270), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        panel.addView(TextView(this).apply {
            text = "⚡ GAME BOOSTER"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.ACCENT)
        })
        tvGame = TextView(this).apply { textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE) }
        tvFps = TextView(this).apply { textSize = 11f; setTextColor(Ui.MUTED) }
        panel.addView(tvGame)
        panel.addView(tvFps)

        tBoost = tile { doBoostToggle() }
        tFg = tile { toggleFg() }
        tHud = tile { setHud(!hudShown) }
        tAuto = tile { editProfile { it.copy(auto = !it.auto) }; refreshLabels() }
        panel.addView(row(64, tBoost, tFg))
        panel.addView(row(64, tHud, tAuto))

        panel.addView(TextView(this).apply {
            text = "FRAME GEN"
            textSize = 10f
            setTextColor(Ui.MUTED)
            setPadding(0, dp(12), 0, 0)
        })
        tMult = chip {
            FgConfig.mult = if (FgConfig.mult >= 4) 2 else FgConfig.mult + 1
            FgConfig.persist(prefs); refreshLabels()
        }
        tMode = chip {
            FgConfig.mode = if (FgConfig.mode == 1) 0 else 1
            FgConfig.persist(prefs); refreshLabels()
        }
        tScale = chip {
            FgConfig.scale = when (FgConfig.scale) { 50 -> 75; 75 -> 100; else -> 50 }
            FgConfig.persist(prefs); refreshLabels()
        }
        panel.addView(row(38, tMult, tMode, tScale))
        panel.addView(TextView(this).apply {
            text = "Pengali • Mode • Resolusi (resolusi berlaku saat start)"
            textSize = 9f
            setTextColor(Ui.MUTED)
            setPadding(0, dp(4), 0, 0)
        })

        val tOpen = chip {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.apply { text = "Buka app" }
        val tClose = chip {
            startService(Intent(this, FrameGenService::class.java).setAction(FrameGenService.ACTION_STOP))
            stopSelf()
        }.apply { text = "Tutup panel" }
        panel.addView(row(38, tOpen, tClose))

        root.addView(bubble)
        root.addView(panel)

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = dp(160) }

        val screenW = resources.displayMetrics.widthPixels
        var sx = 0; var sy = 0; var dx = 0f; var dy = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; dx = e.rawX; dy = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt(); val my = (e.rawY - dy).toInt()
                    if (Math.abs(mx) > dp(6) || Math.abs(my) > dp(6)) moved = true
                    if (moved) { lp.x = sx + mx; lp.y = sy + my; wm.updateViewLayout(root, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (panel.visibility == View.VISIBLE) {
                            panel.visibility = View.GONE
                            lp.x = if (lp.x + dp(22) < screenW / 2) 0 else screenW - dp(44)
                        } else {
                            panel.visibility = View.VISIBLE
                            lp.x = lp.x.coerceIn(0, Math.max(0, screenW - dp(270)))
                        }
                        wm.updateViewLayout(root, lp)
                        refreshLabels()
                    } else if (panel.visibility != View.VISIBLE) {
                        lp.x = if (lp.x + dp(22) < screenW / 2) 0 else screenW - dp(44)
                        wm.updateViewLayout(root, lp)
                    }
                }
            }
            true
        }

        // HUD (tidak bisa disentuh, jadi tidak mengganggu game)
        hudView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = bg(0xAA000000.toInt(), 8)
            text = "HUD..."
        }
        hudLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(6) }

        wm.addView(root, lp)
        attached = true
        State.activePkg?.let { if (Profiles.get(prefs, it).hud) setHud(true, false) }
        refreshLabels()
    }

    fun bringToFront() {
        if (!attached) return
        try {
            if (hudShown) { wm.removeView(hudView); wm.addView(hudView, hudLp) }
            wm.removeView(root)
            wm.addView(root, lp)
        } catch (_: Exception) {}
    }

    fun setHud(on: Boolean, persist: Boolean = true) {
        handler.post {
            if (on && !hudShown) {
                try { wm.addView(hudView, hudLp); hudShown = true } catch (_: Exception) {}
                if (hudShown) bringToFront()
            } else if (!on && hudShown) {
                try { wm.removeView(hudView) } catch (_: Exception) {}
                hudShown = false
            }
            if (persist) editProfile { it.copy(hud = on) }
            refreshLabels()
        }
    }

    /** Dipanggil Booster setelah boost aktif untuk sebuah game. */
    fun onActivated(prof: Profile) {
        setHud(prof.hud, false)
        handler.post { refreshLabels() }
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun refreshLabels() {
        val pk = curPkg()
        tvGame.text = if (pk == null) "(pilih game di app utama)" else label(pk)
        val boosted = State.boostedPkg != null
        tBoost.text = if (boosted) "↩\nNormal" else "⚡\nBoost"
        setActive(tBoost, boosted)
        tFg.text = "🎬\nFrame Gen"
        setActive(tFg, FrameGenService.running)
        tHud.text = "📊\nHUD"
        setActive(tHud, hudShown)
        val auto = pk != null && Profiles.get(prefs, pk).auto
        tAuto.text = "🤖\nAuto-boost"
        setActive(tAuto, auto)
        tMult.text = "${FgConfig.mult}x"
        tMode.text = if (FgConfig.mode == 1) "Motion" else "Blend"
        tScale.text = "${FgConfig.scale}%"
    }

    private fun updateStats(running: Boolean, src: Int, out: Int) {
        tvFps.text = if (running) "Frame Gen aktif: $src → $out FPS" else "Frame Gen mati"
        refreshLabels()
    }

    private fun toast(s: String) = handler.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun doBoostToggle() {
        val pkg = curPkg()
        if (pkg == null) { toast("Pilih game dulu di app utama"); return }
        thread {
            if (State.boostedPkg != null) {
                Booster.deactivate(this) { }
                toast("Dikembalikan normal")
            } else {
                Booster.activate(this, pkg, false) { }
                toast(if (State.boostedPkg != null) "Boost aktif" else "Belum ada akses root/Shizuku")
            }
            handler.post { refreshLabels() }
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

    // ================= MONITOR: auto-boost + HUD =================

    private fun startMonitor() {
        alive = true
        thread {
            var tick = 0
            while (alive) {
                try {
                    if (State.mode == Shell.Mode.NONE && tick % 10 == 0) State.mode = Shell.detect()
                    val mode = State.mode
                    if (mode != Shell.Mode.NONE) {
                        if (tick % 2 == 0 && Profiles.autoList(prefs).isNotEmpty()) checkForeground(mode)
                        if (hudShown) updateHud(mode)
                    } else if (hudShown) {
                        hudText("HUD: butuh root/Shizuku untuk FPS")
                    }
                } catch (_: Exception) {}
                tick++
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun checkForeground(mode: Shell.Mode) {
        val out = Shell.run(mode, "dumpsys window | grep -m1 mCurrentFocus")
        val pkg = Regex("u\\d+ ([\\w.]+)/").find(out)?.groupValues?.get(1) ?: return
        if (pkg == packageName) return
        if (pkg in Profiles.autoList(prefs)) {
            nonGameTicks = 0
            if (State.boostedPkg != pkg) {
                toast("Auto-boost: ${label(pkg)}")
                Booster.activate(this, pkg, true) { }
                handler.post { refreshLabels() }
            }
        } else if (State.boostedPkg != null && State.boostedAuto) {
            nonGameTicks++
            if (nonGameTicks >= 3) {   // ~6 detik di luar game
                nonGameTicks = 0
                Booster.deactivate(this) { }
                toast("Keluar game: pengaturan dikembalikan")
                handler.post { refreshLabels() }
            }
        }
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun findLayer(mode: Shell.Mode, pkg: String): String? {
        val out = Shell.run(mode, "dumpsys SurfaceFlinger --list")
        val cands = out.lines().map { it.trim() }
            .filter { it.contains(pkg) && !it.contains("Background", true) }
            .sortedBy { if (it.startsWith("SurfaceView")) 0 else 1 }
            .take(4)
        var best: String? = null
        var bestFps = -1
        for (c in cands) {
            val f = parseFps(Shell.run(mode, "dumpsys SurfaceFlinger --latency ${q(c)}"))
            if (f > bestFps) { bestFps = f; best = c }
        }
        return best
    }

    private fun parseFps(text: String): Int {
        val ts = ArrayList<Long>()
        for (l in text.lines().drop(1)) {
            val c = l.trim().split(Regex("\\s+"))
            if (c.size < 2) continue
            val t = c[1].toLongOrNull() ?: continue
            if (t <= 0L || t == Long.MAX_VALUE) continue
            ts.add(t)
        }
        if (ts.isEmpty()) return 0
        val latest = ts.max()
        if (System.nanoTime() - latest > 2_000_000_000L) return 0
        return ts.count { it > latest - 1_000_000_000L }
    }

    private fun parseCpuTemp(s: String): String {
        val re = Regex("(?i)cpu|soc|tsens|mtk|cluster")
        for (l in s.lines()) {
            val i = l.indexOf('=')
            if (i < 0) continue
            val v = l.substring(i + 1).trim().toLongOrNull() ?: continue
            if (re.containsMatchIn(l.substring(0, i))) {
                val t = if (v > 1000) v / 1000.0 else v.toDouble()
                if (t in 10.0..120.0) return String.format("%.0f", t)
            }
        }
        return "-"
    }

    private fun hudText(s: String) = handler.post { hudView.text = s }

    private fun updateHud(mode: Shell.Mode) {
        val pkg = curPkg()
        if (pkg != null && (layerName == null || layerPkg != pkg)) {
            if (layerPkg != pkg || searchCooldown <= 0) {
                layerName = findLayer(mode, pkg)
                layerPkg = pkg
                searchCooldown = 5
            } else searchCooldown--
        }
        val cmd = (if (layerName != null) "dumpsys SurfaceFlinger --latency ${q(layerName!!)}; " else "") +
            "echo ===; for z in /sys/class/thermal/thermal_zone*; do " +
            "echo \"\$(cat \$z/type 2>/dev/null)=\$(cat \$z/temp 2>/dev/null)\"; done"
        val out = Shell.run(mode, cmd)
        val parts = out.split("===")
        val fps = if (layerName != null) parseFps(parts[0]) else -1
        if (fps == 0) { zeroTicks++; if (zeroTicks >= 5) { layerName = null; zeroTicks = 0 } } else zeroTicks = 0
        val cpu = parseCpuTemp(parts.getOrElse(1) { "" })

        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bt = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val ram = String.format("%.1f", mi.availMem / 1073741824.0)

        val sb = StringBuilder()
        sb.append("FPS ").append(if (fps >= 0) fps.toString() else "-")
        if (fgStat.isNotEmpty()) sb.append(" | FG ").append(fgStat)
        sb.append(" | CPU ").append(cpu).append("°")
        sb.append(" | BAT ").append(String.format("%.0f", bt)).append("°")
        sb.append(" | RAM ").append(ram).append("G")
        hudText(sb.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        alive = false
        FrameGenService.listener = null
        try { if (hudShown) wm.removeView(hudView) } catch (_: Exception) {}
        hudShown = false
        try { if (attached) wm.removeView(root) } catch (_: Exception) {}
        attached = false
        instance = null
    }
}
