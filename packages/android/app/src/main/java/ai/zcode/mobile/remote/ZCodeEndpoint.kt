package ai.zcode.mobile.remote

import android.net.Uri
import ai.zcode.mobile.BuildConfig

/**
 * Bugfix: 官方壳只认 zcode.z.ai/remote，用户自建 `zcode --web --host 0.0.0.0` 的后端连不上。
 * 这里把「后端」提升为一等公民：一条 endpoint = 名字 + 入口地址 + 访问令牌 + 类型，
 * 由用户显式添加并持久化，URL 白名单改为按 endpoint 钉住的 origin 判定，
 * 而不是按路径前缀判定（自托管 Web UI 挂在 `/`，不在 `/remote`）。
 */
data class ZCodeEndpoint(
    val id: String,
    val name: String,
    /** 入口地址，不含 token。自托管形如 http://192.168.1.20:3030/ ，官方形如 https://zcode.z.ai/remote?... */
    val url: String,
    /** 自托管访问令牌，对应后端 ZCODE_SERVER_AUTH_TOKEN。官方 relay 后端为 null。 */
    val token: String? = null,
    val kind: Kind = Kind.SelfHosted,
    val trustHttp: Boolean = false,
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
) {
    enum class Kind { Official, SelfHosted }

    val isOfficial: Boolean get() = kind == Kind.Official

    /** 明文 http 后端（仅局域网/自托管会出现）。 */
    val isCleartext: Boolean get() = Uri.parse(url).scheme.equals("http", ignoreCase = true)

    /**
     * 真正喂给 WebView 的地址。
     * 后端 hasValidLiteToken 只认 `Authorization: Bearer` 或 `?token=`，
     * query 命中后服务端下发 zcode_lite_token HttpOnly cookie，后续 /api 与 /ws 靠 cookie，
     * 所以令牌只在首屏带上一次即可。
     */
    fun entryUrl(): String {
        val token = token?.trim().orEmpty()
        if (token.isEmpty()) {
            return url
        }
        val builder = Uri.parse(url).buildUpon()
        if (builder.build().getQueryParameter(TOKEN_QUERY).isNullOrEmpty()) {
            builder.appendQueryParameter(TOKEN_QUERY, token)
        }
        return builder.build().toString()
    }

    /** 界面与日志展示用，令牌一律脱敏。 */
    fun displayUrl(): String = redactToken(url)

    /** origin 级别的钉住键，scheme://host:port。 */
    fun originKey(): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return ""
        return originKeyOf(uri)
    }

    companion object {
        const val TOKEN_QUERY = "token"

        fun originKeyOf(uri: Uri?): String {
            val scheme = uri?.scheme?.lowercase().orEmpty()
            val host = uri?.host?.lowercase().orEmpty()
            if (scheme.isEmpty() || host.isEmpty()) {
                return ""
            }
            val port = if (uri!!.port > 0) ":${uri.port}" else ""
            return "$scheme://$host$port"
        }

        val Official: ZCodeEndpoint
            get() = ZCodeEndpoint(
                id = OFFICIAL_ID,
                name = "",
                url = BuildConfig.REMOTE_CONTROL_DEFAULT_URL,
                kind = Kind.Official,
            )

        fun redactToken(raw: String?): String {
            if (raw.isNullOrBlank()) {
                return ""
            }
            return runCatching {
                val uri = Uri.parse(raw)
                if (uri.getQueryParameter(TOKEN_QUERY).isNullOrEmpty()) {
                    raw
                } else {
                    uri.buildUpon().encodedQuery(null).build().toString()
                }
            }.getOrDefault(raw)
        }

        const val OFFICIAL_ID = "official"
    }
}
