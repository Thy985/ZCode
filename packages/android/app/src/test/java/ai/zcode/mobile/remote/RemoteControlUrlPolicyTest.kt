package ai.zcode.mobile.remote

import ai.zcode.mobile.BuildConfig
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 这层是本项目唯一的安全边界：陌生来源不能自动加载、令牌不能留在地址里、
 * 明文 HTTP 必须显式确认。没有真机，就用 Robolectric 把这些分支钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteControlUrlPolicyTest {
    private val context by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    private fun decide(raw: String): TrustDecision = RemoteControlUrlPolicy.decide(context, raw)

    private fun pinnedOriginOf(raw: String): String? =
        (decide(raw) as? TrustDecision.Pinned)?.loadUrl

    @Test
    fun `official relay link stays pinned`() {
        val decision = decide("https://zcode.z.ai/remote?sid=abc&name=Studio")
        assertTrue(decision is TrustDecision.Pinned)
        val pinned = decision as TrustDecision.Pinned
        assertTrue(pinned.endpoint.isOfficial)
        assertEquals("https://zcode.z.ai/remote?sid=abc&name=Studio", pinned.loadUrl)
    }

    @Test
    fun `official host outside remote path is rejected`() {
        // 官方域名的其它页面不能被当成后端，避免 cookie 与登录态被误用。
        assertTrue(decide("https://zcode.z.ai/pricing") is TrustDecision.Invalid)
        assertTrue(decide("http://zcode.z.ai/remote") is TrustDecision.Invalid)
    }

    @Test
    fun `zcode scheme carries route hints and drops stale credentials`() {
        val decision = decide("zcode://remote?sid=s1&mid=m1&name=PC&app_version=3.16.2&hash=leak&t=leak")
        assertTrue(decision is TrustDecision.Pinned)
        val url = (decision as TrustDecision.Pinned).loadUrl
        listOf("sid=s1", "mid=m1", "name=PC", "app_version=3.16.2").forEach { hint ->
            assertTrue("pairing link must keep $hint -> $url", url.contains(hint))
        }
        assertFalse("legacy hash/t must not survive", url.contains("hash=") || url.contains("t=leak"))
    }

    @Test
    fun `dangerous schemes and credentials are invalid`() {
        listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "content://media/external/images/media/1",
            "http://user:pass@192.168.1.20:3030/",
            "",
            "   ",
        ).forEach { raw ->
            assertTrue("should reject: $raw", decide(raw) is TrustDecision.Invalid)
        }
    }

    @Test
    fun `unknown origin needs explicit trust instead of loading`() {
        val decision = decide("http://192.168.1.20:3030/?token=secret123")
        assertTrue(decision is TrustDecision.NeedsTrust)
        val request = decision as TrustDecision.NeedsTrust
        assertEquals("secret123", request.token)
        assertTrue(request.cleartext)
        assertNull(RemoteControlUrlPolicy.resolve(context, "http://192.168.1.20:3030/"))
    }

    @Test
    fun `cleartext endpoint refuses to load unless http was confirmed`() {
        val host = "http://192.168.1.20:3030/"
        ZCodeEndpointStore.upsert(
            context,
            ZCodeEndpoint(id = "lan", name = "工作站", url = host, token = "secret123", trustHttp = false),
        )
        assertTrue(decide(host) is TrustDecision.Invalid)

        ZCodeEndpointStore.upsert(
            context,
            ZCodeEndpoint(id = "lan", name = "工作站", url = host, token = "secret123", trustHttp = true),
        )
        val decision = decide(host)
        assertTrue(decision is TrustDecision.Pinned)
        val pinned = decision as TrustDecision.Pinned
        assertEquals("lan", pinned.endpoint.id)
        assertEquals("$host?token=secret123", pinned.loadUrl)
    }

    @Test
    fun `token never appears in displayed url`() {
        val endpoint = ZCodeEndpoint(
            id = "lan",
            name = "工作站",
            url = "http://192.168.1.20:3030/",
            token = "secret123",
            trustHttp = true,
        )
        assertTrue(endpoint.entryUrl().contains("token=secret123"))
        assertFalse(endpoint.displayUrl().contains("secret123"))
        assertFalse(ZCodeEndpoint.redactToken("http://h/?token=secret123").contains("secret123"))
    }

    @Test
    fun `pinned origin ignores path but not port`() {
        ZCodeEndpointStore.upsert(
            context,
            ZCodeEndpoint(
                id = "secure",
                name = "反代",
                url = "https://zcode.lan.example/remote",
                token = "tok",
            ),
        )
        // 同 origin 的其它路径仍属于该后端（自托管 Web UI 挂在 /）。
        assertEquals(
            "https://zcode.lan.example/tasks?token=tok",
            pinnedOriginOf("https://zcode.lan.example/tasks"),
        )
        // 换端口就是另一个 origin，必须重新确认。
        assertTrue(decide("https://zcode.lan.example:8443/tasks") is TrustDecision.NeedsTrust)
    }

    @Test
    fun `normalizeInput fills scheme and strips one-time token`() {
        assertEquals("http://192.168.1.20:3030/", RemoteControlUrlPolicy.normalizeInput("192.168.1.20:3030"))
        assertEquals("http://localhost:5173/", RemoteControlUrlPolicy.normalizeInput("localhost:5173"))
        // host:port 后面还能带 path/query，不能被当成 scheme。
        assertEquals(
            "http://localhost:5173/remote?name=Mac",
            RemoteControlUrlPolicy.normalizeInput("localhost:5173/remote?name=Mac"),
        )
        assertEquals("https://work.example.com/", RemoteControlUrlPolicy.normalizeInput("work.example.com"))
        assertEquals(
            "http://10.0.0.5:3030/",
            RemoteControlUrlPolicy.normalizeInput("http://10.0.0.5:3030/?token=abc"),
        )
        assertNull(RemoteControlUrlPolicy.normalizeInput("file:///tmp/x"))
        assertNull(RemoteControlUrlPolicy.normalizeInput("javascript:alert(1)"))
        assertNull(RemoteControlUrlPolicy.normalizeInput("http://user:pass@host/"))
        // 带路径的后端地址要原样保留，只有 token 被摘掉。
        assertEquals(
            "http://192.168.1.20:3030/remote?name=PC",
            RemoteControlUrlPolicy.normalizeInput("http://192.168.1.20:3030/remote?name=PC&token=abc"),
        )
    }

    @Test
    fun `official endpoint cannot be deleted and always comes from BuildConfig`() {
        ZCodeEndpointStore.upsert(context, ZCodeEndpoint(id = "lan", name = "a", url = "https://a.test/"))
        val list = ZCodeEndpointStore.readAll(context)
        assertEquals(1, list.count { it.isOfficial })
        assertEquals(BuildConfig.REMOTE_CONTROL_DEFAULT_URL, list.first { it.isOfficial }.url)

        ZCodeEndpointStore.delete(context, ZCodeEndpoint.OFFICIAL_ID)
        assertTrue(ZCodeEndpointStore.readAll(context).any { it.isOfficial })
    }

    @Test
    fun `official relay keeps the login hops in app but nothing else`() {
        val official = ZCodeEndpoint.Official
        val relay = official.originKey()
        // 未登录时 /remote 会跳 OAuth，这条必须留在 App 内，否则 WebView 拿不到会话 cookie。
        assertEquals(
            RemoteControlUrlPolicy.NavigationDecision.LoadInPlace,
            navigate("https://chat.z.ai/api/oauth/authorize?client_id=x", from = relay),
        )
        // 授权域之间可以继续跳，最后回到 /remote/v4 带 code。
        assertEquals(
            RemoteControlUrlPolicy.NavigationDecision.LoadInPlace,
            navigate("https://bigmodel.cn/login", from = "https://chat.z.ai"),
        )
        assertEquals(
            RemoteControlUrlPolicy.NavigationDecision.LoadInPlace,
            navigate("https://zcode.z.ai/remote/v4?code=1", from = "https://bigmodel.cn"),
        )
        // 从任意第三方页顺路进授权域不算登录链路。
        assertEquals(
            RemoteControlUrlPolicy.NavigationDecision.ExternalApp,
            navigate("https://chat.z.ai/api/oauth/authorize", from = "https://evil.example"),
        )
        // 授权域只认 https。
        assertEquals(
            RemoteControlUrlPolicy.NavigationDecision.ExternalApp,
            navigate("http://chat.z.ai/login", from = relay),
        )
        // 官方域里 /remote 之外的页面仍出局，跟 decide() 口径一致。
        assertEquals(
            RemoteControlUrlPolicy.NavigationDecision.ExternalApp,
            navigate("https://zcode.z.ai/cn/share/callback", from = relay),
        )
        // 其它 origin 不受这条通道影响。
        assertEquals(
            RemoteControlUrlPolicy.NavigationDecision.ExternalApp,
            navigate("https://example.com/docs", from = relay),
        )
        // 自建端点没有官方登录通道。
        val local = ZCodeEndpoint(
            id = "lan",
            name = "工作站",
            url = "http://192.168.1.20:3030/",
            trustHttp = true,
        )
        assertEquals(
            RemoteControlUrlPolicy.NavigationDecision.ExternalApp,
            RemoteControlUrlPolicy.decideNavigation(
                endpoint = local,
                uri = uri("https://chat.z.ai/login"),
                currentOriginKey = local.originKey(),
            ),
        )
        // 自建端点自己的 origin 内任意路径都放行（Web UI 挂在 /）。
        assertEquals(
            RemoteControlUrlPolicy.NavigationDecision.LoadInPlace,
            RemoteControlUrlPolicy.decideNavigation(
                endpoint = local,
                uri = uri("http://192.168.1.20:3030/tasks"),
                currentOriginKey = local.originKey(),
            ),
        )
    }

    private fun uri(raw: String): android.net.Uri = android.net.Uri.parse(raw)

    private fun navigate(raw: String, from: String?): RemoteControlUrlPolicy.NavigationDecision =
        RemoteControlUrlPolicy.decideNavigation(
            endpoint = ZCodeEndpoint.Official,
            uri = uri(raw),
            currentOriginKey = from,
        )
}
