package com.lsh.kcal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.LocalDate
import java.time.YearMonth

/** 색 모음 (밝은 화면 / 어두운 화면) */
data class Palette(
    val bg: Int, val fg: Int, val muted: Int, val line: Int, val outFg: Int,
    val sun: Int, val sat: Int, val accent: Int, val accentFg: Int, val selBg: Int, val late: Int,
) {
    companion object {
        val LIGHT = Palette(
            bg = 0xFFFFFFFF.toInt(), fg = 0xFF17212B.toInt(), muted = 0xFF6B7684.toInt(), line = 0xFFE3E7EC.toInt(),
            outFg = 0xFFB7BEC7.toInt(), sun = 0xFFD63A3A.toInt(), sat = 0xFF2F5FD0.toInt(), accent = 0xFF3456C8.toInt(),
            accentFg = 0xFFFFFFFF.toInt(), selBg = 0xFFEEF2FB.toInt(), late = 0xFFD63A3A.toInt(),
        )
        val DARK = Palette(
            bg = 0xFF141A21.toInt(), fg = 0xFFE6EDF3.toInt(), muted = 0xFF98A4B0.toInt(), line = 0xFF2A333D.toInt(),
            outFg = 0xFF4F5A66.toInt(), sun = 0xFFFF7A7A.toInt(), sat = 0xFF7FA6FF.toInt(), accent = 0xFF7A9BFF.toInt(),
            accentFg = 0xFF0E1420.toInt(), selBg = 0xFF1E2833.toInt(), late = 0xFFFF7A7A.toInt(),
        )
    }
}

/**
 * 한 달 달력 그리기 — 앱 화면과 홈 화면 위젯이 같이 씀
 * (관리 시스템 캘린더처럼: 칸마다 일정 막대, 공휴일 빨강, 오늘 동그라미, 안 끝난 일 위로)
 */
class MonthRenderer(private val density: Float, private val pal: Palette) {
    private fun dp(v: Float) = v * density

    private val pNum = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint().apply { strokeWidth = 1f }

    /** 그린 뒤 터치 위치 → 날짜 계산용 */
    val hits = mutableListOf<Pair<RectF, Item>>()
    var top = 0f; var cellW = 0f; var cellH = 0f; var weeks = 6; var start: LocalDate = LocalDate.now()

