package com.symmetricalpalmtree.notesproutsn.ext.cloud

import com.symmetricalpalmtree.notesproutsn.core.Slog
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** A buffered reply: the status code, the whole body as text, and the response headers by
 *  **lower-cased** name (Drive's `Location` for a resumable session is read through [header]). */
class HttpReply(val code: Int, val body: String, val headers: Map<String, String>) {

    val ok: Boolean get() = code in 200..299

    fun header(name: String): String? = headers[name.lowercase()]
}

/** A streamed reply: the status code, how many bytes reached the sink, and the error body when the
 *  call failed before any byte was streamed (a non-2xx is never written to the sink). */
class HttpStreamReply(val code: Int, val bytes: Long, val errorBody: String) {
    val ok: Boolean get() = code in 200..299
}

/** What a request carries, if anything. */
sealed class HttpBody {

    /** A body already in memory — a form post, a JSON metadata document. */
    class Text(val contentType: String, val text: String) : HttpBody()

    /**
     * A body of exactly [length] bytes written by [write]. This is how an upload streams a
     * `ParcelFileDescriptor` straight to Drive without ever holding a notebook in memory — the
     * length is fixed up front so the connection never buffers, and [write] is the only thing that
     * touches the source.
     */
    class Streamed(val contentType: String, val length: Long, val write: (OutputStream) -> Unit) : HttpBody()
}

/** One HTTP call, fully described. [headers] never carries a token in a *log* — it carries the
 *  bearer on the wire, and nothing here is ever printed. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: HttpBody? = null,
)

/**
 * The seam between the REST core and the network — the reason [DriveApi] is JVM-testable at all.
 * The only implementation that reaches the wire is [DriveHttp]; tests hand [DriveApi] a fake.
 */
interface HttpTransport {

    /** Send [request] and read the whole reply into memory. */
    fun send(request: HttpRequest): HttpReply

    /** Send [request] and stream a 2xx body into [out] (never buffered). A non-2xx writes nothing
     *  and answers its error body instead. */
    fun stream(request: HttpRequest, out: OutputStream): HttpStreamReply
}

/**
 * **The one door to the network** in this extension (arc 25 / V2) — and this extension is the one
 * networked process in the app (`DRIVE_PLAN.md` decision 3; the host has no INTERNET permission).
 * Plain `HttpURLConnection` over https, no OkHttp, no new Gradle dependency.
 *
 * Every transport failure — `IOException` and its whole family (`UnknownHostException`,
 * `SocketTimeoutException`, every SSL failure) — becomes the seam's verbatim
 * `IllegalStateException(CloudContract.NETWORK)` here, so nothing above this file has to know what
 * a socket is. An `IllegalStateException` thrown by a body writer (the exact-byte refusals of
 * [ExactCopy]) passes through untouched: that is the provider refusing, not the network failing.
 *
 * **What may be logged:** the method, the http code, a duration, a byte count. **Never** a URL (a
 * query carries file names, and an upload session URI is a bearer of its own), never a body, never
 * a header, never an email. That rule is the whole reason this file logs at all.
 */
object DriveHttp : HttpTransport {

    private const val TAG = "DriveHttp"

    /** og's timeouts, unchanged: a Supernote on hotel wifi wants patience, and the host times the
     *  whole Binder call anyway (`CloudTimeouts`). */
    const val TIMEOUT_MS: Int = 30_000

    const val FORM: String = "application/x-www-form-urlencoded; charset=UTF-8"
    const val JSON: String = "application/json; charset=UTF-8"
    const val OCTET: String = "application/octet-stream"

    private const val BUFFER_BYTES = 32 * 1024

    override fun send(request: HttpRequest): HttpReply {
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            conn = open(request)
            writeBody(conn, request.body)
            val code = conn.responseCode
            val body = readBody(conn)
            Slog.d(TAG) { "${request.method} http $code, ${body.length} chars, ${System.currentTimeMillis() - started} ms" }
            return HttpReply(code, body, headersOf(conn))
        } catch (e: IllegalStateException) {
            // The provider's own refusal from a body writer — not a network failure.
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "${request.method} failed (${e.javaClass.simpleName}) after ${System.currentTimeMillis() - started} ms" }
            throw DriveFailures.network()
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    override fun stream(request: HttpRequest, out: OutputStream): HttpStreamReply {
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            conn = open(request)
            writeBody(conn, request.body)
            val code = conn.responseCode
            if (code !in 200..299) {
                val error = readBody(conn)
                Slog.d(TAG) { "${request.method} stream http $code, ${System.currentTimeMillis() - started} ms" }
                return HttpStreamReply(code, 0L, error)
            }
            var total = 0L
            conn.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                }
            }
            out.flush()
            Slog.d(TAG) { "${request.method} stream http $code, $total B, ${System.currentTimeMillis() - started} ms" }
            return HttpStreamReply(code, total, "")
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "${request.method} stream failed (${e.javaClass.simpleName}) after ${System.currentTimeMillis() - started} ms" }
            throw DriveFailures.network()
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun open(request: HttpRequest): HttpURLConnection {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        // Android's HttpURLConnection is OkHttp underneath and accepts PATCH, which the desktop JDK
        // refuses. Drive's resumable "replace" leg is a PATCH, so this must stay direct.
        conn.requestMethod = request.method
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.doInput = true
        conn.instanceFollowRedirects = true
        for ((name, value) in request.headers) conn.setRequestProperty(name, value)
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: HttpBody?) {
        when (body) {
            null -> Unit
            is HttpBody.Text -> {
                val bytes = body.text.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", body.contentType)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            is HttpBody.Streamed -> {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", body.contentType)
                conn.setFixedLengthStreamingMode(body.length)
                conn.outputStream.use { body.write(it) }
            }
        }
    }

    private fun readBody(conn: HttpURLConnection): String =
        try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e2: Exception) {
                ""
            }
        }

    private fun headersOf(conn: HttpURLConnection): Map<String, String> {
        val out = HashMap<String, String>()
        for ((name, values) in conn.headerFields) {
            val key = name?.lowercase() ?: continue
            val value = values?.firstOrNull() ?: continue
            out[key] = value
        }
        return out
    }
}
