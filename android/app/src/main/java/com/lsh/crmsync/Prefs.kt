package com.lsh.crmsync

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 앱 설정과 전송 기록 저장소 */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("crmsync", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("serverUrl", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = p.edit().putString("serverUrl", v.trim()).apply()

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()

    var callOn: Boolean
        get() = p.getBoolean("callOn", true)
        set(v) = p.edit().putBoolean("callOn", v).apply()

    var kakaoOn: Boolean
        get() = p.getBoolean("kakaoOn", true)
        set(v) = p.edit().putBoolean("kakaoOn", v).apply()

    var smsOn: Boolean
        get() = p.getBoolean("smsOn", true)
        set(v) = p.edit().putBoolean("smsOn", v).apply()

    /** 자동 문자(안부·감사·보험 만기) 이 폰에서 보내기 */
    var autoSmsOn: Boolean
        get() = p.getBoolean("autoSmsOn", true)
        set(v) = p.edit().putBoolean("autoSmsOn", v).apply()

    var wifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", false)
        set(v) = p.edit().putBoolean("wifiOnly", v).apply()

    /** 캡처 파일 이름에 이 단어가 들어가면 카톡 캡처로 판단 (쉼표로 구분) */
    var kakaoKeys: String
        get() = p.getString("kakaoKeys", "KakaoTalk,카카오톡") ?: ""
        set(v) = p.edit().putString("kakaoKeys", v).apply()

    var smsKeys: String
        get() = p.getString("smsKeys", "Messages,메시지,Message") ?: ""
        set(v) = p.edit().putString("smsKeys", v).apply()

    /** 이 시각(초) 이후에 추가된 파일만 보냄. 처음 켤 때 "지금"으로 설정해서 예전 파일이 쏟아지지 않게 함 */
    var sinceAudio: Long
        get() = p.getLong("sinceAudio", 0)
        set(v) = p.edit().putLong("sinceAudio", v).apply()

    var sinceImage: Long
        get() = p.getLong("sinceImage", 0)
        set(v) = p.edit().putLong("sinceImage", v).apply()

    fun isSent(key: String) = p.getStringSet("sent", emptySet())!!.contains(key)

    fun markSent(key: String) {
        val set = LinkedHashSet(p.getStringSet("sent", emptySet())!!)
        set.add(key)
        // 너무 커지지 않게 최근 2000개만 유지
        val trimmed = if (set.size > 2000) set.toList().takeLast(2000).toSet() else set
        p.edit().putStringSet("sent", trimmed).apply()
    }

    fun log(msg: String) {
        val t = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())
        val lines = (p.getString("log", "") ?: "").lines().filter { it.isNotBlank() }.toMutableList()
        lines.add(0, "$t  $msg")
        p.edit().putString("log", lines.take(60).joinToString("\n")).apply()
    }

    val logText: String get() = p.getString("log", "") ?: ""

    fun keys(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_URL = "https://fyyfeqwthbgetwkayodu.supabase.co/functions/v1/ingest"
    }
}
