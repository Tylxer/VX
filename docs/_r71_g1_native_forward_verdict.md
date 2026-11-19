# R71 · G1 原生转发真机结论（2026-11-19）

> 状态：**已定案，不再深挖**。G1 原生转发机制完整可用，但对端无「转发」标记（微信 host 链约束），开关 `chat.forward_native` 保持默认关。
> 本轮曾新增只读探针 + snappick 验证入口，**验证完成后已全部回退**（默认关的死代码无长期价值，避免维护负担），仅留本文档存档结论。

---

## 1. 背景与目标

用户目标：G1「原生转发」——让模块分组/转发走微信自带能力，像微X一样原生渲染、带转发标记。此前取证（`docs/_r29_g1_forward_forensics.md`、`docs/_r29_g2_msg_object.md`、`docs/_r36_forward.md`）已完成，代码也已实现（`ForceForward.nativeForward*()`），但**从未真机验证**，`chat.forward_native` 默认关且注释写明「写宿主对象未经真机验证」。

用户 2026-11-19 要求验 G1，并「参考微X的转发模块」。

---

## 2. 微X转发模块：静态取证受阻（与 R70 删组同一堵墙）

| 材料 | 结果 |
|---|---|
| `_mods/shell.txt`（40MB，`x/classes.dex` dexdump） | 2829 个类，**全是 androidx**，转发相关命中只有 Android 标准库的 `getNextClusterForwardId` / `ACTION_SCROLL_FORWARD`，**零业务代码** |
| `_mods/x/assets/` 7 个密文 | 文件头 `92 c5 01 00` + 熵 8.00，R55 已定案为 native 壳加密业务 dex，静态挖不动 |
| 设备侧 | `me.yun.fkwechat` 已装，无释放的明文 dex |

⇒ 「参考微X转发模块」的静态路线不可行。HCR 探针（13 hook）只覆盖会话列表通知链，**不 hook 转发 NetScene**，动态也抓不到微X发送链。改走"给自己加只读探针直接观测自身转发链"的路线。

---

## 3. G1 机制：真机完整验证通过 ✅

新增只读探针 `ForwardNetSceneProbe`（tag=`FNP`，3 个 hook 点 + 1 fallback），真机 `op=forward sub=pick`（合成 TEXT 快照，msgId=0）转发一条，FNP 铁证逐帧：

```
20:52:06.327 FORWARD native probe=true
20:52:06.328 FORWARD plan strategy=NATIVE kind=TEXT targets=1      ← 判定走原生（非降级）
20:52:06.355 FNP q1.f m[a=-1 b=0 c= d=null e=null f=null] h_before=0  ← ForwardInfo 挂上
20:52:06.357 FNP q1.f subtype=0   ← after 时点 h 未赋值（文档已注明的读取时点限制）
20:52:06.528 FNP q0 ctor forwardMsgId=0 newMsgId=19724386 type=522   ← NetScene 真构造+落库
20:52:06.529 FNP l1.a queued=true                                     ← 真入队
20:52:06.529 FORWARD native text via=l1.a() sub=4 marked=false        ← 子类型 4 正确
20:52:06.529 FORWARD done 已发送 1 个目标 plan=NATIVE                  ← 发送成功
```

**结论**：G1 原生链的每一环都真实执行——NAVITE 策略、q1.f 挂 ForwardInfo、q0 落库（type=522 文本/110 图片）、l1.a 入队、子类型 4/13 正确，消息确实通过微信原生发送链发出。**机制层面完全通，非降级重发。**

探针 hook 签名经主会话独立反汇编核实：`hl0/q0.<init>(String,String,int,int,long)` @classes7 0x711560、`q0.getType()I`=const/16 #522 @0x710f5c、`hl0/l1.a()Z` @0x710360。

---

## 4. 对端无「转发」标记 — 微信 host 链约束，非我方 bug ❌

**真机结果**：上述转发的消息，对端**收到，但显示为普通消息**，无「转发」字样/来源标记。

**这与 R29 附② 的 dex 推断完全吻合**：

1. **文本**：`hl0/q0.<init>(String,String,II,J)` 的第 5 参 `forwardMsgId` 在构造器体内**未被引用**（locals 有 reg=27 J，body 无读取）⇒ 源 msgId 不参与落库 ⇒ 文本转发与「重发」字节码层面几乎等价。**即使用真实 msgId 也大概率无标记**（合成快照 msgId=0 更注定无标记）。
2. **图片**：关键字段是 `hl0/m.a`(forwardFromImgId)——R29 §4 原文钉死它是「图片**不重传 + 带转发标记**」的关键。但我方 `m.a` 恒 -1（`nativeForwardImage` 无①档 CDN imgId 获取路径）⇒ 图片链大概率同样带不上标记。

**本质**：G1 绕原生链一圈，但对端体验 ≈ 降级重发。`chat.forward_native` 保持默认关是正确选择，不应当作常规功能启用。

---

## 5. 图片标记的验证尝试（未完成，但结论已足够）

为验图片链新增了 `snappick` 探针（把 `snapshotOf(staleViewForProbe())` 的真实图片快照喂进 `openTargetPicker`，绕开被微X改坏的菜单注入）。真机两次尝试均失败：

- `snap` / `snappick` 抓到的始终是**同一条 TEXT 快照**（msgId=19723964，ageMs=43s 不更新）⇒ 根因：图片消息长按**不走 `View.performLongClick` trap**（微信图片卡片用自定义手势），pressedView 没被更新。
- 改从消息库取：`ardump --n 200` 确认库里有大量 type=3 图片消息（msgId 19724504/19724487/19724415…），但 `dumpRecent` SQL 硬编码 5 列不含 imgPath，`op=sql` 走朋友圈库（`no such table: message`）⇒ 要读 imgPath 得再改探针。

**成本/收益判断**：R29 ① 级证据已钉死 `m.a=-1` = 无标记，继续深挖的边际收益极低（已派 3 轮子代理/装 4 次包）⇒ 停止，收口。

---

## 6. 其它发现（与本轮无关但需留档）

1. **微X转发按钮闪退 = 微X自身的 bug**：用户点微X的转发按钮后选目标闪退，崩溃栈 `CalledFromWrongThreadException`（view 在 `wx_features_base_thread_*` 线程创建、main 线程 `requestLayout`），全栈是 Android framework（InputMethodManager→InsetsController→ViewRootImpl），**零我方模块帧**；我方 `FORWARD injected=0`、无任何 FORWARD/FNP 日志 ⇒ 没走我方链路。与我方无关。
2. **微X共存改坏我方菜单注入**：`FF classified CHAT_MSG but no box found`（`findMenuBox` 要求菜单词 hits≥2），微X在消息长按菜单注入了「微X模块」项，改变了菜单结构。但 G1 默认关，非阻断，未修。
3. **图片长按抓不到 View**：`performLongClick` trap 对微信图片卡片无效（见 §5）。

---

## 7. 本轮代码处置

按用户选择「只提交结论文档」：
- 回退 `HookEntry.kt` / `EntryReceiver.kt` / `ForceForward.kt` 的 R71 探针改动到 R70 基线（`git checkout --`）。
- 删除新增的 `probe/ForwardNetSceneProbe.kt`。
- 代码回到 R70 已提交状态，**工作区无生产代码变化**。
- 本文档 + 派发审计台账的 R71 记录作为唯一留存。

[DONE] R71 G1 原生转发真机结论存档：机制通、对端无标记（微信 host 链约束）、开关保持默认关、探针代码已回退。
