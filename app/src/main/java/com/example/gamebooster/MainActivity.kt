package com.example.gamebooster

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private var mode = Shell.Mode.NONE
    private val handler = Handler(Looper.getMainLooper())
    private var loading = false
    private var selected: String? = null

    private val appLabels = LinkedHashMap<String, String>()
    private val appIcons = HashMap<String, Drawable>()
    private val myGames = LinkedHashSet<String>()

    private lateinit var gameRow: LinearLayout
    private lateinit var heroIcon: ImageView
    private lateinit var heroName: TextView
    private lateinit var heroSub: TextView
    private lateinit var statRam: TextView
    private lateinit var statTemp: TextView
    private lateinit var statMode: TextView
    private lateinit var tvModeInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var swDnd: Switch
    private lateinit var swKill: Switch
    private lateinit var swCompile: Switch
    private lateinit var swAnim: Switch
    private lateinit var swHz: Switch
    private lateinit var swCpu: Switch
    private lateinit var swAuto: Switch
    private lateinit var swHud: Switch

    private val prefs by lazy { getSharedPreferences("gb", MODE_PRIVATE) }

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> detectMode() }

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadApps()
        renderGames()
        val last = prefs.getString("last", null)
        select(if (last != null && last in myGames) last else myGames.firstOrNull())
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

    // ===================== UI helpers =====================

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Ui.MUTED)
        letterSpacing = 0.1f
        setPadding(dp(4), dp(16), 0, dp(8))
    }

    private fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(this@MainActivity, Ui.CARD, 18)
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }

    private fun sw(t: String): Switch = Switch(this).apply {
        text = t
        textSize = 14f
        setTextColor(Ui.TEXT)
        setPadding(0, dp(9), 0, dp(9))
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Ui.ACCENT, 0xFF444B5C.toInt())
        )
        setOnCheckedChangeListener { _, _ -> saveProfileFromUi() }
    }

    private fun gradBtn(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = Ui.gradient(this@MainActivity, 16)
        setPadding(dp(12), dp(16), dp(12), dp(16))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun ghostBtn(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Ui.TEXT)
        gravity = Gravity.CENTER
        background = Ui.rounded(this@MainActivity, Ui.CARD2, 14)
        setPadding(dp(12), dp(13), dp(12), dp(13))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun statCard(caption: String): Pair<LinearLayout, TextView> {
        val v = TextView(this).apply {
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TEXT)
            text = "-"
        }
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@MainActivity, Ui.CARD, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(this@MainActivity).apply { text = caption; textSize = 11f; setTextColor(Ui.MUTED) })
            addView(v)
        }
        return c to v
    }

    private fun buildUi() {
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(30), dp(16), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "⚡ GAME BOOSTER"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Ui.TEXT)
        })
        root.addView(TextView(this).apply {
            text = "Boost • Frame Gen • HUD • Auto-boost"
            textSize = 12f
            setTextColor(Ui.MUTED)
            setPadding(0, dp(2), 0, dp(14))
        })

        // --- Statistik ---
        val (cRam, vRam) = statCard("RAM BEBAS")
        val (cTemp, vTemp) = statCard("SUHU BATERAI")
        val (cMode, vMode) = statCard("AKSES")
        statRam = vRam; statTemp = vTemp; statMode = vMode
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(cRam, cTemp, cMode).forEachIndexed { i, c ->
            stats.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
        root.addView(stats)
        tvModeInfo = TextView(this).apply {
            textSize = 11f
            setTextColor(Ui.MUTED)
            setPadding(dp(4), dp(6), 0, 0)
            text = "Mendeteksi akses..."
        }
        root.addView(tvModeInfo)

        // --- Daftar game ---
        root.addView(label("GAME KAMU  (tahan lama untuk menghapus)"))
        gameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(gameRow)
        })

        // --- Hero game terpilih ---
        root.addView(gap(14))
        heroIcon = ImageView(this)
        heroName = TextView(this).apply { textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Ui.TEXT) }
        heroSub = TextView(this).apply { textSize = 12f; setTextColor(Ui.MUTED) }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@MainActivity, Ui.CARD, 18)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(heroIcon, LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(heroName)
                addView(heroSub)
            })
        }
        root.addView(hero)
        root.addView(gap(10))
        root.addView(gradBtn("⚡  BOOST & MAIN") { boost() })
        root.addView(gap(8))
        root.addView(ghostBtn("↩  Kembalikan ke normal") { restore() })

        // --- Performa ---
        root.addView(label("PERFORMA  (tersimpan per game)"))
        swAnim = sw("Percepat animasi sistem")
        swDnd = sw("Mode Jangan Ganggu")
        swHz = sw("Paksa refresh rate tinggi (120Hz)")
        swCompile = sw("Optimasi ART game")
        swKill = sw("Tutup app background")
        swCpu = sw("[ROOT] CPU performance + GPU clock")
        root.addView(box().apply { listOf(swAnim, swDnd, swHz, swCompile, swKill, swCpu).forEach { addView(it) } })

        // --- Otomatisasi ---
        root.addView(label("OTOMATISASI"))
        swAuto = sw("Auto-boost saat game ini dibuka")
        swHud = sw("HUD FPS / suhu / RAM di atas game")
        swAuto.setOnCheckedChangeListener { _, on ->
            saveProfileFromUi()
            if (on && !loading) ensureFloating()
        }
        swHud.setOnCheckedChangeListener { _, on ->
            saveProfileFromUi()
            if (!loading) FloatingService.instance?.setHud(on, false)
        }
        root.addView(box().apply { addView(swAuto); addView(swHud) })

        // --- Panel & Frame Gen ---
        root.addView(label("PANEL FLOATING & FRAME GEN"))
        root.addView(box().apply {
            addView(TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(Ui.MUTED)
                setPadding(0, dp(6), 0, dp(10))
                text = "Panel floating dipakai untuk Frame Gen, HUD, dan Auto-boost. " +
                    "Buka game lalu ketuk bubble ⚡. Di dialog capture pilih \"Bagikan satu aplikasi\" -> game (Android 14+)."
            })
            addView(gradBtn("Aktifkan panel floating") { startFloating() })
            addView(gap(8))
            addView(ghostBtn("Hentikan panel floating") {
                startService(Intent(this@MainActivity, FrameGenService::class.java).setAction(FrameGenService.ACTION_STOP))
                stopService(Intent(this@MainActivity, FloatingService::class.java))
            })
            addView(gap(8))
            addView(TextView(this@MainActivity).apply {
                textSize = 11f
                setTextColor(Ui.MUTED)
                text = "Tile Quick Settings: tarik panel notifikasi -> ikon pensil -> tambah \"Game Boost\" dan \"Panel Game\"."
            })
            addView(gap(6))
        })

        root.addView(gap(14))
        root.addView(ghostBtn("Cek ulang root / Shizuku") { detectMode() })

        // --- Log ---
        root.addView(label("LOG"))
        tvLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Ui.MUTED)
            setTextIsSelectable(true)
            background = Ui.rounded(this@MainActivity, Ui.CARD, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            text = "(belum ada aktivitas)\n"
        }
        root.addView(tvLog)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    // ===================== Daftar game =====================

    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val autoGames = mutableSetOf<String>()
        for (ri in packageManager.queryIntentActivities(i, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || appLabels.containsKey(pkg)) continue
            appLabels[pkg] = ri.loadLabel(packageManager).toString()
            appIcons[pkg] = ri.loadIcon(packageManager)
            val ai = ri.activityInfo.applicationInfo
            @Suppress("DEPRECATION")
            val isGame = ai.category == ApplicationInfo.CATEGORY_GAME ||
                (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            if (isGame) autoGames.add(pkg)
        }
        val saved = prefs.getStringSet("my_games", null)
        if (saved == null) {
            myGames.addAll(autoGames)
            saveGames()
        } else {
            myGames.addAll(saved.filter { appLabels.containsKey(it) })
        }
    }

    private fun saveGames() {
        prefs.edit().putStringSet("my_games", myGames.toSet()).apply()
    }

    private fun gameCard(pkg: String): View {
        val sel = pkg == selected
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(10))
            background = Ui.rounded(this@MainActivity, Ui.CARD, 16, if (sel) Ui.ACCENT else Ui.CARD2, 2)
            layoutParams = LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            }
            isClickable = true
            setOnClickListener { select(pkg) }
            setOnLongClickListener { confirmRemove(pkg); true }
        }
        c.addView(ImageView(this).apply {
            setImageDrawable(appIcons[pkg])
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        c.addView(TextView(this).apply {
            text = appLabels[pkg] ?: pkg
            textSize = 11f
            setTextColor(if (sel) Ui.TEXT else Ui.MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        return c
    }

    private fun renderGames() {
        gameRow.removeAllViews()
        for (pkg in myGames) gameRow.addView(gameCard(pkg))
        // kartu "+"
        gameRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.rounded(this@MainActivity, Ui.CARD, 16, Ui.CARD2, 2)
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(100))
            isClickable = true
            setOnClickListener { showAddDialog() }
            addView(TextView(this@MainActivity).apply {
                text = "+"
                textSize = 30f
                setTextColor(Ui.ACCENT)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = "Tambah"
                textSize = 11f
                setTextColor(Ui.MUTED)
                gravity = Gravity.CENTER
            })
        })
    }

    private fun showAddDialog() {
        val cands = appLabels.entries.filter { it.key !in myGames }.sortedBy { it.value.lowercase() }
        if (cands.isEmpty()) { toast("Tidak ada app lain"); return }
        val names = cands.map { it.value }.toTypedArray()
        val checked = BooleanArray(cands.size)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Tambah game")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Tambah") { _, _ ->
                cands.forEachIndexed { i, e -> if (checked[i]) myGames.add(e.key) }
                saveGames()
                if (selected == null) select(myGames.firstOrNull()) else renderGames()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmRemove(pkg: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("Hapus ${appLabels[pkg] ?: pkg} dari daftar?")
            .setPositiveButton("Hapus") { _, _ ->
                myGames.remove(pkg)
                saveGames()
                if (selected == pkg) select(myGames.firstOrNull()) else renderGames()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun select(pkg: String?) {
        selected = pkg
        if (pkg != null) {
            prefs.edit().putString("last", pkg).apply()
            if (State.boostedPkg == null) {
                State.activePkg = pkg
                FgConfig.apply(Profiles.get(prefs, pkg), prefs)
            }
        }
        renderGames()
        updateHero()
        loadProfileToUi()
    }

    private fun updateHero() {
        val pkg = selected
        if (pkg == null) {
            heroIcon.setImageDrawable(null)
            heroName.text = "Belum ada game"
            heroSub.text = "Ketuk kartu + untuk menambah game"
        } else {
            heroIcon.setImageDrawable(appIcons[pkg])
            heroName.text = appLabels[pkg] ?: pkg
            heroSub.text = "Setting di bawah tersimpan khusus game ini"
        }
    }

    // ===================== Profil =====================

    private fun loadProfileToUi() {
        val pkg = selected ?: return
        val pr = Profiles.get(prefs, pkg)
        loading = true
        swAnim.isChecked = pr.anim
        swDnd.isChecked = pr.dnd
        swHz.isChecked = pr.hz
        swCompile.isChecked = pr.compile
        swKill.isChecked = pr.kill
        swCpu.isChecked = pr.cpu && mode == Shell.Mode.ROOT
        swAuto.isChecked = pr.auto
        swHud.isChecked = pr.hud
        loading = false
    }

    private fun saveProfileFromUi() {
        if (loading || !::swHud.isInitialized) return
        val pkg = selected ?: return
        val old = Profiles.get(prefs, pkg)
        Profiles.save(
            prefs, pkg,
            old.copy(
                anim = swAnim.isChecked, dnd = swDnd.isChecked, kill = swKill.isChecked,
                compile = swCompile.isChecked, hz = swHz.isChecked, cpu = swCpu.isChecked,
                auto = swAuto.isChecked, hud = swHud.isChecked
            )
        )
    }

    // ===================== Akses root / Shizuku =====================

    private fun detectMode() {
        thread {
            var m = Shell.Mode.NONE
            val info: String
            if (Shell.hasRoot()) {
                m = Shell.Mode.ROOT
                info = "Root aktif: semua fitur tersedia"
            } else {
                var s = "Tidak ada root. Aktifkan Shizuku (Wireless Debugging) lalu tekan Cek ulang."
                try {
                    if (Shizuku.pingBinder()) {
                        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                            m = Shell.Mode.SHIZUKU
                            s = "Shizuku aktif (non-root): fitur root-only dinonaktifkan"
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
                tvModeInfo.text = info
                statMode.text = when (m) {
                    Shell.Mode.ROOT -> "ROOT"
                    Shell.Mode.SHIZUKU -> "SHIZUKU"
                    else -> "MATI"
                }
                statMode.setTextColor(if (m == Shell.Mode.NONE) Ui.BAD else Ui.GOOD)
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
            statRam.text = String.format("%.1f GB", mi.availMem / 1073741824.0)
            statTemp.text = String.format("%.1f°C", temp)
            statTemp.setTextColor(if (temp >= 42.0) Ui.BAD else Ui.TEXT)
            handler.postDelayed(this, 3000)
        }
    }

    private fun log(s: String) = runOnUiThread {
        if (tvLog.text.startsWith("(belum")) tvLog.text = ""
        tvLog.append(s + "\n")
    }

    // ===================== Aksi =====================

    private fun boost() {
        val pkg = selected
        if (pkg == null) { toast("Tambah dan pilih game dulu"); return }
        if (mode == Shell.Mode.NONE) { log("Belum ada akses root/Shizuku."); toast("Butuh root atau Shizuku"); return }
        saveProfileFromUi()
        thread {
            log("== BOOST ${appLabels[pkg] ?: pkg} ==")
            Booster.activate(this, pkg, false) { log(it) }
            log("Selesai. Membuka game...")
            runOnUiThread {
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
            }
        }
    }

    private fun restore() {
        if (mode == Shell.Mode.NONE) { log("Belum ada akses root/Shizuku."); return }
        thread {
            Booster.deactivate(this) { log(it) }
            log("Selesai.")
        }
    }

    private fun ensureFloating(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan 'Tampil di atas app lain', lalu ketuk Aktifkan panel floating", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return false
        }
        if (FloatingService.instance == null) startService(Intent(this, FloatingService::class.java))
        return true
    }

    private fun startFloating() {
        saveProfileFromUi()
        if (ensureFloating()) toast("Panel aktif: ketuk bubble ⚡")
    }
}
