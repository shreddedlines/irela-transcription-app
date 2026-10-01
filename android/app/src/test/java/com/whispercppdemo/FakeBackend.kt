package com.whispercppdemo

import com.whispercppdemo.transcribe.provider.BackendConfig
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A real HTTP server on localhost standing in for our backend, so tests
 * exercise the bytes the app actually puts on the wire (the same approach as
 * CloudIntegrationTest). No network egress, no credentials.
 *
 * Routes are "METHOD /path"; each answers from its own script, the last entry
 * repeating. POST /v1/installations issues a new token per call unless told
 * otherwise. [start] points BackendConfig at the server; [stop] resets it.
 */
class FakeBackend {

    class Request(val method: String, val path: String, val authorization: String?,
                  val contentType: String?, val body: String)

    val requests = mutableListOf<Request>()
    private val scripts = mutableMapOf<String, List<Pair<Int, String>>>()
    private val served = mutableMapOf<String, Int>()

    @Volatile var registrationStatus = 201
    @Volatile var registrations = 0
        private set

    private lateinit var server: ServerSocket
    @Volatile private var serving = true

    fun route(route: String, vararg replies: Pair<Int, String>) {
        scripts[route] = replies.toList()
        served[route] = 0
    }

    fun calls(route: String): List<Request> = requests.filter { "${it.method} ${it.path}" == route }

    fun start(): FakeBackend {
        serving = true
        server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (serving) {
                val sock = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { handle(sock) }
            }
        }
        BackendConfig.baseUrl = "http://127.0.0.1:${server.localPort}"
        return this
    }

    fun stop() {
        serving = false
        runCatching { server.close() }
        BackendConfig.baseUrl = ""
    }

    private fun handle(sock: Socket) = sock.use { s ->
        val crlf = "\r\n"
        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith(crlf + crlf)) {
            val b = input.read()
            if (b < 0) return@use
            head.append(b.toChar())
        }
        val lines = head.toString().split(crlf).filter { it.isNotBlank() }
        val (method, path) = lines.first().split(" ").let { it[0] to it[1].substringBefore('?') }
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }
        fun header(name: String) = headers.firstOrNull { it.first.equals(name, true) }?.second
        val len = header("Content-Length")?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }

        val route = "$method $path"
        val (status, payload) = synchronized(this) {
            requests += Request(method, path, header("Authorization"), header("Content-Type"),
                                String(body, 0, read, Charsets.UTF_8))
            if (route == "POST /v1/installations" && route !in scripts) {
                registrations++
                if (registrationStatus in 200..299)
                    registrationStatus to """{"installation_id":"install-$registrations","token":"token-$registrations"}"""
                else registrationStatus to """{"error":"busy","retryable":false,"reason":"registration_limited"}"""
            } else {
                val script = scripts[route] ?: listOf(404 to """{"detail":"Not Found"}""")
                val n = served[route] ?: 0
                served[route] = n + 1
                script[minOf(n, script.size - 1)]
            }
        }
        val bytes = payload.toByteArray(Charsets.UTF_8)
        BufferedOutputStream(s.getOutputStream()).use { out ->
            out.write(("HTTP/1.1 $status X${crlf}Content-Type: application/json$crlf" +
                       "Content-Length: ${bytes.size}${crlf}Connection: close$crlf$crlf").toByteArray())
            out.write(bytes)
            out.flush()
        }
    }
}
