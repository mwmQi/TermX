package com.termx.app.power

import android.content.Context
import android.util.Log
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Web Server API for TermX — run an HTTP server from the terminal.
 *
 * Shell usage:
 *   termx-http start [port] [dir]     Start HTTP server
 *   termx-http stop                   Stop HTTP server
 *   termx-http status                 Server status
 *   termx-http port <port>            Change port
 *   termx-http dir <path>             Serve different directory
 *   termx-http cgi <path> <handler>   Add CGI handler
 *   termx-http auth <user> <pass>     Enable basic auth
 *   termx-http no-auth                Disable auth
 *   termx-http logs                   View access logs
 *   termx-http clear-logs             Clear access logs
 *   termx-http upload enable/disable  Enable PUT/POST uploads
 *   termx-http tls <cert> <key>       Enable HTTPS
 */
object WebServerApi {

    private const val TAG = "WebServerApi"
    private const val DEFAULT_PORT = 8080
    private const val DEFAULT_DIR = "/sdcard"
    private const val MAX_LOG_ENTRIES = 500
    private const val MAX_UPLOAD_SIZE = 50 * 1024 * 1024  // 50 MB
    private const val KEEP_ALIVE_TIMEOUT = 5000  // 5 seconds
    private const val MAX_KEEP_ALIVE_REQUESTS = 100

    /** The server socket. */
    @Volatile
    private var serverSocket: ServerSocket? = null

    /** Whether the server is currently running. */
    private val isRunning = AtomicBoolean(false)

    /** The port the server is listening on. */
    private val currentPort = AtomicInteger(DEFAULT_PORT)

    /** The directory being served. */
    @Volatile
    private var serveDirectory: String = DEFAULT_DIR

    /** Thread pool for handling client connections. */
    private var threadPool = Executors.newFixedThreadPool(10)

    /** Access log entries. */
    private val accessLog = ConcurrentLinkedQueue<LogEntry>()

    /** Total bytes served. */
    private val totalBytesServed = java.util.concurrent.atomic.AtomicLong(0)

    /** Total requests handled. */
    private val totalRequests = java.util.concurrent.atomic.AtomicLong(0)

    /** Authentication credentials. */
    @Volatile
    private var authUser: String? = null
    @Volatile
    private var authPass: String? = null

    /** Whether file uploads are allowed. */
    @Volatile
    private var uploadsEnabled = false

    /** CGI handlers mapping path prefix to command. */
    private val cgiHandlers = mutableMapOf<String, String>()

    /** Server start time. */
    @Volatile
    private var startTime: Long = 0L

    /** MIME type map. */
    private val MIME_TYPES = mapOf(
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "json" to "application/json",
        "xml" to "application/xml",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "gz" to "application/gzip",
        "tar" to "application/x-tar",
        "mp3" to "audio/mpeg",
        "mp4" to "video/mp4",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "webm" to "video/webm",
        "apk" to "application/vnd.android.package-archive",
        "bin" to "application/octet-stream",
        "exe" to "application/octet-stream",
        "sh" to "application/x-sh",
        "py" to "text/x-python",
        "java" to "text/x-java-source",
        "kt" to "text/x-kotlin",
        "c" to "text/x-c",
        "cpp" to "text/x-c++",
        "h" to "text/x-c",
        "log" to "text/plain"
    )