    fun draw(
        c: Canvas, w: Float, h: Float, ym: YearMonth, byDate: Map<String, List<Item>>,
        selected: LocalDate?, showTitle: Boolean, compact: Boolean,
    ) {
        c.drawColor(pal.bg)
        hits.clear()
        var y = 0f
        if (showTitle) {
            val th = dp(if (compact) 34f else 44f)
            pText.color = pal.fg; pText.textSize = dp(if (compact) 16f else 20f); pText.typeface = Typeface.DEFAULT_BOLD
            val t = "${ym.year}. ${ym.monthValue}."
            val tw = pText.measureText(t)
            c.drawText(t, (w - tw) / 2, th / 2 + pText.textSize * 0.36f, pText)
            pText.color = pal.muted; pText.textSize = dp(if (compact) 18f else 22f); pText.typeface = Typeface.DEFAULT
            c.drawText("‹", dp(16f), th / 2 + pText.textSize * 0.36f, pText)
            c.drawText("›", w - dp(16f) - pText.measureText("›"), th / 2 + pText.textSize * 0.36f, pText)
            y += th
        }
        // 요일 줄
        val wh = dp(if (compact) 18f else 24f)
        val names = arrayOf("일", "월", "화", "수", "목", "금", "토")
        cellW = w / 7f
        pText.textSize = dp(if (compact) 10.5f else 12f); pText.typeface = Typeface.DEFAULT_BOLD
        for (i in 0..6) {
            pText.color = when (i) { 0 -> pal.sun; 6 -> pal.sat; else -> pal.muted }
            val tw = pText.measureText(names[i])
            c.drawText(names[i], i * cellW + (cellW - tw) / 2, y + wh / 2 + pText.textSize * 0.36f, pText)
        }
        y += wh
        pLine.color = pal.line
        c.drawLine(0f, y, w, y, pLine)

        val first = ym.atDay(1)
        val lead = first.dayOfWeek.value % 7 // 일요일 0
        start = first.minusDays(lead.toLong())
        weeks = (lead + ym.lengthOfMonth() + 6) / 7
        top = y
        cellH = (h - y) / weeks
        val today = LocalDate.now()

        val numSize = dp(if (compact) 11f else 13f)
        val barH = dp(if (compact) 13f else 17f)
        val barText = dp(if (compact) 9f else 11f)
        val gap = dp(if (compact) 1.5f else 2f)
        val numH = dp(if (compact) 16f else 22f)
        val pad = dp(if (compact) 1.5f else 2.5f)

        for (i in 0 until weeks * 7) {
            val d = start.plusDays(i.toLong())
            val col = i % 7; val row = i / 7
            val x0 = col * cellW; val y0 = top + row * cellH
            val inMonth = d.month == ym.month
            val key = d.toString()
            val list = byDate[key].orEmpty()
            val hol = list.firstOrNull { it.isHoliday }

            if (selected != null && d == selected) { pFill.color = pal.selBg; c.drawRect(x0, y0, x0 + cellW, y0 + cellH, pFill) }
            // 날짜 숫자
            pNum.textSize = numSize
            pNum.color = when {
                !inMonth -> pal.outFg
                hol != null || col == 0 -> pal.sun
                col == 6 -> pal.sat
                else -> pal.fg
            }
            val num = d.dayOfMonth.toString()
            val nx = x0 + dp(4f); val ny = y0 + numH * 0.72f
            if (d == today) {
                val r = numSize * 0.85f
                val cx = nx + pNum.measureText(num) / 2
                pFill.color = pal.accent
                c.drawCircle(cx, ny - numSize * 0.36f, r, pFill)
                pNum.color = pal.accentFg
            }
            c.drawText(num, nx, ny, pNum)
            if (hol != null && inMonth) {
                pText.typeface = Typeface.DEFAULT; pText.textSize = barText * 0.95f; pText.color = pal.sun
                val hx = nx + pNum.measureText(num) + dp(if (d == today) 7f else 3f)
                drawClip(c, hol.title, hx, ny, x0 + cellW - dp(2f) - hx)
            }

            // 일정 막대
            val bars = list.filter { !it.isHoliday }
            val room = ((cellH - numH - pad) / (barH + gap)).toInt().coerceAtLeast(0)
            val show = if (bars.size > room) (room - 1).coerceAtLeast(0) else bars.size
            var by = y0 + numH
            for (k in 0 until show) {
                val it = bars[k]
                val r = RectF(x0 + pad, by, x0 + cellW - pad, by + barH)
                hits.add(RectF(r) to it)
                val a = if (it.done || !inMonth) 0.5f else 1f
                pFill.color = withAlpha(it.bg, a)
                c.drawRoundRect(r, dp(3f), dp(3f), pFill)
                if (it.late && !it.done) { pFill.color = pal.late; c.drawRect(r.left, r.top, r.left + dp(2.5f), r.bottom, pFill) }
                pText.typeface = if (it.done) Typeface.DEFAULT else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                pText.textSize = barText; pText.color = withAlpha(it.fg, if (it.done) 0.75f else 1f)
                pText.isStrikeThruText = it.done
                drawClip(c, it.label, r.left + dp(3f), r.top + barH / 2 + barText * 0.36f, r.width() - dp(5f))
                pText.isStrikeThruText = false
                by += barH + gap
            }
            if (bars.size > show) {
                pText.typeface = Typeface.DEFAULT_BOLD; pText.textSize = barText; pText.color = pal.muted
                c.drawText("+${bars.size - show}", x0 + pad + dp(2f), by + barText, pText)
            }
            // 칸 구분선
            c.drawLine(x0, y0 + cellH, x0 + cellW, y0 + cellH, pLine)
        }
    }

    /** 터치한 위치의 일정 막대 (조금 넉넉하게) */
    fun itemAt(x: Float, y: Float): Item? = hits.firstOrNull { (r, _) -> x >= r.left && x <= r.right && y >= r.top - dp(1.5f) && y <= r.bottom + dp(1.5f) }?.second

    /** 터치한 위치의 날짜 */
    fun dateAt(x: Float, y: Float): LocalDate? {
        if (y < top || cellW <= 0 || cellH <= 0) return null
        val col = (x / cellW).toInt().coerceIn(0, 6)
        val row = ((y - top) / cellH).toInt()
        if (row !in 0 until weeks) return null
        return start.plusDays((row * 7 + col).toLong())
    }

    private fun drawClip(c: Canvas, s: String, x: Float, y: Float, maxW: Float) {
        if (maxW <= 0) return
        if (pText.measureText(s) <= maxW) { c.drawText(s, x, y, pText); return }
        val n = pText.breakText(s, true, maxW, null)
        c.drawText(s, 0, n.coerceAtLeast(0), x, y, pText)
    }

    private fun withAlpha(color: Int, a: Float): Int {
        val al = ((color ushr 24) * a).toInt().coerceIn(0, 255)
        return (al shl 24) or (color and 0x00FFFFFF)
    }
}
