package com.lsh.kcal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** 앱 화면의 한 달 달력: 날짜 누르면 선택, 좌우로 밀면 달 이동 */
@SuppressLint("ViewConstructor")
class MonthView(ctx: Context, pal: Palette) : View(ctx) {
    private val renderer = MonthRenderer(resources.displayMetrics.density, pal)
    var month: YearMonth = YearMonth.now()
        set(v) { field = v; invalidate() }
    var selected: LocalDate? = LocalDate.now()
        set(v) { field = v; invalidate() }
    var byDate: Map<String, List<Item>> = emptyMap()
        set(v) { field = v; invalidate() }

    var onPick: (LocalDate) -> Unit = {}
    var onItem: (Item) -> Unit = {}
    var onSwipe: (Int) -> Unit = {}

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val it0 = renderer.itemAt(e.x, e.y)
            if (it0 != null) onItem(it0) else renderer.dateAt(e.x, e.y)?.let { onPick(it) }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val dx = e2.x - (e1?.x ?: e2.x)
            if (abs(dx) > width / 6f && abs(vx) > abs(vy)) { onSwipe(if (dx < 0) 1 else -1); return true }
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gd.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width.toFloat(), height.toFloat(), month, byDate, selected, showTitle = false, compact = width < 900)
    }
}
