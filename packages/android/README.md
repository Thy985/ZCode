# ZCode 安卓版壳

上游 [zai-org/ZCode](https://github.com/zai-org/ZCode)（Apache-2.0）`packages/android` 的扩展版。

官方那个安卓工程是真能跑的，但定位是「远控壳」：只认 `https://zcode.z.ai/remote`，桌面 UI 直接塞进 WebView。
本项目把它推进到手机上真能用：**支持自建 `zcode --web` 后端、能往任务里传图片和文件、能缩放、能接系统分享**。

Agent 仍然跑在 PC 或服务器上，手机是客户端。不是把 Node 后端塞进手机——上游 server 依赖 `node-pty`、`ssh2`
等原生模块并锁定 Node 24，Android 上直跑不现实。

## 相对官方改了什么

| 项 | 官方 | 本项目 |
| --- | --- | --- |
| 包名 | `com.example.zcode_mobile` | `ai.zcode.mobile`，versionName 跟随上游 `3.16.2-android.1` |
| 可连后端 | 正式包硬编码只允许 `zcode.z.ai/remote` | 端点列表：官方 relay + 任意数量自建后端（含端口与路径） |
| 地址校验 | 按 `/remote` 路径前缀白名单 | 按用户显式信任过的 `scheme://host:port` 钉住；陌生来源先弹「信任此后端」确认 |
| 鉴权 | 官方 OAuth/relay 配对 | 自建后端首屏带一次 `?token=`，命中后端 `zcode_lite_token` HttpOnly Cookie，之后 `/api` 与 `/ws` 靠 Cookie |
| 附件 | `allowFileAccess=false`、无 `onShowFileChooser`，网页附件按钮点了没反应 | 接上文件选择器 + 相册/视频 + 拍照（FileProvider `content://`） |
| 明文 HTTP | 全局禁 | `network_security_config` 放开，且 http 端点需单独勾选允许；按端点切换 `mixedContentMode`，否则 `ws://` 会被掐断 |
| 缩放 | 关闭 | 打开双指缩放，并提供「桌面版式 / 移动版式」切换（UA + wide viewport） |
| 小屏适配 | 无 | 页面加载后补 `viewport-fit=cover`、抬输入框字号到 16px（防聚焦放大糊版），只在可信 origin 上注入；自建端点再叠一层窄视口适配，见下节 |
| 新窗口 | `window.open` / `target=_blank` 静默失败 | 收敛到 `zcode-ext:` 出口后交给系统浏览器 |
| 分享 | 无 | 接受 `ACTION_SEND text/plain`，落剪贴板并提示长按粘贴 |
| 令牌卫生 | 远控 URL 原样进通知与 intent | 通知/intent 只带端点 id 与脱敏地址，回跳按 id 还原会话；`onPause` 主动 `CookieManager.flush()` 保住 WebView 会话 |
| 官方 relay 登录 | 只放行 `/remote`，OAuth 页被判外链 | 给官方端点开一条有界的登录通道：`chat.z.ai` / `bigmodel.cn` 的 https 授权页留在 App 内，回跳后恢复 origin 钉住 |
| 下载 | 无处理 | `setDownloadListener` 转交系统浏览器 |

## 两条使用路径

### 1. 官方远控（跟原来一样）

ZCode 桌面端 → 远控 → 出二维码 → 本 App「扫描配对二维码」。走 `zcode://remote?sid=…`，
`sid/mid/name/app_version` 路由参数按上游 `buildWebRemoteControlExternalQrUrl` 的字段名保留。

二维码里只有设备 `sid`，不带凭据。relay V2 要求手机 terminal 先用**同一个 Z.ai 账号**的 JWT 换一次性
token（`docs/web-remote-control/relay-v2-pairing.md`：`T->>R: 同账号 JWT + POST token(role=terminal)`），
所以首次打开 `/remote` 会在 App 内跳到 `chat.z.ai` / `bigmodel.cn` 的授权页，登录后回跳
`zcode.z.ai/remote/v4` 完成配对。这条通道只对官方端点开放、只认 https、只允许在 relay 与授权域之间来回，
其它 origin 仍旧转系统浏览器。顶部「退出登录」清掉 WebView 的 cookie 与 DOM storage 用于换账号。

### 2. 自建后端（本项目新增）

在跑 ZCode 的机器上起 Web 服务并放开到局域网：

```bash
# 发行包
ZCODE_SERVER_AUTH_TOKEN='换成你自己的长随机串' zcode --web --host 0.0.0.0 --port 3030 --no-open

# 或源码仓库
ZCODE_SERVER_WORKSPACE=/path/to/project pnpm dev:web
```

手机与电脑需在同一网络。App 里「添加后端」：

- 名称：`家里工作站`
- 后端地址：`http://192.168.1.20:3030`（只填 IP 时会自动按局域网补成 http）
- 访问令牌：与 `ZCODE_SERVER_AUTH_TOKEN` 一致
- 勾选「允许明文 HTTP（仅局域网）」

不加令牌也能连，前提是该后端启动时没设令牌；非 `127.0.0.1` 监听时上游会强制生成令牌，App 会把令牌单独存进配置而不是留在地址里。

要真正安全请用 HTTPS 反代（Caddy / nginx + 证书），然后只填 `https://…` 地址，此时可以整体关掉明文。

## 自建 Web UI 的移动端适配

上游只在 `/remote/v4` 那套手机远控壳里做过窄视口重组——`WorkspaceShellLayout.tsx` 里所有 `max-md:` 类都挂在
`isWebRemoteControlShell` 上。根路由 `/` 的桌面工作区没有任何宽度断点（`packages/ui/src/styles.css` 里 16 处
`@media` 全是 `prefers-reduced-motion` / `forced-colors` / `hover`），所以手机上打开自托管后端必然是
264px 侧栏 + `min-w-[320px]` 主栏 + 右侧面板挤进 412px 屏。

`web/MobileAdaptation.kt` 在自建端点且处于移动版式时注入一层适配样式，只做 CSS 能可靠做到的部分：

- 侧栏脱流成覆盖式抽屉（它的折叠态本来就是纯 `opacity-0` + `pointer-events-none`、组件不卸载，接管定位不违反应用状态机），
  配左侧边缘拉手与遮罩，只在检测到 `[data-workspace-shell="true"]` 后才现身，登录页/错误页不会冒出来；
- 解除 `#content` 的 `min-w-[320px]`、去掉对话框在窄屏上多余的圆角与左右边框；
- 右侧面板展开时转全屏 sheet（按 `aria-hidden="false"` 判定，不跟应用的展开/收起状态抢可见性）；
- `[role=dialog]` / `[role=alertdialog]` 限高 82dvh 并允许滚动，桌面弹窗溢出屏幕是这个场景最常见的坏点；
- `--ui-font-size` 14px → 16px（`:root` 上的单变量，全站 `--text-ui-*` 都从它派生）；header/工具条/菜单项触控目标抬到 40px。

**它做不到也不该做的事**：把三栏重排成单列。主栏尺寸由 `react-resizable-panels` 用 JS 写内联 style，上游在
`WorkspaceShellLayout.tsx:2363-2372` 记过这个坑——用 CSS 把对话列 flex 压成 0，库按各 panel 的 offsetWidth 求和算
group 尺寸，展开收起时两者皆 0，resize 得到 NaN，面板永久停在收起态。真正的重组必须在 `packages/ui` 里做：
把 `isWebRemoteControlShell` 那套 `max-md:` 泛化成 `useCompactLayout()`（`matchMedia("(max-width: 767px)")`），
compact 下绕开 resizable-panels 的百分比尺寸。见 `ui-compact-layout.patch`（随本仓库一起交付的独立产物）。

验证方式：沙箱里用 chromium headless 跑复刻真实 DOM 骨架的 harness，实测注入脚本无报错、`--ui-font-size` 生效、
侧栏 `position: fixed` + `min(84vw,340px)`、`#content` 的 `min-width` 归 0、右侧面板转 fixed 全屏、抽屉开合与遮罩
`display` 切换正确。注意 headless `--dump-dom` 不推进 CSS transition，看抽屉终态需要临时关掉 transition。

## 构建

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.1.0"
./scripts/build-apk.sh          # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r <apk>
```

正式签名：

```bash
export ZCODE_KEYSTORE_PATH=... ZCODE_KEYSTORE_PASSWORD=... ZCODE_KEY_ALIAS=... ZCODE_KEY_PASSWORD=...
./scripts/build-apk.sh release
```

需要 JDK 17+、Gradle wrapper 9.4.1、AGP 9.2.1、Kotlin 2.2.10（均沿用上工程与 `mise.toml`）。
wrapper 拉 `services.gradle.org` 慢的网可以换镜像，但务必核对校验和（wrapper 里已锁 `distributionSha256Sum`，不匹配会直接失败）：

```bash
sha256sum gradle-9.4.1-bin.zip   # 应为 2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
```

测试与静态检查：

```bash
./gradlew :app:testDebugUnitTest   # 13 条：URL 策略 / 导航策略 / 适配层作用域
./gradlew :app:lintDebug           # 0 error
```

## 安全边界

- 陌生 origin 一律需要一次显式确认，确认后按 `scheme://host:port` 钉住；WebView 内跳往其它站点会被拦并转系统浏览器。
- 唯一例外的通道是官方 relay 的 OAuth：仅官方端点、仅 https、仅 `chat.z.ai` / `bigmodel.cn`，且来源必须是
  relay 或授权链自身（`RemoteControlUrlPolicy.decideNavigation`），自建端点与任意第三方页都借不到这条道。
- `allowFileAccess` 保持关闭，附件走 `content://`，网页拿不到设备文件路径。
- 网页权限只放开 `clipboard-sanitized-write`（API 33+），摄像头/麦克风一律拒绝（传图走原生选择器）。
- 令牌不进通知、不进 intent、不进日志；展示一律脱敏。持久化在应用私有 SharedPreferences，需要更强静态加密可把
  `ZCodeEndpointStore` 换成 `EncryptedSharedPreferences`，接口不变。
- 全局放开明文 HTTP 是唯一需要权衡的地方：因为用户后端地址不可枚举。请优先 HTTPS 反代。

## 已知限制

- 分享文本目前落剪贴板 + 提示长按粘贴，没有直接注入网页输入框——那要依赖上游 DOM 结构选择器，太脆。
  真正干净的做法是在 `packages/web` 里加一个可寻址的输入接口，再由壳层调用。
- WebView 保活靠 `dataSync` 前台服务，能显著降低被杀概率，但不保证 socket 不断；状态恢复仍依赖上游
  replayable snapshot 协议。
- 已覆盖的是 `RemoteControlUrlPolicy` / `ZCodeEndpointStore` / `MobileAdaptation` 的纯逻辑分支（13 条 Robolectric + JUnit）。
  WebView 的实际行为——扫码配对、附件选择器、`ws://` 重连、桌面/移动版式切换、注入后的窄视口布局——仍需真机回归。
- 移动端适配层是**缓解**不是重组：三栏结构保留，侧栏变抽屉、右侧面板变全屏 sheet。觉得反而碍事时切到「桌面版式」
  即可完全关掉注入（`MobileAdaptation.appliesTo` 只在移动版式 + 自建端点时为真）。
- 适配层依赖上游 DOM 上的 `data-workspace-shell` / `#sidebar` / `#content` / `data-workspace-side-frame` /
  `--ui-font-size` 这些锚点。它们都是上游刻意留的稳定标记而非 Tailwind 工具类，但升级 ZCode 后仍应对照
  `MobileAdaptationTest` 与上面的选择器清单复核一遍。
- 分发的 `app-debug.apk` 用 Android debug 证书签名，只适合自用侧载；正式分发请走上面的签名流程出自签的 release 包。
  全 ABI 包约 38 MB，`-PzcodeAbi=arm64-v8a` 出的真机包约 23 MB，差的是 MLKit 扫码那三套多余的 `.so`。