    /**
     * Represents a single access log entry.
     */
    data class LogEntry(
        val timestamp: Long,
        val method: String,
        val path: String,
        val status: Int,
        val size: Long,
        val clientIp: String,
        val userAgent: String?
    ) {
        fun toLogString(): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
            return "$time $clientIp ${method.padEnd(6)} $path $status ${formatSize(size)}"
        }
    }

    // ---- Start server ----

    /**
     * Start the HTTP server.
     * @param port Port to listen on (default 8080)
     * @param directory Directory to serve files from
     */
    fun start(context: Context, port: Int = DEFAULT_PORT, directory: String = DEFAULT_DIR): String {
        return try {
            if (isRunning.get()) {
                return "Server is already running on port ${currentPort.get()}"
            }

            // Validate directory
            val dir = File(directory)
            if (!dir.exists() || !dir.isDirectory) {
                return "Error: Directory does not exist: $directory"
            }

            serveDirectory = directory
            currentPort.set(port)

            // Create server socket
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
                soTimeout = 1000  // For checking isRunning periodically
            }

            isRunning.set(true)
            startTime = System.currentTimeMillis()

            // Clear old state
            accessLog.clear()
            totalBytesServed.set(0)
            totalRequests.set(0)

            // Start accepting connections in a background thread
            Thread({
                acceptLoop()
            }, "TermX-HttpServer").start()

            // Get the actual port (in case 0 was specified for auto-assign)
            val actualPort = serverSocket?.localPort ?: port
            currentPort.set(actualPort)

            // Get local IP address
            val localIp = getLocalIpAddress(context)

            Log.i(TAG, "HTTP server started on port $actualPort serving $directory")

            buildString {
                appendLine("=== HTTP Server Started ===")
                appendLine("Port:      $actualPort")
                appendLine("Directory: $directory")
                appendLine("Local URL: http://$localIp:$actualPort")
                appendLine("Auth:      ${if (authUser != null) "Enabled" else "Disabled"}")
                appendLine("Uploads:   ${if (uploadsEnabled) "Enabled" else "Disabled"}")
            }
        } catch (e: BindException) {
            "Error: Port $port is already in use"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Main accept loop — runs in a background thread.
     */
    private fun acceptLoop() {
        while (isRunning.get()) {
            try {
                val client = serverSocket?.accept() ?: break
                client.soTimeout = KEEP_ALIVE_TIMEOUT
                threadPool.execute {
                    handleClient(client)
                }
            } catch (e: SocketTimeoutException) {
                // Normal — check isRunning again
            } catch (e: SocketException) {
                if (isRunning.get()) Log.e(TAG, "Accept error", e)
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "Accept error", e)
            }
        }
    }

    /**
     * Handle a single client connection (supports keep-alive).
     */
    private fun handleClient(client: Socket) {
        var keepAlive = true
        var requestCount = 0

        try {
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            while (keepAlive && isRunning.get() && requestCount < MAX_KEEP_ALIVE_REQUESTS) {
                val request = readRequest(input)
                if (request == null) break  // Connection closed or timeout

                requestCount++
                val response = processRequest(request, client.getInetAddress().hostAddress ?: "unknown")
                writeResponse(output, response)

                // Determine keep-alive
                val connection = request.headers["connection"]?.lowercase()
                keepAlive = connection != "close" && requestCount < MAX_KEEP_ALIVE_REQUESTS
            }
        } catch (e: SocketTimeoutException) {
            // Keep-alive timeout — normal
        } catch (e: SocketException) {
            // Client disconnected
        } catch (e: Exception) {
            Log.d(TAG, "Client handler error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ---- Stop server ----

    /**
     * Stop the HTTP server.
     */
    fun stop(): String {
        return try {
            if (!isRunning.get()) {
                return "Server is not running"
            }

            isRunning.set(false)

            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null

            val uptime = formatDuration(System.currentTimeMillis() - startTime)

            Log.i(TAG, "HTTP server stopped. Uptime: $uptime, Requests: ${totalRequests.get()}")

            buildString {
                appendLine("=== HTTP Server Stopped ===")
                appendLine("Uptime:    $uptime")
                appendLine("Requests:  ${totalRequests.get()}")
                appendLine("Data Sent: ${formatSize(totalBytesServed.get())}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop server", e)
            "Error: ${e.message}"
        }
    }

    // ---- Status ----

    /**
     * Get server status.
     */
    fun status(): String {
        return buildString {
            appendLine("=== HTTP Server Status ===")
            appendLine("Running:   ${isRunning.get()}")
            if (isRunning.get()) {
                val uptime = formatDuration(System.currentTimeMillis() - startTime)
                appendLine("Port:      ${currentPort.get()}")
                appendLine("Directory: $serveDirectory")
                appendLine("Uptime:    $uptime")
                appendLine("Requests:  ${totalRequests.get()}")
                appendLine("Data Sent: ${formatSize(totalBytesServed.get())}")
                appendLine("Auth:      ${if (authUser != null) "Enabled (${authUser})" else "Disabled"}")
                appendLine("Uploads:   ${if (uploadsEnabled) "Enabled" else "Disabled"}")
                appendLine("CGI:       ${if (cgiHandlers.isNotEmpty()) cgiHandlers.size else "None"} handler(s)")
            }
        }
    }

    // ---- Port / Directory ----

    /**
     * Change the server port (requires restart).
     */
    fun setPort(port: Int): String {
        return if (port < 1 || port > 65535) {
            "Error: Invalid port number. Must be 1-65535"
        } else {
            val wasRunning = isRunning.get()
            if (wasRunning) stop()
            currentPort.set(port)
            if (wasRunning) "Port set to $port. Restart the server to apply."
            else "Port set to $port"
        }
    }

    /**
     * Change the served directory.
     */
    fun setDirectory(path: String): String {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            return "Error: Directory does not exist: $path"
        }
        serveDirectory = path
        Log.i(TAG, "Serving directory changed to: $path")
        return "Serving directory set to: $path"
    }

    // ---- Auth ----

    /**
     * Enable basic authentication.
     */
    fun setAuth(user: String, pass: String): String {
        authUser = user
        authPass = pass
        Log.i(TAG, "Basic auth enabled for user: $user")
        return "Basic authentication enabled. User: $user"
    }

    /**
     * Disable authentication.
     */
    fun disableAuth(): String {
        authUser = null
        authPass = null
        return "Authentication disabled"
    }

    // ---- Uploads ----

    /**
     * Enable or disable file uploads (PUT/POST).
     */
    fun setUploads(enabled: Boolean): String {
        uploadsEnabled = enabled
        return "File uploads ${if (enabled) "enabled" else "disabled"}"
    }

    // ---- CGI ----

    /**
     * Add a CGI handler mapping.
     * @param pathPrefix URL path prefix (e.g., "/cgi/")
     * @param handler Command to execute (e.g., "/system/bin/sh")
     */
    fun addCgiHandler(pathPrefix: String, handler: String): String {
        cgiHandlers[pathPrefix] = handler
        Log.i(TAG, "CGI handler added: $pathPrefix -> $handler")
        return "CGI handler added: $pathPrefix -> $handler"
    }

    // ---- Logs ----

    /**
     * View access logs.
     */
    fun logs(limit: Int = 50): String {
        return if (accessLog.isEmpty()) {
            "No access log entries"
        } else {
            val entries = accessLog.takeLast(limit)
            buildString {
                appendLine("=== Access Logs (last ${entries.size} of ${accessLog.size}) ===")
                entries.forEach { appendLine(it.toLogString()) }
            }
        }
    }

    /**
     * Clear access logs.
     */
    fun clearLogs(): String {
        val count = accessLog.size
        accessLog.clear()
        return "Cleared $count log entries"
    }

    // ---- Request handling ----

    /**
     * Represents a parsed HTTP request.
     */
    data class HttpRequest(
        val method: String,
        val path: String,
        val version: String,
        val headers: Map<String, String>,
        val body: ByteArray?
    )

    /**
     * Represents an HTTP response.
     */
    data class HttpResponse(
        val statusCode: Int,
        val statusText: String,
        val headers: Map<String, String>,
        val body: ByteArray?
    )

    /**
     * Read and parse an HTTP request from the input stream.
     */
    private fun readRequest(input: InputStream): HttpRequest? {
        return try {
            // Read request line
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(" ", limit = 3)
            if (parts.size < 3) return null

            val method = parts[0]
            val path = parts[1]
            val version = parts[2]

            // Read headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key.lowercase()] = value
                }
            }

            // Read body if Content-Length is present
            val body = if (headers.containsKey("content-length")) {
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength > 0 && contentLength <= MAX_UPLOAD_SIZE) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read > 0) buf.copyOf(read) else null
                } else null
            } else null

            HttpRequest(method, path, version, headers, body)
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            Log.d(TAG, "Request parse error", e)
            null
        }
    }

    /**
     * Process an HTTP request and generate a response.
     */
    private fun processRequest(request: HttpRequest, clientIp: String): HttpResponse {
        val method = request.method.uppercase()
        val decodedPath = URLDecoder.decode(request.path, "UTF-8")
        val path = if (decodedPath.startsWith("/")) decodedPath else "/$decodedPath"

        totalRequests.incrementAndGet()

        // Check authentication
        if (authUser != null) {
            val authHeader = request.headers["authorization"]
            if (!checkAuth(authHeader)) {
                logAccess(method, path, 401, 0, clientIp, request.headers["user-agent"])
                return HttpResponse(401, "Unauthorized",
                    mapOf("WWW-Authenticate" to "Basic realm=\"TermX\"", "Content-Type" to "text/plain"),
                    "401 Unauthorized".toByteArray())
            }
        }

        // Check CGI handlers
        for ((prefix, handler) in cgiHandlers) {
            if (path.startsWith(prefix)) {
                return handleCgi(request, path, handler, clientIp)
            }
        }

        // Route by method
        return when (method) {
            "GET", "HEAD" -> handleGet(request, path, clientIp)
            "PUT", "POST" -> if (uploadsEnabled) handleUpload(request, path, clientIp) else methodNotAllowed()
            "DELETE" -> if (uploadsEnabled) handleDelete(path, clientIp) else methodNotAllowed()
            "OPTIONS" -> handleOptions()
            else -> methodNotAllowed()
        }
    }

    /**
     * Handle GET and HEAD requests — serve files or directory listings.
     */
    private fun handleGet(request: HttpRequest, path: String, clientIp: String): HttpResponse {
        val file = File(serveDirectory, path)

        // Security: prevent path traversal
        if (!file.canonicalPath.startsWith(File(serveDirectory).canonicalPath)) {
            logAccess(request.method, path, 403, 0, clientIp, request.headers["user-agent"])
            return simpleResponse(403, "Forbidden")
        }

        if (!file.exists()) {
            logAccess(request.method, path, 404, 0, clientIp, request.headers["user-agent"])
            return simpleResponse(404, "Not Found")
        }

        return if (file.isDirectory) {
            // Check for index.html
            val indexFile = File(file, "index.html")
            if (indexFile.exists() && indexFile.isFile) {
                serveFile(indexFile, request, clientIp)
            } else {
                serveDirectoryListing(file, path, request, clientIp)
            }
        } else {
            serveFile(file, request, clientIp)
        }
    }

    /**
     * Serve a single file with support for range requests.
     */
    private fun serveFile(file: File, request: HttpRequest, clientIp: String): HttpResponse {
        val mimeType = getMimeType(file.name)
        val fileSize = file.length()
        var statusCode = 200
        var statusText = "OK"

        // Range request support
        val rangeHeader = request.headers["range"]
        var offset = 0L
        var length = fileSize

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val ranges = rangeHeader.substring(6).split("-")
            if (ranges.isNotEmpty()) {
                offset = ranges[0].toLongOrNull() ?: 0L
                length = if (ranges.size > 1 && ranges[1].isNotEmpty()) {
                    (ranges[1].toLongOrNull() ?: fileSize) - offset + 1
                } else {
                    fileSize - offset
                }
                statusCode = 206
                statusText = "Partial Content"
            }
        }

        try {
            val bytes = if (offset == 0L && length == fileSize) {
                file.readBytes()
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray(length.toInt())
                    raf.readFully(buf)
                    buf
                }
            }

            totalBytesServed.addAndGet(bytes.size.toLong())
            logAccess(request.method, request.path, statusCode, bytes.size.toLong(), clientIp, request.headers["user-agent"])

            val headers = mutableMapOf(
                "Content-Type" to mimeType,
                "Content-Length" to bytes.size.toString(),
                "Last-Modified" to formatDate(file.lastModified()),
                "Accept-Ranges" to "bytes"
            )
            if (statusCode == 206) {
                headers["Content-Range"] = "bytes $offset-${offset + bytes.size - 1}/$fileSize"
            }

            // HEAD method returns headers only
            val body = if (request.method == "HEAD") null else bytes

            HttpResponse(statusCode, statusText, headers, body)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serve file: ${file.path}", e)
            simpleResponse(500, "Internal Server Error")
        }
    }

    /**
     * Generate an HTML directory listing.
     */
    private fun serveDirectoryListing(dir: File, path: String, request: HttpRequest, clientIp: String): HttpResponse {
        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()

        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html><head><meta charset='utf-8'>")
            appendLine("<title>Index of $path</title>")
            appendLine("<style>")
            appendLine("body { font-family: monospace; background: #1e1e1e; color: #d4d4d4; margin: 20px; }")
            appendLine("a { color: #569cd6; text-decoration: none; } a:hover { text-decoration: underline; }")
            appendLine("table { border-collapse: collapse; width: 100%; }")
            appendLine("th, td { padding: 6px 12px; text-align: left; border-bottom: 1px solid #333; }")
            appendLine("th { color: #608b4e; } .dir { color: #dcdcaa; } .size { text-align: right; color: #9cdcfe; }")
            appendLine("</style></head><body>")
            appendLine("<h1>Index of $path</h1>")
            appendLine("<table><tr><th>Name</th><th>Size</th><th>Modified</th></tr>")

            // Parent directory link
            if (path != "/") {
                val parentPath = path.trimEnd('/').substringBeforeLast('/')
                appendLine("<tr><td><a href='$parentPath/' class='dir'>../</a></td><td>-</td><td>-</td></tr>")
            }

            for (file in files) {
                val name = file.name
                val href = if (path.endsWith("/")) "$path$name" else "$path/$name"
                val cssClass = if (file.isDirectory) "dir" else ""
                val suffix = if (file.isDirectory) "/" else ""
                val size = if (file.isDirectory) "-" else formatSize(file.length())
                val modified = formatDate(file.lastModified())
                appendLine("<tr><td><a href='$href$suffix' class='$cssClass'>$name$suffix</a></td>" +
                    "<td class='size'>$size</td><td>$modified</td></tr>")
            }

            appendLine("</table>")
            appendLine("<hr><small>TermX Web Server | ${totalRequests.get()} requests served</small>")
            appendLine("</body></html>")
        }

        val bytes = html.toByteArray()
        totalBytesServed.addAndGet(bytes.size.toLong())
        logAccess(request.method, path, 200, bytes.size.toLong(), clientIp, request.headers["user-agent"])

        return HttpResponse(200, "OK",
            mapOf("Content-Type" to "text/html; charset=utf-8", "Content-Length" to bytes.size.toString()),
            bytes)
    }

    /**
     * Handle file upload via PUT or POST.
     */
    private fun handleUpload(request: HttpRequest, path: String, clientIp: String): HttpResponse {
        val body = request.body ?: return simpleResponse(400, "Bad Request — no body")

        if (body.size > MAX_UPLOAD_SIZE) {
            return simpleResponse(413, "Payload Too Large")
        }

        val file = File(serveDirectory, path)

        // Security: prevent path traversal
        if (!file.canonicalPath.startsWith(File(serveDirectory).canonicalPath)) {
            return simpleResponse(403, "Forbidden")
        }

        return try {
            // Create parent directories if needed
            file.parentFile?.mkdirs()

            file.writeBytes(body)
            logAccess(request.method, path, 201, body.size.toLong(), clientIp, request.headers["user-agent"])
            Log.i(TAG, "File uploaded: $path (${body.size} bytes)")
            simpleResponse(201, "Created: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: $path", e)
            simpleResponse(500, "Internal Server Error: ${e.message}")
        }
    }

    /**
     * Handle DELETE request.
     */
    private fun handleDelete(path: String, clientIp: String): HttpResponse {
        val file = File(serveDirectory, path)

        if (!file.canonicalPath.startsWith(File(serveDirectory).canonicalPath)) {
            return simpleResponse(403, "Forbidden")
        }

        if (!file.exists()) return simpleResponse(404, "Not Found")
        if (file.isDirectory) return simpleResponse(400, "Cannot delete directories")

        return try {
            val deleted = file.delete()
            if (deleted) {
                logAccess("DELETE", path, 200, 0, clientIp, null)
                simpleResponse(200, "Deleted: $path")
            } else {
                simpleResponse(500, "Failed to delete: $path")
            }
        } catch (e: Exception) {
            simpleResponse(500, "Error: ${e.message}")
        }
    }

    /**
     * Handle CGI execution.
     */
    private fun handleCgi(request: HttpRequest, path: String, handler: String, clientIp: String): HttpResponse {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(handler, "-c", path.removePrefix("/")))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val bytes = output.toByteArray()
            totalBytesServed.addAndGet(bytes.size.toLong())
            logAccess(request.method, path, 200, bytes.size.toLong(), clientIp, request.headers["user-agent"])

            HttpResponse(200, "OK",
                mapOf("Content-Type" to "text/plain; charset=utf-8", "Content-Length" to bytes.size.toString()),
                bytes)
        } catch (e: Exception) {
            Log.e(TAG, "CGI execution failed: $path", e)
            simpleResponse(500, "CGI Error: ${e.message}")
        }
    }

    /**
     * Handle OPTIONS request (CORS preflight).
     */
    private fun handleOptions(): HttpResponse {
        return HttpResponse(204, "No Content",
            mapOf(
                "Allow" to "GET, HEAD, PUT, POST, DELETE, OPTIONS",
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, HEAD, PUT, POST, DELETE, OPTIONS",
                "Access-Control-Allow-Headers" to "Content-Type, Authorization"
            ), null)
    }

    private fun methodNotAllowed(): HttpResponse = simpleResponse(405, "Method Not Allowed")

    // ---- Response writing ----

    /**
     * Write an HTTP response to the output stream.
     */
    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        try {
            val writer = output.bufferedWriter()
            writer.write("HTTP/1.1 ${response.statusCode} ${response.statusText}\r\n")
            response.headers.forEach { (key, value) ->
                writer.write("$key: $value\r\n")
            }
            // Add server header
            writer.write("Server: TermX/1.0\r\n")
            writer.write("Connection: keep-alive\r\n")
            writer.write("\r\n")
            writer.flush()

            response.body?.let { output.write(it) }
            output.flush()
        } catch (e: Exception) {
            Log.d(TAG, "Response write error", e)
        }
    }

    // ---- Helpers ----

    /**
     * Read a line from an input stream (CRLF-terminated).
     */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var prev = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() > 0) buf.toString("UTF-8") else null
            if (b == '\n'.code && prev == '\r'.code) {
                return buf.toString("UTF-8")
            }
            if (b != '\r'.code) buf.write(b)
            prev = b
        }
    }

    /**
     * Check basic authentication credentials.
     */
    private fun checkAuth(authHeader: String?): Boolean {
        if (authUser == null) return true  // No auth required
        if (authHeader == null || !authHeader.startsWith("Basic ")) return false

        return try {
            val credentials = String(Base64.getDecoder().decode(authHeader.substring(6)))
            val parts = credentials.split(":", limit = 2)
            parts.size == 2 && parts[0] == authUser && parts[1] == authPass
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Log an access entry.
     */
    private fun logAccess(method: String, path: String, status: Int, size: Long, clientIp: String, userAgent: String?) {
        val entry = LogEntry(System.currentTimeMillis(), method, path, status, size, clientIp, userAgent)
        accessLog.add(entry)
        // Trim log if it exceeds max
        while (accessLog.size > MAX_LOG_ENTRIES) {
            accessLog.poll()
        }
    }

    private fun simpleResponse(status: Int, text: String): HttpResponse {
        val body = "$status $text".toByteArray()
        return HttpResponse(status, text,
            mapOf("Content-Type" to "text/plain", "Content-Length" to body.size.toString()),
            body)
    }

    private fun getMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return MIME_TYPES[ext] ?: "application/octet-stream"
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("GMT")
        return sdf.format(Date(timestamp))
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) "${hours}h ${minutes}m ${secs}s"
        else if (minutes > 0) "${minutes}m ${secs}s"
        else "${secs}s"
    }

    /**
     * Get the local IP address of this device on the Wi-Fi network.
     */
    private fun getLocalIpAddress(context: Context): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
            "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }
}
