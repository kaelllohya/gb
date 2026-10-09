package com.example.gamebooster

import android.content.SharedPreferences

object FgConfig {
    @Volatile var mult = 2      // 2x / 3x / 4x
    @Volatile var mode = 1      // 0 = blend (ringan), 1 = motion-compensated
    @Volatile var scale = 75    // skala resolusi capture (%)

    fun load(p: SharedPreferences) {
        mult = p.getInt("fg_mult", 2)
        mode = p.getInt("fg_mode", 1)
        scale = p.getInt("fg_scale", 75)
    }

    fun save(p: SharedPreferences) {
        p.edit().putInt("fg_mult", mult).putInt("fg_mode", mode).putInt("fg_scale", scale).apply()
    }

    /** Terapkan setting Frame Gen dari profil sebuah game. */
    fun apply(pr: Profile, p: SharedPreferences) {
        mult = pr.fgMult
        mode = pr.fgMode
        scale = pr.fgScale
        save(p)
    }

    /** Simpan nilai sekarang ke global dan ke profil game yang aktif. */
    fun persist(p: SharedPreferences) {
        save(p)
        val pk = State.activePkg ?: return
        Profiles.save(p, pk, Profiles.get(p, pk).copy(fgMult = mult, fgMode = mode, fgScale = scale))
    }
}
