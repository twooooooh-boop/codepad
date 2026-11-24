package com.forai.codepad

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Saves the stack trace of an uncaught exception so it can be shown (and shared) on the next start. */
object CrashReporter {
    private fun file(ctx: Context): File = File(ctx.filesDir, "last_crash.txt")

    fun install(app: Application) {
        val f = file(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(Thread.UncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val version = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (e: Exception) {
                    "?"
                }
                f.writeText(
                    "CodePad crash\n" +
                            "Time: " + Date() + "\n" +
                            "Thread: " + thread.name + "\n" +
                            "Device: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE +
                            " (API " + Build.VERSION.SDK_INT + ")\n" +
                            "Version: " + version + "\n\n" + sw.toString()
                )
            } catch (e: Throwable) {
                // never let the reporter itself crash
            }
            previous?.uncaughtException(thread, error)
        })
    }

    fun pending(ctx: Context): String? {
        val f = file(ctx)
        return if (f.exists()) f.readText() else null
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}

class CodePadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
