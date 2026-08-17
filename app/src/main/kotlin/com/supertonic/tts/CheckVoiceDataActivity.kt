package com.supertonic.tts

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech

class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = intent
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ArrayList(AVAILABLE_VOICES))
            .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }

    companion object {
        private val AVAILABLE_VOICES = listOf(
            "eng", "kor", "jpn", "zho", "ara", "bul", "ces", "dan", "deu", "ell",
            "spa", "est", "fin", "fra", "hin", "hrv", "hun", "ind", "ita", "lit",
            "lav", "nld", "pol", "por", "ron", "rus", "slk", "slv", "swe", "tur",
            "ukr", "vie"
        )
    }
}
