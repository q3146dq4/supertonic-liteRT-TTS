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
import audio.soniqo.speech.InferenceBackend
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
    private var benchmarkJob: Job? = null
    private var player: MediaPlayer? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var engineInitSeconds = 0.0

    private lateinit var status: TextView
    private lateinit var speedLabel: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var voiceSpinner: Spinner
    private lateinit var stepsSpinner: Spinner
    private lateinit var threadsSpinner: Spinner
    private lateinit var backendSpinner: Spinner
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
    // Expose the full practical range instead of a few presets.
    private val steps = (1..64).toList()
    private val threadCounts = (1..8).toList()
    private val benchmarkThreadCounts = (1..8).toList()
    private val backends = listOf(
        "CPU / XNNPACK" to InferenceBackend.CPU_XNNPACK,
        "GPU 실험적 / 자동 품질검사·CPU 복구" to InferenceBackend.GPU_LITERT,
        "NNAPI 실험적 / 자동 품질검사·CPU 복구" to InferenceBackend.NNAPI_DEVICE,
        "Qualcomm NPU/HTP 실험적 / Snapdragon 전용" to InferenceBackend.QUALCOMM_NPU,
    )
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
        speedBar = SeekBar(this).apply {
            max = 55
            // 0.25 + (15 * 0.05) = 1.00. Avoid briefly exposing the SeekBar's
            // zero-position value before restoreSettings() applies preferences.
            progress = 15
        }
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

        root.addView(TextView(this).apply { text = "Inference backend"; textSize = 16f; setPadding(0, 12, 0, 4) })
        backendSpinner = Spinner(this)
        backendSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, backends.map { it.first })
        root.addView(backendSpinner)
        root.addView(TextView(this).apply {
            text = "기본 Backend는 CPU/XNNPACK입니다. RTF는 생성 시간÷음성 길이이며 낮을수록 빠릅니다. Snapdragon 8 Elite Gen 5 기기의 동일 설정 End-to-end RTF는 CPU 0.188, QNN GPU hybrid 0.260, NNAPI 1.936으로 CPU가 가장 빨랐습니다. 테스트한 Helio G99 기기에서는 GPU가 비유한 출력으로 실패했고, NNAPI는 RTF 2.148에 Audio peak 0.029 / RMS 0.002의 손상된 바람 소리를 냈습니다. 따라서 GPU·NNAPI·NPU는 모두 실험적입니다. 앱은 NaN/Inf뿐 아니라 비정상 저에너지/과대 출력을 검사하고, 첫 음성이 전달되기 전에 실패하면 같은 요청을 CPU로 한 번 자동 재실행합니다. NPU 선택은 삭제하지 않았지만 공개 FP32 모델의 HTP Encoder·VE는 차단하고 HTP Vocoder probe만 보존했습니다. 프로세스를 종료시킨 DSP VE 경로는 사용하지 않습니다."
            textSize = 13f; setPadding(0, 0, 0, 8)
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
            setText("안녕하세요. Supertonic-3 LiteRT 시스템 TTS 엔진 테스트입니다. 한국어 음성 합성 성능과 품질을 확인하기 위한 테스트 문장입니다.")
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
        root.addView(Button(this).apply {
            text = "논스트리밍 벤치마크 · 1~8 threads"
            setOnClickListener { startNonStreamingBenchmark() }
        })
        root.addView(Button(this).apply {
            text = "스트리밍 벤치마크 · 1~8 threads"
            setOnClickListener { startStreamingBenchmark() }
        })
        root.addView(TextView(this).apply {
            text = "두 thread 벤치마크는 Backend 선택과 무관하게 CPU/XNNPACK으로 고정하고, 현재 Voice / Steps / Chunk / 테스트 언어를 유지한 채 CPU threads만 1~8까지 순차 측정합니다. GPU/NNAPI/NPU는 위 Backend에서 선택 후 START로 측정하세요. 자동 CPU 복구가 발생하면 PERFORMANCE PROFILE의 Requested/Active backend와 Fallback reason에 표시됩니다. 논스트리밍은 순수 전체 합성 성능, 스트리밍은 실제 chunk callback 기준 TTFA / callback gap / RTF를 함께 측정합니다. 스트리밍 벤치마크는 현재 '다음 청크 미리 생성' 설정을 그대로 사용하며 자동 재생은 하지 않습니다."
            textSize = 13f
            setPadding(0, 2, 0, 8)
        })
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
        // Keep the label synchronized with the initialized SeekBar even before
        // restoreSettings() replaces it with the persisted value.
        updateSpeedLabel()
    }

    private fun restoreSettings() {
        val savedVoice = TtsSettings.voice(this)
        voiceSpinner.setSelection(voiceIds.indexOf(savedVoice).takeIf { it >= 0 } ?: 0)
        stepsSpinner.setSelection(steps.indexOf(TtsSettings.steps(this)).takeIf { it >= 0 } ?: steps.indexOf(4))
        threadsSpinner.setSelection(threadCounts.indexOf(TtsSettings.threads(this)).takeIf { it >= 0 } ?: threadCounts.indexOf(4))
        backendSpinner.setSelection(backends.indexOfFirst { it.second == TtsSettings.backend(this) }.takeIf { it >= 0 } ?: 0)
        chunkSpinner.setSelection(chunkModes.indexOfFirst { it.second == TtsSettings.chunkMode(this) }.takeIf { it >= 0 } ?: 1)
        chunkManualInput.setText(TtsSettings.manualChunkCap(this).toString())
        chunkManualInput.visibility = if (TtsSettings.chunkMode(this) == TtsSettings.CHUNK_MANUAL) android.view.View.VISIBLE else android.view.View.GONE
        preGenerationSpinner.setSelection(if (TtsSettings.preGeneration(this)) TtsSettings.preGenerationQueue(this) - 1 else 0)
        chunkGapMinInput.setText(TtsSettings.chunkGapMinMs(this).toString())
        chunkGapMaxInput.setText(TtsSettings.chunkGapMaxMs(this).toString())
        trailingTrimInput.setText(TtsSettings.trailingSilenceTrimMs(this).toString())
        val savedSpeed = TtsSettings.speed(this).coerceIn(0.25f, 3.0f)
        speedBar.progress = ((savedSpeed - 0.25f) / 0.05f).roundToInt().coerceIn(0, speedBar.max)
        // Listeners are intentionally attached only after restoration, so a
        // programmatic progress change above does not trigger onProgressChanged.
        // Refresh the visible value explicitly; otherwise 1.00 can be applied
        // internally while the first-render label remains at 0.25.
        updateSpeedLabel()
        val savedTestLanguage = TtsSettings.testLanguage(this)
        languageSpinner.setSelection(languages.indexOfFirst { it.second == savedTestLanguage }.takeIf { it >= 0 } ?: 0)
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
        backendSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val backend = currentBackend()
                TtsSettings.setBackend(this@MainActivity, backend)
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

        languageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                TtsSettings.setTestLanguage(this@MainActivity, currentLanguage())
            }
        }

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
    private fun currentBackend(): InferenceBackend = backends[backendSpinner.selectedItemPosition.coerceIn(0, backends.lastIndex)].second
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
        TtsSettings.setTestLanguage(this, currentLanguage())
        TtsSettings.setBackend(this, currentBackend())
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

    private fun startNonStreamingBenchmark() {
        if (!ModelManager.areTtsModelsReady(this)) {
            ensureModel()
            Toast.makeText(this, "모델 준비가 먼저 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (benchmarkJob?.isActive == true) {
            Toast.makeText(this, "벤치마크가 이미 실행 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        persistUiSettings()
        player?.stop(); player?.release(); player = null
        if (synthJob?.isActive == true) synthesizer?.stop()
        synthJob?.cancel()
        synthesizer?.close(); synthesizer = null

        val original = textInput.text?.toString()?.trim().orEmpty()
        if (original.isBlank()) {
            Toast.makeText(this, "벤치마크할 테스트 문장을 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val text = PronunciationRules.apply(this, original)
        val voice = currentVoice()
        val stepCount = currentSteps()
        val selectedLang = currentLanguage()
        val lang = if (selectedLang == "na" || (allowNaCheck.isChecked && PronunciationRules.isMixedScript(text))) "na" else selectedLang
        val chunkCap = currentChunkCap()
        val pregenQueue = preGenerationQueue()
        val gapMin = currentChunkGapMin()
        val gapMax = currentChunkGapMax()
        val trailingTrim = currentTrailingTrim()
        val originalThreads = currentThreads()

        data class BenchResult(
            val threads: Int,
            val nativeMs: Double,
            val durationSec: Double,
            val rtf: Double,
            val veMs: Double,
            val vocoderMs: Double,
            val engineInitMs: Double,
        )

        benchmarkJob = scope.launch {
            status.text = "논스트리밍 벤치마크 시작… · Steps=$stepCount · Chunk=$chunkCap · Lang=$lang"
            rtfView.text = ""
            val results = mutableListOf<BenchResult>()
            runCatching {
                for ((index, threads) in benchmarkThreadCounts.withIndex()) {
                    withContext(Dispatchers.Main) {
                        status.text = "논스트리밍 ${index + 1}/${benchmarkThreadCounts.size} · ${threads} threads…"
                    }
                    val result = withContext(Dispatchers.Default) {
                        val initStart = System.nanoTime()
                        val benchSynth = SpeechSynthesizer(SpeechSynthesizerConfig(
                            modelDir = ModelManager.modelDir(applicationContext).absolutePath,
                            voiceId = voice,
                            speed = 1.0f,
                            totalSteps = stepCount,
                            numThreads = threads,
                            chunkCap = chunkCap,
                            useNnapi = false,
                            backend = InferenceBackend.CPU_XNNPACK,
                            preGenerationQueue = pregenQueue,
                            chunkGapMinMs = gapMin,
                            chunkGapMaxMs = gapMax,
                            trailingSilenceTrimMs = trailingTrim,
                        ))
                        val initMs = (System.nanoTime() - initStart) / 1_000_000.0
                        try {
                            benchSynth.setVoice(voice)
                            benchSynth.setSpeed(1.0f)
                            benchSynth.setTotalSteps(stepCount)
                            // Keep speculative pre-generation off for a fair CPU-thread comparison.
                            benchSynth.setPreGeneration(false)
                            val value = benchSynth.synthesize(text, lang)
                            val profile = parseProfile(value.profile)
                            val nativeMs = (profile["total"] as? Double) ?: 0.0
                            val durationSec = value.pcm16.size / 2.0 / value.sampleRate
                            val veMs = (profile["ve_steps"] as? List<*>)?.sumOf { (it as? Double) ?: 0.0 } ?: 0.0
                            val vocoderMs = (profile["vocoder"] as? Double) ?: 0.0
                            BenchResult(
                                threads = threads,
                                nativeMs = nativeMs,
                                durationSec = durationSec,
                                rtf = if (durationSec > 0.0) nativeMs / 1000.0 / durationSec else Double.POSITIVE_INFINITY,
                                veMs = veMs,
                                vocoderMs = vocoderMs,
                                engineInitMs = initMs,
                            )
                        } finally {
                            benchSynth.close()
                        }
                    }
                    results += result
                    withContext(Dispatchers.Main) {
                        val partial = results.joinToString("\n") {
                            String.format(Locale.US, "%dT  Native %.3fs  RTF %.3f", it.threads, it.nativeMs / 1000.0, it.rtf)
                        }
                        rtfView.text = "NON-STREAMING THREAD BENCHMARK\n$partial"
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    status.text = "벤치마크 실패: ${e.message ?: e.javaClass.simpleName}"
                }
            }.onSuccess {
                val sorted = results.sortedBy { it.rtf }
                val best = sorted.firstOrNull()
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder("NON-STREAMING THREAD BENCHMARK\n")
                    sb.append("고정 조건: Steps=$stepCount · Chunk=$chunkCap · Lang=$lang\n\n")
                    sb.append("Threads | Native | RTF | VE total | Vocoder | Init\n")
                    for (r in results) {
                        sb.append(String.format(Locale.US, "%dT      %.3fs   %.3f   %.1fms   %.1fms   %.0fms\n",
                            r.threads, r.nativeMs / 1000.0, r.rtf, r.veMs, r.vocoderMs, r.engineInitMs))
                    }
                    if (best != null) {
                        sb.append("\nBEST: ${best.threads} threads · RTF ${String.format(Locale.US, "%.3f", best.rtf)} · Native ${String.format(Locale.US, "%.3f", best.nativeMs / 1000.0)}s")
                    }
                    rtfView.text = sb.toString()
                    status.text = "논스트리밍 벤치마크 완료${best?.let { " · 최적 ${it.threads} threads" } ?: ""}"
                    // Benchmark is observational only: restore the user's selected thread setting.
                    threadsSpinner.setSelection(threadCounts.indexOf(originalThreads).coerceAtLeast(0))
                    synthesizer?.close(); synthesizer = null
                }
            }
        }
    }

    private fun startStreamingBenchmark() {
        if (!ModelManager.areTtsModelsReady(this)) {
            ensureModel()
            Toast.makeText(this, "모델 준비가 먼저 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (benchmarkJob?.isActive == true) {
            Toast.makeText(this, "벤치마크가 이미 실행 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        persistUiSettings()
        player?.stop(); player?.release(); player = null
        if (synthJob?.isActive == true) synthesizer?.stop()
        synthJob?.cancel()
        synthesizer?.close(); synthesizer = null

        val original = textInput.text?.toString()?.trim().orEmpty()
        if (original.isBlank()) {
            Toast.makeText(this, "벤치마크할 테스트 문장을 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val text = PronunciationRules.apply(this, original)
        val voice = currentVoice()
        val stepCount = currentSteps()
        val selectedLang = currentLanguage()
        val lang = if (selectedLang == "na" || (allowNaCheck.isChecked && PronunciationRules.isMixedScript(text))) "na" else selectedLang
        val chunkCap = currentChunkCap()
        val pregenEnabled = preGenerationSpinner.selectedItemPosition > 0
        val pregenQueue = preGenerationQueue()
        val gapMin = currentChunkGapMin()
        val gapMax = currentChunkGapMax()
        val trailingTrim = currentTrailingTrim()
        val originalThreads = currentThreads()

        data class StreamBenchResult(
            val threads: Int,
            val nativeMs: Double,
            val wallMs: Double,
            val durationSec: Double,
            val rtf: Double,
            val wallRtf: Double,
            val ttfaMs: Double,
            val nativeTtfaMs: Double,
            val callbacks: Int,
            val maxCallbackGapMs: Double,
            val avgCallbackGapMs: Double,
            val nativeMaxGapMs: Double,
            val nativeAvgGapMs: Double,
            val veMs: Double,
            val vocoderMs: Double,
            val engineInitMs: Double,
        )

        benchmarkJob = scope.launch {
            status.text = "스트리밍 벤치마크 시작… · Steps=$stepCount · Chunk=$chunkCap · Lang=$lang · Pregen=${if (pregenEnabled) pregenQueue else "OFF"}"
            rtfView.text = ""
            val results = mutableListOf<StreamBenchResult>()
            runCatching {
                for ((index, threads) in benchmarkThreadCounts.withIndex()) {
                    withContext(Dispatchers.Main) {
                        status.text = "스트리밍 ${index + 1}/${benchmarkThreadCounts.size} · ${threads} threads…"
                    }
                    val result = withContext(Dispatchers.Default) {
                        val initStart = System.nanoTime()
                        val benchSynth = SpeechSynthesizer(SpeechSynthesizerConfig(
                            modelDir = ModelManager.modelDir(applicationContext).absolutePath,
                            voiceId = voice,
                            speed = 1.0f,
                            totalSteps = stepCount,
                            numThreads = threads,
                            chunkCap = chunkCap,
                            useNnapi = false,
                            backend = InferenceBackend.CPU_XNNPACK,
                            preGenerationQueue = pregenQueue,
                            chunkGapMinMs = gapMin,
                            chunkGapMaxMs = gapMax,
                            trailingSilenceTrimMs = trailingTrim,
                        ))
                        val initMs = (System.nanoTime() - initStart) / 1_000_000.0
                        try {
                            benchSynth.setVoice(voice)
                            benchSynth.setSpeed(1.0f)
                            benchSynth.setTotalSteps(stepCount)
                            benchSynth.setPreGeneration(pregenEnabled)
                            benchSynth.setPreGenerationQueue(pregenQueue)
                            benchSynth.setChunkGap(gapMin, gapMax)
                            benchSynth.setTrailingSilenceTrimMs(trailingTrim)

                            val startNs = System.nanoTime()
                            var firstAudioNs = 0L
                            var previousAudioNs = 0L
                            var totalBytes = 0L
                            var callbackCount = 0
                            val callbackGaps = mutableListOf<Double>()

                            benchSynth.synthesizeStreaming(text, lang) { pcm, _ ->
                                if (pcm.isNotEmpty()) {
                                    val now = System.nanoTime()
                                    if (firstAudioNs == 0L) firstAudioNs = now
                                    if (previousAudioNs != 0L) callbackGaps += (now - previousAudioNs) / 1_000_000.0
                                    previousAudioNs = now
                                    callbackCount++
                                    totalBytes += pcm.size.toLong()
                                }
                            }
                            val wallMs = (System.nanoTime() - startNs) / 1_000_000.0
                            val profile = parseProfile(benchSynth.lastProfile())
                            val nativeMs = (profile["total"] as? Double) ?: wallMs
                            val durationSec = totalBytes / 2.0 / benchSynth.sampleRate
                            val ttfaMs = if (firstAudioNs != 0L) (firstAudioNs - startNs) / 1_000_000.0 else 0.0
                            val veMs = (profile["ve_steps"] as? List<*>)?.sumOf { (it as? Double) ?: 0.0 } ?: 0.0
                            val vocoderMs = (profile["vocoder"] as? Double) ?: 0.0
                            StreamBenchResult(
                                threads = threads,
                                nativeMs = nativeMs,
                                wallMs = wallMs,
                                durationSec = durationSec,
                                rtf = if (durationSec > 0.0) nativeMs / 1000.0 / durationSec else Double.POSITIVE_INFINITY,
                                wallRtf = if (durationSec > 0.0) wallMs / 1000.0 / durationSec else Double.POSITIVE_INFINITY,
                                ttfaMs = ttfaMs,
                                nativeTtfaMs = (profile["ttfa_ms"] as? Double) ?: 0.0,
                                callbacks = callbackCount,
                                maxCallbackGapMs = callbackGaps.maxOrNull() ?: 0.0,
                                avgCallbackGapMs = if (callbackGaps.isNotEmpty()) callbackGaps.average() else 0.0,
                                nativeMaxGapMs = (profile["max_chunk_gap_ms"] as? Double) ?: 0.0,
                                nativeAvgGapMs = (profile["avg_chunk_gap_ms"] as? Double) ?: 0.0,
                                veMs = veMs,
                                vocoderMs = vocoderMs,
                                engineInitMs = initMs,
                            )
                        } finally {
                            benchSynth.close()
                        }
                    }
                    results += result
                    withContext(Dispatchers.Main) {
                        val partial = results.joinToString("\n") {
                            String.format(Locale.US, "%dT  TTFA %.0fms  RTF %.3f  Gap %.0fms", it.threads, it.ttfaMs, it.rtf, it.maxCallbackGapMs)
                        }
                        rtfView.text = "STREAMING THREAD BENCHMARK\n$partial"
                    }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    status.text = "스트리밍 벤치마크 실패: ${e.message ?: e.javaClass.simpleName}"
                }
            }.onSuccess {
                // For actual perceived TTS responsiveness, rank primarily by TTFA, then by
                // maximum callback starvation gap, then by overall RTF.
                val best = results.minWithOrNull(compareBy<StreamBenchResult> { it.ttfaMs }.thenBy { it.maxCallbackGapMs }.thenBy { it.rtf })
                withContext(Dispatchers.Main) {
                    val sb = StringBuilder("STREAMING THREAD BENCHMARK\n")
                    sb.append("고정 조건: Steps=$stepCount · Chunk=$chunkCap · Lang=$lang · Pregen=${if (pregenEnabled) pregenQueue else "OFF"}\n")
                    sb.append("※ Speed는 실제 시스템 TTS의 streaming 경로와 동일하게 1.00x로 측정\n\n")
                    sb.append("T | TTFA | Native RTF | Wall RTF | Max gap | Avg gap | Cb | Native | VE | Vocoder | Init\n")
                    for (r in results) {
                        sb.append(String.format(Locale.US,
                            "%d | %.0fms | %.3f | %.3f | %.0fms | %.0fms | %d | %.3fs | %.0fms | %.0fms | %.0fms\n",
                            r.threads, r.ttfaMs, r.rtf, r.wallRtf, r.maxCallbackGapMs, r.avgCallbackGapMs,
                            r.callbacks, r.nativeMs / 1000.0, r.veMs, r.vocoderMs, r.engineInitMs))
                    }
                    if (best != null) {
                        sb.append("\nBEST (체감 우선): ${best.threads} threads · TTFA ${String.format(Locale.US, "%.0f", best.ttfaMs)}ms · Max gap ${String.format(Locale.US, "%.0f", best.maxCallbackGapMs)}ms · RTF ${String.format(Locale.US, "%.3f", best.rtf)}")
                    }
                    sb.append("\n\nNative profile gap/TTFA도 내부적으로 함께 기록됩니다. 위 TTFA/Gap은 실제 Kotlin callback 도착 시각 기준이라 시스템 TTS 체감 비교에 더 직접적입니다.")
                    rtfView.text = sb.toString()
                    status.text = "스트리밍 벤치마크 완료${best?.let { " · 체감 최적 ${it.threads} threads" } ?: ""}"
                    threadsSpinner.setSelection(threadCounts.indexOf(originalThreads).coerceAtLeast(0))
                    synthesizer?.close(); synthesizer = null
                }
            }
        }
    }

    private fun startSynthesis() {
        if (!ModelManager.areTtsModelsReady(this)) { ensureModel(); Toast.makeText(this, "모델 준비가 먼저 필요합니다.", Toast.LENGTH_SHORT).show(); return }
        persistUiSettings(); player?.stop(); player?.release(); player = null
        // Coroutine cancellation cannot interrupt a blocking JNI call. Explicitly signal
        // the native engine as well, otherwise a newly launched synthesis can sit on the
        // JNI mutex waiting for the previous cancelled job to finish.
        if (synthJob?.isActive == true) synthesizer?.stop()
        synthJob?.cancel()
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
            val requestStartNs = System.nanoTime()
            var workerDispatchWaitMs = 0.0
            var engineAcquireMs = 0.0
            var nativeSettingsMs = 0.0
            var sdkSynthesizeMs = 0.0
            var speedDispatchWaitMs = 0.0
            runCatching {
                val value = withContext(Dispatchers.Default) {
                    workerDispatchWaitMs = (System.nanoTime() - requestStartNs) / 1_000_000.0
                    val engineStartNs = System.nanoTime()
                    val synth = getOrCreateSynthesizer(ModelManager.modelDir(applicationContext).absolutePath)
                    engineAcquireMs = (System.nanoTime() - engineStartNs) / 1_000_000.0

                    val settingsStartNs = System.nanoTime()
                    synth.setVoice(voice); synth.setSpeed(1.0f); synth.setTotalSteps(stepCount); synth.setPreGeneration(TtsSettings.preGeneration(applicationContext))
                    synth.setPreGenerationQueue(preGenerationQueue())
                    synth.setChunkGap(currentChunkGapMin(), currentChunkGapMax())
                    synth.setTrailingSilenceTrimMs(currentTrailingTrim())
                    nativeSettingsMs = (System.nanoTime() - settingsStartNs) / 1_000_000.0

                    val sdkStartNs = System.nanoTime()
                    val result = synth.synthesize(text, lang)
                    sdkSynthesizeMs = (System.nanoTime() - sdkStartNs) / 1_000_000.0
                    result
                }

                val speedDispatchStartNs = System.nanoTime()
                val adjusted = withContext(Dispatchers.Default) {
                    speedDispatchWaitMs = (System.nanoTime() - speedDispatchStartNs) / 1_000_000.0
                    AudioSpeedProcessor.apply(value.pcm16, value.sampleRate, speed)
                }
                val preWavElapsed = (System.nanoTime() - requestStartNs) / 1_000_000_000.0
                val duration = adjusted.pcm16.size / 2.0 / value.sampleRate
                val profile = parseProfile(value.profile)
                val fallback = (profile["accelerator_fallback"] as? String)
                    ?.takeUnless { it == "none" }
                val nativeTotal = (profile["total"] as? Double)?.div(1000.0) ?: preWavElapsed
                val wav = File(filesDir, "generated.wav")
                val wavStartNs = System.nanoTime()
                writeWav(wav, adjusted.pcm16, value.sampleRate, 1)
                val wavWriteMs = (System.nanoTime() - wavStartNs) / 1_000_000.0
                val totalToWav = (System.nanoTime() - requestStartNs) / 1_000_000_000.0
                withContext(Dispatchers.Main) {
                    status.text = if (fallback == null) {
                        "생성 완료 · 자동 재생 · ${String.format(Locale.US, "%.2f", duration)}초"
                    } else {
                        "가속 출력 오류 → CPU 자동 복구 완료 · 자동 재생 · ${String.format(Locale.US, "%.2f", duration)}초"
                    }
                    rtfView.text = formatProfile(
                        profile = profile,
                        elapsed = preWavElapsed,
                        rtf = if (duration > 0) preWavElapsed / duration else 0.0,
                        duration = duration,
                        nativeTotal = nativeTotal,
                        speedProcessMs = adjusted.processingMs,
                        appliedSpeed = speed,
                        appliedVoice = voice,
                        appliedSteps = stepCount,
                        lang = lang,
                        workerDispatchWaitMs = workerDispatchWaitMs,
                        engineAcquireMs = engineAcquireMs,
                        nativeSettingsMs = nativeSettingsMs,
                        sdkSynthesizeMs = sdkSynthesizeMs,
                        speedDispatchWaitMs = speedDispatchWaitMs,
                        wavWriteMs = wavWriteMs,
                        totalToWav = totalToWav,
                    )
                    playButton.apply { isEnabled = true; playLast() }
                }
            }.onFailure { e -> withContext(Dispatchers.Main) { status.text = "음성 생성 실패:\n${e.message ?: e.javaClass.simpleName}" } }
        }
    }

    private fun getOrCreateSynthesizer(dir: String): SpeechSynthesizer {
        synthesizer?.let { return it }
        val started = TimeSource.Monotonic.markNow()
        return SpeechSynthesizer(SpeechSynthesizerConfig(
            modelDir = dir, voiceId = currentVoice(), speed = 1.0f, totalSteps = currentSteps(), numThreads = currentThreads(), chunkCap = currentChunkCap(), useNnapi = false, backend = currentBackend(),
            preGenerationQueue = preGenerationQueue(), chunkGapMinMs = currentChunkGapMin(), chunkGapMaxMs = currentChunkGapMax(), trailingSilenceTrimMs = currentTrailingTrim(),
            nativeLibraryDir = applicationInfo.nativeLibraryDir,
            acceleratorCacheDir = File(cacheDir, "accelerator_cache").apply { mkdirs() }.absolutePath
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
                              speedProcessMs: Double, appliedSpeed: Float, appliedVoice: String, appliedSteps: Int, lang: String,
                              workerDispatchWaitMs: Double, engineAcquireMs: Double, nativeSettingsMs: Double,
                              sdkSynthesizeMs: Double, speedDispatchWaitMs: Double, wavWriteMs: Double,
                              totalToWav: Double): String {
        fun ms(key: String) = String.format(Locale.US, "%.1f ms", (profile[key] as? Double ?: 0.0))
        val stepValues = profile["ve_steps"] as? List<*> ?: emptyList<Any>()
        val sb = StringBuilder("PERFORMANCE PROFILE\n")
        sb.append("Engine init            ").append(String.format(Locale.US, "%.3f s", engineInitSeconds)).append('\n')
        sb.append("Duration Predictor     ").append(ms("dp")).append('\n')
        sb.append("Text Encoder           ").append(ms("encoder")).append('\n')
        stepValues.forEachIndexed { i, v -> sb.append("VE Step ${i + 1}".padEnd(23)).append(String.format(Locale.US, "%.1f ms", (v as? Double ?: 0.0))).append('\n') }
        sb.append("Vocoder                ").append(ms("vocoder")).append('\n')
        sb.append("Tensor buffer/copy     ").append(ms("tensor_copy")).append('\n')
        sb.append("Core chunking          ").append(ms("chunking")).append('\n')
        sb.append("Core token process     ").append(ms("token_process")).append('\n')
        sb.append("Core latent setup      ").append(ms("latent_setup")).append('\n')
        sb.append("Core append/crossfade  ").append(ms("append")).append('\n')
        sb.append("Core stream emit       ").append(ms("stream_emit")).append('\n')
        sb.append("Core pregen cleanup    ").append(ms("pregen_cleanup")).append('\n')
        sb.append("Core final postprocess ").append(ms("final_postprocess")).append('\n')
        sb.append("Native total           ").append(String.format(Locale.US, "%.3f s", nativeTotal)).append('\n')
        sb.append("End-to-end              ").append(String.format(Locale.US, "%.3f s", elapsed)).append('\n')
        sb.append("Audio duration          ").append(String.format(Locale.US, "%.3f s", duration)).append('\n')
        sb.append("Native RTF              ").append(String.format(Locale.US, "%.3f", if (duration > 0) nativeTotal / duration else 0.0)).append('\n')
        sb.append("End-to-end RTF          ").append(String.format(Locale.US, "%.3f", rtf)).append('\n')
        sb.append("--- DIAGNOSTIC BREAKDOWN ---\n")
        sb.append("Worker dispatch wait   ").append(String.format(Locale.US, "%.1f ms", workerDispatchWaitMs)).append('\n')
        sb.append("Engine acquire/init     ").append(String.format(Locale.US, "%.1f ms", engineAcquireMs)).append('\n')
        sb.append("Native settings JNI     ").append(String.format(Locale.US, "%.1f ms", nativeSettingsMs)).append('\n')
        sb.append("SDK synthesize call     ").append(String.format(Locale.US, "%.1f ms", sdkSynthesizeMs)).append('\n')
        sb.append("JNI mutex wait          ").append(ms("jni_lock_wait")).append('\n')
        sb.append("JNI arg conversion      ").append(ms("jni_arg_convert")).append('\n')
        sb.append("JNI core call           ").append(ms("jni_core")).append('\n')
        sb.append("JNI PCM f32->s16        ").append(ms("jni_pcm_convert")).append('\n')
        sb.append("JNI ByteArray alloc     ").append(ms("jni_bytearray_alloc")).append('\n')
        sb.append("JNI ByteArray copy      ").append(ms("jni_bytearray_copy")).append('\n')
        sb.append("JNI total               ").append(ms("jni_total")).append('\n')
        sb.append("JNI PCM samples         ").append(profile["jni_pcm_samples"] ?: "?").append('\n')
        sb.append("Speed dispatch wait     ").append(String.format(Locale.US, "%.1f ms", speedDispatchWaitMs)).append('\n')
        sb.append("WAV write               ").append(String.format(Locale.US, "%.1f ms", wavWriteMs)).append('\n')
        sb.append("Total incl. WAV         ").append(String.format(Locale.US, "%.3f s", totalToWav)).append('\n')
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
        sb.append("Backend report          ").append(profile["backend"] ?: currentBackend().name).append('\n')
        sb.append("Requested backend       ").append(profile["requested_backend"] ?: currentBackend().name).append('\n')
        sb.append("Active backend          ").append(profile["active_backend"] ?: currentBackend().name).append('\n')
        sb.append("Fallback reason         ").append(profile["accelerator_fallback"] ?: "none").append('\n')
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
    override fun onDestroy() { benchmarkJob?.cancel(); stopAll(); synthesizer?.close(); synthesizer = null; scope.cancel(); super.onDestroy() }
}
