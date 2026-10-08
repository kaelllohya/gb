package com.example.gamebooster

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private var mode = Shell.Mode.NONE
    private val games = mutableListOf<Pair<String, String>>()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvMode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var spinner: Spinner
    private lateinit var swDnd: Switch
    private lateinit var swKill: Switch
    private lateinit var swCompile: Switch
    private lateinit var swAnim: Switch
    private lateinit var swHz: Switch
    private lateinit var swCpu: Switch

    private val prefs by lazy { getSharedPreferences("gb", MODE_PRIVATE) }

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> detectMode() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadGames()
        try { Shizuku.addRequestPermissionResultListener(permListener) } catch (_: Throwable) {}
        detectMode()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusLoop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusLoop)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { Shizuku.removeRequestPermissionResultListener(permListener) } catch (_: Throwable) {}
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        fun title(t: String) = TextView(this).apply { text = t; textSize = 22f; setTypeface(null, Typeface.BOLD) }
        fun sw(t: String, on: Boolean) = Switch(this).apply { text = t; isChecked = on; setPadding(0, dp(6), 0, dp(6)) }

        root.addView(title("Game Booster"))
        tvMode = TextView(this).apply { text = "Mode: mendeteksi..."; setPadding(0, dp(6), 0, dp(2)) }
        tvStatus = TextView(this).apply { setPadding(0, 0, 0, dp(10)) }
        root.addView(tvMode)
        root.addView(tvStatus)

        root.addView(TextView(this).apply { text = "Pilih game:" })
        spinner = Spinner(this)
        root.addView(spinner)

        val o = Boost.optionsFrom(prefs)
        swAnim = sw("Percepat animasi sistem", o.anim)
        swDnd = sw("Mode Jangan Ganggu", o.dnd)
        swHz = sw("Paksa refresh rate tinggi (120Hz)", o.refresh)
        swCompile = sw("Optimasi ART game (lebih lama, tapi loading cepat)", o.compile)
        swKill = sw("Tutup app background (termasuk keyboard/launcher pihak ketiga)", o.killBg)
        swCpu = sw("[ROOT] CPU performance + GPU clock", o.cpu)
        listOf(swAnim, swDnd, swHz, swCompile, swKill, swCpu).forEach { root.addView(it) }

        root.addView(Button(this).apply { text = "BOOST & MAIN"; setOnClickListener { boost() } })
        root.addView(Button(this).apply { text = "KEMBALIKAN KE NORMAL"; setOnClickListener { restore() } })
        root.addView(Button(this).apply { text = "AKTIFKAN PANEL FLOATING (+ Frame Gen)"; setOnClickListener { startFloating() } })
        root.addView(Button(this).apply {
            text = "Hentikan panel floating"
            setOnClickListener {
                startService(Intent(this@MainActivity, FrameGenService::class.java).setAction(FrameGenService.ACTION_STOP))
                stopService(Intent(this@MainActivity, FloatingService::class.java))
            }
        })
        root.addView(Button(this).apply { text = "Cek ulang root / Shizuku"; setOnClickListener { detectMode() } })

        root.addView(TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
            text = "Frame Gen: buka game dulu, lalu ketuk bubble ⚡ -> Frame Gen. " +
                "Di dialog capture pilih \"Bagikan satu aplikasi\" -> pilih game (Android 14+), " +
                "supaya overlay tidak ikut ter-capture."
        })

        tvLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(tvLog)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun loadGames() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(i, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
            .let { games.addAll(it) }
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, games.map { it.first }
        )
        prefs.getString("last", null)?.let { last ->
            val idx = games.indexOfFirst { it.second == last }
            if (idx >= 0) spinner.setSelection(idx)
        }
    }

    private fun detectMode() {
        thread {
            var m = Shell.Mode.NONE
            val info: String
            if (Shell.hasRoot()) {
                m = Shell.Mode.ROOT
                info = "ROOT aktif (semua fitur)"
            } else {
                var s = "Tidak ada root. Aktifkan Shizuku (Wireless Debugging) lalu tekan cek ulang."
                try {
                    if (Shizuku.pingBinder()) {
                        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                            m = Shell.Mode.SHIZUKU
                            s = "Shizuku aktif (non-root)"
                        } else {
                            Shizuku.requestPermission(100)
                            s = "Izinkan akses Shizuku di dialog yang muncul"
                        }
                    }
                } catch (_: Throwable) {}
                info = s
            }
            mode = m
            State.mode = m
            runOnUiThread {
                tvMode.text = "Mode: $info"
                swCpu.isEnabled = m == Shell.Mode.ROOT
                if (m != Shell.Mode.ROOT) swCpu.isChecked = false
            }
        }
    }

    private val statusLoop = object : Runnable {
        override fun run() {
            val mi = ActivityManager.MemoryInfo()
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            tvStatus.text = "RAM bebas: ${mi.availMem / 1048576} MB / ${mi.totalMem / 1048576} MB   |   Suhu baterai: $temp°C"
            handler.postDelayed(this, 3000)
        }
    }

    private fun log(s: String) = runOnUiThread { tvLog.append(s + "\n") }

    private fun selectedPkg(): Pair<String, String>? =
        if (games.isEmpty()) null else games[spinner.selectedItemPosition]

    private fun saveSelection() {
        selectedPkg()?.let { prefs.edit().putString("last", it.second).apply() }
        Boost.saveOptions(
            prefs,
            Boost.Options(swDnd.isChecked, swKill.isChecked, swCompile.isChecked, swAnim.isChecked, swHz.isChecked, swCpu.isChecked)
        )
    }

    private fun boost() {
        val g = selectedPkg() ?: return
        if (mode == Shell.Mode.NONE) { log("Belum ada akses root/Shizuku."); return }
        saveSelection()
        val m = mode
        thread {
            log("== BOOST ${g.first} ==")
            Boost.execute(m, g.second, packageName, prefs) { log(it) }
            log("Selesai. Membuka game...")
            runOnUiThread {
                packageManager.getLaunchIntentForPackage(g.second)?.let { startActivity(it) }
            }
        }
    }

    private fun restore() {
        val g = selectedPkg() ?: return
        if (mode == Shell.Mode.NONE) { log("Belum ada akses root/Shizuku."); return }
        val m = mode
        thread {
            log("== RESTORE ==")
            Boost.executeRestore(m, g.second, prefs) { log(it) }
            log("Selesai.")
        }
    }

    private fun startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan 'Tampil di atas app lain' lalu ketuk tombol ini lagi", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        saveSelection()
        startService(Intent(this, FloatingService::class.java))
        Toast.makeText(this, "Panel aktif: ketuk bubble ⚡", Toast.LENGTH_SHORT).show()
    }
}
