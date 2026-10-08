package com.example.gamebooster

import android.content.SharedPreferences

object Boost {
    data class Options(
        val dnd: Boolean,
        val killBg: Boolean,
        val compile: Boolean,
        val anim: Boolean,
        val refresh: Boolean,
        val cpu: Boolean
    )

    data class Step(val label: String, val cmd: String)

    private const val GOV = "/sys/devices/system/cpu/cpu*/cpufreq/scaling_governor"
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0/force_clk_on"
    const val READ_GOV = "for f in $GOV; do echo \"\$f=\$(cat \$f)\"; done"

    fun optionsFrom(p: SharedPreferences) = Options(
        p.getBoolean("o_dnd", true), p.getBoolean("o_kill", true), p.getBoolean("o_compile", true),
        p.getBoolean("o_anim", true), p.getBoolean("o_hz", false), p.getBoolean("o_cpu", false)
    )

    fun saveOptions(p: SharedPreferences, o: Options) {
        p.edit().putBoolean("o_dnd", o.dnd).putBoolean("o_kill", o.killBg)
            .putBoolean("o_compile", o.compile).putBoolean("o_anim", o.anim)
            .putBoolean("o_hz", o.refresh).putBoolean("o_cpu", o.cpu).apply()
    }

    fun steps(pkg: String, self: String, root: Boolean, o: Options): List<Step> {
        val l = mutableListOf<Step>()

        // ===== NON-ROOT (jalan di root & Shizuku) =====
        l += Step("Game Mode: Performance", "cmd game mode performance $pkg")
        l += Step("Matikan battery saver", "settings put global low_power 0")
        if (o.anim) l += Step(
            "Animasi sistem 0.5x",
            "settings put global window_animation_scale 0.5; " +
                "settings put global transition_animation_scale 0.5; " +
                "settings put global animator_duration_scale 0.5"
        )
        if (o.dnd) l += Step("Mode jangan ganggu (DND)", "cmd notification set_dnd priority")
        if (o.refresh) l += Step(
            "Refresh rate maksimal (120Hz)",
            "settings put system min_refresh_rate 120.0; settings put system peak_refresh_rate 120.0"
        )
        if (o.compile) l += Step("Optimasi ART (speed) untuk game", "cmd package compile -m speed -f $pkg")
        if (o.killBg) l += Step(
            "Tutup app background",
            "for p in \$(pm list packages -3 | cut -d: -f2); do " +
                "case \$p in $pkg|$self|moe.shizuku.privileged.api) ;; *) am force-stop \$p;; esac; done"
        )
        l += Step("Trim cache sistem", "cmd package trim-caches 2G")

        // ===== ROOT ONLY =====
        if (root) {
            l += Step("[ROOT] Drop cache RAM", "sync; echo 3 > /proc/sys/vm/drop_caches")
            if (o.cpu) {
                l += Step("[ROOT] CPU governor: performance", "for f in $GOV; do echo performance > \$f; done")
                l += Step("[ROOT] GPU clock tetap aktif (Adreno)", "[ -e $KGSL ] && echo 1 > $KGSL")
            }
        }
        return l
    }

    fun restoreSteps(pkg: String, root: Boolean): List<Step> {
        val l = mutableListOf<Step>()
        l += Step("Game Mode: Standard", "cmd game mode standard $pkg")
        l += Step(
            "Animasi 1.0x",
            "settings put global window_animation_scale 1.0; " +
                "settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0"
        )
        l += Step("DND off", "cmd notification set_dnd off")
        l += Step("Refresh rate default", "settings delete system min_refresh_rate; settings delete system peak_refresh_rate")
        if (root) l += Step("[ROOT] GPU clock normal", "[ -e $KGSL ] && echo 0 > $KGSL")
        return l
    }

    fun saveGovernors(p: SharedPreferences) {
        val out = Shell.run(Shell.Mode.ROOT, READ_GOV)
        val lines = out.lines().filter { it.contains("=") && !it.endsWith("=performance") }
        if (lines.isNotEmpty()) p.edit().putString("gov", lines.joinToString("\n")).apply()
    }

    fun restoreGovernors(p: SharedPreferences, log: (String) -> Unit) {
        val saved = p.getString("gov", null) ?: return
        for (line in saved.lines()) {
            val parts = line.split("=", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) Shell.run(Shell.Mode.ROOT, "echo ${parts[1]} > ${parts[0]}")
        }
        log("• [ROOT] CPU governor dikembalikan")
    }

    fun execute(mode: Shell.Mode, pkg: String, self: String, p: SharedPreferences, log: (String) -> Unit) {
        val o = optionsFrom(p)
        val root = mode == Shell.Mode.ROOT
        if (root && o.cpu) saveGovernors(p)
        for (s in steps(pkg, self, root, o)) {
            val out = Shell.run(mode, s.cmd)
            log("• ${s.label}" + if (out.isNotBlank()) "\n    ${out.take(160)}" else "")
        }
    }

    fun executeRestore(mode: Shell.Mode, pkg: String, p: SharedPreferences, log: (String) -> Unit) {
        val root = mode == Shell.Mode.ROOT
        for (s in restoreSteps(pkg, root)) {
            val out = Shell.run(mode, s.cmd)
            log("• ${s.label}" + if (out.isNotBlank()) "\n    ${out.take(160)}" else "")
        }
        if (root) restoreGovernors(p, log)
    }
}
