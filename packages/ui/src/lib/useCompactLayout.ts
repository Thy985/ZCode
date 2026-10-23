// 紧凑布局断点 hook。断点字面量由 mobileTextInput 的 useIsMobileCompactViewport 统一持有
// （`(max-width: 767px)`），布局层不重复硬编码；上游若调整紧凑断点只需改一处。
// 无 DOM 环境（SSR）由该 hook 内部兜底返回 false，因此服务端首帧按桌面布局渲染。
import { useIsMobileCompactViewport } from "@/lib/mobileTextInput.js";

export function useCompactLayout(): boolean {
  return useIsMobileCompactViewport();
}
