package com.lsh.crmsync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 새 통화녹음 / 카톡·문자 캡처를 찾아 서버로 보내는 작업.
 * - 사진첩/녹음 폴더에 변화가 생기면 바로 실행 (content URI trigger)
 * - 혹시 놓친 게 있어도 15분마다 한 번 더 확인 (periodic)
 */
class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = Scanner.scan(applicationContext)
        if (tags.contains(Sched.TAG_TRIGGER)) Sched.armTrigger(applicationContext)
        return if (r) Result.success() else Result.retry()
    }
}

object Scanner {
    private val lock = Any()
    private val audioUri: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val imageUri: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** @return false면 네트워크 문제 등으로 나중에 다시 시도 */
    fun scan(ctx: Context): Boolean = synchronized(lock) {
        val prefs = Prefs(ctx)
        if (prefs.token.isBlank()) return true
        var ok = true
        if (prefs.callOn) ok = scanCalls(ctx, prefs) && ok
        if (prefs.kakaoOn || prefs.smsOn) ok = scanShots(ctx, prefs) && ok
        ok
    }

    private fun isoKst(sec: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        f.timeZone = TimeZone.getTimeZone("Asia/Seoul")
        return f.format(Date(sec * 1000))
    }

    private fun scanCalls(ctx: Context, prefs: Prefs): Boolean {
        val since = prefs.sinceAudio
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
        )
        val sel = "${MediaStore.MediaColumns.DATE_ADDED} > ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0 AND (" +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE '%Call%' OR " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '통화 %' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'Call recording%')"
        val now = System.currentTimeMillis() / 1000
        var newSince = since
        ctx.contentResolver.query(audioUri, proj, sel, arrayOf(since.toString()), "${MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val added = c.getLong(3)
                val modified = c.getLong(4)
                val mime = c.getString(5) ?: "audio/mp4"
                val size = c.getLong(6)
                // 녹음 파일이 아직 쓰이는 중일 수 있으니 수정된 지 30초 안 된 건 다음 차례에
                if (now - modified < 30 || size <= 0) { prefs.sinceAudio = newSince; return true }
                val key = "a:$id:$name"
                if (!prefs.isSent(key)) {
                    // 갤럭시 녹음(3GP)을 표준 m4a로 변환해서 보냄. 변환이 안 되면 원본 그대로
                    val src = ContentUris.withAppendedId(audioUri, id)
                    val tmp = java.io.File(ctx.cacheDir, "call_upload.m4a")
                    val conv = AudioConverter.toM4a(ctx, src, tmp)
                    val r = if (conv != null) Uploader.sendFile(ctx, android.net.Uri.fromFile(conv), name, "audio/mp4", "call", isoKst(modified))
                            else Uploader.sendFile(ctx, src, name, mime, "call", isoKst(modified))
                    tmp.delete()
                    prefs.log("📞 $name — ${r.message}")
                    if (!r.ok) { prefs.sinceAudio = newSince; return false }
                    prefs.markSent(key)
                }
                newSince = added
            }
        }
        prefs.sinceAudio = newSince
        return true
    }

    private fun scanShots(ctx: Context, prefs: Prefs): Boolean {
        val since = prefs.sinceImage
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.MIME_TYPE,
        )
        val sel = "${MediaStore.MediaColumns.DATE_ADDED} > ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0 AND " +
            "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE '%Screenshot%' OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'Screenshot%')"
        val kakaoKeys = prefs.keys(prefs.kakaoKeys)
        val smsKeys = prefs.keys(prefs.smsKeys)
        var newSince = since
        ctx.contentResolver.query(imageUri, proj, sel, arrayOf(since.toString()), "${MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val added = c.getLong(3)
                val mime = c.getString(4) ?: "image/jpeg"
                val kind = when {
                    prefs.kakaoOn && kakaoKeys.any { name.contains(it, ignoreCase = true) } -> "kakao"
                    prefs.smsOn && smsKeys.any { name.contains(it, ignoreCase = true) } -> "sms"
                    else -> null // 일반 캡처는 보내지 않음
                }
                val key = "i:$id:$name"
                if (kind != null && !prefs.isSent(key)) {
                    val r = Uploader.sendFile(ctx, ContentUris.withAppendedId(imageUri, id), name, mime, kind, isoKst(added))
                    prefs.log((if (kind == "kakao") "💛 " else "💬 ") + "$name — ${r.message}")
                    if (!r.ok) { prefs.sinceImage = newSince; return false }
                    prefs.markSent(key)
                }
                newSince = added
            }
        }
        prefs.sinceImage = newSince
        return true
    }

    /** 최근 n일 파일을 다시 보내도록 기준 시각을 되돌림 (서버가 중복은 걸러줌) */
    fun rewind(ctx: Context, days: Int) {
        val p = Prefs(ctx)
        val t = System.currentTimeMillis() / 1000 - days * 86400L
        p.sinceAudio = t
        p.sinceImage = t
    }
}

object Sched {
    const val TAG_TRIGGER = "trigger"
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun start(ctx: Context) {
        val periodic = PeriodicWorkRequestBuilder<ScanWorker>(Duration.ofMinutes(15))
            .setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("scan-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
        armTrigger(ctx, ExistingWorkPolicy.KEEP)
    }

    fun wifiConstraints(ctx: Context): Constraints {
        val type = if (Prefs(ctx).wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return Constraints.Builder().setRequiredNetworkType(type).build()
    }

    /** 사진첩·녹음 폴더가 바뀌면 깨어나는 작업을 걸어둠 (한 번 실행되면 다시 걸어야 함) */
    fun armTrigger(ctx: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val c = Constraints.Builder()
            .setRequiredNetworkType(if (Prefs(ctx).wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .addContentUriTrigger(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
            .addContentUriTrigger(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(15))
            .setTriggerContentMaxDelay(Duration.ofSeconds(60))
            .build()
        val req = OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(c).addTag(TAG_TRIGGER).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("scan-trigger", policy, req)
    }

    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(wifiConstraints(ctx)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("scan-now", ExistingWorkPolicy.REPLACE, req)
    }
}
