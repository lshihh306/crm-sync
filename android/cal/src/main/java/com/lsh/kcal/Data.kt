package com.lsh.kcal

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** 달력 한 줄 (관리 시스템이 올려 둔 일정 / 가망 고객 연락 / 공휴일) */
data class Item(
    val id: String,
    val date: String,
    val time: String,
    val title: String,
    val memo: String,
    val kind: String,      // event | todo | done | hol
    val cat: String,
    val bg: Int,
    val fg: Int,
    val done: Boolean,
    val late: Boolean,
    val phone: String,
    val ref: String,
    val repeat: String,
    val sort: Int,
) {
    val isEvent get() = kind == "event"
    val isProspect get() = kind == "todo" || kind == "done"
    val isHoliday get() = kind == "hol"
    val label get() = if (time.isNotEmpty()) "$time $title" else title

    companion object {
        fun color(s: String?, def: Int): Int = try {
            if (s.isNullOrBlank()) def else android.graphics.Color.parseColor(s)
        } catch (_: Exception) { def }

        fun from(o: JSONObject) = Item(
            id = o.optString("id"),
            date = o.optString("date").take(10),
            time = o.optString("time"),
            title = o.optString("title"),
            memo = o.optString("memo"),
            kind = o.optString("kind"),
            cat = o.optString("cat"),
            bg = color(o.optString("bg"), 0xFF4A6CD4.toInt()),
            fg = color(o.optString("fg"), 0xFFFFFFFF.toInt()),
            done = o.optBoolean("done"),
            late = o.optBoolean("late"),
            phone = o.optString("phone"),
            ref = o.optString("ref"),
            repeat = o.optString("repeat"),
            sort = o.optInt("sort"),
        )
    }
}

/** 설정·캐시 저장소 */
class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("kcal", Context.MODE_PRIVATE)

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()

    var cache: String
        get() = p.getString("cache", "") ?: ""
        set(v) = p.edit().putString("cache", v).apply()

    var syncedAt: String
        get() = p.getString("syncedAt", "") ?: ""
        set(v) = p.edit().putString("syncedAt", v).apply()

    var fetchedAt: Long
        get() = p.getLong("fetchedAt", 0)
        set(v) = p.edit().putLong("fetchedAt", v).apply()

    var pending: Int
        get() = p.getInt("pending", 0)
        set(v) = p.edit().putInt("pending", v).apply()

    /** 위젯에서 보고 있는 달 (이번 달 기준 몇 달 앞/뒤) */
    var widgetOffset: Int
        get() = p.getInt("widgetOffset", 0)
        set(v) = p.edit().putInt("widgetOffset", v).apply()

    var widgetOffsetDay: String
        get() = p.getString("widgetOffsetDay", "") ?: ""
        set(v) = p.edit().putString("widgetOffsetDay", v).apply()

    fun items(): List<Item> {
        val raw = cache
        if (raw.isBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { Item.from(a.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun byDate(): Map<String, List<Item>> = group(items())

    companion object {
        /** 날짜별로 묶고, 하루 안에서는 공휴일 → 안 끝난 일 → 끝난 일 순서 */
        fun group(list: List<Item>): Map<String, List<Item>> =
            list.groupBy { it.date }.mapValues { (_, v) ->
                v.sortedWith(compareBy<Item>({ if (it.isHoliday) 0 else 1 }, { if (it.done) 1 else 0 }, { if (it.isEvent) 0 else 1 }, { it.time.ifEmpty { "99" } }, { it.sort }))
            }
    }
}

/** 서버 함수(cal) 호출 */
object Api {
    const val URL = "https://fyyfeqwthbgetwkayodu.supabase.co/functions/v1/cal"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    class ApiError(msg: String, val code: Int = 0) : Exception(msg)

    fun call(token: String, body: JSONObject): JSONObject {
        body.put("app", "관리 캘린더 1.2")
        val req = Request.Builder().url(URL)
            .header("x-cal-token", token)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: ""
            val o = try { JSONObject(txt) } catch (_: Exception) { JSONObject() }
            if (r.code == 401) throw ApiError("연결 코드가 맞지 않아요", 401)
            if (!r.isSuccessful) throw ApiError(o.optString("error").ifEmpty { "서버 오류 (${r.code})" }, r.code)
            if (o.has("error")) throw ApiError(o.optString("error"))
            return o
        }
    }

    /** 전체 목록을 받아서 캐시에 저장 */
    fun refresh(ctx: Context): Int {
        val st = Store(ctx)
        if (st.token.isBlank()) throw ApiError("연결 코드를 먼저 넣어 주세요", 401)
        val today = LocalDate.now()
        val o = call(st.token, JSONObject().put("action", "list")
            .put("from", today.minusDays(70).toString())
            .put("to", today.plusDays(420).toString()))
        val items = o.optJSONArray("items") ?: JSONArray()
        st.cache = items.toString()
        st.syncedAt = o.optString("synced_at")
        st.pending = o.optInt("pending")
        st.fetchedAt = System.currentTimeMillis()
        return items.length()
    }
}
