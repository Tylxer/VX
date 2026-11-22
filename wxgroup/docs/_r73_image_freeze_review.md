# R73 图片转发冻结逻辑 —— fresh-eyes 复核 / 主会话自审登记

> 状态：[DONE]（两次独立复核 lane 均无实质产物，按「零产出三选一」转主会话自审 + 台账登记；见 `docs/派发审计台账.md` 03:01/03:02 记录）。
> 边界：本文件为复核产物；复核期间未改源码/未装机/未跑 gradle/commit。复核后主会话按自审结论修了一处回归并已重跑闸门。
> 复核对象：工作区相对 HEAD `d09d3b2` 的 `ForceForward.kt`、`R73ImageForwardFixTest.kt`、`R73LatestMsgFixTest.kt` diff。
> 根因终稿：`wxgroup/docs/_r73_image_forward_misselect_forensics.md`（A1：陈旧 View + F0 TOCTOU）。

## 裁决摘要

**修前：需修后装机**（1 个 P1）；**修后：可装机**（P0/P1 均无，仅余需真机验证项）。

- P1（已修）：`allowsViewText(kind)` 分支让 CARD/LINK/LOCATION 也走 `ref?.content` 优先，原逻辑这三类只用 `viewText`；ref.content 是 appmsg XML，真机可能把 XML 当正文发出。修法：`TEXT` 才 `ref.content` 优先；`CARD/LINK/LOCATION` 恢复 `viewText` 优先，完全不采信 `ref.content`；`IMAGE/VIDEO/VOICE/FILE/UNKNOWN` 恒空。单测同步锁住该优先级。
- P0：无。消息身份冻结、门禁/快照同源、g0 降级保留三条主链自审通过。
- 残留：M1/M2/M3 子机制未唯一，但修法对三者都稳健；真机仍需按 §真机判据验收。

## 1. CARD/LINK/LOCATION 正文优先级（P1，已修）

- 【源码确定】原 `snapshotOf`：`viewText.ifBlank { if (kind == TEXT) ref.content else "" }` ⇒ CARD/LINK/LOCATION 只用 viewText。
- 【源码确定】修前实现：`if (allowsViewText(kind)) ref?.content?.takeIf{...} ?: viewText else ""` ⇒ 三类 display-only kind 也优先 ref.content，存在 appmsg XML 发出风险。
- 【已修】现实现：`when { TEXT -> ref.content ?: viewText; allowsViewText(kind) -> viewText; else -> "" }`。`allowsViewText` 仍仅允许 TEXT/CARD/LINK/LOCATION 使用 viewText；非文本文件型恒空。
- 【单测】`R73ImageForwardFixTest` 锁 `allowsViewText(kind) -> viewText`、`CARD` 副本返回 viewText 且断言不发 XML；`snapshotOfGatesViewTextByKind` 源码断言同步。

## 2. 冻结字段时序（通过）

- 【源码确定】`freshRef = runCatching { msgRefOf(v) }.getOrNull()` 在 `synchronized(pressLock)` 前执行，长按当刻 View 新鲜，F0 解析到真被按住的消息；失败为 null、不阻断菜单。
- 【源码确定】newBurst 分支内 `burstFrozenRef = freshRef`、`burstFrozenAt = burstStartAt`；`BURST_GAP_MS=500ms` 内不覆盖，`burstStartAt` 更新时同步绑定，防跨轮串味。
- 【推断】新增一次 `msgRefOf(v)` 反射在长按主线程，量级与原 gate 同链，未引入循环/全表扫；真机观察卡顿即可。

## 3. resolveRefForBurst 优先级与同源（通过）

- 【源码确定】优先级 frozen > cachedRef() > 现场 msgRefOf；冻结仅在 `burstFrozenAt == burstStartAt && startAt > 0L` 认领。
- 【源码确定】最新一条：frozen 可为 null，g0 cached 保留兜底；慢选 >10s：frozen 仍有效，不再走陈旧 View；View 回收重绑：frozen 钉死 msgId/path，不随 F0 漂移。
- 【源码确定】`onShowGridMenu` 与 `snapshotOf` 均调用 `resolveRefForBurst(v)`；`hasRef = ref != null`；`NativeMenuGate.shouldInject(enabled, lastPressAt, now, hasRef)` 四条件未变。无「门禁放行但快照 UNKNOWN」分叉。

## 4. 红线回归（通过）

- 未改 `ForwardLogic.planFor`、`canResendFile`、`ForwardNativeLogic`，未打开原生转发。
- g0 134 类 hook、`cachedAt >= burstStartAt`、新 burst 清 `cachedG0Ref/cachedG0At`、`stripSender`、`BURST_TTL/staleViewForProbe` 均保留。
- `R73LatestMsgFixTest` 两条旧源码断言同步为「同源入口保留 cachedRef/msgRefOf 降级」，语义等价，不是删测试规避。

## 5. 单测有效性（通过）

- 新 `R73ImageForwardFixTest.kt` 11 例：冻结图片 ref 胜过 stale view text（A1 回归锁）、冻结为空时 cached 兜底、过期冻结拒绝、新 burst 清冻结/缓存、allowsViewText 门禁表、源码断言冻结字段/同源调用/正文门禁/g0 红线。
- 变异映射：删冻结或 newBurst 不赋值 → `frozenFieldsDeclaredAndWrittenOnNewBurst` 红；拆散门禁/快照同源 → `gateAndSnapshotShareFrozenResolve` 红；IMAGE 用 viewText → `snapshotOfGatesViewTextByKind` / `allowsViewTextPureFunctionLocksKindGate` 红；CARD 误用 ref.content → 新增断言红；清缓存/时效被删 → `newBurstClearsG0CacheAndKeepsTtl` 红。
- 逻辑复刻是等价副本，真正随生产变异变红的是源码断言层。

## 6. 真机判据（装机后最小步骤）

1. 长按那张图片，菜单出现后（点转发前）跑 `op=forward sub=snap`：期望 `kind=IMAGE`、`msgId=19725728`、`path` 非空、`exists=true`、`textLen=0`；不得再出现 `kind=TEXT msgId=19725729 textLen=2`。
2. 点「转发（模块）」选一个安全目标：期望 `FORWARD plan` 为 `RESEND_FILE`，对端收到图片，不再收到「妈的」。
3. 回归：普通文本消息仍 `kind=TEXT` 且正文正确；最新一条消息仍有模块按钮且能发；慢选 >10s 仍不发空/SKIP。
4. 若发现 `FF ref src=frozen msgId=...` 与长按对象不符，记录日志再判，不直接改代码。

[DONE]
