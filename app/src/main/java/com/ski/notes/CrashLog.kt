package com.ski.notes

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the details of a crash so the app can show them on the next launch. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val header = "Ski Notes ${appVersion(app)} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " · thread ${thread.name}\n\n"
                file(app).writeText(header + sw.toString().take(12_000))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns the saved crash report (and deletes it), or null. */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            f.readText().also { f.delete() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}
