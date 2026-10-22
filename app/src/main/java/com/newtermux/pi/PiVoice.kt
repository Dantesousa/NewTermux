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
 * Voz no painel do Pi: fala a resposta em voz alta (TTS).
 *
 * O ditado (microfone -> texto) usa o [com.newtermux.features.SpeechInputManager]
 * direto na tela, porque precisa de permissao e ciclo de vida da Activity.
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
     * Fala o texto (limpo de markdown). Se ja estiver falando essa mesma
     * mensagem, para (toggle). Se estiver falando outra, troca pra nova.
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

                                @Deprecated("usado em APIs antigas")
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
                    Log.w(TAG, "nao consegui iniciar o TTS", e)
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
            Log.w(TAG, "falha ao falar", e)
            isSpeaking = false
            speakingKey = null
        }
    }

    /**
     * Tira a sintaxe do markdown pra nao soletrar "**" e "```" em voz alta.
     * Bloco de codigo vira "bloco de código", link vira o texto.
     */
    fun stripMarkdownForSpeech(text: String): String {
        var s = text
        s = s.replace(Regex("```[\\s\\S]*?```"), " bloco de código. ")
        s = s.replace(Regex("`([^`]*)`"), "$1")
        s = s.replace(Regex("\\[([^\\]]*)]\\([^\\)]*\\)"), "$1")
        s = s.replace(Regex("https?://\\S+"), " link. ")
        s = s.replace("<u>", "").replace("</u>", "").replace("<U>", "").replace("</U>", "")
        s = s.replace("**", "").replace("__", "").replace("~~", "").replace("++", "")
        // italico solto e headings no comeco da linha
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^>\\s?"), "")
        s = s.replace(Regex("(?m)^\\s*[-*•+]\\s+"), "")
        s = s.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        s = s.replace("*", "").replace("_", "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }
}
