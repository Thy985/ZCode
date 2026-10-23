package ai.zcode.mobile.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import ai.zcode.mobile.BuildConfig
import ai.zcode.mobile.remote.RemoteControlUrlPolicy
import ai.zcode.mobile.remote.ZCodeEndpoint

/**
 * Bugfix: 官方壳的 WebView 配置是按桌面远控页写的——禁缩放、禁 content 访问、没有文件选择器，
 * 于是手机上点「添加图片/附件」毫无反应，桌面布局在小屏上挤成一团，明文 http 后端还会被
 * MIXED_CONTENT_NEVER_ALLOW 直接把 ws 实时通道掐断。
 * 这里把 WebView 抽成独立一层，按端点类型决定安全档位，并补上手机端必需的出口：
 * 附件选择、拍照、下载、target=_blank、配对链接跳转。
 */

/** 页面 `<input type=file>` 触发后，由 Compose 层拉起系统选择器或相机再回填。 */
fun interface ZCodeFileChooserCallback {
    fun onResult(uris: List<Uri>)
}

data class ZCodeWebViewConfig(
    val endpoint: ZCodeEndpoint,
    val desktopMode: Boolean = false,
)

class ZCodeWebViewHost(
    context: Context,
    private val config: ZCodeWebViewConfig,
    private val onLoadingChanged: (Boolean) -> Unit,
    private val onProgressChanged: (Int) -> Unit,
    private val onMainFrameError: (String) -> Unit,
    private val onExternalNavigation: (Uri) -> Unit,
    private val onDownloadRequested: (String) -> Unit,
    private val onPairingRequest: (Uri) -> Boolean,
    private val onFileChooser: (WebChromeClient.FileChooserParams, ZCodeFileChooserCallback) -> Boolean,
) {
    private var pendingChooser: ValueCallback<Array<Uri>>? = null

    /** WebView 当前所在页面的 origin，用于判定「这次导航是从哪儿发起的」。 */
    private var currentOriginKey: String? = null

    val webView: WebView = WebView(context).apply {
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        overScrollMode = WebView.OVER_SCROLL_NEVER
        applyEndpointSettings(settings, config)
        webChromeClient = ChromeClient()
        webViewClient = Client()
        setDownloadListener { url, _, _, _, _ ->
            if (url.isNotBlank()) {
                onDownloadRequested(url)
            }
        }
    }

    fun load(url: String) {
        webView.loadUrl(url)
    }

    fun destroy() {
        // 页面在等的 callback 必须收尾，否则 web 端的上传组件会一直停在选状态。
        pendingChooser?.onReceiveValue(null)
        pendingChooser = null
        webView.destroy()
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgressChanged(newProgress)
        }

        override fun onShowFileChooser(
            view: WebView?,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            pendingChooser?.onReceiveValue(null)
            pendingChooser = null
            val handled = onFileChooser(
                params,
                ZCodeFileChooserCallback { uris ->
                    pendingChooser = null
                    callback.onReceiveValue(uris.toTypedArray().takeIf { it.isNotEmpty() })
                },
            )
            if (handled) {
                pendingChooser = callback
            }
            return handled
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // 只放开剪贴板写入。摄像头/麦克风一律拒绝——手机传图走原生选择器，不走网页 getUserMedia。
            // Android 没有公开的 RESOURCE_CLIPBOARD_WRITE 常量，可用的是 API 33 起的 "clipboard-sanitized-write"。
            val origin = request.origin?.let { ZCodeEndpoint.originKeyOf(Uri.parse(it.toString())) }
            val allowed = if (origin != null && origin == config.endpoint.originKey() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                request.resources.filter { it == CLIPBOARD_SANITIZED_WRITE }
            } else {
                emptyList()
            }
            if (allowed.isEmpty()) {
                request.deny()
            } else {
                request.grant(allowed.toTypedArray())
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            currentOriginKey = url?.let { raw ->
                runCatching { ZCodeEndpoint.originKeyOf(Uri.parse(raw)) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
            onLoadingChanged(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onLoadingChanged(false)
            if (!isTrustedUrl(url)) {
                return
            }
            view?.evaluateJavascript(MOBILE_ADAPTATION_SCRIPT, null)
            // 自建后端加载的是桌面工作区页，额外叠一层窄视口适配；官方 /remote 页上游已按手机写过。
            if (MobileAdaptation.appliesTo(config.endpoint, config.desktopMode)) {
                view?.evaluateJavascript(MobileAdaptation.script(), null)
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val uri = request.url
            if (!request.isForMainFrame) {
                return false
            }

            // 注入脚本把 target=_blank 与 window.open 转成 zcode-ext:<encoded>，这里是唯一出口。
            if (uri.scheme == EXTERNAL_SCHEME) {
                decodeExternal(uri)?.let(onExternalNavigation)
                return true
            }

            if (uri.scheme == "zcode") {
                return onPairingRequest(uri)
            }

            // 钉住 origin 内的页面留在 App 里；官方 relay 的登录链路（chat.z.ai / bigmodel.cn）
            // 同样留在 App 里，否则 WebView 拿不到会话 cookie，扫码后停在 auth-required。
            val navigation = RemoteControlUrlPolicy.decideNavigation(
                endpoint = config.endpoint,
                uri = uri,
                currentOriginKey = currentOriginKey,
            )
            if (navigation == RemoteControlUrlPolicy.NavigationDecision.LoadInPlace) {
                return false
            }

            onExternalNavigation(uri)
            return true
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame != true) {
                return
            }
            onLoadingChanged(false)
            onMainFrameError(error?.description?.toString().orEmpty())
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            if (request?.isForMainFrame == true) {
                onLoadingChanged(false)
                onMainFrameError("HTTP ${errorResponse?.statusCode ?: 0}")
            }
        }
    }

    private fun isTrustedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) {
            return false
        }
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return config.endpoint.originKey() == ZCodeEndpoint.originKeyOf(uri)
    }

    private fun decodeExternal(uri: Uri): Uri? {
        val encoded = uri.schemeSpecificPart?.removePrefix("//") ?: return null
        return runCatching { Uri.parse(Uri.decode(encoded)) }
            .getOrNull()
            ?.takeIf { it.scheme == "http" || it.scheme == "https" }
    }

    companion object {
        private const val EXTERNAL_SCHEME = "zcode-ext"
        private const val CLIPBOARD_SANITIZED_WRITE = "clipboard-sanitized-write"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        @SuppressLint("SetJavaScriptEnabled")
        private fun applyEndpointSettings(settings: WebSettings, config: ZCodeWebViewConfig) {
            val endpoint = config.endpoint
            val baseline = settings.userAgentString
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false

                // 手机端必须能双指缩放，官方壳把它关了，桌面布局在小屏上不可读。
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = config.desktopMode
                loadWithOverviewMode = config.desktopMode

                allowFileAccess = false
                allowContentAccess = true
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = if (endpoint.isCleartext) {
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                } else {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                userAgentString = if (config.desktopMode) {
                    DESKTOP_USER_AGENT
                } else {
                    "$baseline ZCodeAndroid/${BuildConfig.VERSION_NAME}"
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
        }

        /**
         * 只在可信 origin 上执行：补 viewport-fit、抬高输入框字号（避免 Android 聚焦时自动放大糊版）、
         * 并把 target=_blank / window.open 收敛到 zcode-ext: 出口。
         * 注意 evaluateJavascript 只吃裸 JS，不能用 "javascript:" 前缀。
         */
        private val MOBILE_ADAPTATION_SCRIPT = """
(function () {
  try {
    var d = document;
    if (!d || !d.head) { return; }
    var want = 'width=device-width, initial-scale=1, viewport-fit=cover';
    var meta = d.querySelector('meta[name=viewport]');
    if (!meta) {
      meta = d.createElement('meta');
      meta.setAttribute('name', 'viewport');
      d.head.appendChild(meta);
    }
    if (meta.getAttribute('content') !== want) { meta.setAttribute('content', want); }
    if (!d.getElementById('zcode-mobile-shell')) {
      var style = d.createElement('style');
      style.id = 'zcode-mobile-shell';
      style.textContent = [
        'html{-webkit-text-size-adjust:100%;}',
        '@media (max-width:720px){textarea,input[type=text],input[type=search]{font-size:16px!important;}}'
      ].join('');
      d.head.appendChild(style);
    }
    if (window.__zcodeShellHooked) { return; }
    window.__zcodeShellHooked = true;
    window.__zcodeNative = { platform: 'android', shell: '${BuildConfig.VERSION_NAME}' };
    var exit = function (href) {
      try { window.location.href = '$EXTERNAL_SCHEME://' + encodeURIComponent(href); } catch (e) {}
    };
    d.addEventListener('click', function (event) {
      var node = event.target;
      while (node && node.tagName !== 'A') { node = node.parentElement; }
      if (node && node.target === '_blank' && node.href) {
        event.preventDefault();
        exit(node.href);
      }
    }, true);
    var nativeOpen = window.open;
    window.open = function (url) {
      if (url) { exit(String(url)); } else { try { nativeOpen.call(window); } catch (e) {} }
      return null;
    };
  } catch (e) {}
})();
"""
    }
}
