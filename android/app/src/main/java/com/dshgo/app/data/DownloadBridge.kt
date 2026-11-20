package com.dshgo.app.data

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import java.io.File
import java.io.OutputStream
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 网页里的文件下载。
 *
 * 为什么需要这个类：DSH 的「下载 / 另存为」不是发一个普通链接请求，而是
 * `URL.createObjectURL(blob)` + `document.createElement('a').click()`（桌面端才用
 * `window.desktop.save()`）。WebView 对 `blob:` 既不发起网络请求、也不会自己弹任何东西，
 * 所以**没有 DownloadListener 的时候点下载就是彻底静默**——页面还会提示「已开始下载」。
 *
 * 两条路：
 * 1. `blob:` / `data:` → 页面里 `fetch` 回原始字节，切成 384 KB 一片用 Base64 通过
 *    `dshgoDownload` 这个 JS 接口送到原生，边收边写（不整块进内存，8 MB 文件也不炸）。
 * 2. 普通 http(s) → 交给系统的 [DownloadManager]，顺带把 WebView 的 Cookie 带过去
 *    （DSH 的附件下载要过 `dsh-auth-*` 那层闸门）。
 *
 * 安全边界：所有 [JavascriptInterface] 方法只接受「当前页面 host == 连接条目 host」时
 * 的调用。这样即使页面被换成了别的站点（例如用户点开了外链），也调不动写文件的接口。
 */
