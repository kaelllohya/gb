package com.example.gamebooster

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object State {
    @Volatile var mode: Shell.Mode = Shell.Mode.NONE
}

object Shell {
    enum class Mode { ROOT, SHIZUKU, NONE }

    fun hasRoot(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    fun shizukuGranted(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun detect(): Mode = when {
        hasRoot() -> Mode.ROOT
        shizukuGranted() -> Mode.SHIZUKU
        else -> Mode.NONE
    }

    // Shizuku.newProcess bersifat private di API 13+, jadi dipanggil via reflection
    private fun shizukuProcess(cmd: String): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }

    fun run(mode: Mode, cmd: String): String {
        return try {
            val p: Process = when (mode) {
                Mode.ROOT -> ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                Mode.SHIZUKU -> shizukuProcess(cmd)
                Mode.NONE -> return "Tidak ada akses root/Shizuku"
            }
            val out = p.inputStream.bufferedReader().readText()
            val err = if (mode == Mode.SHIZUKU) p.errorStream.bufferedReader().readText() else ""
            p.waitFor()
            (out + err).trim()
        } catch (e: Exception) {
            "ERR: ${e.message}"
        }
    }
}
