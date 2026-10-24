package com.newtermux.pi

import android.content.Context
import org.json.JSONObject

/**
 * Remembers the model the user picked for each Pi session.
 *
 * Pi itself does not carry the model over when you switch back to an older session, so the app
 * keeps a session file -> model map and re-applies it after `get_state` reports which session is
 * open. Brand new sessions are deliberately not in the map, which is what makes them fall back to
 * the default model from `settings.json`.
 */
object PiSessionModels {

    private const val PREFS = "pi_session_models"
    private const val KEY = "by_session"
    private const val MAX_ENTRIES = 200

    data class Choice(val provider: String, val modelId: String)

    fun get(context: Context, sessionFile: String): Choice? {
        if (sessionFile.isEmpty()) return null
        val entry = read(context).optJSONObject(sessionFile) ?: return null
        val provider = entry.optString("provider")
        val modelId = entry.optString("modelId")
        if (provider.isEmpty() || modelId.isEmpty()) return null
        return Choice(provider, modelId)
    }

    fun put(context: Context, sessionFile: String, provider: String, modelId: String) {
        if (sessionFile.isEmpty() || provider.isEmpty() || modelId.isEmpty()) return
        val map = read(context)
        map.put(
            sessionFile,
            JSONObject()
                .put("provider", provider)
                .put("modelId", modelId)
                .put("at", System.currentTimeMillis())
        )
        prune(map)
        prefs(context).edit().putString(KEY, map.toString()).apply()
    }

    fun remove(context: Context, sessionFile: String) {
        if (sessionFile.isEmpty()) return
        val map = read(context)
        if (map.has(sessionFile)) {
            map.remove(sessionFile)
            prefs(context).edit().putString(KEY, map.toString()).apply()
        }
    }

    /** Keeps the newest entries only, so the preference never grows without bound. */
    private fun prune(map: JSONObject) {
        if (map.length() <= MAX_ENTRIES) return
        val oldest = map.keys().asSequence()
            .sortedBy { map.optJSONObject(it)?.optLong("at") ?: 0L }
            .take(map.length() - MAX_ENTRIES)
            .toList()
        oldest.forEach { map.remove(it) }
    }

    private fun read(context: Context): JSONObject = runCatching {
        JSONObject(prefs(context).getString(KEY, "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