class DownloadBridge(
    private val context: Context,
    private val webView: WebView,
    entryUrl: String,
) {

    private class Sink(
        val name: String,
        val mime: String,
        val out: OutputStream,
        val uri: Uri?,
        val file: File?,
    )

    private val sinks = ConcurrentHashMap<String, Sink>()

    private val entryHost: String? = runCatching { Uri.parse(entryUrl).host }.getOrNull()

    // ------------------------------------------------------------------
    // 入口：DownloadListener
    // ------------------------------------------------------------------

    fun handle(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val name = guessName(url, contentDisposition, mimeType)
        when {
            url.startsWith("blob:", true) || url.startsWith("data:", true) ->
                fetchInPage(url, name)
            url.startsWith("http://", true) || url.startsWith("https://", true) ->
                startHttp(url, userAgent, name, mimeType)
            else -> toast("这个地址没法下载：${url.take(40)}")
        }
    }

    /** 普通 http(s)：DownloadManager 负责落盘和通知栏进度。 */
    private fun startHttp(url: String, userAgent: String?, name: String, mimeType: String?) {
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
                userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
                mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            toast("开始下载：$name")
        }.onFailure {
            Log.w(TAG, "DownloadManager 失败：$url", it)
            toast("下载失败：${it.message?.take(60) ?: "未知原因"}")
        }
    }

    /**
     * `blob:` / `data:`：页面里读字节，切片送回原生。
     *
     * 为什么用 `FileReader.readAsDataURL` 而不是 `arrayBuffer` + 自己 base64：
     * 后者要在 JS 里手写 base64 编码，慢且容易在超大文件上把主线程卡死；
     * `readAsDataURL` 是浏览器原生实现，切到 384 KB 一片后几乎没有开销。
     */
    private fun fetchInPage(url: String, name: String) {
        val script = """
            (function () {
              var src = ${quote(url)}, nm = ${quote(name)};
              fetch(src).then(function (r) { return r.blob(); }).then(function (b) {
                var id = String(Date.now()) + Math.random().toString(36).slice(2, 8);
                var mime = b.type || 'application/octet-stream';
                if (typeof dshgoDownload === 'undefined') { return; }
                dshgoDownload.begin(id, nm, mime);
                var CHUNK = 393216, off = 0;
                function step() {
                  if (off >= b.size) { dshgoDownload.end(id); return; }
                  var part = b.slice(off, off + CHUNK);
                  off += CHUNK;
                  var fr = new FileReader();
                  fr.onload = function () {
                    var s = String(fr.result || '');
                    var i = s.indexOf(',');
                    dshgoDownload.send(id, i >= 0 ? s.slice(i + 1) : '');
                    step();
                  };
                  fr.onerror = function () { dshgoDownload.fail(id, '读不到文件内容'); };
                  fr.readAsDataURL(part);
                }
                step();
              }).catch(function (e) {
                if (typeof dshgoDownload !== 'undefined') dshgoDownload.fail('', '下载失败：' + e);
              });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    // ------------------------------------------------------------------
    // JS 接口
    // ------------------------------------------------------------------

    @JavascriptInterface
    fun begin(id: String, name: String, mime: String) {
        if (!trusted()) return
        runCatching {
            val safe = sanitize(name)
            val m = mime.ifBlank { "application/octet-stream" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, m)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("系统没有给出下载条目")
                val out = context.contentResolver.openOutputStream(uri)
                    ?: error("打不开下载条目")
                sinks[id] = Sink(safe, m, out, uri, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "downloads")
                dir.mkdirs()
                val file = File(dir, safe)
                sinks[id] = Sink(safe, m, file.outputStream(), null, file)
            }
        }.onFailure {
            Log.w(TAG, "begin 失败", it)
            fail(id, it.message ?: "创建文件失败")
        }
    }

    @JavascriptInterface
    fun send(id: String, base64: String) {
        val sink = sinks[id] ?: return
        runCatching {
            sink.out.write(Base64.decode(base64, Base64.DEFAULT))
        }.onFailure {
            Log.w(TAG, "写入失败：${sink.name}", it)
            fail(id, "写入失败：${it.message?.take(40) ?: "未知原因"}")
        }
    }

    @JavascriptInterface
    fun end(id: String) {
        val sink = sinks.remove(id) ?: return
        runCatching {
            sink.out.flush()
            sink.out.close()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sink.uri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(sink.uri, values, null, null)
                toast("已保存到「下载」：${sink.name}")
            } else if (sink.file != null) {
                toast("已保存：${sink.file.absolutePath}")
            }
        }.onFailure {
            Log.w(TAG, "收尾失败：${sink.name}", it)
            toast("保存失败：${it.message?.take(60) ?: "未知原因"}")
        }
    }

    @JavascriptInterface
    fun fail(id: String, message: String) {
        sinks.remove(id)?.let { runCatching { it.out.close() } }
        toast(message.take(120))
    }

    /**
     * 页面里的钩子把 `blob:` / `data:` 地址交回这里，走和 DownloadListener 同一条分片通道。
     *
     * 为什么需要它：WebView 对 `<a download href="blob:…">.click()` **既不发网络请求、
     * 也不触发 DownloadListener**，所以光有 setDownloadListener 在真机上是静默的
     * （2026-11-20 实测：点「下载」毫无反应）。只能自己在页面里把这次点击截下来。
     *
     * 必须 post 回主线程：这个方法是 JS 桥在 JavaBridge 线程上调用进来的，
     * 而 evaluateJavascript 只能在主线程用。
     */
    @JavascriptInterface
    fun saveBlobUrl(url: String, name: String) {
        val safe = name.ifBlank { guessName(url, null, null) }
        if (!trusted(url)) {
            toast("下载被拒：页面不是当前连接")
            return
        }
        toast("收到下载：$safe")
        webView.post { fetchInPage(url, safe) }
    }

    fun installHook() {
        noteUrl(webView.url)
        runCatching {
            webView.evaluateJavascript(HOOK_JS, null)
            // 装完立刻回读一次：页面里到底有没有 __dshgoDlHook，这是唯一可靠的证据。
            webView.evaluateJavascript("String(!!window.__dshgoDlHook)") { value ->
                val ok = value?.contains("true") == true
                if (ok) {
                    if (!hookVerified) {
                        hookVerified = true
                        toast("下载钩子已就位")
                    }
                    Log.i(TAG, "钩子已就位 ${webView.url}")
                } else {
                    Log.w(TAG, "钩子未装上：$value")
                }
            }
        }.onFailure { Log.w(TAG, "装下载钩子失败", it) }
    }

    private var hookVerified = false

    // ------------------------------------------------------------------

    /** 只有当前页面和连接条目同源时才认 JS 接口的调用。 */
    private fun trusted(url: String? = null): Boolean {
        val expected = entryHost
        if (expected.isNullOrBlank()) return true

        // 页面地址必须在 UI 线程读，JS 桥线程上 webView.url 会是 null —— 之前就是栽在这里，
        // 导致钩子明明拦到了下载，却被判成「不是当前连接」直接丢掉。
        val currentHost = runCatching { pageUrl?.let { Uri.parse(it).host } }.getOrNull()
        if (!currentHost.isNullOrBlank()) {
            val ok = currentHost == expected
            if (!ok) Log.w(TAG, "主机不匹配 page=$pageUrl entry=$entryHost")
            return ok
        }

        // 页面地址还没记下时，退一步看 URL 自己带的 origin（blob:http://host:port/uuid 里就有）。
        val fromUrl = runCatching {
            url?.removePrefix("blob:")?.let { Uri.parse(it).host }
        }.getOrNull()
        if (!fromUrl.isNullOrBlank()) return fromUrl == expected

        Log.w(TAG, "没有页面地址可比，放行 url=$url")
        return true
    }

    /** 由 UI 线程（每次页面载入/导航）记下当前地址，供 JS 桥判断同源。 */
    fun noteUrl(url: String?) {
        pageUrl = url
    }

    @Volatile
    private var pageUrl: String? = null

    private fun toast(text: String) {
        val activity = context as? android.app.Activity
        activity?.runOnUiThread { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
            ?: Log.i(TAG, text)
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        val fallback = "dshgo-${System.currentTimeMillis()}"
        return (cleaned.ifBlank { fallback }).take(120)
    }

    /** 从 Content-Disposition / URL 里猜一个像样的文件名。 */
    private fun guessName(url: String, contentDisposition: String?, mimeType: String?): String {
        contentDisposition?.let { cd ->
            val star = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(cd)
            if (star != null) {
                val raw = star.groupValues[1].trim().trim('"')
                val value = raw.substringAfter("''", raw)
                val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
                if (decoded.isNotBlank()) return decoded
            }
            val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)
            plain?.groupValues?.getOrNull(1)?.trim()?.let { if (it.isNotBlank()) return it }
        }
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (last.isNotBlank() && !last.startsWith("blob:")) return last
        val ext = when {
            mimeType?.contains("pdf", true) == true -> ".pdf"
            mimeType?.contains("png", true) == true -> ".png"
            mimeType?.contains("jpeg", true) == true || mimeType?.contains("jpg", true) == true -> ".jpg"
            mimeType?.contains("zip", true) == true -> ".zip"
            mimeType?.startsWith("text/", true) == true -> ".txt"
            else -> ""
        }
        return "dshgo-${System.currentTimeMillis()}$ext".lowercase(Locale.ROOT)
    }

    /** 把任意字符串变成 JS 字面量（文件名里可能有引号、反斜杠）。 */
    private fun quote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("</", "<\\/")
        return "\"$escaped\""
    }

    private companion object {
        const val TAG = "dshgo-download"

        /**
         * 装进页面的钩子。
         *
         * DSH 的「下载」最终就是一句 `anchor.download = name; anchor.click()`，href 是
         * `createObjectURL(blob)` 出来的 `blob:` 地址。WebView 对这种点击什么都不做，
         * 所以这里把 `HTMLAnchorElement.prototype.click`（以及 `dispatchEvent('click')`
         * 那种写法）换掉：只要 href 是 `blob:`/`data:` 且带 download 属性，就交给原生。
         *
         * 只挂原型上的方法，不碰页面数据；同一个页面装两次会直接返回。
         */
        val HOOK_JS = """
            (function () {
              if (window.__dshgoDlHook) return;
              window.__dshgoDlHook = true;

              var blobs = window.__dshgoBlobs || (window.__dshgoBlobs = new Map());
              var origCreate = URL.createObjectURL;
              URL.createObjectURL = function (obj) {
                var url = origCreate.call(URL, obj);
                try {
                  if (obj && typeof obj.size === 'number') {
                    blobs.set(url, obj);
                    if (blobs.size > 16) { blobs.delete(blobs.keys().next().value); }
                  }
                } catch (e) {}
                return url;
              };

              function handoff(href, name) {
                try {
                  if (window.dshgoDownload && window.dshgoDownload.saveBlobUrl) {
                    window.dshgoDownload.saveBlobUrl(href, name || '');
                    return true;
                  }
                } catch (e) {}
                return false;
              }

              function isOurs(href, anchor) {
                if (!href) return false;
                if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return false;
                return !!(anchor.hasAttribute && anchor.hasAttribute('download'));
              }

              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                try {
                  if (isOurs(this.href, this) && handoff(this.href, this.getAttribute('download'))) return;
                } catch (e) {}
                return origClick.apply(this, arguments);
              };

              var origDispatch = HTMLAnchorElement.prototype.dispatchEvent;
              HTMLAnchorElement.prototype.dispatchEvent = function (ev) {
                try {
                  if (ev && ev.type === 'click' && isOurs(this.href, this) &&
                      handoff(this.href, this.getAttribute('download'))) return true;
                } catch (e) {}
                return origDispatch.apply(this, arguments);
              };

            })();
        """.trimIndent()
    }
}
