package com.newtermux.pi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Starts the pi-rpc daemon without typing in the terminal.
 *
 * Node does not run inside the APK (glibc vs Bionic build, Pi needs Node 18+),
 * so the daemon runs in the real Termux and the app just launches it through
 * TermuxService (Runner app-shell, background) and reconnects on TCP 127.0.0.1.
 */
object PiDaemon {
    private const val TAG = "NebulaPi"
    const val PORT = PiRpcClient.DEFAULT_PORT

    /** Candidates for the start script, in order of preference. */
    private fun candidates(home: String): List<String> = listOf(
        "$home/work/NewTermux/pi-daemon/pi-rpc-start.sh",
        "$home/work/nebula-pi/daemon/pi-rpc-start.sh",
        "$home/.local/share/nebula-pi/pi-rpc-start.sh",
        "$home/bin/pi-rpc"
    )

    fun scriptPath(): String? {
        val home = try {
            android.os.Environment.getExternalStorageDirectory()?.let {
                // Real Termux HOME, not shared storage.
                null
            }
            "/data/data/com.termux/files/home"
        } catch (_: Exception) {
            "/data/data/com.termux/files/home"
        }
        // Tries the real HOME via getenv first (works in the main app).
        val envHome = try { System.getenv("HOME") } catch (_: Exception) { null }
        val homes = listOfNotNull(envHome, home).distinct()
        for (h in homes) {
            for (c in candidates(h)) {
                try {
                    if (File(c).canExecute() || File(c).exists()) return c
                } catch (_: Exception) { }
            }
        }
        return null
    }

    /**
     * Dispara o start script via TermuxService (mesmo pacote).
     * Returns true if the intent was sent. The daemon takes ~1-3s to come up.
     * In the demo flavor (different UID, no real env) it returns false: then
     * `pi-rpc` has to be run in the real Termux once.
     */
    fun ensureRunning(context: Context): Boolean {
        val appCtx = context.applicationContext
        // Demo has no TermuxService with real env (fake terminal).
        if (appCtx.packageName.endsWith(".demo")) {
            Log.i(TAG, "demo flavor: not auto-starting the daemon, use pi-rpc in real Termux")
            return false
        }
        val script = scriptPath()
        if (script == null) {
            Log.w(TAG, "pi-rpc-start.sh script not found")
            return false
        }
        return try {
            val uri = Uri.parse("file://$script")
            val intent = Intent(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE,
                uri
            )
            intent.setClassName(appCtx.packageName, "com.termux.app.TermuxService")
            intent.putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER,
                "app-shell"
            )
            intent.putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR,
                "/data/data/com.termux/files/home"
            )
            intent.putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_LABEL,
                "Nebula Pi daemon"
            )
            intent.putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION,
                "Sobe o pi-rpc-daemon (TCP 127.0.0.1:$PORT)"
            )
            // ShellCreateMode ALWAYS: if already running, the script detects it and exits.
            intent.putExtra(
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE,
                "always"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    appCtx.startForegroundService(intent)
                } catch (_: Exception) {
                    appCtx.startService(intent)
                }
            } else {
                appCtx.startService(intent)
            }
            Log.i(TAG, "daemon start request sent: $script")
            true
        } catch (e: Exception) {
            Log.w(TAG, "failed to request daemon start", e)
            false
        }
    }
}
