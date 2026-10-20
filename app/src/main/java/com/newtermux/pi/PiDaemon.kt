package com.newtermux.pi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Sobe o daemon pi-rpc sem precisar digitar no terminal.
 *
 * O Node nao roda dentro do APK (build glibc vs Bionic, Pi exige Node 18+),
 * entao o daemon roda no Termux de verdade e o app so dispara ele via
 * TermuxService (Runner app-shell, background) e reconecta no TCP 127.0.0.1.
 */
object PiDaemon {
    private const val TAG = "NebulaPi"
    const val PORT = PiRpcClient.DEFAULT_PORT

    /** Candidatos do script de subida, na ordem de preferencia. */
    private fun candidates(home: String): List<String> = listOf(
        "$home/work/NewTermux/pi-daemon/pi-rpc-start.sh",
        "$home/work/nebula-pi/daemon/pi-rpc-start.sh",
        "$home/.local/share/nebula-pi/pi-rpc-start.sh",
        "$home/bin/pi-rpc"
    )

    fun scriptPath(): String? {
        val home = try {
            android.os.Environment.getExternalStorageDirectory()?.let {
                // HOME real do Termux, nao o storage compartilhado.
                null
            }
            "/data/data/com.termux/files/home"
        } catch (_: Exception) {
            "/data/data/com.termux/files/home"
        }
        // Tenta o HOME real via getenv primeiro (funciona no app principal).
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
     * Retorna true se o intent foi enviado. O daemon leva ~1-3s pra subir.
     * No flavor demo (outro UID, sem env real) retorna false: ai tem que
     * rodar `pi-rpc` no Termux de verdade uma vez.
     */
    fun ensureRunning(context: Context): Boolean {
        val appCtx = context.applicationContext
        // Demo nao tem TermuxService com env real (terminal fake).
        if (appCtx.packageName.endsWith(".demo")) {
            Log.i(TAG, "flavor demo: nao auto-subo daemon, use pi-rpc no Termux real")
            return false
        }
        val script = scriptPath()
        if (script == null) {
            Log.w(TAG, "script pi-rpc-start.sh nao encontrado")
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
            // ShellCreateMode ALWAYS: se ja rodando, o script detecta e sai.
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
            Log.i(TAG, "pedido de subida do daemon enviado: $script")
            true
        } catch (e: Exception) {
            Log.w(TAG, "falha ao pedir subida do daemon", e)
            false
        }
    }
}
