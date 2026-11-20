package com.dshgo.app.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast

/**
 * 网页里的「复制」。
 *
 * 为什么需要这个类：DSH 的文件菜单「复制文件路径」最后调的是
 * `navigator.clipboard.writeText(...)`，而 `navigator.clipboard` 只在**安全上下文**
 * （https 或 localhost）里存在。手机连的是 `http://10.x.x.x:3081`，页面拿到的就是
 * `undefined`，菜单于是直接报「无法复制」。
 *
 * 做法：在页面里给 `navigator.clipboard` 装一个替身，`writeText` 转给原生剪贴板。
 *
 * 线程注意：`@JavascriptInterface` 方法跑在 WebView 的 JavaBridge 线程，**不是** UI 线程。
 * 那里读 `webView.url` 拿到的是 null（WebView 非线程安全），所以当前页面地址必须由 UI
 * 线程提前缓存到 [pageUrl] 里，判同源时只用缓存。
 */
class ClipboardBridge(
    private val context: Context,
    private val webView: WebView,
    entryUrl: String,
) {

    private val entryHost: String? = runCatching { Uri.parse(entryUrl).host }.getOrNull()

    @Volatile
    private var pageUrl: String? = null

    /** UI 线程调用：记下当前页面地址（JavaBridge 线程不能直接读 webView.url）。 */
    fun noteUrl(url: String?) {
        pageUrl = url
    }

    @JavascriptInterface
    fun copy(text: String) {
        if (!trusted()) {
            Log.w(TAG, "复制被拒：页面不是当前连接（page=$pageUrl）")
            toast("复制被拒：页面不是当前连接")
            return
        }
        val value = text.trim()
        if (value.isEmpty()) {
            toast("复制内容为空")
            return
        }
        val ok = runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("dsh", value))
        }.isSuccess
        if (ok) {
            Log.i(TAG, "已复制 ${value.length} 字")
            toast("已复制 ${value.length} 字")
        } else {
            toast("复制失败：系统剪贴板不可用")
        }
    }

    /** 页面载入后装替身。幂等：`window.__dshgoClipboard` 已经在了就直接返回。 */
    fun installHook() {
        webView.post {
            webView.evaluateJavascript(HOOK_JS) { }
        }
    }

    private fun trusted(): Boolean {
        val host = entryHost ?: return true          // 没有条目 host 可比：放行（自建/纯 IP 场景）
        val current = pageUrl ?: return true         // 还没记到地址（首屏极早期）：放行
        val pageHost = runCatching { Uri.parse(current).host }.getOrNull() ?: return true
        return pageHost.equals(host, ignoreCase = true)
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "dshgo-clipboard"

        val HOOK_JS = """
            (function () {
              if (window.__dshgoClipboard) return;
              window.__dshgoClipboard = true;
              // http:// 不是安全上下文，页面里的 navigator.clipboard 是 undefined
              // （菜单「复制文件路径」就报「无法复制」）。给一个能用的替身。
              try {
                var clip = navigator.clipboard;
                if (!clip) {
                  clip = {};
                  Object.defineProperty(navigator, 'clipboard', {
                    configurable: true,
                    get: function () { return clip; },
                  });
                }
                clip.writeText = function (t) {
                  try { window.dshgoClipboard.copy(String(t)); return Promise.resolve(); }
                  catch (e) { return Promise.reject(e); }
                };
                clip.readText = function () { return Promise.reject(new Error('unsupported')); };
              } catch (e) {}
            })();
        """.trimIndent()
    }
}
