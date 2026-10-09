package com.example.gamebooster

import android.content.SharedPreferences
import org.json.JSONObject

data class Profile(
    val anim: Boolean = true,
    val dnd: Boolean = true,
    val kill: Boolean = true,
    val compile: Boolean = true,
    val hz: Boolean = false,
    val cpu: Boolean = false,
    val auto: Boolean = false,
    val hud: Boolean = false,
    val fgMult: Int = 2,
    val fgMode: Int = 1,
    val fgScale: Int = 75
) {
    fun toOptions() = Boost.Options(dnd, kill, compile, anim, hz, cpu)

    fun toJson(): String = JSONObject()
        .put("anim", anim).put("dnd", dnd).put("kill", kill).put("compile", compile)
        .put("hz", hz).put("cpu", cpu).put("auto", auto).put("hud", hud)
        .put("fgMult", fgMult).put("fgMode", fgMode).put("fgScale", fgScale)
        .toString()

    companion object {
        fun fromJson(s: String?): Profile {
            if (s == null) return Profile()
            return try {
                val j = JSONObject(s)
                Profile(
                    j.optBoolean("anim", true), j.optBoolean("dnd", true), j.optBoolean("kill", true),
                    j.optBoolean("compile", true), j.optBoolean("hz", false), j.optBoolean("cpu", false),
                    j.optBoolean("auto", false), j.optBoolean("hud", false),
                    j.optInt("fgMult", 2), j.optInt("fgMode", 1), j.optInt("fgScale", 75)
                )
            } catch (e: Exception) {
                Profile()
            }
        }
    }
}

object Profiles {
    fun get(p: SharedPreferences, pkg: String): Profile = Profile.fromJson(p.getString("prof_$pkg", null))

    fun save(p: SharedPreferences, pkg: String, prof: Profile) {
        val set = (p.getStringSet("auto_pkgs", emptySet()) ?: emptySet()).toMutableSet()
        if (prof.auto) set.add(pkg) else set.remove(pkg)
        p.edit().putString("prof_$pkg", prof.toJson()).putStringSet("auto_pkgs", set).apply()
    }

    fun autoList(p: SharedPreferences): Set<String> =
        (p.getStringSet("auto_pkgs", emptySet()) ?: emptySet()).toSet()
}
