package com.lsh.crmsync

import java.io.ByteArrayOutputStream

/**
 * 사진 문자(MMS) 메시지 파일(M-Send.req)을 직접 만드는 작은 인코더.
 * OMA MMS Encapsulation 1.2 / WAP WSP 인코딩 규칙대로 헤더 + (SMIL, 사진, 글) 3개 파트를 담음.
 * 실제 전송은 안드로이드 SmsManager.sendMultimediaMessage 가 통신사 MMS 서버로 보냄.
 */
object MmsPdu {
    private class Out : ByteArrayOutputStream() {
        fun b(v: Int) = write(v and 0xFF)
        fun bytes(a: ByteArray) = write(a, 0, a.size)
        /** WSP Text-string: 0x80 이상으로 시작하면 0x7F 붙임, 끝에 0 */
        fun text(s: String) { val a = s.toByteArray(Charsets.UTF_8); if (a.isNotEmpty() && (a[0].toInt() and 0xFF) >= 0x80) b(0x7F); bytes(a); b(0) }
        fun uintvar(v: Long) {
            var x = v; val tmp = ArrayList<Int>()
            tmp.add((x and 0x7F).toInt()); x = x shr 7
            while (x > 0) { tmp.add(((x and 0x7F) or 0x80).toInt()); x = x shr 7 }
            for (i in tmp.indices.reversed()) b(tmp[i])
        }
        /** Value-length + 내용 */
        fun withLength(inner: ByteArray) {
            if (inner.size <= 30) b(inner.size) else { b(0x1F); uintvar(inner.size.toLong()) }
            bytes(inner)
        }
    }

    private fun part(contentType: ByteArray, contentId: String, location: String, data: ByteArray): Pair<ByteArray, ByteArray> {
        val h = Out()
        h.bytes(contentType)
        h.b(0xC0); h.b(0x22); h.bytes("<$contentId>".toByteArray()); h.b(0) // Content-ID (quoted-string)
        h.b(0x8E); h.text(location)                                         // Content-Location
        return h.toByteArray() to data
    }

    /** text/plain; charset=utf-8 */
    private fun ctText(): ByteArray { val o = Out(); o.withLength(byteArrayOf(0x83.toByte(), 0x81.toByte(), 0xEA.toByte())); return o.toByteArray() }
    /** application/smil; charset=utf-8 */
    private fun ctSmil(): ByteArray { val i = Out(); i.text("application/smil"); i.b(0x81); i.b(0xEA); val o = Out(); o.withLength(i.toByteArray()); return o.toByteArray() }
    private fun ctImage(mime: String): ByteArray = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> byteArrayOf(0x9E.toByte())
        "image/png" -> byteArrayOf(0xA0.toByte())
        "image/gif" -> byteArrayOf(0x9D.toByte())
        else -> { val o = Out(); o.text(mime); o.toByteArray() }
    }

    /** images: (사진 바이트, mime) 최대 2장 정도. 사진마다 한 장면(par), 글은 첫 장면에 */
    fun build(to: String, text: String, images: List<Pair<ByteArray, String>>): ByteArray {
        fun ext(m: String) = when (m.lowercase()) { "image/png" -> "png"; "image/gif" -> "gif"; else -> "jpg" }
        val parts = ArrayList<Pair<ByteArray, ByteArray>>()
        val smil = buildString {
            append("<smil><head><layout><root-layout/>")
            if (images.isNotEmpty()) append("<region id=\"Image\" top=\"0\" left=\"0\" height=\"70%\" width=\"100%\" fit=\"meet\"/>")
            append("<region id=\"Text\" top=\"70%\" left=\"0\" height=\"30%\" width=\"100%\" fit=\"scroll\"/>")
            append("</layout></head><body>")
            if (images.isEmpty()) append("<par dur=\"8000ms\"><text src=\"text.txt\" region=\"Text\"/></par>")
            images.forEachIndexed { i, (_, m) ->
                append("<par dur=\"8000ms\"><img src=\"image${i + 1}.${ext(m)}\" region=\"Image\"/>")
                if (i == 0) append("<text src=\"text.txt\" region=\"Text\"/>")
                append("</par>")
            }
            append("</body></smil>")
        }
        parts.add(part(ctSmil(), "smil", "smil.xml", smil.toByteArray(Charsets.UTF_8)))
        images.forEachIndexed { i, (data, m) -> parts.add(part(ctImage(m), "image${i + 1}", "image${i + 1}.${ext(m)}", data)) }
        parts.add(part(ctText(), "text", "text.txt", text.toByteArray(Charsets.UTF_8)))

        val o = Out()
        o.b(0x8C); o.b(0x80)                                   // X-Mms-Message-Type: m-send-req
        o.b(0x98); o.text("T" + System.currentTimeMillis().toString(16)) // X-Mms-Transaction-ID
        o.b(0x8D); o.b(0x92)                                   // X-Mms-MMS-Version: 1.2
        o.b(0x89); o.b(0x01); o.b(0x81)                        // From: insert-address-token (통신사가 내 번호로 채움)
        o.b(0x97); o.text("$to/TYPE=PLMN")                      // To
        o.b(0x8A); o.b(0x80)                                   // X-Mms-Message-Class: personal
        o.b(0x8F); o.b(0x81)                                   // X-Mms-Priority: normal
        o.b(0x86); o.b(0x81)                                   // X-Mms-Delivery-Report: no
        o.b(0x90); o.b(0x81)                                   // X-Mms-Read-Report: no
        // Content-Type: application/vnd.wap.multipart.related; type="application/smil"; start="<smil>"
        val ct = Out(); ct.b(0xB3); ct.b(0x89); ct.text("application/smil"); ct.b(0x8A); ct.text("<smil>")
        o.b(0x84); o.withLength(ct.toByteArray())
        // 본문 (multipart)
        o.uintvar(parts.size.toLong())
        for ((headers, data) in parts) {
            o.uintvar(headers.size.toLong()); o.uintvar(data.size.toLong()); o.bytes(headers); o.bytes(data)
        }
        return o.toByteArray()
    }
}
