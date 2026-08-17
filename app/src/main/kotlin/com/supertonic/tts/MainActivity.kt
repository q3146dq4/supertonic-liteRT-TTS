package com.supertonic.tts

import android.app.AlertDialog
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import audio.soniqo.speech.TtsSettings
import audio.soniqo.speech.rules.PronunciationRules
import audio.soniqo.speech.audio.AudioSpeedProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.TimeSource

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var modelJob: Job? = null
    private var synthJob: Job? = null
    private var player: MediaPlayer? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var engineInitSeconds = 0.0

    private lateinit var status: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var voiceSpinner: Spinner
    private lateinit var stepsSpinner: Spinner
    private lateinit var threadsSpinner: Spinner
    private lateinit var chunkSpinner: Spinner
    private lateinit var chunkManualInput: EditText
    private lateinit var preGenerationSpinner: Spinner
    private lateinit var chunkGapMinInput: EditText
    private lateinit var chunkGapMaxInput: EditText
    private lateinit var trailingTrimInput: EditText
    private lateinit var languageSpinner: Spinner
    private lateinit var allowNaCheck: android.widget.CheckBox
    private lateinit var textInput: EditText
    private lateinit var rtfView: TextView
    private lateinit var ruleStatus: TextView
    private lateinit var playButton: Button

    private val builtinVoices = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")
    private val steps = listOf(4, 5, 6, 8, 10, 12)
    private val threadCounts = listOf(2, 4, 8)
    private val chunkModes = listOf(
        "보수적 · 짧게 (40)" to TtsSettings.CHUNK_CONSERVATIVE,
        "균형 · 기본 (64)" to TtsSettings.CHUNK_BALANCED,
        "긴 문장 · 길게 (88)" to TtsSettings.CHUNK_LONG,
        "수동 설정 (24–96)" to TtsSettings.CHUNK_MANUAL,
    )
    private val languages = listOf(
        "자동 (na)" to "na",
        "한국어 (ko)" to "ko", "English (en)" to "en", "日本語 (ja)" to "ja", "中文 (zh)" to "zh",
        "Deutsch (de)" to "de", "Français (fr)" to "fr", "Español (es)" to "es", "Italiano (it)" to "it",
        "Português (pt)" to "pt", "Русский (ru)" to "ru"
    )

    private var voiceIds: MutableList<String> = builtinVoices.toMutableList()
    private var voiceLabels: MutableList<String> = builtinVoices.toMutableList()

    private val voiceImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCustomVoice(uri)
    }
    private val ruleImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importRules(uri)
    }
    private val ruleExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportRules(uri)
    }

    private val saveWavLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) {
            val wav = File(filesDir, "generated.wav")
            if (wav.exists()) contentResolver.openOutputStream(uri)?.use { out -> wav.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restoreSettings()
        bindSettingPersistence()
        refreshRuleStatus()
        ensureModel()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 28, 32, 32) }
        root.addView(TextView(this).apply { text = "Supertonic LiteRT"; textSize = 30f })
        root.addView(TextView(this).apply {
            text = "Soniqo Supertonic-3 · LiteRT 4-graph\nVoice / Speed / Steps 설정은 시스템 TTS에도 적용됩니다."
            textSize = 14f; setPadding(0, 4, 0, 18)
        })
        status = TextView(this).apply { text = "모델 준비 확인 중…"; textSize = 16f }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "모델 다운로드 / 재확인 (약 380 MB)"
            setOnClickListener { ensureModel(force = true) }
        })

        root.addView(TextView(this).apply { text = "Speaker / Voice ID"; textSize = 16f; setPadding(0, 20, 0, 4) })
        voiceSpinner = Spinner(this)
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceLabels)
        root.addView(voiceSpinner)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val import = Button(this@MainActivity).apply {
                text = "Custom Voice 가져오기"
                setOnClickListener { ensureModelAnd { voiceImport.launch(arrayOf("application/json", "text/plain", "*/*")) } }
            }
            val manage = Button(this@MainActivity).apply {
                text = "Custom Voice 삭제"
                setOnClickListener { showCustomVoiceManager() }
            }
            import.setSingleLine(true)
            manage.setSingleLine(true)
            val buttonLp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val buttonLp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            buttonLp2.setMargins(8, 0, 0, 0)
            addView(import, buttonLp1)
            addView(manage, buttonLp2)
        })

        speedLabel = TextView(this).apply { textSize = 16f }
        root.addView(speedLabel)
        speedBar = SeekBar(this).apply { max = 55 }
        root.addView(speedBar)

        root.addView(TextView(this).apply { text = "Flow matching steps (빠름 ↔ 품질)"; textSize = 16f; setPadding(0, 12, 0, 4) })
        stepsSpinner = Spinner(this)
        stepsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, steps.map { "$it steps" })
        root.addView(stepsSpinner)

        root.addView(TextView(this).apply { text = "CPU threads"; textSize = 16f; setPadding(0, 8, 0, 4) })
        threadsSpinner = Spinner(this)
        threadsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, threadCounts.map { "$it threads" })
        root.addView(threadsSpinner)

        root.addView(TextView(this).apply {
            text = "청크 크기 (TTFA ↔ 청크 간격)"
            textSize = 16f
            setPadding(0, 12, 0, 4)
        })
        chunkSpinner = Spinner(this)
        chunkSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, chunkModes.map { it.first })
        root.addView(chunkSpinner)
        chunkManualInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "수동 청크 길이: 24–96 codepoint"
            setText(TtsSettings.manualChunkCap(this@MainActivity).toString())
            visibility = android.view.View.GONE
        }
        root.addView(chunkManualInput)
        root.addView(TextView(this).apply {
            text = "권장 범위 24–96. 값이 작을수록 첫 음성이 빨라지는 대신 청크 수와 경계가 늘어날 수 있습니다. 96을 넘겨도 모델 입력창(128)은 자동 재분할되지만 duration overflow 위험이 커져 허용하지 않습니다."
            textSize = 13f
            setPadding(0, 2, 0, 8)
        })

        root.addView(TextView(this).apply {
            text = "다음 청크 미리 생성"
            textSize = 16f
            setPadding(0, 12, 0, 4)
        })
        preGenerationSpinner = Spinner(this)
        preGenerationSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("OFF", "2청크", "3청크"))
        root.addView(preGenerationSpinner)
        root.addView(TextView(this).apply {
            text = "OFF는 원래 단일 엔진 경로입니다. 2/3청크에서는 추가 LiteRT 엔진으로 다음 청크를 미리 생성합니다. CPU·RAM 사용량이 증가합니다."
            textSize = 13f
            setPadding(0, 0, 0, 8)
        })

        root.addView(TextView(this).apply {
            text = "청크 gap 최소 / 최대 허용"; textSize = 16f; setPadding(0, 4, 0, 4)
        })
        val gapRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chunkGapMinInput = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setSingleLine(true); hint = "최소 ms"; setText(TtsSettings.chunkGapMinMs(this@MainActivity).toString()) }
        chunkGapMaxInput = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setSingleLine(true); hint = "최대 ms"; setText(TtsSettings.chunkGapMaxMs(this@MainActivity).toString()) }
        gapRow.addView(chunkGapMinInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val gapMaxLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); gapMaxLp.setMargins(8, 0, 0, 0)
        gapRow.addView(chunkGapMaxInput, gapMaxLp)
        root.addView(gapRow)
        root.addView(TextView(this).apply {
            text = "최소값은 의도적인 청크 간 간격입니다. 최대값은 실제 생성 gap을 판정하는 상한(경고 기준)입니다. 생성 자체가 이보다 느리면 강제로 줄일 수는 없고, pre-generation queue가 이를 보완합니다."; textSize = 13f; setPadding(0, 0, 0, 8)
        })

        root.addView(TextView(this).apply { text = "Trailing silence trim (최대)"; textSize = 16f; setPadding(0, 4, 0, 4) })
        trailingTrimInput = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setSingleLine(true); hint = "0–500 ms"; setText(TtsSettings.trailingSilenceTrimMs(this@MainActivity).toString()) }
        root.addView(trailingTrimInput)
        root.addView(TextView(this).apply {
            text = "청크 끝과 최종 출력의 저에너지 후행 무음을 최대 몇 ms까지 잘라낼지 설정합니다. 0이면 자르지 않습니다."; textSize = 13f; setPadding(0, 0, 0, 8)
        })

        preGenerationSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val enabled = position > 0
                TtsSettings.setPreGeneration(this@MainActivity, enabled)
                if (enabled) TtsSettings.setStreamingControls(this@MainActivity, position + 1, currentChunkGapMin(), currentChunkGapMax(), currentTrailingTrim())
                runCatching {
                    synthesizer?.setPreGeneration(enabled)
                    if (enabled) synthesizer?.setPreGenerationQueue(position + 1)
                }
            }
        }

        root.addView(TextView(this).apply {
            text = "Backend\nSoniqo Supertonic-3 LiteRT · CPU (current speech-core runtime)"
            textSize = 14f; setPadding(0, 12, 0, 4)
        })

        root.addView(TextView(this).apply { text = "테스트 언어"; textSize = 16f; setPadding(0, 12, 0, 4) })
        languageSpinner = Spinner(this)
        languageSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.first })
        root.addView(languageSpinner)

        allowNaCheck = android.widget.CheckBox(this).apply {
            text = "혼합 언어일 때 na 사용 허용"
            isChecked = TtsSettings.allowNa(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                TtsSettings.setAllowNa(this@MainActivity, checked)
            }
        }
        root.addView(allowNaCheck)
        root.addView(TextView(this).apply {
            text = "끄면 선택한 언어(예: ko)를 그대로 사용합니다. 켜면 한·영/한·일 등 혼합 문장에서만 na로 전환합니다."
            textSize = 13f
            setPadding(0, 0, 0, 8)
        })

        textInput = EditText(this).apply {
            minLines = 6; maxLines = 14; gravity = android.view.Gravity.TOP
            hint = "여기에 테스트 문장을 입력하세요"
            setText("안녕하세요. Supertonic LiteRT 시스템 엔진 테스트입니다. 이것은 차세대 kaldi를 사용하는 텍스트 음성 변환 엔진입니다.")
        }
        root.addView(textInput)

        val ruleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ruleLp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val ruleLp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val ruleLp3 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        ruleLp2.setMargins(8, 0, 0, 0)
        ruleLp3.setMargins(8, 0, 0, 0)
        ruleRow.addView(Button(this).apply { text = "정규식 설정"; setSingleLine(true); setOnClickListener { startActivity(Intent(this@MainActivity, PronunciationRulesActivity::class.java)) } }, ruleLp1)
        ruleRow.addView(Button(this).apply { text = "가져오기"; setSingleLine(true); setOnClickListener { ruleImport.launch(arrayOf("application/json", "text/plain", "*/*")) } }, ruleLp2)
        ruleRow.addView(Button(this).apply { text = "내보내기"; setSingleLine(true); setOnClickListener { ruleExport.launch("supertonic-pronunciation-rules.json") } }, ruleLp3)
        root.addView(ruleRow)
        ruleStatus = TextView(this).apply { textSize = 13f; setPadding(0, 4, 0, 6) }
        root.addView(ruleStatus)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(this).apply { text = "START"; setOnClickListener { startSynthesis() } }
        playButton = Button(this).apply { text = "PLAY"; isEnabled = false; setOnClickListener { playLast() } }
        val stop = Button(this).apply { text = "STOP"; setOnClickListener { stopAll() } }
        row1.addView(start, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(playButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row1)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val save = Button(this@MainActivity).apply { text = "SAVE WAV"; setOnClickListener { saveLast() } }
            val share = Button(this@MainActivity).apply { text = "SHARE"; setOnClickListener { shareLast() } }
            addView(save, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(share, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        rtfView = TextView(this).apply { textSize = 14f; setPadding(0, 14, 0, 10) }
        root.addView(rtfView)
        root.addView(TextView(this).apply {
            text = "시스템 TTS 사용: Android 설정 → 일반 관리 → 텍스트 음성 변환 → 기본 엔진 → Supertonic LiteRT\n\nCustom Voice JSON은 Supertonic-3 호환 style_ttl/style_dp JSON을 사용합니다.\n※ 혼합 언어 na 전환은 위의 ‘혼합 언어일 때 na 사용 허용’이 켜져 있을 때만 적용됩니다."
            textSize = 14f; setPadding(0, 18, 0, 0)
        })

        setContentView(android.widget.ScrollView(this).apply { addView(root) })
        // Deliberately keep the first playback at normal speed.
        updateSpeedLabel()
    }

    private fun restoreSettings() {
        val savedVoice = TtsSettings.voice(this)
        voiceSpinner.setSelection(voiceIds.indexOf(savedVoice).takeIf { it >= 0 } ?: 0)
        stepsSpinner.setSelection(steps.indexOf(TtsSettings.steps(this)).takeIf { it >= 0 } ?: 0)
        threadsSpinner.setSelection(threadCounts.indexOf(TtsSettings.threads(this)).takeIf { it >= 0 } ?: 1)
        chunkSpinner.setSelection(chunkModes.indexOfFirst { it.second == TtsSettings.chunkMode(this) }.takeIf { it >= 0 } ?: 1)
        chunkManualInput.setText(TtsSettings.manualChunkCap(this).toString())
        chunkManualInput.visibility = if (TtsSettings.chunkMode(this) == TtsSettings.CHUNK_MANUAL) android.view.View.VISIBLE else android.view.View.GONE
        preGenerationSpinner.setSelection(if (TtsSettings.preGeneration(this)) TtsSettings.preGenerationQueue(this) - 1 else 0)
        chunkGapMinInput.setText(TtsSettings.chunkGapMinMs(this).toString())
        chunkGapMaxInput.setText(TtsSettings.chunkGapMaxMs(this).toString())
        trailingTrimInput.setText(TtsSettings.trailingSilenceTrimMs(this).toString())
        val savedSpeed = TtsSettings.speed(this).coerceIn(0.25f, 3.0f)
        speedBar.progress = ((savedSpeed - 0.25f) / 0.05f).roundToInt().coerceIn(0, speedBar.max)
    }

    private fun refreshVoices(selected: String? = TtsSettings.voice(this)) {
        val modelDir = runCatching { ModelManager.modelDir(applicationContext) }.getOrNull()
        val custom = modelDir?.resolve("voice_styles")?.listFiles()?.asSequence()
            ?.filter { it.extension.equals("json", true) }
            ?.map { it.nameWithoutExtension }
            ?.filter { it !in builtinVoices }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?.toList().orEmpty()
        voiceIds = (builtinVoices + custom).toMutableList()
        voiceLabels = (builtinVoices.map { it } + custom.map { "Custom · ${it.removePrefix("custom_")}" }).toMutableList()
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceLabels)
        voiceSpinner.setSelection(voiceIds.indexOf(selected ?: "F1").takeIf { it >= 0 } ?: 0)
    }

    private fun bindSettingPersistence() {
        voiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val voice = currentVoice()
                TtsSettings.save(this@MainActivity, voice, currentSpeed(), currentSteps(), currentThreads(), currentChunkMode(), currentManualChunkCap())
                runCatching { synthesizer?.setVoice(voice) }
            }
        }
        stepsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val stepCount = currentSteps()
                TtsSettings.save(this@MainActivity, currentVoice(), currentSpeed(), stepCount, currentThreads(), currentChunkMode(), currentManualChunkCap())
                synthesizer?.setTotalSteps(stepCount)
            }
        }
        threadsSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val threads = currentThreads()
                TtsSettings.save(this@MainActivity, currentVoice(), currentSpeed(), currentSteps(), threads, currentChunkMode(), currentManualChunkCap())
                // Thread count is a graph/interpreter creation option. Recreate lazily on next START.
                synthesizer?.close()
                synthesizer = null
            }
        }
        chunkSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val mode = chunkModes[position.coerceIn(0, chunkModes.lastIndex)].second
                chunkManualInput.visibility = if (mode == TtsSettings.CHUNK_MANUAL) android.view.View.VISIBLE else android.view.View.GONE
                TtsSettings.setChunkSettings(this@MainActivity, mode, currentManualChunkCap())
                synthesizer?.close()
                synthesizer = null
            }
        }
        chunkManualInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val cap = currentManualChunkCap()
                chunkManualInput.setText(cap.toString())
                TtsSettings.setChunkSettings(this@MainActivity, currentChunkMode(), cap)
            }
        }
        val commitStreamingControls = {
            val minGap = currentChunkGapMin()
            val maxGap = currentChunkGapMax().coerceAtLeast(minGap)
            val trail = currentTrailingTrim()
            chunkGapMinInput.setText(minGap.toString())
            chunkGapMaxInput.setText(maxGap.toString())
            trailingTrimInput.setText(trail.toString())
            TtsSettings.setStreamingControls(this@MainActivity, preGenerationQueue(), minGap, maxGap, trail)
            runCatching {
                synthesizer?.setPreGenerationQueue(preGenerationQueue())
                synthesizer?.setChunkGap(minGap, maxGap)
                synthesizer?.setTrailingSilenceTrimMs(trail)
            }
        }
        chunkGapMinInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitStreamingControls() }
        chunkGapMaxInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitStreamingControls() }
        trailingTrimInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitStreamingControls() }

        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedLabel()
                if (fromUser) {
                    val speed = currentSpeed()
                    TtsSettings.save(this@MainActivity, currentVoice(), speed, currentSteps(), currentThreads(), currentChunkMode(), currentManualChunkCap())
                    // Native synthesis stays at 1x. Speed is applied afterward with Sonic so
                    // 1.5x/2x cannot shorten the model's duration tensor and clip words.
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun updateSpeedLabel() {
        val value = 0.25f + speedBar.progress * 0.05f
        speedLabel.text = "Speed ${String.format(Locale.US, "%.2f", value)}"
    }

    private fun currentVoice(): String = voiceIds[voiceSpinner.selectedItemPosition.coerceIn(0, voiceIds.lastIndex)]
    private fun currentSteps(): Int = steps[stepsSpinner.selectedItemPosition.coerceIn(0, steps.lastIndex)]
    private fun currentThreads(): Int = threadCounts[threadsSpinner.selectedItemPosition.coerceIn(0, threadCounts.lastIndex)]
    private fun currentChunkMode(): String = chunkModes[chunkSpinner.selectedItemPosition.coerceIn(0, chunkModes.lastIndex)].second
    private fun currentManualChunkCap(): Int = chunkManualInput.text?.toString()?.toIntOrNull()?.coerceIn(TtsSettings.MIN_CHUNK_CAP, TtsSettings.MAX_CHUNK_CAP) ?: TtsSettings.manualChunkCap(this)
    private fun currentChunkCap(): Int = when (currentChunkMode()) {
        TtsSettings.CHUNK_CONSERVATIVE -> 40
        TtsSettings.CHUNK_LONG -> 88
        TtsSettings.CHUNK_MANUAL -> currentManualChunkCap()
        else -> 64
    }
    private fun preGenerationQueue(): Int = if (preGenerationSpinner.selectedItemPosition > 0) preGenerationSpinner.selectedItemPosition + 1 else 2
    private fun currentChunkGapMin(): Int = chunkGapMinInput.text?.toString()?.toIntOrNull()?.coerceIn(TtsSettings.MIN_GAP_MS, TtsSettings.MAX_GAP_MS) ?: TtsSettings.DEFAULT_GAP_MIN_MS
    private fun currentChunkGapMax(): Int = chunkGapMaxInput.text?.toString()?.toIntOrNull()?.coerceIn(TtsSettings.MIN_GAP_MS, TtsSettings.MAX_GAP_MS) ?: TtsSettings.DEFAULT_GAP_MAX_MS
    private fun currentTrailingTrim(): Int = trailingTrimInput.text?.toString()?.toIntOrNull()?.coerceIn(TtsSettings.MIN_TRAILING_TRIM_MS, TtsSettings.MAX_TRAILING_TRIM_MS) ?: TtsSettings.DEFAULT_TRAILING_TRIM_MS
    private fun currentSpeed(): Float = 0.25f + speedBar.progress * 0.05f
    private fun currentLanguage(): String = languages[languageSpinner.selectedItemPosition.coerceIn(0, languages.lastIndex)].second
    private fun persistUiSettings() {
        TtsSettings.save(this, currentVoice(), currentSpeed(), currentSteps(), currentThreads(), currentChunkMode(), currentManualChunkCap())
        TtsSettings.setPreGeneration(this, preGenerationSpinner.selectedItemPosition > 0)
        TtsSettings.setStreamingControls(this, preGenerationQueue(), currentChunkGapMin(), currentChunkGapMax(), currentTrailingTrim())
    }

    private fun ensureModel(force: Boolean = false) {
        if (modelJob?.isActive == true) return
        if (!force && ModelManager.areTtsModelsReady(this)) {
            status.text = "Soniqo Supertonic-3 모델 준비 완료 (약 380 MB)"
            refreshVoices()
            return
        }
        modelJob = scope.launch {
            status.text = "모델 확인/다운로드 중… 0%"
            runCatching {
                ModelManager.ensureTtsModels(applicationContext) { done, total, file ->
                    val percent = ((done * 100.0) / total.coerceAtLeast(1L)).coerceIn(0.0, 100.0).roundToInt()
                    runOnUiThread { status.text = "모델 다운로드/확인 중… $percent%\n$file" }
                }
            }.onSuccess {
                status.text = "Soniqo Supertonic-3 모델 준비 완료 (약 380 MB)"
                refreshVoices()
            }.onFailure { e -> status.text = "모델 준비 실패:\n${e.message}\n\n다시 시도해 주세요." }
        }
    }

    private fun ensureModelAnd(action: () -> Unit) {
        if (ModelManager.areTtsModelsReady(this)) action() else {
            ensureModel()
            Toast.makeText(this, "모델 준비가 먼저 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCustomVoiceManager() {
        val dir = File(ModelManager.modelDir(applicationContext), "voice_styles")
        val files = dir.listFiles()?.filter { it.extension.equals("json", true) && it.nameWithoutExtension !in builtinVoices }.orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }
        if (files.isEmpty()) { Toast.makeText(this, "삭제할 Custom Voice가 없습니다.", Toast.LENGTH_SHORT).show(); return }
        val names = files.map { it.nameWithoutExtension.removePrefix("custom_") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Custom Voice 삭제").setItems(names) { _, which ->
            val target = files[which]
            AlertDialog.Builder(this).setTitle("삭제 확인").setMessage("${target.nameWithoutExtension}을(를) 삭제할까요?")
                .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                    val wasSelected = currentVoice() == target.nameWithoutExtension
                    target.delete()
                    if (wasSelected) TtsSettings.save(this, "F1", currentSpeed(), currentSteps(), currentThreads(), currentChunkMode(), currentManualChunkCap())
                    synthesizer?.close(); synthesizer = null
                    refreshVoices(if (wasSelected) "F1" else currentVoice())
                    Toast.makeText(this, "삭제 완료", Toast.LENGTH_SHORT).show()
                }.show()
        }.setNegativeButton("닫기", null).show()
    }

    private fun importCustomVoice(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(ModelManager.modelDir(applicationContext), "voice_styles").apply { mkdirs() }
                val rawName = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                } ?: "custom_voice.json"
                val stem = rawName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "voice" }
                val id = "custom_${stem.take(48)}"
                val out = File(dir, "$id.json")
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalArgumentException("JSON을 읽을 수 없습니다.")
                val obj = JSONObject(text)
                if (!obj.has("style_ttl") || !obj.has("style_dp")) {
                    throw IllegalArgumentException("Supertonic-3 voice JSON이 아닙니다. style_ttl/style_dp가 필요합니다.")
                }
                out.writeText(text, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    synthesizer?.close(); synthesizer = null
                    refreshVoices(id)
                    TtsSettings.save(this@MainActivity, id, currentSpeed(), currentSteps(), currentThreads(), currentChunkMode(), currentManualChunkCap())
                    Toast.makeText(this@MainActivity, "Custom Voice 추가: $id", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Voice JSON 가져오기 실패: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun importRules(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalArgumentException("JSON을 읽을 수 없습니다.")
                val count = PronunciationRules.importJson(this@MainActivity, text)
                withContext(Dispatchers.Main) {
                    refreshRuleStatus()
                    Toast.makeText(this@MainActivity, "발음/정규식 규칙 ${count}개 가져옴", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "규칙 가져오기 실패: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun exportRules(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(PronunciationRules.toJson(this).toString(2).toByteArray(Charsets.UTF_8))
            }
            refreshRuleStatus()
            Toast.makeText(this, "규칙 JSON 저장 완료", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(this, "규칙 내보내기 실패: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun refreshRuleStatus() {
        if (::ruleStatus.isInitialized) ruleStatus.text = "발음/정규식 규칙: ${PronunciationRules.count(this)}개"
    }

    private fun startSynthesis() {
        if (!ModelManager.areTtsModelsReady(this)) { ensureModel(); Toast.makeText(this, "모델 준비가 먼저 필요합니다.", Toast.LENGTH_SHORT).show(); return }
        persistUiSettings(); player?.stop(); player?.release(); player = null; synthJob?.cancel()
        val original = textInput.text?.toString()?.trim().orEmpty()
        if (original.isBlank()) { Toast.makeText(this, "텍스트를 입력하세요.", Toast.LENGTH_SHORT).show(); return }
        val text = PronunciationRules.apply(this, original)
        val voice = currentVoice(); val speed = currentSpeed(); val stepCount = currentSteps()
        val selectedLang = currentLanguage()
        val lang = if (selectedLang == "na" ||
            (allowNaCheck.isChecked && PronunciationRules.isMixedScript(text))) "na" else selectedLang
        status.text = "음성 생성 중…\nVoice=$voice · Speed=${String.format(Locale.US, "%.2f", speed)} · Steps=$stepCount · Chunk=${currentChunkCap()} · Lang=$lang"
        rtfView.text = ""
        synthJob = scope.launch {
            val started = TimeSource.Monotonic.markNow()
            runCatching {
                val value = withContext(Dispatchers.Default) {
                    val synth = getOrCreateSynthesizer(ModelManager.modelDir(applicationContext).absolutePath)
                    synth.setVoice(voice); synth.setSpeed(1.0f); synth.setTotalSteps(stepCount); synth.setPreGeneration(TtsSettings.preGeneration(applicationContext))
                    synth.setPreGenerationQueue(preGenerationQueue())
                    synth.setChunkGap(currentChunkGapMin(), currentChunkGapMax())
                    synth.setTrailingSilenceTrimMs(currentTrailingTrim())
                    synth.synthesize(text, lang)
                }
                val adjusted = withContext(Dispatchers.Default) { AudioSpeedProcessor.apply(value.pcm16, value.sampleRate, speed) }
                val elapsed = started.elapsedNow().inWholeMilliseconds / 1000.0
                val duration = adjusted.pcm16.size / 2.0 / value.sampleRate
                val profile = parseProfile(value.profile)
                val nativeTotal = (profile["total"] as? Double)?.div(1000.0) ?: elapsed
                val wav = File(filesDir, "generated.wav")
                writeWav(wav, adjusted.pcm16, value.sampleRate, 1)
                withContext(Dispatchers.Main) {
                    status.text = "생성 완료 · 자동 재생 · ${String.format(Locale.US, "%.2f", duration)}초"
                    rtfView.text = formatProfile(profile, elapsed, if (duration > 0) elapsed / duration else 0.0, duration, nativeTotal, adjusted.processingMs, speed, voice, stepCount, lang)
                    playButton.apply { isEnabled = true; playLast() }
                }
            }.onFailure { e -> withContext(Dispatchers.Main) { status.text = "음성 생성 실패:\n${e.message ?: e.javaClass.simpleName}" } }
        }
    }

    private fun getOrCreateSynthesizer(dir: String): SpeechSynthesizer {
        synthesizer?.let { return it }
        val started = TimeSource.Monotonic.markNow()
        return SpeechSynthesizer(SpeechSynthesizerConfig(
            modelDir = dir, voiceId = currentVoice(), speed = 1.0f, totalSteps = currentSteps(), numThreads = currentThreads(), chunkCap = currentChunkCap(), useNnapi = false,
            preGenerationQueue = preGenerationQueue(), chunkGapMinMs = currentChunkGapMin(), chunkGapMaxMs = currentChunkGapMax(), trailingSilenceTrimMs = currentTrailingTrim()
        )).also {
            engineInitSeconds = started.elapsedNow().inWholeMilliseconds / 1000.0
            synthesizer = it
        }
    }

    private fun parseProfile(profile: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        if (profile.isBlank()) return out
        for (entry in profile.split(';')) {
            val parts = entry.split('=', limit = 2); if (parts.size != 2) continue
            out[parts[0]] = parts[1].toDoubleOrNull() ?: parts[1]
        }
        val stepsString = profile.substringAfter("ve_steps=", "").substringBefore(';')
        if (stepsString.isNotBlank()) out["ve_steps"] = stepsString.split(',').mapNotNull { it.toDoubleOrNull() }
        return out
    }

    private fun formatProfile(profile: Map<String, Any>, elapsed: Double, rtf: Double, duration: Double, nativeTotal: Double,
                              speedProcessMs: Double, appliedSpeed: Float, appliedVoice: String, appliedSteps: Int, lang: String): String {
        fun ms(key: String) = String.format(Locale.US, "%.1f ms", (profile[key] as? Double ?: 0.0))
        val stepValues = profile["ve_steps"] as? List<*> ?: emptyList<Any>()
        val sb = StringBuilder("PERFORMANCE PROFILE\n")
        sb.append("Engine init            ").append(String.format(Locale.US, "%.3f s", engineInitSeconds)).append('\n')
        sb.append("Duration Predictor     ").append(ms("dp")).append('\n')
        sb.append("Text Encoder           ").append(ms("encoder")).append('\n')
        stepValues.forEachIndexed { i, v -> sb.append("VE Step ${i + 1}".padEnd(23)).append(String.format(Locale.US, "%.1f ms", (v as? Double ?: 0.0))).append('\n') }
        sb.append("Vocoder                ").append(ms("vocoder")).append('\n')
        sb.append("Tensor buffer/copy     ").append(ms("tensor_copy")).append('\n')
        sb.append("Native total           ").append(String.format(Locale.US, "%.3f s", nativeTotal)).append('\n')
        sb.append("End-to-end              ").append(String.format(Locale.US, "%.3f s", elapsed)).append('\n')
        sb.append("Audio duration          ").append(String.format(Locale.US, "%.3f s", duration)).append('\n')
        sb.append("Native RTF              ").append(String.format(Locale.US, "%.3f", if (duration > 0) nativeTotal / duration else 0.0)).append('\n')
        sb.append("End-to-end RTF          ").append(String.format(Locale.US, "%.3f", rtf)).append('\n')
        sb.append("TTFA (native stream)    ").append(String.format(Locale.US, "%.1f ms", (profile["ttfa_ms"] as? Double ?: 0.0))).append('\n')
        sb.append("Speed processing        ").append(String.format(Locale.US, "%.1f ms", speedProcessMs)).append('\n')
        sb.append("Applied voice           ").append(appliedVoice).append('\n')
        sb.append("Applied speed           ").append(String.format(Locale.US, "%.2f", appliedSpeed)).append('\n')
        sb.append("Applied steps           ").append(appliedSteps).append('\n')
        sb.append("Language                ").append(lang).append('\n')
        sb.append("Chunk cap               ").append(profile["chunk_cap"] ?: currentChunkCap()).append('\n')
        sb.append("Chunks                  ").append(profile["chunks"] ?: "0").append('\n')
        sb.append("Truncated chunks        ").append(profile["truncated_chunks"] ?: "0").append('\n')
        sb.append("Chunk silence           ").append(profile["chunk_silence_ms"] ?: "?").append(" ms\n")
        sb.append("Max chunk gap           ").append(profile["max_chunk_gap_ms"] ?: "?").append(" ms\n")
        sb.append("Avg chunk gap           ").append(profile["avg_chunk_gap_ms"] ?: "?").append(" ms\n")
        sb.append("Pre-generation         ").append(profile["pregen"] ?: if (TtsSettings.preGeneration(this)) "on" else "off").append("\n")
        sb.append("Pre-gen queue           ").append(profile["pregen_queue_depth"] ?: preGenerationQueue()).append(" chunks\n")
        sb.append("Gap min / max target    ").append(profile["chunk_gap_min_ms"] ?: currentChunkGapMin()).append(" / ").append(profile["chunk_gap_max_ms"] ?: currentChunkGapMax()).append(" ms\n")
        sb.append("Gap over max count      ").append(profile["chunk_gap_over_max_count"] ?: "0").append("\n")
        sb.append("Pre-generated chunks   ").append(profile["pregen_used_chunks"] ?: "0").append("\n")
        sb.append("Audio peak              ").append(profile["peak"] ?: "?").append('\n')
        sb.append("Audio RMS               ").append(profile["rms"] ?: "?").append('\n')
        sb.append("Leading silence         ").append(profile["lead_silence_ms"] ?: "?").append(" ms\n")
        sb.append("Trailing silence        ").append(profile["trail_silence_ms"] ?: "?").append(" ms\n")
        sb.append("Backend                 Soniqo Supertonic-3 LiteRT CPU").append('\n')
        sb.append("CPU threads              ").append(profile["threads"] ?: currentThreads())
        return sb.toString()
    }

    private fun playLast() {
        val wav = File(filesDir, "generated.wav")
        if (!wav.exists()) { Toast.makeText(this, "먼저 음성을 생성하세요.", Toast.LENGTH_SHORT).show(); return }
        player?.stop(); player?.release()
        player = MediaPlayer().apply {
            setDataSource(wav.absolutePath)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare(); start()
        }
    }

    private fun stopAll() { synthJob?.cancel(); synthesizer?.stop(); player?.stop(); player?.release(); player = null }
    private fun saveLast() {
        val wav = File(filesDir, "generated.wav")
        if (!wav.exists()) { Toast.makeText(this, "먼저 음성을 생성하세요.", Toast.LENGTH_SHORT).show(); return }
        saveWavLauncher.launch("supertonic_tts.wav")
    }
    private fun shareLast() {
        val wav = File(filesDir, "generated.wav")
        if (!wav.exists()) { Toast.makeText(this, "먼저 음성을 생성하세요.", Toast.LENGTH_SHORT).show(); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.supertonic.tts.fileprovider", wav)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share audio"))
    }
    private fun writeWav(file: File, pcm16: ByteArray, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2; val blockAlign = channels * 2
        FileOutputStream(file).use { out ->
            fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun int32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun int16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            ascii("RIFF"); int32(36 + pcm16.size); ascii("WAVE"); ascii("fmt "); int32(16); int16(1); int16(channels); int32(sampleRate); int32(byteRate); int16(blockAlign); int16(16); ascii("data"); int32(pcm16.size); out.write(pcm16)
        }
    }
    override fun onDestroy() { stopAll(); synthesizer?.close(); synthesizer = null; scope.cancel(); super.onDestroy() }
}
