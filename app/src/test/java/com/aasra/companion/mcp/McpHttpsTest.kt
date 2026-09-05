package com.aasra.companion.mcp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/** Real TLS sockets, no MockWebServer dependency and no production cleartext/trust bypass. */
class McpHttpsTest {
    private class Headers : LinkedHashMap<String, MutableList<String>>() {
        fun add(name: String, value: String) { getOrPut(name) { mutableListOf() }.add(value) }
    }

    private class HttpExchange(private val socket: Socket) : Closeable {
        val requestHeaders = Headers()
        val responseHeaders = Headers()
        val requestMethod: String
        val requestBody: ByteArrayInputStream
        val responseBody = socket.getOutputStream()
        init {
            socket.soTimeout = 5000
            val input = socket.getInputStream().buffered()
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (bytes.size() < 8192) {
                    val byte = input.read()
                    if (byte < 0) throw IOException("EOF")
                    if (byte == 10) return bytes.toString("UTF-8").removeSuffix("\r")
                    bytes.write(byte)
                }
                throw IOException("Header line too large")
            }
            requestMethod = line().substringBefore(' ')
            while (true) {
                val line = line()
                if (line.isEmpty()) break
                requestHeaders.add(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
            }
            val length = requestHeaders["content-length"]?.single()?.toInt() ?: 0
            require(length in 0..65536)
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(body, offset, length - offset)
                if (n < 0) throw IOException("Truncated body")
                offset += n
            }
            requestBody = ByteArrayInputStream(body)
        }
        fun sendResponseHeaders(code: Int, length: Long) {
            responseHeaders.add("Connection", "close")
            if (length != 0L) responseHeaders.add("Content-Length", maxOf(0, length).toString())
            val head = buildString {
                append("HTTP/1.1 $code Test\r\n")
                responseHeaders.forEach { (name, values) -> values.forEach { append("$name: $it\r\n") } }
                append("\r\n")
            }
            responseBody.write(head.toByteArray())
            responseBody.flush()
        }
        override fun close() = socket.close()
    }

    private class Server(val onCall: (HttpExchange, JsonObject) -> Unit) : Closeable {
        val calls = Collections.synchronizedList(mutableListOf<JsonObject>())
        val headers = Collections.synchronizedList(mutableListOf<Map<String, List<String>>>())
        private val workers = Executors.newCachedThreadPool()
        private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
        private val server = (tls.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            bind(InetSocketAddress("127.0.0.1", 0))
        }
        init {
            workers.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: IOException) { break }
                    sockets += socket
                    workers.execute {
                        try { socket.use { handle(HttpExchange(it)) } } catch (_: IOException) {
                            // TLS rejection, cancellation and early SSE close are expected in these tests.
                        } finally { sockets.remove(socket) }
                    }
                }
            }
        }
        private fun handle(exchange: HttpExchange) {
                exchange.use {
                    val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    calls += message
                    headers += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() }
                    assertEquals("POST", exchange.requestMethod)
                    when (message["method"]!!.jsonPrimitive.content) {
                        "initialize" -> {
                            exchange.responseHeaders.add("Mcp-Session-Id", "wire-session")
                            exchange.responseHeaders.add("Set-Cookie", "session=must-not-reuse; Secure")
                            jsonReply(exchange, message, """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"local","version":"1"}}""")
                        }
                        "notifications/initialized" -> exchange.sendResponseHeaders(202, -1)
                        "tools/list" -> jsonReply(exchange, message, """{"tools":[{"name":"write","inputSchema":{"type":"object"}}]}""")
                        else -> onCall(exchange, message)
                    }
                }
        }
        val endpoint = "https://localhost:${server.localPort}/mcp"
        fun client(http: OkHttpClient = trustedHttp()) = McpClient(endpoint, http)
        override fun close() {
            server.close()
            synchronized(sockets) { sockets.forEach { it.close() } }
            workers.shutdownNow()
        }
    }

    @Test fun actualTlsHeadersAndSseResponseCompleteWithoutWaitingForEof() = runBlocking {
        val release = CountDownLatch(1)
        Server { exchange, message ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(("id: primer\ndata:\n\n: keepalive\n\n" +
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":${message["id"]},\"result\":{\"content\":[],\"structuredContent\":{\"real\":true}}}\n\n").toByteArray())
            exchange.responseBody.flush()
            release.await(5, TimeUnit.SECONDS)
        }.use { server ->
            try {
                server.client().use { client ->
                    client.discoverTools()
                    val result = withTimeout(3000) { client.callTool(client.prepareCall("write", "{}")) }
                    assertTrue(result.json.contains("structuredContent"))
                    assertEquals(4, server.calls.size)
                    server.headers.forEachIndexed { i, headers ->
                        assertNull(headers["authorization"])
                        assertNull(headers["cookie"])
                        assertEquals(listOf("application/json, text/event-stream"), headers["accept"])
                        assertEquals(if (i == 0) null else listOf("wire-session"), headers["mcp-session-id"])
                        assertEquals(if (i == 0) null else listOf("2025-11-25"), headers["mcp-protocol-version"])
                    }
                }
            } finally { release.countDown() }
        }
    }

    @Test fun realRedirectAndRetryAfterZeroCannotReplayPost() = runBlocking {
        for (status in listOf(302, 307, 408, 503)) {
            Server { exchange, _ ->
                exchange.responseHeaders.add("Location", "http://localhost:1/must-not-follow")
                exchange.responseHeaders.add("Retry-After", "0")
                exchange.sendResponseHeaders(status, -1)
            }.use { server -> server.client().use { client ->
                client.discoverTools()
                val approval = client.prepareCall("write", "{}")
                try { client.callTool(approval); fail("Expected HTTP $status") } catch (e: McpException) {
                    assertEquals(McpError.HTTP, e.kind)
                    assertEquals("HTTP $status", e.detail)
                }
                assertEquals(1, server.calls.count { it["method"]!!.jsonPrimitive.content == "tools/call" })
            } }
        }
    }

    @Test fun stalledResponseTimesOutWithoutRetry() = runBlocking {
        val release = CountDownLatch(1)
        Server { exchange, _ ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(": ready\n\n".toByteArray())
            exchange.responseBody.flush()
            release.await(5, TimeUnit.SECONDS)
        }.use { server ->
            // Reduce only the socket read timeout in this test, below the production 15-second bound.
            val http = trustedHttp().newBuilder().addInterceptor {
                it.withReadTimeout(250, TimeUnit.MILLISECONDS).proceed(it.request())
            }.build()
            try {
                server.client(http).use { client ->
                    client.discoverTools()
                    val approval = client.prepareCall("write", "{}")
                    try { withTimeout(3000) { client.callTool(approval) }; fail("Expected timeout") } catch (e: McpException) {
                        assertEquals(McpError.NETWORK, e.kind)
                    }
                    assertEquals(4, server.calls.size)
                }
            } finally { release.countDown() }
        }
    }

    @Test fun coroutineCancellationCancelsSocketAndConsumesApproval() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Server { exchange, _ ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(": waiting\n\n".toByteArray())
            exchange.responseBody.flush()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }.use { server ->
            try {
                server.client().use { client ->
                    client.discoverTools()
                    val approval = client.prepareCall("write", "{}")
                    val call = async(start = CoroutineStart.UNDISPATCHED) { client.callTool(approval) }
                    assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
                    withTimeout(1000) { call.cancelAndJoin() }
                    try { client.callTool(approval); fail("Consumed approval reused") } catch (e: McpException) {
                        assertEquals(McpError.APPROVAL_REQUIRED, e.kind)
                    }
                    assertEquals(4, server.calls.size)
                }
            } finally { release.countDown() }
        }
    }

    @Test fun productionClientRejectsUntrustedLocalCertificate() = runBlocking {
        Server { _, _ -> fail("TLS should fail first") }.use { server ->
            McpClient(server.endpoint).use { client ->
                try { client.discoverTools(); fail("Untrusted TLS accepted") } catch (e: McpException) {
                    assertEquals(McpError.NETWORK, e.kind)
                }
                assertTrue(server.calls.isEmpty())
            }
        }
    }

    companion object {
        private lateinit var temp: File
        private lateinit var tls: SSLContext
        private lateinit var trustManager: X509TrustManager

        @BeforeClass @JvmStatic fun createEphemeralTestCertificate() {
            temp = Files.createTempDirectory("aasra-mcp-test-").toFile()
            val store = File(temp, "local.p12")
            val password = "ephemeral-test-only"
            val process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/keytool").path,
                "-genkeypair", "-alias", "local", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.path, "-storepass", password, "-noprompt",
            ).redirectErrorStream(true).start()
            assertTrue("keytool timed out", process.waitFor(20, TimeUnit.SECONDS))
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
            val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, password.toCharArray()) } }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password.toCharArray()) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
            trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
            tls = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
        }

        @AfterClass @JvmStatic fun removeEphemeralTestCertificate() { if (::temp.isInitialized) temp.deleteRecursively() }
        private fun trustedHttp() = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trustManager).build()
        private fun jsonReply(exchange: HttpExchange, message: JsonObject, result: String) {
            val bytes = """{"jsonrpc":"2.0","id":${message["id"]},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }
}
