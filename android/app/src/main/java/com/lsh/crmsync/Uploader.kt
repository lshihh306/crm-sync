package com.lsh.crmsync

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.util.concurrent.TimeUnit

/** 서버(ingest)로 파일이나 텍스트를 보냄 */
object Uploader {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Result(val ok: Boolean, val message: String)

    fun sendFile(ctx: Context, uri: Uri, fileName: String, mime: String, kind: String, occurredAtIso: String? = null): Result {
        val prefs = Prefs(ctx)
        if (prefs.token.isBlank()) return Result(false, "앱 출입증(APP_TOKEN)을 먼저 넣어주세요")
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun writeTo(sink: BufferedSink) {
                ctx.contentResolver.openInputStream(uri)!!.use { sink.writeAll(it.source()) }
            }
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("kind", kind)
            .addFormDataPart("file_name", fileName)
            .addFormDataPart("file", fileName, body)
        if (occurredAtIso != null) form.addFormDataPart("occurred_at", occurredAtIso)
        return post(prefs, form.build())
    }

    fun sendText(ctx: Context, text: String, kind: String = "text"): Result {
        val prefs = Prefs(ctx)
        if (prefs.token.isBlank()) return Result(false, "앱 출입증(APP_TOKEN)을 먼저 넣어주세요")
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("kind", kind)
            .addFormDataPart("text", text)
            .build()
        return post(prefs, form)
    }

    private fun post(prefs: Prefs, body: RequestBody): Result {
        val req = Request.Builder().url(prefs.serverUrl)
            .header("x-app-token", prefs.token)
            .post(body).build()
        return try {
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (r.isSuccessful) Result(true, if (txt.contains("duplicate")) "이미 보낸 파일" else "전송 완료")
                else Result(false, "서버 오류 ${r.code}: ${txt.take(120)}")
            }
        } catch (e: Exception) {
            Result(false, "네트워크 오류: ${e.message}")
        }
    }
}
