package com.newtermux.features

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App-side bridge to the `nebula-containers` shell helper.
 *
 * The helper script owns every proot-distro operation (listing, sizes, install, removal and the
 * graphical stack), so the CLI, the settings screen and any future automation share one
 * implementation. The app only installs the bundled copy into $PREFIX/bin and runs it with the
 * Termux environment.
 */
object NebulaContainers {

    const val SCRIPT_NAME = "nebula-containers"
    const val X11_VIEWER_PACKAGE = "com.termux.x11"

    private const val TAG = "NebulaContainers"
    private const val DEFAULT_TIMEOUT_SECONDS = 120L

    data class Container(
        val name: String,
        val version: String,
        val sizeKb: Long,
        val complete: Boolean,
        val running: Boolean,
        val family: String,
    )

    data class Totals(val containersKb: Long, val cacheKb: Long, val freeKb: Long)

    data class Image(
        val name: String,
        val description: String,
        val stars: String,
        val pulls: String,
        val official: Boolean,
    )

    data class Doctor(
        val prootDistro: Boolean,
        val termuxX11: Boolean,
        val viewer: Boolean,
        val freeKb: Long,
    )

    data class Result(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
        val message: String get() = stderr.ifBlank { stdout }.trim()
    }

    // ------------------------------------------------------------------ paths

    /** $PREFIX, resolved at runtime so the demo flavor works too. */
    fun prefixDir(context: Context): File = File(context.filesDir, "usr")

    fun homeDir(context: Context): File = File(context.filesDir, "home")

    fun scriptFile(context: Context): File = File(prefixDir(context), "bin/$SCRIPT_NAME")

