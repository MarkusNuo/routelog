package io.github.markusnuo.routelog.testing

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A minimal HTTP/1.1 server on 127.0.0.1 for upload tests, so the real OkHttp client is used
 * end to end. Debug builds allow cleartext (network security config), which is what lets the test
 * use http://. Records every request and answers with [respond].
 *
 * With [answerBeforeBody] the server answers right after the headers and closes the connection
 * without reading the body, like a server that refuses a body that is too large (413) or sent
 * without a valid grant (401). The recorded request then has an empty body.
 */
class TestServer(
    private val respond: (Request) -> Response = { Response(204) },
    private val answerBeforeBody: Boolean = false,
) : Closeable {
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        val bodyText get() = body.decodeToString()
    }

    data class Response(val status: Int, val headers: Map<String, String> = emptyMap())

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<Request>()
    val baseUrl get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "test-server") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use(::handle)
                } catch (e: IOException) {
                    // Closed, or a client gave up; keep serving until closed.
                }
            }
        }
    }

    private fun handle(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = if (answerBeforeBody) 0 else headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val request = Request(parts[0], parts.getOrElse(1) { "" }, headers, body)
        requests.add(request)
        val response = respond(request)
        val head = buildString {
            append("HTTP/1.1 ${response.status} Test\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: 0\r\nConnection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(head.toByteArray())
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            line.write(b)
        }
    }

    override fun close() = socket.close()
}
