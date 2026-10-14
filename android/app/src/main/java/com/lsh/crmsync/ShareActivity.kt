package com.lsh.crmsync

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/** 다른 앱의 "공유하기" → "관리 시스템으로 보내기" */
class ShareActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (prefs.token.isBlank()) {
            Toast.makeText(this, "먼저 '상담 자동전송' 앱에서 출입증을 저장해 주세요", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val i = intent
        val type = i.type ?: ""
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND_MULTIPLE -> streams(i)
            else -> listOfNotNull(stream(i))
        }
        val text = i.getStringExtra(Intent.EXTRA_TEXT)

        Toast.makeText(this, "관리 시스템으로 보내는 중…", Toast.LENGTH_SHORT).show()
        thread {
            val results = mutableListOf<String>()
            if (uris.isEmpty() && !text.isNullOrBlank()) {
                val r = Uploader.sendText(ctx, text)
                prefs.log("📝 공유 텍스트 — ${r.message}")
                results += r.message
            }
            for (u in uris) {
                val name = nameOf(u) ?: "shared_${System.currentTimeMillis()}"
                val mime = contentResolver.getType(u) ?: type
                val r = when {
                    // 카톡 "대화 내보내기"는 txt 파일로 옴 → 텍스트로 읽어서 보냄
                    mime.startsWith("text/") || name.endsWith(".txt", true) -> {
                        val body = contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                        Uploader.sendText(ctx, (if (!text.isNullOrBlank()) "$text\n\n" else "") + body.takeLast(60000), "text")
                    }
                    mime.startsWith("audio/") -> {
                        val tmp = java.io.File(cacheDir, "share_upload.m4a")
                        val conv = AudioConverter.toM4a(ctx, u, tmp)
                        val r0 = if (conv != null) Uploader.sendFile(ctx, android.net.Uri.fromFile(conv), name, "audio/mp4", "call") else Uploader.sendFile(ctx, u, name, mime, "call")
                        tmp.delete(); r0
                    }
                    else -> {
                        val kind = when {
                            prefs.keys(prefs.kakaoKeys).any { name.contains(it, true) } -> "kakao"
                            prefs.keys(prefs.smsKeys).any { name.contains(it, true) } -> "sms"
                            else -> "image"
                        }
                        Uploader.sendFile(ctx, u, name, mime.ifBlank { "image/jpeg" }, kind)
                    }
                }
                prefs.log("📤 $name — ${r.message}")
                results += r.message
            }
            runOnUiThread {
                val ok = results.isNotEmpty() && results.all { it == "전송 완료" || it == "이미 보낸 파일" }
                Toast.makeText(this, if (ok) "보냈어요. 관리 시스템에서 확인하세요" else results.joinToString("\n").ifBlank { "보낼 내용이 없어요" }, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun nameOf(u: Uri): String? = try {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { u.lastPathSegment }
}
