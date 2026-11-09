package com.lsh.kcal

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var st: Store
    private lateinit var pal: Palette
    private lateinit var monthView: MonthView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private var dayDlg: android.app.Dialog? = null
    private val io = Executors.newSingleThreadExecutor()
    private var month: YearMonth = YearMonth.now()
    private var sel: LocalDate = LocalDate.now()
    private var byDate: Map<String, List<Item>> = emptyMap()
    private var loading = false

    private val CATS = listOf("auto" to "자동 (제목으로 판단)", "meet" to "출고·미팅 (빨강)", "call" to "연락 (연한 파랑)", "duty" to "당직 (보라)", "rent" to "월세 (금색)", "normal" to "기본 (파랑)")

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Store(this)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        pal = if (night) Palette.DARK else Palette.LIGHT
        intent?.getStringExtra("month")?.let { runCatching { month = YearMonth.parse(it) } }
        buildUi()
        byDate = st.byDate(); render()
        Sync.schedule(this)
        if (st.token.isBlank()) askToken(first = true) else refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("month")?.let { runCatching { month = YearMonth.parse(it); render() } }
    }

    override fun onResume() {
        super.onResume()
        if (st.token.isNotBlank() && System.currentTimeMillis() - st.fetchedAt > 60_000) refresh()
    }

    // ---------- 화면 ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(4)) }
        bar.addView(iconBtn("‹") { month = month.minusMonths(1); render() })
        title = TextView(this).apply { textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.fg); gravity = Gravity.CENTER; setOnClickListener { pickMonth() } }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconBtn("›") { month = month.plusMonths(1); render() })
        bar.addView(textBtn("오늘") { month = YearMonth.now(); sel = LocalDate.now(); render() })
        bar.addView(iconBtn("⟳") { refresh(true) })
        bar.addView(iconBtn("⚙") { settings() })
        root.addView(bar)

        status = TextView(this).apply { textSize = 12f; setTextColor(pal.muted); setPadding(dp(14), 0, dp(14), dp(2)) }
        root.addView(status)

        monthView = MonthView(this, pal).apply {
            onPick = { d -> sel = d; if (YearMonth.from(d) != month) month = YearMonth.from(d); render(); showDay(d) }
            onItem = { item -> sel = LocalDate.parse(item.date); render(); itemMenu(item) }
            onSwipe = { n -> month = month.plusMonths(n.toLong()); render() }
        }
        // 달력은 화면 가득 + 오른쪽 아래 [+] 버튼 (관리 시스템 캘린더처럼)
        val frame = FrameLayout(this)
        frame.addView(monthView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(TextView(this).apply {
            text = "+"; textSize = 30f; setTextColor(pal.accentFg); gravity = Gravity.CENTER
            background = round(pal.accent, dp(30).toFloat()); elevation = dp(6).toFloat()
            contentDescription = "일정 추가"; setOnClickListener { editEvent(null) }
        }, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(22)) })
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun iconBtn(s: String, f: () -> Unit) = TextView(this).apply {
        text = s; textSize = 24f; setTextColor(pal.fg); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)); setOnClickListener { f() }
    }
    private fun textBtn(s: String, f: () -> Unit) = TextView(this).apply {
        text = s; textSize = 14f; setTextColor(pal.accent); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)); setOnClickListener { f() }
    }
    private fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }

    private fun render() {
        title.text = "${month.year}년 ${month.monthValue}월"
        monthView.byDate = byDate; monthView.month = month; monthView.selected = sel
        val syn = st.syncedAt.takeIf { it.length >= 16 }?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")) }.getOrNull() }
        status.text = buildString {
            if (loading) append("불러오는 중… ")
            if (syn != null) append("관리 시스템 마지막 반영 ${syn.monthValue}/${syn.dayOfMonth} ${"%02d:%02d".format(syn.hour, syn.minute)}")
            if (st.pending > 0) append(" · 관리 시스템 반영 대기 ${st.pending}건")
        }
        MonthWidget.updateAll(this)
    }

    /** 아래에서 올라오는 창 (관리 시스템 웹페이지의 팝업처럼) */
    private fun sheet(title: String, sub: String?, content: View, buttons: List<Pair<String, () -> Unit>> = emptyList(), accent: Int? = null): android.app.Dialog {
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(pal.bg); cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f) }
            setPadding(dp(20), dp(10), dp(20), dp(20))
        }
        box.addView(View(this).apply { background = round(pal.line, dp(3).toFloat()) }, LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        val hd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (accent != null) hd.addView(View(this).apply { background = round(accent, dp(4).toFloat()) }, LinearLayout.LayoutParams(dp(8), dp(28)).apply { rightMargin = dp(10) })
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tcol.addView(TextView(this).apply { text = title; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.fg) })
        if (!sub.isNullOrBlank()) tcol.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(pal.muted) })
        hd.addView(tcol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hd.addView(TextView(this).apply { text = "닫기"; textSize = 15f; setTextColor(pal.muted); setPadding(dp(12), dp(8), 0, dp(8)); setOnClickListener { dlg.dismiss() } })
        box.addView(hd)
        val sc = ScrollView(this).apply { addView(content) }
        box.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(10) })
        if (buttons.isNotEmpty()) {
            val br = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
            buttons.forEachIndexed { i, (label, f) ->
                val primary = i == buttons.lastIndex
                br.addView(TextView(this).apply {
                    text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (primary) pal.accentFg else if (label.contains("삭제")) pal.sun else pal.fg)
                    background = if (primary) round(pal.accent, dp(12).toFloat()) else GradientDrawable().apply { setColor(pal.selBg); cornerRadius = dp(12).toFloat() }
                    setOnClickListener { dlg.dismiss(); f() }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
            box.addView(br)
        }
        dlg.setContentView(box)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            attributes = attributes.apply { dimAmount = 0.45f }
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        box.maxHeightCap(resources.displayMetrics.heightPixels * 85 / 100)
        dlg.show()
        return dlg
    }
    private fun LinearLayout.maxHeightCap(px: Int) {
        viewTreeObserver.addOnGlobalLayoutListener { if (height > px) { layoutParams = layoutParams?.apply { height = px }; requestLayout() } }
    }

    /** 날짜 누르면 그날 일정 창 */
    private fun showDay(d: LocalDate) {
        dayDlg?.dismiss()
        val list = byDate[d.toString()].orEmpty()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.filter { it.isHoliday }.forEach { h ->
            box.addView(TextView(this).apply { text = "🎌 ${h.title}"; setTextColor(pal.sun); textSize = 14f; setPadding(0, 0, 0, dp(6)) })
        }
        val rest = list.filter { !it.isHoliday }
        if (rest.isEmpty()) box.addView(TextView(this).apply { text = "일정이 없어요."; setTextColor(pal.muted); textSize = 15f; setPadding(0, dp(10), 0, dp(10)) })
        rest.forEach { box.addView(itemRow(it)) }
        val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        dayDlg = sheet("${d.monthValue}월 ${d.dayOfMonth}일 ($dow)", if (rest.isEmpty()) null else "일정 ${rest.size}개", box, listOf("+ 일정 추가" to { editEvent(null) }))
    }

    private fun itemRow(it: Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8)); setOnClickListener { _ -> dayDlg?.dismiss(); itemMenu(it) }
        }
        val strip = View(this).apply { background = round(it.bg, dp(3).toFloat()); alpha = if (it.done) 0.5f else 1f }
        row.addView(strip, LinearLayout.LayoutParams(dp(6), dp(46)).apply { rightMargin = dp(10) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val kindTag = when (it.kind) { "todo" -> if (it.late) "📞 연락 (지연) · " else "📞 연락 · "; "done" -> "✓ 연락함 · "; else -> "" }
        col.addView(TextView(this).apply {
            text = kindTag + it.label; textSize = 16f; setTextColor(if (it.done) pal.muted else pal.fg); setTypeface(typeface, Typeface.BOLD)
            if (it.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        })
        if (it.memo.isNotBlank()) col.addView(TextView(this).apply { text = it.memo; textSize = 13f; setTextColor(pal.muted); maxLines = 2 })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (it.phone.isNotBlank()) row.addView(iconBtn("📞") { dial(it.phone) })
        return row
    }

    // ---------- 동작 ----------
    private fun refresh(manual: Boolean = false) {
        if (loading) return
        loading = true; render()
        io.execute {
            val err = runCatching { Api.refresh(this) }.exceptionOrNull()
            runOnUiThread {
                loading = false
                byDate = st.byDate(); render()
                if (err != null) {
                    if ((err as? Api.ApiError)?.code == 401) askToken(first = false)
                    else if (manual) toast(err.message ?: "불러오지 못했어요")
                } else if (manual) toast("새로 불러왔어요")
            }
        }
    }

    private fun send(body: JSONObject, ok: String) {
        io.execute {
            val err = runCatching { Api.call(st.token, body); Api.refresh(this) }.exceptionOrNull()
            runOnUiThread {
                byDate = st.byDate(); render()
                toast(err?.message ?: ok)
                if (!isFinishing) showDay(sel)
            }
        }
    }

    /** 일정 누르면 자세히 보기 창 */
    private fun itemMenu(it: Item) {
        val d = LocalDate.parse(it.date)
        val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun line(k: String, v: String, onTap: (() -> Unit)? = null) {
            if (v.isBlank()) return
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(7)) }
            r.addView(TextView(this).apply { text = k; textSize = 14f; setTextColor(pal.muted) }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            r.addView(TextView(this).apply {
                text = v; textSize = 15.5f; setTextColor(if (onTap != null) pal.accent else pal.fg); setTextIsSelectable(onTap == null)
                if (onTap != null) { setTypeface(typeface, Typeface.BOLD); setOnClickListener { onTap() } }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(r)
        }
        val kind = when { it.kind == "todo" -> if (it.late) "가망 고객 연락 (지연)" else "가망 고객 연락"; it.kind == "done" -> "연락 기록"
            else -> when (it.cat) { "meet" -> "출고·미팅"; "call" -> "연락 · 고객"; "duty" -> "당직"; "rent" -> "월세"; else -> "개인 일정" } }
        line("날짜", "${d.year}. ${d.monthValue}. ${d.dayOfMonth}. ($dow)")
        line("시간", it.time.ifEmpty { if (it.isEvent) "종일" else "" })
        line("종류", kind + if (it.repeat == "monthly") " · 매월 반복" else "")
        line("상태", if (it.done) "✓ 완료" else if (it.isEvent || it.kind == "todo") "진행 전" else "")
        line("연락처", it.phone) { dial(it.phone) }
        line("메모", it.memo)

        val btns = mutableListOf<Pair<String, () -> Unit>>()
        when {
            it.isEvent -> {
                btns += "삭제" to { confirmDelete(it) }
                btns += "수정" to { editEvent(it) }
                btns += (if (it.done) "완료 취소" else "✓ 완료") to { send(JSONObject().put("action", "done").put("ref", it.ref).put("date", it.date).put("done", !it.done), if (it.done) "완료를 취소했어요" else "완료했어요") }
            }
            it.kind == "todo" -> {
                btns += "날짜 옮기기" to { pickDate(d) { nd -> send(JSONObject().put("action", "pmove").put("ref", it.ref).put("date", nd.toString()), "${nd.monthValue}/${nd.dayOfMonth}로 옮겼어요") } }
                btns += "✓ 연락 완료" to { prospectDone(it) }
            }
        }
        if (it.phone.isNotBlank() && btns.size < 3) btns.add(0, "📞 전화" to { dial(it.phone) })
        dayDlg = sheet(it.title, null, box, btns, accent = it.bg)
    }

    private fun confirmDelete(it: Item) {
        AlertDialog.Builder(this).setTitle("일정 삭제").setMessage("'${it.label}' 일정을 지울까요?" + if (it.repeat == "monthly") "\n(매월 반복 일정 전체가 지워져요)" else "")
            .setPositiveButton("삭제") { _, _ -> send(JSONObject().put("action", "delete").put("ref", it.ref), "삭제했어요") }
            .setNegativeButton("취소", null).show()
    }

    private fun field(hint: String, value: String, multi: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); textSize = 16f
        inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT
        if (multi) { minLines = 2; maxLines = 5 }
    }
    private fun label(s: String) = TextView(this).apply { text = s; textSize = 13f; setTextColor(pal.muted); setPadding(0, dp(10), 0, dp(2)) }
    private fun form(vararg v: View): ScrollView {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        v.forEach { box.addView(it) }
        return ScrollView(this).apply { addView(box) }
    }

    /** 일정 추가·수정 */
    private fun editEvent(ev: Item?) {
        var date = ev?.let { x -> LocalDate.parse(x.date) } ?: sel
        var time = ev?.time ?: ""
        val dateBtn = Button(this).apply { isAllCaps = false }
        val timeBtn = Button(this).apply { isAllCaps = false }
        fun upd() {
            dateBtn.text = "${date.year}. ${date.monthValue}. ${date.dayOfMonth}. (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"
            timeBtn.text = if (time.isEmpty()) "시간 없음 (종일)" else time
        }
        upd()
        dateBtn.setOnClickListener { pickDate(date) { d -> date = d; upd() } }
        timeBtn.setOnClickListener {
            AlertDialog.Builder(this).setItems(arrayOf("시간 고르기", "시간 없음 (종일)")) { _, w ->
                if (w == 1) { time = ""; upd() }
                else {
                    val (h, m) = if (time.length == 5) time.substring(0, 2).toInt() to time.substring(3).toInt() else 10 to 0
                    TimePickerDialog(this, { _, hh, mm -> time = "%02d:%02d".format(hh, mm); upd() }, h, m, true).show()
                }
            }.show()
        }
        val t = field("일정 내용 (예: 조현익 출고)", ev?.title ?: "")
        val memo = field("메모 (선택)", ev?.memo ?: "", multi = true)
        val sp = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, CATS.map { c -> c.second }) }
        sp.setSelection(CATS.indexOfFirst { c -> c.first == (if (ev == null) "auto" else ev.cat) }.coerceAtLeast(0))
        val rep = CheckBox(this).apply { text = "매월 같은 날 반복"; isChecked = ev?.repeat == "monthly" }
        val dlg = AlertDialog.Builder(this).setTitle(if (ev == null) "일정 추가" else "일정 수정")
            .setView(form(label("날짜"), dateBtn, label("시간"), timeBtn, label("내용"), t, label("메모"), memo, label("종류 (색깔)"), sp, rep))
            .setPositiveButton("저장", null).setNegativeButton("취소", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = t.text.toString().trim()
                if (title.isEmpty()) { t.error = "내용을 적어 주세요"; return@setOnClickListener }
                val body = JSONObject().put("action", if (ev == null) "add" else "update")
                    .put("date", date.toString()).put("time", time).put("title", title)
                    .put("memo", memo.text.toString().trim()).put("cat", CATS[sp.selectedItemPosition].first)
                    .put("repeat", if (rep.isChecked) "monthly" else "")
                if (ev != null) body.put("ref", ev.ref)
                sel = date; month = YearMonth.from(date)
                send(body, if (ev == null) "추가했어요" else "저장했어요")
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    /** 가망 고객 연락 완료 + 다음 연락일 */
    private fun prospectDone(it: Item) {
        var next: LocalDate? = null
        val txt = field("어떤 이야기를 나눴나요 (선택)", "", multi = true)
        val nextBtn = Button(this).apply { isAllCaps = false; text = "다음 연락일 없음" }
        fun upd() { nextBtn.text = next?.let { d -> "다음 연락: ${d.monthValue}/${d.dayOfMonth} (${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})" } ?: "다음 연락일 없음" }
        nextBtn.setOnClickListener {
            AlertDialog.Builder(this).setItems(arrayOf("내일", "3일 뒤", "1주 뒤", "2주 뒤", "한 달 뒤", "날짜 고르기", "없음")) { _, w ->
                val t = LocalDate.now()
                when (w) {
                    0 -> next = t.plusDays(1); 1 -> next = t.plusDays(3); 2 -> next = t.plusWeeks(1); 3 -> next = t.plusWeeks(2); 4 -> next = t.plusMonths(1)
                    5 -> { pickDate(next ?: t.plusDays(1)) { d -> next = d; upd() }; return@setItems }
                    else -> next = null
                }
                upd()
            }.show()
        }
        val act = field("다음에 할 일 (선택)", "")
        AlertDialog.Builder(this).setTitle("${it.title} · 연락 완료")
            .setView(form(label("통화 내용"), txt, label("다음 연락"), nextBtn, label("다음에 할 일"), act))
            .setPositiveButton("저장") { _, _ ->
                val b = JSONObject().put("action", "pdone").put("ref", it.ref).put("date", it.date)
                    .put("text", txt.text.toString().trim()).put("nextAction", act.text.toString().trim())
                next?.let { d -> b.put("next", d.toString()) }
                send(b, next?.let { d -> "다음 연락: ${d.monthValue}/${d.dayOfMonth}" } ?: "연락 완료로 저장했어요")
            }.setNegativeButton("취소", null).show()
    }

    private fun pickDate(init: LocalDate, f: (LocalDate) -> Unit) {
        DatePickerDialog(this, { _, y, m, d -> f(LocalDate.of(y, m + 1, d)) }, init.year, init.monthValue - 1, init.dayOfMonth).show()
    }

    private fun pickMonth() {
        pickDate(sel) { d -> sel = d; month = YearMonth.from(d); render() }
    }

    private fun dial(phone: String) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { c -> c.isDigit() }))) }
    }

    private fun askToken(first: Boolean) {
        val e = field("연결 코드", st.token).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        AlertDialog.Builder(this).setTitle(if (first) "관리 시스템 연결" else "연결 코드 확인")
            .setMessage("관리 시스템 → 캘린더 아래 [📱 갤럭시 앱 연결]에 있는 연결 코드를 붙여넣어 주세요.")
            .setView(FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(e) })
            .setCancelable(!first)
            .setPositiveButton("연결") { _, _ -> st.token = e.text.toString(); refresh(true) }
            .apply { if (!first) setNegativeButton("취소", null) }
            .show()
    }

    private fun settings() {
        AlertDialog.Builder(this).setTitle("설정")
            .setItems(arrayOf("연결 코드 바꾸기", "지금 새로 불러오기", "앱 정보")) { _, w ->
                when (w) {
                    0 -> askToken(first = false)
                    1 -> refresh(true)
                    else -> AlertDialog.Builder(this).setTitle("관리 캘린더 1.3")
                        .setMessage("관리 시스템 달력을 휴대폰에서 보고 고치는 앱이에요.\n\n· 관리 시스템이 열릴 때마다 달력이 새로 맞춰져요.\n· 앱에서 추가·수정·완료한 내용은 앱과 위젯에 바로 보이고, 관리 시스템을 열면 그쪽에도 들어가요.\n· 홈 화면 위젯: 빈 곳 길게 누르기 → 위젯 → 관리 캘린더")
                        .setPositiveButton("닫기", null).show()
                }
            }.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Suppress("unused") private val unusedColor = Color.TRANSPARENT
}
