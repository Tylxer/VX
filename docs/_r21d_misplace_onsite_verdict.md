# R21d 角标错贴「下标乱了」现场取证裁决（2026-11-27 21:28~21:32）

> 档位=Lite 依据=执行 Lite 产物 `_r21d_badge_misplace_forensics.md` Q3 已定判据 + 事实汇总（非判据设计/根因推理）。主会话执行，用户报障「当前界面就是错误」。
> 零改码/未装机/未提交；只发只读探针（dump/stat/kvget）+ 可逆开关（show_hint false→true 已复位）。

## 1 现场事实（【真机实证】）

| 项 | 实测 |
|---|---|
| 角标 | `已阻止撤回 · 12368` @ bounds `[43,1522][432,1587]`，`class=android.widget.TextView`、`resource-id=''`、`index=1` —— HintBadge 动态添加特征（非微信自带提示） |
| 所属 item | 正文 `a?`（`bqj` y=1356）、发送者 `史俊杰`（`bxt` y=1293）；消息行 `#bqh [0,1271][1440,1522]` |
| 落点判据 | P1 不在左上角 ✅ / P2 消息行下方 ✅ / P3 不压时间戳 ✅ / P4 水平侧一致 ✅ —— 位置对，**贴错了消息** |
| 全文扫描 | 全屏所有可见 TextView 中**无 `12368` 正文** ⇒ 摘要来自另一条（另一会话/另一时刻）消息 |
| stat（开态） | `enabled=true msg=true hint=true rescued=540 bound=0 added=3 binds=723 resolved=720 hits=4 missed=0 verifyFail=0 masked=0 talkerMismatch=0` |
| 台账规模 | `rescued=540`（10-28 时 208 ⇒ 跨版本单调累积，撞号概率随时长上升） |

证据文件：`_r21d_misplace_ui_evidence.xml`（uiautomator 全屏）、`_r21d_misplace_dbg.txt`（LogX 片段）。

## 2 对照 Lite Q3/Q5 判据

**升级判据命中**：错贴 item 的角标摘要 `12368` 与 `txt=` 正文 `a?` **不匹配**（摘要来自另一条消息）+ `stat` 的 `talkerMismatch=0`（< 错贴条数 1）⇒ Lite Q5「升级到落码观测增强（E）→ 视结果收紧 talker 闸门（A）」成立。

**证伪 4 条逐条核对**：
1. 错贴 item 的 u= 与 msgsnap 不一致 ⇒ 本次未取 msgsnap（界面错贴会话是群聊，长按会改状态）；但摘要与正文不匹配已独立坐实撞号，pos 分支由 11-16 dex 反汇编排除【dex 实证·高】，机会低。
2. 所有错贴摘要都能在 txt 找到正文 ⇒ **否**（`12368` 不在 `a?` 里）。
3. 两侧 talker 非空不等却仍贴 ⇒ `talkerMismatch=0`，非此支。
4. 关开关角标不消失 ⇒ **本次确实不消失**（见 §3），但根因是 `bound=0` 导致 sweep 停摆，非"不是 HintBadge 机制"——角标 View 特征（`TextView`/空 resource-id/index=1）已证是模块动态添加，且 msg=true 闸门在 onBind 时生效（:240）。⇒ **证伪 4 不成立**，整条链路不推翻。

## 3 第二处独立缺陷（本轮新发现）

**关 `show_hint=false` 后角标不消失**（3 秒等待，UI 角标数仍为 1），开回 true 也不变。`stat` 显示 `enabled=false hint=false bound=0` 仍角标在屏。

根因【源码实证】：`ensureSweep()` 在 `bound.isEmpty()` 时**直接 return**（`HintBadge.kt:563`），sweep 定时器不再调度；而当前 `bound=0`（角标所属 View 未被登记或是被 WeakHashMap 回收），关开关后 `sweep()` 从不执行 ⇒ 没有代码去摘屏上那枚角标。

⚠️ 这与 R21d §10.5 的"双向即时生效 PASS"不矛盾：那次验收时 `bound=2`（view 还在 bound 里），本次 `bound=0`。**判据对象必须是用户可见角标**（本次正确），不是 `bound` 计数。

⚠️ 也说明 Lite 产物 Q3 的 `dump`（遍历 `bound`）在 `bound=0` 时**逐条行为空**（本次 dump 只打了 header）——现场定位靠 uiautomator 直接看 View，不能只靠 `dump`。

## 4 裁决与下一步

- **根因【推断·高 → 现场实证升级】**：撞号错贴已由"摘要与正文不匹配 + 全屏无源正文 + talkerMismatch=0 + 角标确为模块所画"四证坐实。talker 空串放行（`HintBadge.kt:336-337` + `AntiRevoke.kt:710` `talker ?: ""`）是唯一未被反证的解释分支。
- **go**（Lite Q5）：落码观测增强 E（扩 `HintBadge.dump()` 字段：当前/台账 talker、origContent 原文、实际 View 角标文案）——从"条件 GO"升级为"建议 GO"，因为现场判据已坐实升级条件。修法 A（收紧 talker 闸门）仍待增强观测后定，避免凭猜测改判据（D3）。
- **暂不改码**：本轮只读取证完成，是否落码由用户批准。
- **现场复位**：`show_hint` 已回读 `true`（`PROBE kv 21:31:04.266`），四探针未动，G1 未动。

## 5 诚实边界

- 【UNKNOWN】错贴那条 B 的 msgId 与 A 的 msgId 是否相同（未做 msgsnap，避免长按改变界面）；撞号来自恢复/换机还是台账自然累积未知。
- 【UNKNOWN】`bound=0` 的精确成因（WeakHashMap GC vs view detach 后 remove）未追；只确认了"bound=0 ⇒ sweep 停摆 ⇒ 关开关不摘角标"的因果链。
- 所有结论基于单屏一次取证；非多次重复统计。