    /**
     * Installs the script in $PREFIX/bin, rewriting it whenever the bundled asset changed so app
     * upgrades always ship the matching helper.
     */
    fun ensureScript(context: Context): File {
        val target = scriptFile(context)
        val asset = try {
            context.assets.open("$SCRIPT_NAME.sh").use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "bundled script is missing: ${e.message}")
            return target
        }
        val current = if (target.exists()) runCatching { target.readBytes() }.getOrNull() else null
        if (current == null || !current.contentEquals(asset)) {
            target.parentFile?.mkdirs()
            target.writeBytes(asset)
        }
        target.setExecutable(true, false)
        return target
    }

    /** Termux environment for child processes, read from the env file the app already generates. */
    private fun termuxEnv(context: Context): Map<String, String> {
        val prefix = prefixDir(context)
        val env = linkedMapOf<String, String>()
        val envFile = File(prefix, "etc/termux/termux.env")
        if (envFile.isFile) {
            envFile.forEachLine { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("export ")) return@forEachLine
                val body = trimmed.removePrefix("export ")
                val eq = body.indexOf('=')
                if (eq <= 0) return@forEachLine
                val key = body.substring(0, eq).trim()
                val value = body.substring(eq + 1).trim().removeSurrounding("\"")
                if (key.isNotEmpty()) env[key] = value
            }
        }
        env["PREFIX"] = prefix.path
        env["HOME"] = homeDir(context).path
        env["PATH"] = "${prefix.path}/bin"
        env["TMPDIR"] = File(prefix, "tmp").path
        env.remove("LD_PRELOAD")
        return env
    }

    private fun newProcess(context: Context, args: List<String>): ProcessBuilder {
        val prefix = prefixDir(context)
        val builder = ProcessBuilder(args)
        builder.directory(File(prefix, "bin").takeIf { it.isDirectory } ?: context.filesDir)
        builder.environment().apply {
            putAll(termuxEnv(context))
        }
        builder.redirectErrorStream(false)
        return builder
    }

    // ------------------------------------------------------------------ exec

    /** Runs the helper and captures its output. Never blocks the caller's dispatcher. */
    suspend fun exec(
        context: Context,
        vararg args: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): Result = withContext(Dispatchers.IO) {
        val script = ensureScript(context)
        val bash = File(prefixDir(context), "bin/bash")
        if (!bash.canExecute()) {
            return@withContext Result(3, "", "Termux bash is not installed yet — bootstrap has not finished")
        }

        val process = try {
            newProcess(context, listOf(bash.path, script.path) + args).start()
        } catch (e: Exception) {
            Log.e(TAG, "could not start $SCRIPT_NAME ${args.joinToString(" ")}: ${e.message}")
            return@withContext Result(1, "", e.message ?: "failed to start helper")
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { runCatching { process.inputStream.bufferedReader().copyTo(StringBuilderWriter(stdout)) } }
        val errThread = Thread { runCatching { process.errorStream.bufferedReader().copyTo(StringBuilderWriter(stderr)) } }
        outThread.start()
        errThread.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.join(1000)
            errThread.join(1000)
            return@withContext Result(1, stdout.toString(), "timed out after ${timeoutSeconds}s")
        }
        outThread.join(2000)
        errThread.join(2000)

        Result(process.exitValue(), stdout.toString(), stderr.toString())
    }

    /** Appends to a [StringBuilder] from a reader thread. */
    private class StringBuilderWriter(private val builder: StringBuilder) : java.io.Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            builder.append(String(cbuf, off, len))
        }

        override fun flush() {}
        override fun close() {}
    }

    // ------------------------------------------------------------------ queries

    suspend fun list(context: Context): List<Container> {
        val result = exec(context, "list", "--json", timeoutSeconds = 60)
        if (!result.ok) {
            Log.w(TAG, "list failed: ${result.message}")
            return emptyList()
        }
        return parseList(result.stdout)
    }

    internal fun parseList(raw: String): List<Container> = runCatching {
        val array = JSONObject(raw).optJSONArray("containers") ?: return emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    Container(
                        name = o.optString("name"),
                        version = o.optString("version").ifBlank { "?" },
                        sizeKb = o.optLong("size_kb"),
                        complete = o.optBoolean("complete"),
                        running = o.optBoolean("running"),
                        family = o.optString("family"),
                    )
                )
            }
        }
    }.getOrElse {
        Log.w(TAG, "could not parse container list: ${it.message}")
        emptyList()
    }

    suspend fun totals(context: Context): Totals {
        val result = exec(context, "total", "--json", timeoutSeconds = 60)
        if (!result.ok) return Totals(0, 0, 0)
        return runCatching {
            val o = JSONObject(result.stdout)
            Totals(o.optLong("containers_kb"), o.optLong("cache_kb"), o.optLong("free_kb"))
        }.getOrElse { Totals(0, 0, 0) }
    }

    suspend fun search(context: Context, query: String): List<Image> {
        val result = exec(context, "search", query, "--json", timeoutSeconds = 120)
        if (!result.ok) {
            Log.w(TAG, "search failed: ${result.message}")
            return emptyList()
        }
        return runCatching {
            val array = JSONObject(result.stdout).optJSONArray("images") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        Image(
                            name = o.optString("name"),
                            description = o.optString("description"),
                            stars = o.optString("stars"),
                            pulls = o.optString("pulls"),
                            official = o.optBoolean("official"),
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    suspend fun doctor(context: Context): Doctor {
        val result = exec(context, "doctor", "--json", timeoutSeconds = 60)
        return runCatching {
            val o = JSONObject(result.stdout)
            Doctor(
                prootDistro = o.optBoolean("proot_distro"),
                termuxX11 = o.optBoolean("termux_x11"),
                viewer = o.optBoolean("x11_viewer"),
                freeKb = o.optLong("free_kb"),
            )
        }.getOrElse { Doctor(false, false, false, 0) }
    }

    // ------------------------------------------------------------------ actions

    suspend fun remove(context: Context, name: String) = exec(context, "remove", name, timeoutSeconds = 180)

    suspend fun clearCache(context: Context) = exec(context, "clear-cache", timeoutSeconds = 120)

    suspend fun setupDesktop(context: Context, name: String) =
        exec(context, "gui", "setup", name, timeoutSeconds = 900)

    /** True when the graphical stack (window manager + terminal) is already installed. */
    suspend fun desktopReady(context: Context, name: String): Boolean {
        val result = exec(context, "gui", "status", name, "--json", timeoutSeconds = 90)
        return runCatching { JSONObject(result.stdout).optBoolean("ready") }.getOrDefault(false)
    }

    /** Command handed to the terminal for installs: long running and worth watching live. */
    fun installCommand(name: String, withDesktop: Boolean): String =
        buildString {
            append(SCRIPT_NAME)
            append(" install ")
            append(name)
            if (withDesktop) append(" --desktop")
        }

    /**
     * Starts the desktop in the background and returns immediately. The helper detaches itself, so
     * the app does not have to babysit a long-lived process.
     */
    fun startDesktop(context: Context, name: String, app: String = "desktop") {
        val script = ensureScript(context)
        val prefix = prefixDir(context)
        val log = File(prefix, "tmp/nebula-containers-gui.log")
        val command = "setsid nohup '${script.path}' gui start '$name' --app '$app' --no-viewer " +
            ">> '${log.path}' 2>&1 &"
        runCatching {
            newProcess(context, listOf(File(prefix, "bin/bash").path, "-c", command)).start()
        }.onFailure { Log.e(TAG, "could not start desktop: ${it.message}") }
        openX11Viewer(context)
    }

    fun openX11Viewer(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(X11_VIEWER_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { false }
    }

    suspend fun stopDesktop(context: Context) = exec(context, "gui", "stop", timeoutSeconds = 60)

    // ------------------------------------------------------------------ formatting

    fun formatKb(kb: Long): String = when {
        kb >= 1024L * 1024L -> String.format("%.1f GB", kb / 1024.0 / 1024.0)
        kb >= 1024L -> String.format("%.0f MB", kb / 1024.0)
        else -> "$kb KB"
    }
}
