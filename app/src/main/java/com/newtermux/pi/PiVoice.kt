package com.newtermux.pi

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Voice in the Pi panel: speaks the response out loud (TTS).
 *
 * Dictation (microphone -> text) uses [com.newtermux.features.SpeechInputManager]
 * right on the screen, because it needs permission and the Activity lifecycle.
 */
object PiTts {

    private const val TAG = "NebulaPi"

    var isSpeaking by mutableStateOf(false)
        private set
    var speakingKey by mutableStateOf<String?>(null)
        private set

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    /**
     * Speaks the text (stripped of markdown). If the same message is already
     * being spoken, stop (toggle). If another one is being spoken, switch to it.
     */
    fun speak(context: Context, key: String, text: String) {
        if (isSpeaking && speakingKey == key) {
            stop()
            return
        }
        stopInternal()
        val clean = stripMarkdownForSpeech(text).take(4000)
        if (clean.isBlank()) return
        speakingKey = key
        val engine = tts
        if (engine != null && ready) {
            doSpeak(engine, clean)
        } else {
            pending = clean
            if (engine == null) {
                try {
                    tts = TextToSpeech(context.applicationContext) { status ->
                        ready = status == TextToSpeech.SUCCESS
                        val t = tts
                        if (ready && t != null) {
                            val pt = Locale("pt", "BR")
                            t.language =
                                if (t.isLanguageAvailable(pt) >= TextToSpeech.LANG_AVAILABLE) pt
                                else Locale.getDefault()
                            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {
                                    isSpeaking = true
                                }

                                override fun onDone(utteranceId: String?) {
                                    isSpeaking = false
                                    speakingKey = null
                                }

                                @Deprecated("used on older APIs")
                                override fun onError(utteranceId: String?) {
                                    isSpeaking = false
                                    speakingKey = null
                                }

                                override fun onError(utteranceId: String?, errorCode: Int) {
                                    isSpeaking = false
                                    speakingKey = null
                                }
                            })
                            pending?.let { doSpeak(t, it) }
                        } else {
                            isSpeaking = false
                            speakingKey = null
                        }
                        pending = null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "could not start TTS", e)
                    pending = null
                    speakingKey = null
                }
            }
        }
    }

    fun stop() {
        stopInternal()
        speakingKey = null
    }

    private fun stopInternal() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        pending = null
        isSpeaking = false
    }

    fun shutdown() {
        stopInternal()
        speakingKey = null
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }

    private fun doSpeak(engine: TextToSpeech, clean: String) {
        try {
            isSpeaking = true
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "nebula-pi-tts")
        } catch (e: Exception) {
            Log.w(TAG, "failed to speak", e)
            isSpeaking = false
            speakingKey = null
        }
    }

    /**
     * Strips markdown syntax so it does not spell out "**" and "```" out loud.
     * A code block becomes "code block", a link becomes the text.
     */
    fun stripMarkdownForSpeech(text: String): String {
        var s = text
        s = s.replace(Regex("```[\\s\\S]*?```"), " code block. ")
        s = s.replace(Regex("`([^`]*)`"), "$1")
        s = s.replace(Regex("\\[([^\\]]*)]\\([^\\)]*\\)"), "$1")
        s = s.replace(Regex("https?://\\S+"), " link. ")
        s = s.replace("<u>", "").replace("</u>", "").replace("<U>", "").replace("</U>", "")
        s = s.replace("**", "").replace("__", "").replace("~~", "").replace("++", "")
        // stray italic and headings at the start of the line
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^>\\s?"), "")
        s = s.replace(Regex("(?m)^\\s*[-*•+]\\s+"), "")
        s = s.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        s = s.replace("*", "").replace("_", "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }
}
