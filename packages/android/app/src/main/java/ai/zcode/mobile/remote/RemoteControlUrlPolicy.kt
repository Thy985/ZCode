package ai.zcode.mobile.remote

import android.content.Context
import android.net.Uri

/**
 * Bugfix: 原实现把「能不能打开」写成硬编码的 zcode.z.ai + /remote 路径判断，
 * 自托管 `zcode --web` 的入口在 `/` 且端口随用户，正式包一律被拒。
 * 现在改成端点钉住模型：只信任用户显式添加过的 origin，未知来源不直接拒绝、
 * 而是交给界面发起一次「是否信任此后端」的确认，确认后写入配置再放行。
 */
sealed interface TrustDecision {
    /** 命中已保存端点，可直接加载。 */
    data class Pinned(val endpoint: ZCodeEndpoint, val loadUrl: String) : TrustDecision

    /** 来源未登记，需要用户确认后才能变成端点。 */
    data class NeedsTrust(val uri: Uri, val token: String?, val cleartext: Boolean) : TrustDecision

    /** 地址本身不合法（非 http(s)、缺 host、带凭据、危险 scheme）。 */
    data class Invalid(val raw: String?) : TrustDecision
}

object RemoteControlUrlPolicy {
    private const val ZCODE_REMOTE_SCHEME = "zcode"
    private const val ZCODE_REMOTE_HOST = "remote"
    private const val OFFICIAL_HOST = "zcode.z.ai"
    private const val OFFICIAL_REMOTE_PATH_PREFIX = "/remote"

    /**
     * 官方 relay 的登录通道 origin，来自上游 packages/web/src/auth/webZaiOAuthConfig.ts：
     * chat.z.ai/api/oauth/authorize 与 bigmodel.cn/login，回跳固定是 zcode.z.ai/remote/v4。
     * 不放开这条，扫桌面二维码后会停在 auth-required——授权页被当成外链丢给系统浏览器，
     * WebView 里拿不到会话 cookie，配对永远完不成。
     */
    private val OFFICIAL_AUTH_ORIGIN_KEYS = setOf("https://chat.z.ai", "https://bigmodel.cn")

    /**
     * WebView 主框架导航的落点。
     */
    enum class NavigationDecision {
        /** 钉住端点内部，或官方 relay 的登录链路，留在 App 里加载。 */
        LoadInPlace,

        /** 其余一律交给系统浏览器。 */
        ExternalApp,
    }

    /**
     * `currentOriginKey` 是 WebView 当前所在页面的 origin（即这次导航的来源页）。
     * 登录通道只允许在 relay 与授权域之间来回，不允许从任意第三方页顺路进来。
     */
    fun decideNavigation(
        endpoint: ZCodeEndpoint?,
        uri: Uri,
        currentOriginKey: String?,
    ): NavigationDecision {
        val pinnedKey = endpoint?.originKey()?.takeIf { it.isNotBlank() }
            ?: return NavigationDecision.ExternalApp
        val targetKey = ZCodeEndpoint.originKeyOf(uri)
        if (targetKey.isEmpty()) {
            return NavigationDecision.ExternalApp
        }
        if (targetKey == pinnedKey) {
            // 官方端点继续限定 /remote 路径，与 decide() 的口径一致，避免 zcode.z.ai 其它页面被当成本地会话。
            val outsideRemote = endpoint.isOfficial &&
                !uri.path.orEmpty().startsWith(OFFICIAL_REMOTE_PATH_PREFIX)
            return if (outsideRemote) NavigationDecision.ExternalApp else NavigationDecision.LoadInPlace
        }
        val from = currentOriginKey.orEmpty()
        val fromRelayOrAuth = from == pinnedKey || from in OFFICIAL_AUTH_ORIGIN_KEYS
        if (endpoint?.isOfficial == true &&
            uri.scheme.equals("https", ignoreCase = true) &&
            targetKey in OFFICIAL_AUTH_ORIGIN_KEYS &&
            fromRelayOrAuth
        ) {
            return NavigationDecision.LoadInPlace
        }
        return NavigationDecision.ExternalApp
    }

    /** 兼容旧调用：命中已钉住端点返回可加载地址，否则 null。 */
    fun resolve(context: Context, raw: String?): String? =
        raw?.let { resolve(context, runCatching { Uri.parse(it.trim()) }.getOrNull()) }

    fun resolve(context: Context, uri: Uri?): String? =
        uri?.let { (decide(context, it) as? TrustDecision.Pinned)?.loadUrl }

    fun isAllowedRemoteUrl(context: Context, raw: String?): Boolean =
        resolve(context, raw) != null

    fun decide(context: Context, raw: String?): TrustDecision {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return TrustDecision.Invalid(raw)
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            ?: return TrustDecision.Invalid(raw)
        return decide(context, uri)
    }

