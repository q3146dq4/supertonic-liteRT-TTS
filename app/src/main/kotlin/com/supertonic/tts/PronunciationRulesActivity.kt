package com.supertonic.tts

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import audio.soniqo.speech.rules.PronunciationRules

/** Full in-app pronunciation/regex manager. JSON import/export remains available. */
class PronunciationRulesActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var count: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = "정규식 설정"; textSize = 28f; setTypeface(null, android.graphics.Typeface.BOLD) })
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Button(this).apply { text = "←"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        top.addView(Button(this).apply { text = "+ 정규식 추가"; setOnClickListener { editRule(-1, null) } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val actionLp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val actionLp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        actionLp2.setMargins(8, 0, 0, 0)
        actions.addView(Button(this).apply { text = "기본값"; setSingleLine(true); setOnClickListener { resetDefaults() } }, actionLp1)
        actions.addView(Button(this).apply { text = "내보내기"; setSingleLine(true); setOnClickListener { exportDialog() } }, actionLp2)
        root.addView(actions)
        count = TextView(this).apply { textSize = 16f; setPadding(4, 16, 4, 4) }
        root.addView(count)
        root.addView(TextView(this).apply {
            text = "위에서부터 순서대로 적용됩니다. 화면의 원문은 바꾸지 않고 TTS에 전달되는 텍스트에만 적용됩니다."
            textSize = 14f
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val rules = PronunciationRules.load(this)
        count.text = "총 ${rules.size}개 규칙"
        rules.forEachIndexed { i, rule ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16)
                setBackgroundColor(0xFFF3F3F3.toInt())
            }
            card.addView(TextView(this).apply { text = "${i + 1}. ${if (rule.isRegex) "정규식" else "문자열 치환"}"; textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD) })
            card.addView(TextView(this).apply { text = "패턴: ${rule.term}"; textSize = 15f; setPadding(0, 8, 0, 0) })
            card.addView(TextView(this).apply { text = "치환: ${if (rule.replacement.isEmpty()) "(삭제)" else rule.replacement}"; textSize = 15f })
            card.addView(TextView(this).apply { text = "${if (rule.isRegex) "정규식" else "문자열"} · ${if (rule.ignoreCase) "대소문자 구분 안 함" else "대소문자 구분"} · ${if (rule.enabled) "사용" else "꺼짐"}"; textSize = 13f })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Button(this@PronunciationRulesActivity).apply { text = "↑"; setOnClickListener { PronunciationRules.move(this@PronunciationRulesActivity, i, -1); refresh() } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            buttons.addView(Button(this@PronunciationRulesActivity).apply { text = "↓"; setOnClickListener { PronunciationRules.move(this@PronunciationRulesActivity, i, 1); refresh() } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            buttons.addView(Button(this@PronunciationRulesActivity).apply { text = "${if (rule.enabled) "사용 중" else "꺼짐"}"; setOnClickListener { PronunciationRules.update(this@PronunciationRulesActivity, i, rule.copy(enabled = !rule.enabled)); refresh() } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            buttons.addView(Button(this@PronunciationRulesActivity).apply { text = "편집"; setOnClickListener { editRule(i, rule) } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            buttons.addView(Button(this@PronunciationRulesActivity).apply { text = "삭제"; setOnClickListener { confirmDelete(i) } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(buttons)
            val lp = LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 8, 0, 8); list.addView(card, lp)
        }
    }

    private fun editRule(index: Int, existing: PronunciationRules.Rule?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 8, 30, 0) }
        val term = EditText(this).apply { hint = "패턴"; setSingleLine(false); setText(existing?.term.orEmpty()) }
        val replacement = EditText(this).apply { hint = "치환 (비워두면 삭제)"; setText(existing?.replacement.orEmpty()) }
        val regex = CheckBox(this).apply { text = "정규식"; isChecked = existing?.isRegex ?: true }
        val ignore = CheckBox(this).apply { text = "대소문자 구분 안 함"; isChecked = existing?.ignoreCase ?: true }
        box.addView(term); box.addView(replacement); box.addView(regex); box.addView(ignore)
        AlertDialog.Builder(this).setTitle(if (index < 0) "정규식 추가" else "정규식 편집").setView(box)
            .setNegativeButton("취소", null).setPositiveButton("저장") { _, _ ->
                val r = PronunciationRules.Rule(term.text.toString(), replacement.text.toString(), ignore.isChecked, regex.isChecked, existing?.enabled ?: true)
                val ok = if (index < 0) PronunciationRules.add(this, r) else PronunciationRules.update(this, index, r)
                if (!ok) Toast.makeText(this, "저장 실패", Toast.LENGTH_SHORT).show()
                refresh()
            }.show()
    }

    private fun confirmDelete(index: Int) {
        AlertDialog.Builder(this).setTitle("규칙 삭제").setMessage("이 규칙을 삭제할까요?")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> PronunciationRules.delete(this, index); refresh() }.show()
    }

    private fun resetDefaults() {
        AlertDialog.Builder(this).setTitle("기본값으로 복원").setMessage("현재 정규식 설정을 모두 지우고 기본값으로 복원합니다.")
            .setNegativeButton("취소", null).setPositiveButton("복원") { _, _ ->
                PronunciationRules.save(this, listOf(
                    PronunciationRules.Rule("커버\\s*(?:접기/보기)", "", true, true),
                    PronunciationRules.Rule("[一-龥]", "", true, true),
                    PronunciationRules.Rule("[a-zA-Z0-9]{15,}", "", true, true)
                ))
                refresh()
            }.show()
    }

    private fun exportDialog() {
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"; putExtra(android.content.Intent.EXTRA_TITLE, "supertonic-pronunciation-rules.json")
        }
        startActivityForResult(intent, 7001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7001 && resultCode == RESULT_OK && data?.data != null) runCatching {
            contentResolver.openOutputStream(data.data!!)?.use { it.write(PronunciationRules.toJson(this).toString(2).toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "규칙 JSON 저장 완료", Toast.LENGTH_SHORT).show()
        }
    }
}
