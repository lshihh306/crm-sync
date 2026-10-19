package com.lsh.crmsync

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 자동 문자: 관리 시스템이 서버에 올려둔 "보낼 문자"를 가져와서 이 폰 유심(본번호)으로 보내고, 결과를 서버에 알려줌.
 * 보낸 문자는 안드로이드가 자동으로 삼성 메시지 앱 대화방에도 남겨줌.
 */
class SmsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            SmsSender.run(applicationContext)
            CallbackSender.scan(applicationContext) // 통화 직후 놓친 콜백이 있으면 여기서 처리
        } catch (t: Throwable) {
            Prefs(applicationContext).log("⚠️ 자동 문자 작업 오류: ${t.javaClass.simpleName} ${t.message ?: ""}".take(200))
        }
        return Result.success()
    }
}

object SmsSender {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val lock = Any()
    private const val ACTION_SENT = "com.lsh.crmsync.SMS_SENT"
    private val reqCode = AtomicInteger(1000)

    val PERMS = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)

    fun hasPermission(ctx: Context) = PERMS.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /** ingest 주소에서 sms 함수 주소를 만듦 */
    private fun url(prefs: Prefs) = prefs.serverUrl.replace(Regex("/ingest/?$"), "/sms")

    fun call(prefs: Prefs, body: JSONObject): JSONObject? {
        val req = Request.Builder().url(url(prefs))
            .header("x-app-token", prefs.token)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (r.isSuccessful) JSONObject(txt) else { prefs.log("✉️ 자동 문자 서버 오류 ${r.code}"); null }
            }
        } catch (e: Exception) { null }
    }

    private fun appInfo(ctx: Context): String {
        val v = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
        return "상담 자동전송 $v · ${Build.MODEL}"
    }

    fun run(ctx: Context) = synchronized(lock) {
        val prefs = Prefs(ctx)
        if (prefs.token.isBlank() || !prefs.autoSmsOn) return
        if (!hasPermission(ctx)) {
            call(prefs, JSONObject().put("action", "ping").put("app", appInfo(ctx) + " · 문자 권한 없음"))
            return
        }
        val res = call(prefs, JSONObject().put("action", "due").put("app", appInfo(ctx))) ?: return
        val items = res.optJSONArray("items") ?: return
        if (items.length() == 0) return
        prefs.log("✉️ 자동 문자 ${items.length()}건 가져옴")
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val id = it.getString("id")
            val name = it.optString("name")
            val err: String? = try {
                val phone = it.optString("phone").filter { c -> c.isDigit() }
                val text = it.optString("body")
                val img = it.optString("image").takeIf { s -> s.isNotBlank() && s != "null" }
                if (phone.length < 10 || text.isBlank()) "전화번호나 문구가 비어 있어요"
                else if (img != null) sendMms(ctx, phone, text, android.util.Base64.decode(img, android.util.Base64.DEFAULT), it.optString("image_mime", "image/jpeg"))
                else send(ctx, phone, text)
            } catch (t: Throwable) {
                "앱 오류: ${t.javaClass.simpleName} ${t.message ?: ""}".take(200)
            }
            prefs.log(if (err == null) "✉️ 자동 문자 보냄 · $name" else "⚠️ 자동 문자 실패 · $name · $err")
            // 한 통 보낼 때마다 바로 결과 보고 (실패하면 한 번 더)
            val one = JSONArray().put(JSONObject().put("id", id).put("ok", err == null).apply { if (err != null) put("error", err) })
            val body = JSONObject().put("action", "report").put("results", one)
            if (call(prefs, body) == null) { Thread.sleep(3000); if (call(prefs, body) == null) prefs.log("⚠️ 발송 결과를 서버에 못 알렸어요 · $name") }
            // 통신사 스팸 감지를 피하려고 한 통씩 간격을 둠
            if (i < items.length() - 1) Thread.sleep(8000)
        }
    }

    /** @return null이면 성공, 아니면 실패 사유 */
    fun send(ctx: Context, phone: String, text: String): String? {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        val latch = CountDownLatch(parts.size)
        val fails = mutableListOf<Int>()
        val tag = "id" + System.nanoTime()
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra("tag") != tag) return
                if (resultCode != android.app.Activity.RESULT_OK) synchronized(fails) { fails.add(resultCode) }
                latch.countDown()
            }
        }
        try {
            ContextCompat.registerReceiver(ctx.applicationContext, rcv, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            val sent = ArrayList<PendingIntent>()
            for (k in parts.indices) {
                val i = Intent(ACTION_SENT).setPackage(ctx.packageName).putExtra("tag", tag).putExtra("part", k)
                sent.add(PendingIntent.getBroadcast(ctx, reqCode.incrementAndGet(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            if (parts.size == 1) sms.sendTextMessage(phone, null, text, sent[0], null)
            else sms.sendMultipartTextMessage(phone, null, parts, sent, null)
            if (!latch.await(90, TimeUnit.SECONDS)) return "90초 안에 발송 결과가 오지 않았어요 (문자 앱에서 확인해 주세요)"
            return if (fails.isEmpty()) null else "통신사 발송 실패 (코드 ${fails.first()}) · 신호나 요금제를 확인해 주세요"
        } catch (e: Exception) {
            return "발송 오류: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { ctx.applicationContext.unregisterReceiver(rcv) } catch (_: Exception) {}
        }
    }

    /** 사진 문자(MMS). @return null이면 성공, 아니면 실패 사유 */
    fun sendMms(ctx: Context, phone: String, text: String, image: ByteArray, mime: String): String? {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val dir = java.io.File(ctx.cacheDir, "mms").apply { mkdirs() }
        // 오래된 메시지 파일 정리
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3600_000 }?.forEach { it.delete() }
        val f = java.io.File(dir, "send_${System.nanoTime()}.pdu")
        f.writeBytes(MmsPdu.build(phone, text, image, mime.ifBlank { "image/jpeg" }))
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.mms", f)
        // 통신사 MMS 서비스가 이 파일을 읽을 수 있게 허용
        for (pkg in listOf("com.android.phone", "com.android.mms.service", "com.samsung.android.messaging")) {
            try { ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        }
        val latch = CountDownLatch(1)
        var code = 0
        val tag = "mms" + System.nanoTime()
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra("tag") != tag) return
                code = resultCode; latch.countDown()
            }
        }
        try {
            ContextCompat.registerReceiver(ctx.applicationContext, rcv, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            val pi = PendingIntent.getBroadcast(ctx, reqCode.incrementAndGet(),
                Intent(ACTION_SENT).setPackage(ctx.packageName).putExtra("tag", tag),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            sms.sendMultimediaMessage(ctx, uri, null, null, pi)
            if (!latch.await(150, TimeUnit.SECONDS)) return "150초 안에 사진 문자 결과가 오지 않았어요 (메시지 앱에서 확인해 주세요)"
            return if (code == android.app.Activity.RESULT_OK) null else "사진 문자 발송 실패 (코드 $code) · 모바일 데이터가 켜져 있는지 확인해 주세요"
        } catch (e: Exception) {
            return "사진 문자 오류: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { ctx.applicationContext.unregisterReceiver(rcv) } catch (_: Exception) {}
        }
    }

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 15분마다 확인 (+ 감시 서비스가 켜져 있으면 5분마다) */
    fun start(ctx: Context) {
        val periodic = PeriodicWorkRequestBuilder<SmsWorker>(Duration.ofMinutes(15)).setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sms-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SmsWorker>().setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sms-now", ExistingWorkPolicy.KEEP, req)
    }
}
