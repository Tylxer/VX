## 🔴 拍一拍（需求 6.1「关闭拍一拍」）—— 2026-10-19 取证定案

- ⚠️ **手势层此路不通**（真机实证）：`GestureDetector#onTouchEvent` 探针（`op=quiet sub=pat sub2=dbltap`）
  双击头像后只抓到 `android.view.ViewRootImplExtImpl$ScrollGestureListener`（系统自己的滚动手势），
  栈全是 framework（`ViewPostImeInputStage`），**零个微信类**。
  ⇒ 微信头像双击**没有注册** `OnDoubleTapListener`，别再在 GestureDetector 上浪费轮次。
- ✅ **正解是静态反编译**（`tools/_r4_dexscan.py` 扫 dex 字符串 + `tools/_r4_dexref.py` 反查 const-string / invoke）：
  `adb pull` base.apk（379MB，16 秒）→ 15 个 dex 里 `classes4.dex` 命中 `patted` 11 处：
  | 类#方法 | 常量 | 角色 |
  |---|---|---|
  | `zx2/j#d()V` | `scene %d, sendPatMsg to %s, pattedUser %s` | **发送执行体**（拦截点） |
  | `wx2/a#c(StringBuilder, te0/q, String, tj/f, int, int)` | `</fromUser><pattedUser>` `</pattedUser><template>` | 拼 pat XML |
  | `xx2/i#run()` | `.sysmsg.pat.pattedusername` | 接收侧 XPath 解析 |
  | `vx2/l#onSceneEnd` | `update pat msg svrId %d...` | pat 的 NetScene 回调 |
  | `dh4/x#e(String,Map)` | `.pattedUser` → `q54/db4` | XML → 数据结构 |
- ✅ **`zx2/j#d()V` 的调用点只有 2 处**（invoke 反查）：
  `com.tencent.mm.ui.chatting.view.ChattingAvatarImageView#performAccessibilityAction(I,Bundle)Z`
  与 `zx2/d#handleMessage(Message)`。
  ⇒ **双击头像 → 头像 View 的 AccessibilityAction → `zx2/j#d()` → 发拍一拍**。
  这也解释了手势探针空手而归：微信走的是 **AccessibilityAction**，不是 GestureDetector。
- **拦截点选 `zx2/j#d()`**：只影响拍一拍发送（不影响头像单击 / 无障碍服务 / 其它 action 分支），
  无参 void → `param.result = null` 即可跳过原实现，无返回值语义要猜。
- 探针：`op=quiet sub=pat sub2=status|anchor|cls|dbltap|dbltapoff|uiprobe|uiprobeoff`

### ✅ 真机验收（2026-10-19，双向对照）
| 组 | 开关 | 现象 | `blocked` |
|---|---|---|---|
| 实验组 | `chat.disable_pat=true` | 双击好友头像**没有**出现「你拍了拍 xxx」 | 0 → **2**（拦了 2 次） |
| 对照组 | `chat.disable_pat=false` | 双击照常出现「你拍了拍 verity」 | 停在 2 不涨（放行） |

- 运行时自检：`QUIET cls zx2.j OK` + `installed=true hooks=1`（混淆锚点在这台机器上确实存在）。
- ⚠️ 一个**不影响功能**的细节：`uiprobe` 装在 `ChattingAvatarImageView#performAccessibilityAction` 上
  **一次都没被打到**，而 `zx2/j#d()` 被拦了 2 次 ⇒ 双击实际走的是 `zx2/d#handleMessage` 那条
  （或 invoke 反查没覆盖到的路径）。别据此以为上层就是 AccessibilityAction ——
  好在拦截点在业务层，两条入口都覆盖。
- 验收脚本：`python tools/_r4_patverify.py`（装包 + 开开关 + 等人双击）/ `--off`（反向对照）/ `--nowait`。

---

## 🔴 红包（需求 6.3 自动拆红包）—— 2026-10-19 真机取证

真发 0.01 红包 → 停在「开」界面 → `op=redpacket sub=tree` 的产出：

- **Activity**：`com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI`
  （`RedPacketLogic.isRedPacketClass` 已能识别，日志 `rpClass=true`）
- ⚠️ **「开」按钮的文案在 `content-desc`，不在 `text`**：
  `android.widget.ImageButton 360x360 click cd="开"` 与 `android.widget.Button 360x360 click cd="开"`
  （两个控件重叠，都能点）。**只扫 text 会漏判**，必须 text + content-desc 一起扫（现有逻辑已覆盖，日志里 `<<OPEN>>`）。
- 干扰项（DENY）：`BaseEmojiView cd="表情"`、`WeImageView cd="关闭"`、`TextView txt="重新加载"`。
- ⚠️ **「点了但没拆开」的初版结论已被推翻**（2026-10-19 晚）：当时用 `op=redpacket sub=fake`
  强制点，日志出现 `REDPACKET clicked "开"` 就被当成"链路可用"。
  真机复测证明 **`performClick()` 对微信这个 PAG 按钮无效**（返回 true、红包不开）——
  `clicked` 只说明"调到了某个 listener"，**不能当成功判据**（详见下方「验收判据纠正」）。
- 取证脚本：`python tools/_r4_hb.py`（等人操作 100s）→ `python tools/_r4_hb.py --wait=0`（只 dump 不停留）。

---

