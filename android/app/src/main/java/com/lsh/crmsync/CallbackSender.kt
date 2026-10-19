package com.lsh.crmsync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 콜백 문자: 통화가 끝나면 통화기록을 보고
 *  - 받거나 건 통화(통화 시간 있음) → "통화 후" 문자
 *  - 부재중 → "부재중" 문자
 *  - 거절 → "거절" 문자
 * 서버에 물어서 그 번호에 처음일 때만 보냄 (번호당 평생 한 번). 문구는 관리 시스템에서 바꿈.
 */
object CallbackSender {
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()

    fun hasPermission(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** 통화기록을 확인해서 새로 생긴 통화마다 콜백 처리 (백그라운드 스레드에서 실행해야 함) */
    fun scan(ctx: Context) = synchronized(lock) {
        val prefs = Prefs(ctx)
        if (prefs.token.isBlank() || !prefs.callbackOn) return
        if (!hasPermission(ctx) || !SmsSender.hasPermission(ctx)) return
        val now = System.currentTimeMillis()
        // 처음 켤 때는 "지금 이후" 통화만 (예전 통화기록에 문자가 쏟아지지 않게)
        if (prefs.cbSince == 0L) { prefs.cbSince = now; return }
        val since = prefs.cbSince
        val calls = mutableListOf<Triple<Long, String, Pair<String, String>>>() // date, type, (number, name)
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE, CallLog.Calls.CACHED_NAME),
                "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()), "${CallLog.Calls.DATE} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val num = (c.getString(0) ?: "").filter { it.isDigit() }
                    val type = c.getInt(1); val dur = c.getLong(2); val date = c.getLong(3)
                    val name = c.getString(4) ?: ""
                    val kind = when (type) {
                        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.OUTGOING_TYPE -> if (dur > 0) "after" else null
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        else -> null
                    }
                    if (kind != null) calls.add(Triple(date, kind, num to name))
                    else prefs.cbSince = maxOf(prefs.cbSince, date)
                }
            }
        } catch (e: Exception) {
            prefs.log("⚠️ 통화기록을 읽지 못했어요: ${e.javaClass.simpleName}")
            return
        }
        for ((date, kind, who) in calls) {
            val (num, name) = who
            prefs.cbSince = maxOf(prefs.cbSince, date)
            if (!Regex("^01\\d{8,9}$").matches(num)) continue // 휴대폰 번호에만
            val res = SmsSender.call(prefs, JSONObject().put("action", "callback").put("type", kind).put("phone", num).put("name", name)) ?: continue
            if (!res.optBoolean("send")) continue
            val id = res.getString("id"); val text = res.getString("body")
            val err = try { SmsSender.send(ctx, num, text) } catch (t: Throwable) { "앱 오류: ${t.javaClass.simpleName}" }
            val label = mapOf("after" to "통화 후", "missed" to "부재중", "rejected" to "거절")[kind]
            prefs.log(if (err == null) "📨 콜백 문자 보냄($label) · ${name.ifBlank { num }}" else "⚠️ 콜백 문자 실패 · ${name.ifBlank { num }} · $err")
            val one = JSONArray().put(JSONObject().put("id", id).put("ok", err == null).apply { if (err != null) put("error", err) })
            SmsSender.call(prefs, JSONObject().put("action", "report").put("results", one))
        }
    }

    fun scanLater(ctx: Context, delayMs: Long) {
        Handler(Looper.getMainLooper()).postDelayed({ io.execute { try { scan(ctx.applicationContext) } catch (_: Throwable) {} } }, delayMs)
    }

    /** 감시 서비스가 켜져 있는 동안 통화 상태를 지켜보다가, 통화가 끝나면(대기 상태로 돌아오면) 확인 */
    fun watch(ctx: Context): () -> Unit {
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return {}
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return {}
        var last = TelephonyManager.CALL_STATE_IDLE
        val onState: (Int) -> Unit = { st ->
            if (st == TelephonyManager.CALL_STATE_IDLE && last != TelephonyManager.CALL_STATE_IDLE) scanLater(ctx, 4000) // 통화기록이 저장될 시간을 줌
            last = st
        }
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onState(state)
                }
                tm.registerTelephonyCallback(ctx.mainExecutor, cb)
                ({ tm.unregisterTelephonyCallback(cb) })
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("old api") override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
                }
                @Suppress("DEPRECATION") tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                ({ @Suppress("DEPRECATION") tm.listen(l, PhoneStateListener.LISTEN_NONE) })
            }
        } catch (_: Exception) { {} }
    }
}
