package ai.zcode.mobile.web

import ai.zcode.mobile.remote.ZCodeEndpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 适配层的作用域：只能落在自建端点的移动版式上。
 * 官方 /remote 页上游已经按手机重写过，再叠一层会把它的底部导航和抽屉搞乱。
 */
class MobileAdaptationTest {
    private val official = ZCodeEndpoint(
        id = "official",
        name = "",
        url = "https://zcode.z.ai/remote",
        kind = ZCodeEndpoint.Kind.Official,
    )

    private val selfHosted = ZCodeEndpoint(
        id = "lan",
        name = "工作站",
        url = "http://192.168.1.20:3030/",
        kind = ZCodeEndpoint.Kind.SelfHosted,
        trustHttp = true,
    )

    @Test
    fun `adaptation applies to self-hosted mobile layout only`() {
        assertTrue(MobileAdaptation.appliesTo(selfHosted, desktopMode = false))
        assertFalse(MobileAdaptation.appliesTo(selfHosted, desktopMode = true))
        assertFalse(MobileAdaptation.appliesTo(official, desktopMode = false))
        assertFalse(MobileAdaptation.appliesTo(official, desktopMode = true))
    }

    @Test
    fun `script is bare javascript without the scheme prefix`() {
        // evaluateJavascript 不接受 "javascript:" 前缀，带了就是静默不执行。
        val script = MobileAdaptation.script()
        assertFalse(script.trimStart().startsWith("javascript:"))
        assertTrue(script.contains("zcode-android-mobile"))
        // 幂等守卫：SPA 内重复注入不能叠出两套样式与两个抽屉拉手。
        assertTrue(script.contains("getElementById('zcode-android-mobile')"))
    }
}
