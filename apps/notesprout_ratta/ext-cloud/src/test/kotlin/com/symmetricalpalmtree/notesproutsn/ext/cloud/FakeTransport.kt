package com.symmetricalpalmtree.notesproutsn.ext.cloud

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * The Drive provider's test double for the network (arc 25 / V2) — the reason [DriveApi] is
 * testable at all. It records every request (with the body **as it was actually written**, so a
 * multipart assembly and an exact-byte refusal are both observable) and answers from a scripted
 * handler.
 */
class FakeTransport : HttpTransport {

    class Call(val request: HttpRequest, val bodyBytes: ByteArray?) {
        val method: String get() = request.method
        val url: String get() = request.url
        val bodyText: String get() = bodyBytes?.toString(Charsets.UTF_8) ?: ""
    }

    val calls = mutableListOf<Call>()

    /** What a request is answered with. The default is a 404 so an unscripted call is obvious. */
    var handler: (HttpRequest) -> HttpReply = { HttpReply(404, "{}", emptyMap()) }

    /** What a streamed request is answered with; the handler writes the body itself. */
    var streamHandler: (HttpRequest, OutputStream) -> HttpStreamReply = { _, _ -> HttpStreamReply(404, 0L, "{}") }

    override fun send(request: HttpRequest): HttpReply {
        calls += Call(request, capture(request))
        return handler(request)
    }

    override fun stream(request: HttpRequest, out: OutputStream): HttpStreamReply {
        calls += Call(request, capture(request))
        return streamHandler(request, out)
    }

    /** The requests so far, oldest first, as `METHOD url`. */
    fun trace(): List<String> = calls.map { "${it.method} ${it.url}" }

    fun bearerOf(index: Int): String? = calls[index].request.headers["Authorization"]

    private fun capture(request: HttpRequest): ByteArray? = when (val body = request.body) {
        null -> null
        is HttpBody.Text -> body.text.toByteArray(Charsets.UTF_8)
        // Writing it here is what a real connection would do — including throwing the exact-byte
        // refusal out of `send`, exactly as `DriveHttp` lets it through.
        is HttpBody.Streamed -> ByteArrayOutputStream().also { body.write(it) }.toByteArray()
    }

    companion object {
        fun ok(body: String, headers: Map<String, String> = emptyMap()): HttpReply =
            HttpReply(200, body, headers)

        /** A `files` reply carrying exactly the rows given, already in Drive's JSON shape. */
        fun fileList(vararg rows: String): String = """{"files":[${rows.joinToString(",")}]}"""

        fun file(
            id: String,
            name: String,
            folder: Boolean = false,
            size: String? = null,
            modifiedTime: String? = null,
        ): String {
            val parts = mutableListOf("\"id\":\"$id\"", "\"name\":\"$name\"")
            parts += "\"mimeType\":\"" + (if (folder) DriveRest.FOLDER_MIME else "application/octet-stream") + "\""
            if (size != null) parts += "\"size\":\"$size\""
            if (modifiedTime != null) parts += "\"modifiedTime\":\"$modifiedTime\""
            return "{" + parts.joinToString(",") + "}"
        }
    }
}
