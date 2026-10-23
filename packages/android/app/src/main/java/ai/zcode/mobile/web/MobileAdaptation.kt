package ai.zcode.mobile.web

import ai.zcode.mobile.remote.ZCodeEndpoint

/**
 * Bugfix: 自托管 `zcode --web` 的根路由是桌面工作区，上游只在 `/remote/v4` 那套手机壳里做了窄视口
 * 重组（`WorkspaceShellLayout.tsx` 里的 `max-md:` 全部挂在 `isWebRemoteControlShell` 上），
 * 根路由什么都没做：264px 侧栏 + `min-w-[320px]` 主栏 + 右侧面板直接塞进 412px 屏。
 *
 * 这里只做 CSS 可靠做到的部分。布局的百分比尺寸由 react-resizable-panels 用 JS 写内联 style，
 * 上游在 `WorkspaceShellLayout.tsx:2363-2372` 明确记过：用 CSS 把对话列 flex 压成 0，库按各 panel
 * 的 offsetWidth 求和算 group 尺寸，展开收起时两者皆 0，resize 得到 NaN，面板永久停在收起态。
 * 所以「把三栏重排成单列」必须在 packages/ui 里做，壳层硬来一定会留下修不完的洞。
 *
 * 能可靠做的依据：侧栏折叠态本来就是纯 CSS（`opacity-0` + `pointer-events-none`，组件不卸载），
 * 我们接管它的定位与可见性不违反应用的状态机；主栏的 `min-w-[320px]` 是静态类；
 * `--ui-font-size` 是 `:root` 上的单个变量（`packages/ui/src/styles.css:76`）。
 */
object MobileAdaptation {
    private const val STYLE_ID = "zcode-android-mobile"

    /** 只有自建端点且处于移动版式时注入；官方 `/remote` 页上游已经按手机写过，别去叠一层。 */
    fun appliesTo(endpoint: ZCodeEndpoint, desktopMode: Boolean): Boolean =
        !desktopMode && endpoint.kind == ZCodeEndpoint.Kind.SelfHosted

    /** evaluateJavascript 只吃裸 JS，不能带 `javascript:` 前缀。 */
    fun script(): String = SCRIPT

    private val SCRIPT = """
(function () {
  var d = document;
  if (!d || d.getElementById('$STYLE_ID')) { return; }

  var css = `
html[data-zcode-mobile] { --ui-font-size: 16px; }

html[data-zcode-mobile] #sidebar {
  position: fixed !important;
  top: 0 !important;
  bottom: 0 !important;
  left: 0 !important;
  right: auto !important;
  width: min(84vw, 340px) !important;
  max-width: none !important;
  height: auto !important;
  opacity: 1 !important;
  pointer-events: auto !important;
  z-index: 80;
  transform: translateX(-105%);
  transition: transform .22s ease !important;
  box-shadow: 0 10px 40px rgb(0 0 0 / .5);
}
html[data-zcode-mobile][data-zcode-shell="1"][data-zcode-drawer="open"] #sidebar {
  transform: none;
}

html[data-zcode-mobile] #content { min-width: 0 !important; }
html[data-zcode-mobile] [data-workspace-conversation-frame="true"] {
  border-radius: 0 !important;
  border-left-width: 0 !important;
  border-right-width: 0 !important;
}

html[data-zcode-mobile][data-zcode-shell="1"] [data-workspace-side-frame="true"][aria-hidden="false"] {
  position: fixed !important;
  top: 0 !important;
  right: 0 !important;
  bottom: 0 !important;
  left: 0 !important;
  width: 100% !important;
  max-width: none !important;
  height: 100vh !important;
  height: 100dvh !important;
  z-index: 90;
  border-radius: 0 !important;
}

html[data-zcode-mobile] [role="dialog"],
html[data-zcode-mobile] [role="alertdialog"] {
  width: min(94vw, 560px) !important;
  max-width: none !important;
  max-height: 82vh !important;
  max-height: 82dvh !important;
  overflow: auto !important;
}

html[data-zcode-mobile] [data-testid="workspace-header"] button,
html[data-zcode-mobile] [role="toolbar"] button,
html[data-zcode-mobile] [role="menuitem"] { min-height: 40px; }

html[data-zcode-mobile] #zcode-android-drawer { display: none; }
html[data-zcode-mobile][data-zcode-shell="1"] #zcode-android-drawer {
  display: flex;
  position: fixed;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 26px;
  height: 68px;
  border: 1px solid rgb(255 255 255 / .16);
  border-left: 0;
  border-radius: 0 12px 12px 0;
  background: #202124;
  color: #f8f8f8;
  font: 16px/1 system-ui, sans-serif;
  align-items: center;
  justify-content: center;
  z-index: 120;
  opacity: .85;
  padding: 0;
}
html[data-zcode-mobile] #zcode-android-scrim {
  display: none;
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  background: rgb(0 0 0 / .45);
  z-index: 70;
}
html[data-zcode-mobile][data-zcode-shell="1"][data-zcode-drawer="open"] #zcode-android-scrim {
  display: block;
}
`;

  var style = d.createElement('style');
  style.id = '$STYLE_ID';
  style.textContent = css;
  (d.head || d.documentElement).appendChild(style);
  d.documentElement.setAttribute('data-zcode-mobile', '');
  d.documentElement.setAttribute('data-zcode-drawer', 'closed');

  // 抽屉拉手挂在 body 上：React 只接管 #root，SPA 路由切换不会把它冲掉。
  var scrim = d.createElement('div');
  scrim.id = 'zcode-android-scrim';
  d.body.appendChild(scrim);

  var tab = d.createElement('button');
  tab.id = 'zcode-android-drawer';
  tab.type = 'button';
  tab.setAttribute('aria-label', 'ZCode sidebar');
  tab.textContent = '\u2630';
  d.body.appendChild(tab);

  var isOpen = function () {
    return d.documentElement.getAttribute('data-zcode-drawer') === 'open';
  };
  tab.addEventListener('click', function () {
    d.documentElement.setAttribute('data-zcode-drawer', isOpen() ? 'closed' : 'open');
  });
  scrim.addEventListener('click', function () {
    d.documentElement.setAttribute('data-zcode-drawer', 'closed');
  });

  // 登录页、错误页没有工作区骨架，别在那些页面上冒出手柄。
  var tries = 0;
  var timer = setInterval(function () {
    tries += 1;
    if (d.querySelector('[data-workspace-shell="true"], #sidebar')) {
      d.documentElement.setAttribute('data-zcode-shell', '1');
      clearInterval(timer);
    } else if (tries > 40) {
      clearInterval(timer);
    }
  }, 250);
})();

"""
}
