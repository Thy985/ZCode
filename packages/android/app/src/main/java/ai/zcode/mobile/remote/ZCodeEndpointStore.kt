package ai.zcode.mobile.remote

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 后端配置持久层。官方端点始终存在且不可删除，自建端点由用户显式添加。
 *
 * 安全说明：token 存放在应用私有 SharedPreferences，不进媒体库、不进日志、不进最近记录，
 * 任何对外展示的字符串都必须走 ZCodeEndpoint.displayUrl() / redactToken()。
 * 需要更强静态加密时，可把本类换成 EncryptedSharedPreferences 实现，接口不变。
 */
object ZCodeEndpointStore {
    private const val PREFS_NAME = "zcode_endpoints_v1"
    private const val KEY_ENDPOINTS = "endpoints"

    fun readAll(context: Context): List<ZCodeEndpoint> {
        val stored = runCatching {
            val raw = prefs(context).getString(KEY_ENDPOINTS, null) ?: return@runCatching emptyList()
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    endpointOf(item)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())

        // 官方端点由 BuildConfig 决定，不接受本地旧副本，避免默认地址变更后仍连旧域。
        val selfHosted = stored.filterNot { it.isOfficial || it.id == ZCodeEndpoint.OFFICIAL_ID }
        return (listOf(ZCodeEndpoint.Official) + selfHosted).sortedWith(
            compareByDescending<ZCodeEndpoint> { it.lastUsedAt }.thenBy { it.isOfficial.not() },
        )
    }

    fun find(context: Context, id: String?): ZCodeEndpoint? {
        if (id.isNullOrBlank()) {
            return null
        }
        return readAll(context).firstOrNull { it.id == id }
    }

    /** 按已钉住的 origin 找后端；scheme + host + port 必须完全一致。 */
    fun matchOrigin(context: Context, uri: Uri?): ZCodeEndpoint? {
        val scheme = uri?.scheme?.lowercase().orEmpty()
        val host = uri?.host?.lowercase().orEmpty()
        if (scheme.isEmpty() || host.isEmpty()) {
            return null
        }
        val port = if (uri!!.port > 0) uri.port else defaultPort(scheme)
        return readAll(context).firstOrNull { endpoint ->
            val endpointUri = runCatching { Uri.parse(endpoint.url) }.getOrNull() ?: return@firstOrNull false
            val endpointPort = if (endpointUri.port > 0) endpointUri.port else defaultPort(endpointUri.scheme.orEmpty())
            endpointUri.scheme?.lowercase() == scheme && endpointUri.host?.lowercase() == host && endpointPort == port
        }
    }

    fun upsert(context: Context, endpoint: ZCodeEndpoint): List<ZCodeEndpoint> {
        val existing = readAll(context).toMutableList()
        val replacement = endpoint.copy(
            id = endpoint.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = endpoint.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        val index = existing.indexOfFirst { it.id == replacement.id }
        if (index >= 0) {
            existing[index] = replacement
        } else {
            existing.add(replacement)
        }
        write(context, existing)
        return readAll(context)
    }

    fun delete(context: Context, id: String): List<ZCodeEndpoint> {
        if (id == ZCodeEndpoint.OFFICIAL_ID) {
            return readAll(context)
        }
        write(context, readAll(context).filterNot { it.id == id })
        return readAll(context)
    }

    fun touch(context: Context, id: String): List<ZCodeEndpoint> {
        val now = System.currentTimeMillis()
        write(context, readAll(context).map { if (it.id == id) it.copy(lastUsedAt = now) else it })
        return readAll(context)
    }

    private fun endpointOf(item: JSONObject): ZCodeEndpoint? {
        val url = item.optString("url").takeIf { it.isNotBlank() } ?: return null
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            return null
        }
        val kind = runCatching { ZCodeEndpoint.Kind.valueOf(item.optString("kind", "SelfHosted")) }
            .getOrDefault(ZCodeEndpoint.Kind.SelfHosted)
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        return ZCodeEndpoint(
            id = id,
            name = item.optString("name").takeIf { it.isNotBlank() } ?: Uri.parse(url).host.orEmpty(),
            url = url,
            token = item.optString("token").takeIf { it.isNotBlank() },
            kind = kind,
            trustHttp = item.optBoolean("trustHttp", false),
            createdAt = item.optLong("createdAt", 0L),
            lastUsedAt = item.optLong("lastUsedAt", 0L),
        )
    }

    private fun write(context: Context, endpoints: List<ZCodeEndpoint>) {
        val array = JSONArray()
        endpoints.forEach { endpoint ->
            array.put(
                JSONObject()
                    .put("id", endpoint.id)
                    .put("name", endpoint.name)
                    .put("url", endpoint.url)
                    .put("token", endpoint.token.orEmpty())
                    .put("kind", endpoint.kind.name)
                    .put("trustHttp", endpoint.trustHttp)
                    .put("createdAt", endpoint.createdAt)
                    .put("lastUsedAt", endpoint.lastUsedAt),
            )
        }
        prefs(context).edit().putString(KEY_ENDPOINTS, array.toString()).apply()
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
