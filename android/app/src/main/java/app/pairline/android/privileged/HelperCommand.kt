package app.pairline.android.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import java.io.File

/**
 * The one command that starts Pairline's helper ([HelperMain]) as the shell user (D-054). Pairline runs it through
 * Wireless debugging; on Android 10, or without Wi-Fi, the user runs [computerCommand] from a computer once per
 * restart. `setsid` lets the helper outlive the connection that started it.
 */
object HelperCommand {
    const val PROCESS_NAME = "pairline-helper"
    private const val SCRIPT = "start-helper.sh"

    /**
     * The shell must not exit right away: adbd ends everything a command started when its shell exits, and the
     * helper needs a moment to detach into its own session (`setsid`) first. Ends with `;` so more can follow.
     */
    fun forShell(context: Context): String {
        val apk = context.applicationInfo.sourceDir
        val user = Process.myUid() / 100_000
        return "CLASSPATH='$apk' setsid app_process /system/bin --nice-name=$PROCESS_NAME " +
            "${HelperMain::class.java.name} --user=$user </dev/null >/dev/null 2>&1 & sleep 1;"
    }

    /**
     * Writes the start script to Pairline's folder in shared storage, where `adb shell` can read it, and returns the
     * command to type on the computer. The script holds only this command (the APK path changes with updates, so it
     * is rewritten each time the screen shows it).
     */
    fun computerCommand(context: Context): String? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val script = File(dir, SCRIPT)
        runCatching { script.writeText("#!/system/bin/sh\n${forShell(context)}\necho \"Pairline's helper started.\"\n") }
            .onFailure { return null }
        return "adb shell sh ${shellPath(script)}"
    }

    /** /storage/emulated/0/… as the shell user sees it (the path typed on the computer). */
    @SuppressLint("SdCardPath")
    private fun shellPath(file: File): String = file.absolutePath.replace(Regex("^/storage/emulated/0/"), "/sdcard/")
}
