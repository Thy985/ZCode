package ai.zcode.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.zcode.mobile.remote.RemoteControlKeepAliveService
import ai.zcode.mobile.remote.RemoteControlUrlPolicy
import ai.zcode.mobile.remote.TrustDecision
import ai.zcode.mobile.remote.ZCodeEndpoint
import ai.zcode.mobile.remote.ZCodeEndpointStore
import ai.zcode.mobile.ui.RemoteControlApp
import ai.zcode.mobile.ui.theme.ZcodemobileTheme

/**
 * Bugfix: 官方壳的状态是「一个 url 字符串」，只能同时存在一个远控会话，
 * 也没有后端配置的概念。这里改成以 ZCodeEndpoint 为中心：
 * 当前会话是一个端点（含令牌），加上一条待确认的信任请求，分享进来的文本单独暂存。
 */
class MainActivity : ComponentActivity() {
    private var endpoint by mutableStateOf<ZCodeEndpoint?>(null)
    private var loadUrl by mutableStateOf<String?>(null)
    private var endpoints by mutableStateOf<List<ZCodeEndpoint>>(emptyList())
    private var pendingTrust by mutableStateOf<TrustDecision.NeedsTrust?>(null)
    private var pendingShareText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        endpoints = ZCodeEndpointStore.readAll(this)

        val restored = savedInstanceState?.getString(KEY_ENDPOINT_ID)?.let { ZCodeEndpointStore.find(this, it) }
            ?: intent.getStringExtra(RemoteControlKeepAliveService.EXTRA_ENDPOINT_ID)
                ?.let { ZCodeEndpointStore.find(this, it) }
        if (restored != null) {
            openEndpoint(restored)
        } else {
            handleViewIntent(intent)
        }
        captureShareText(intent)

        setContent {
            ZcodemobileTheme {
                RemoteControlApp(
                    endpoint = endpoint,
                    loadUrl = loadUrl,
                    endpoints = endpoints,
                    pendingTrust = pendingTrust,
                    pendingShareText = pendingShareText,
                    onOpenEndpoint = { target ->
                        pendingShareText = null
                        openEndpoint(target)
                    },
                    onOpenRaw = ::openRaw,
                    onCloseRemote = ::closeRemote,
                    onSaveEndpoint = ::saveEndpoint,
                    onDeleteEndpoint = ::deleteEndpoint,
                    onConfirmTrust = ::confirmTrust,
                    onDismissTrust = { pendingTrust = null },
                    onShareTextConsumed = { pendingShareText = null },
                    onClearSession = ::clearSession,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val restored = intent.getStringExtra(RemoteControlKeepAliveService.EXTRA_ENDPOINT_ID)
            ?.let { ZCodeEndpointStore.find(this, it) }
        if (restored != null && openEndpoint(restored)) {
            return
        }
        if (!handleViewIntent(intent)) {
            captureShareText(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        endpoint?.id?.let { outState.putString(KEY_ENDPOINT_ID, it) }
    }

    /**
     * Bugfix: 官方 relay 走 WebView 会话，Android 只在大致合适时机才把 cookie 落盘，
     * 进程被回收后登录态会丢，扫码配对又得从头来一次。退到后台时主动 flush。
     */
    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    /** 顶部「退出登录」：清掉 WebView 的 cookie 与 DOM storage，换 Z.ai 账号或配对卡死时用。 */
    private fun clearSession() {
        val cookies = CookieManager.getInstance()
        cookies.removeSessionCookies(null)
        cookies.removeAllCookies(null)
        cookies.flush()
        WebStorage.getInstance().deleteAllData()
    }

    private fun openEndpoint(target: ZCodeEndpoint): Boolean = openResolved(target, target.entryUrl())

    private fun openResolved(target: ZCodeEndpoint, url: String): Boolean {
        if (url.isBlank()) {
            return false
        }
        pendingTrust = null
        endpoint = target
        loadUrl = url
        ZCodeEndpointStore.touch(this, target.id).also { endpoints = it }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNotificationPermissionIfNeeded()
        // 前台服务只带脱敏地址，令牌不进通知、不进最近任务缩略图。
        val title = target.name.ifBlank { getString(R.string.remote_endpoint_official_name) }
        RemoteControlKeepAliveService.start(this, target.id, title, ZCodeEndpoint.redactToken(url))
        return true
    }

    /** 扫码、手输、深链共用入口：命中端点直接打开，未登记来源转为一次信任确认。 */
    private fun openRaw(raw: String?): Boolean = openDecision(RemoteControlUrlPolicy.decide(this, raw))

    private fun openDecision(decision: TrustDecision): Boolean = when (decision) {
        is TrustDecision.Pinned -> openResolved(decision.endpoint, decision.loadUrl)
        is TrustDecision.NeedsTrust -> {
            pendingTrust = decision
            false
        }
        is TrustDecision.Invalid -> false
    }

    private fun handleViewIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (intent.action != Intent.ACTION_VIEW) {
            return false
        }
        val decision = RemoteControlUrlPolicy.decide(this, data)
        if (decision is TrustDecision.Pinned) {
            return openResolved(decision.endpoint, decision.loadUrl)
        }
        // zcode:// 与自建 http 深链都可能是首次配对，转成确认流程后视为已消费该 intent。
        return openDecision(decision) || decision is TrustDecision.NeedsTrust
    }

    /** 系统「分享」文本：不做脆弱的 DOM 注入，先落到剪贴板并提示用户长按粘贴。 */
    private fun captureShareText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val manager = getSystemService(android.content.ClipboardManager::class.java)
            manager?.setPrimaryClip(android.content.ClipData.newPlainText("ZCode", text))
        }
        pendingShareText = text
    }

    private fun closeRemote() {
        endpoint = null
        loadUrl = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RemoteControlKeepAliveService.stop(this)
    }

    private fun saveEndpoint(target: ZCodeEndpoint): ZCodeEndpoint? {
        val normalized = RemoteControlUrlPolicy.normalizeInput(target.url) ?: return null
        val stored = target.copy(url = normalized, token = target.token?.trim()?.takeIf { it.isNotBlank() })
        endpoints = ZCodeEndpointStore.upsert(this, stored)
        return endpoints.firstOrNull { it.id == stored.id } ?: stored
    }

    private fun deleteEndpoint(id: String) {
        endpoints = ZCodeEndpointStore.delete(this, id)
        if (endpoint?.id == id) {
            closeRemote()
        }
    }

    /** 信任确认后把来源固化为端点：令牌从地址里摘出来单独存放，一次性配对参数（sid/mid）保留。 */
    private fun confirmTrust(uri: Uri, name: String, token: String?, trustHttp: Boolean): Boolean {
        val cleaned = RemoteControlUrlPolicy.stripToken(uri)
        val saved = saveEndpoint(
            ZCodeEndpoint(
                id = "",
                name = name.ifBlank { uri.host.orEmpty() },
                url = cleaned,
                token = token,
                kind = ZCodeEndpoint.Kind.SelfHosted,
                trustHttp = trustHttp,
            ),
        ) ?: return false
        return openEndpoint(saved)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    companion object {
        private const val KEY_ENDPOINT_ID = "endpointId"
        private const val REQUEST_NOTIFICATIONS = 4001
    }
}
