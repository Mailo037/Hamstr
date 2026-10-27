package app.hamstr.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Android's own browser resolves the player; downloads need neither Chrome nor a server. */
@SuppressLint("StaticFieldLeak") // Only the application context is retained, never an Activity.
internal object CineSrc {
    private lateinit var context: Context
    private val slot = Mutex()

    fun init(context: Context) { this.context = context.applicationContext }

    suspend fun resolve(target: CineSrcTarget): String = slot.withLock {
        withTimeoutOrNull(90_000) { resolvePlayer(target) }
            ?: error("CineSrc did not expose an accessible HLS stream within 90 seconds. Check the connection and Android System WebView, then retry.")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolvePlayer(target: CineSrcTarget): String = coroutineScope {
        val resolverScope = this
        val playerUrl = target.embed + (if ('?' in target.embed) "&" else "?") + "autoplay=false&muted=true"
        val result = CompletableDeferred<Pair<String, String>>()
        val pageTitle = CompletableDeferred<String>()
        val seen = ConcurrentHashMap.newKeySet<String>()
        var browser: WebView? = null
        var userAgent = ""
        try {
            withContext(Dispatchers.Main) {
                val view = WebView(context)
                browser = view
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    mediaPlaybackRequiresUserGesture = true
                    setSupportMultipleWindows(false)
                    // Use the installed WebView's current browser version.
                    userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
                    userAgent = userAgentString
                }
                view.webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView, title: String?) {
                        if (!title.isNullOrBlank() && !title.startsWith("CineSrc")) pageTitle.complete(title)
                    }
                }
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.toString() != playerUrl

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        if (url != playerUrl) view.stopLoading()
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        // Ignore ads and embedded third-party players. Only CineSrc's own
                        // playlist endpoint is allowed to hand a URL to the native engine.
                        if (uri.scheme == "https" && uri.host == "cinesrc.st" &&
                            uri.path.orEmpty().startsWith("/api/playlist/") && uri.path.orEmpty().endsWith(".m3u8") &&
                            seen.add(uri.toString()) && !result.isCompleted
                        ) {
                            val url = uri.toString()
                            resolverScope.launch(Dispatchers.IO) {
                                try {
                                    result.complete(url to playlist(url, userAgent, target.embed))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w("HamstrCineSrc", "Native playlist verification failed", e)
                                }
                            }
                        }
                        return null
                    }
                }
                view.loadUrl(playerUrl)
            }
            val (url, body) = result.await()
            val title = withTimeoutOrNull(3_000) { pageTitle.await() } ?: target.id
            info(target, title, url, body, userAgent).toString()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                browser?.apply { stopLoading(); webViewClient = WebViewClient(); webChromeClient = null; destroy() }
            }
        }
    }

    private suspend fun playlist(url: String, ua: String, referer: String): String = runInterruptible {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", ua)
            connection.setRequestProperty("Referer", referer)
            check(connection.responseCode in 200..299) { "CineSrc playlist returned HTTP ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            check(body.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) { "CineSrc did not return an HLS playlist" }
            body
        } finally { connection.disconnect() }
    }

    /** yt-dlp JSON lets either downloader reuse the same WebView resolution and qualities. */
    private fun info(target: CineSrcTarget, title: String, url: String, body: String, ua: String): JSONObject {
        val formats = JSONArray()
        val headers = JSONObject().put("User-Agent", ua).put("Referer", target.embed)
        fun format(id: String, stream: String, height: Int? = null): JSONObject = JSONObject()
            .put("format_id", id).put("url", stream).put("ext", "mp4").put("protocol", "m3u8_native")
            .put("vcodec", "unknown").put("acodec", "unknown").put("http_headers", headers)
            .apply { if (height != null) put("height", height) }
        var height: Int? = null
        var pending = false
        for (line in body.lineSequence().map(String::trim)) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                pending = true
            } else if (pending && line.isNotEmpty() && !line.startsWith('#')) {
                val variant = URI(url).resolve(line)
                if (variant.scheme == "https") formats.put(format("hls-${height ?: formats.length()}", variant.toString(), height))
                pending = false
            }
        }
        if (formats.length() == 0) formats.put(format("hls", url))
        // Both yt-dlp and Cheek's compatible selector expect worst-to-best order.
        val sorted = JSONArray((0 until formats.length()).map { formats.getJSONObject(it) }.sortedBy { it.optInt("height") })
        return JSONObject().put("id", target.id).put("title", title).put("webpage_url", target.embed)
            .put("extractor", "CineSrc").put("extractor_key", "CineSrc").put("formats", sorted)
            .put("http_headers", headers).put("is_live", false)
    }
}