    fun decide(context: Context, uri: Uri): TrustDecision {
        val scheme = uri.scheme?.lowercase().orEmpty()

        // zcode://remote?sid=..&mid=..&name=.. 是桌面端二维码下发的配对协议，转成官方 relay 地址。
        if (scheme == ZCODE_REMOTE_SCHEME && uri.host?.lowercase() == ZCODE_REMOTE_HOST) {
            val official = ZCodeEndpoint.Official
            return TrustDecision.Pinned(official, mergeRemoteUrl(official.url, uri))
        }

        if (scheme != "http" && scheme != "https") {
            return TrustDecision.Invalid(uri.toString())
        }
        if (uri.userInfo != null) {
            return TrustDecision.Invalid(uri.toString())
        }
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) {
            return TrustDecision.Invalid(uri.toString())
        }

        // 官方域必须 https，且路径限 /remote，避免 zcode.z.ai 上的其它页面被当成本地后端。
        if (host == OFFICIAL_HOST) {
            if (scheme != "https" || !uri.path.orEmpty().startsWith(OFFICIAL_REMOTE_PATH_PREFIX)) {
                return TrustDecision.Invalid(uri.toString())
            }
            val official = ZCodeEndpoint.Official
            return TrustDecision.Pinned(official, uri.toString())
        }

        val pinned = ZCodeEndpointStore.matchOrigin(context, uri)
        if (pinned != null) {
            if (pinned.isCleartext && !pinned.trustHttp) {
                return TrustDecision.Invalid(uri.toString())
            }
            return TrustDecision.Pinned(pinned, withToken(pinned, uri))
        }

        // 未登记的来源不直接放行，也不直接拒绝：交由界面发起一次信任确认。
        return TrustDecision.NeedsTrust(
            uri = uri,
            token = extractToken(uri),
            cleartext = scheme == "http",
        )
    }

    /**
     * 归一化用户手输地址：补 scheme、补路径、去掉一次性 token。
     * 局域网 IP 与 .local 默认按 http 处理，其余默认 https。
     */
    fun normalizeInput(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        // 已经带 scheme 的输入按原样解析；非 http(s) 直接拒绝，
        // 避免出现 file:///x 被补成 https://file:///x 这种把危险地址伪装成正常后端的怪结果。
        val withScheme = when {
            hasSchemePrefix(trimmed) -> trimmed
            trimmed.startsWith("192.168.") || trimmed.startsWith("10.") ||
                trimmed.startsWith("172.") || trimmed.startsWith("localhost") ||
                trimmed.endsWith(".local") || Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+").containsMatchIn(trimmed) ->
                "http://$trimmed"
            else -> "https://$trimmed"
        }
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return null
        }
        val normalized = uri.buildUpon()
            .path(uri.path.orEmpty().ifBlank { "/" })
            .build()
        return stripToken(normalized)
    }

    /**
     * `localhost:5173` 这种 host:port 写法在语法上看着像 scheme 前缀，实际只是缺了 scheme 的地址；
     * 冒号后是纯端口（可带 path/query）时不按 scheme 处理，否则 file:///x、javascript: 会被误当成地址。
     */
    private fun hasSchemePrefix(raw: String): Boolean {
        val rest = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:(.*)$").find(raw)?.groupValues?.get(1)
            ?: return false
        return !Regex("\\d{1,5}([/?#].*)?").matches(rest)
    }

    fun extractToken(uri: Uri): String? =
        uri.getQueryParameter(ZCodeEndpoint.TOKEN_QUERY)?.takeIf { it.isNotBlank() }

    fun stripToken(uri: Uri): String {
        if (extractToken(uri) == null) {
            return uri.toString()
        }
        val builder = uri.buildUpon().encodedQuery(null)
        uri.queryParameterNames
            .filterNot { it == ZCodeEndpoint.TOKEN_QUERY }
            .forEach { builder.appendQueryParameter(it, uri.getQueryParameter(it)) }
        return builder.build().toString()
    }

    private fun withToken(endpoint: ZCodeEndpoint, uri: Uri): String {
        if (!extractToken(uri).isNullOrBlank()) {
            return uri.toString()
        }
        val token = endpoint.token?.trim().orEmpty()
        if (token.isEmpty()) {
            return uri.toString()
        }
        return uri.buildUpon().appendQueryParameter(ZCodeEndpoint.TOKEN_QUERY, token).build().toString()
    }

    /** 保留 zcode:// 携带的 sid/mid/name/app_version 路由提示，字段名与 shared/web-remote-control.ts 一致。 */
    private fun mergeRemoteUrl(baseUrl: String, source: Uri): String {
        val base = Uri.parse(baseUrl).buildUpon()
        source.queryParameterNames
            .filter { it in QR_ROUTE_PARAMS }
            .forEach { key ->
                source.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let { base.appendQueryParameter(key, it) }
            }
        source.encodedFragment?.let { base.encodedFragment(it) }
        return base.build().toString()
    }

    private val QR_ROUTE_PARAMS = setOf("sid", "mid", "name", "app_version")
}
