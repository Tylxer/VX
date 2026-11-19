# R73「g0 入口缓存 h9」独立对抗复核

> 复核人：r73-g0-cache-review（hy4flash 临时规则，与实现同档，仅作对抗视角）。
> 性质：**只读复核**——未改任何源码、未碰 adb/设备/gradle/commit。
> 被复核对象：`ForceForward.kt` g0 缓存方案 + `R73LatestMsgFixTest.kt` + `docs/_r73_latest_msg_fix.md`。
> 宿主：微信 8.0.48 / 2588；模块 com.lx.wxgroup。
> 核心任务：钉死 P0 时序疑点，给「可装机 / 打回」二选一裁决。

---

## 〇、裁决摘要（先给结论）

**裁决：可装机（带一条必须写进真机判据的时序口径修正 + 一条低优先提醒）。**

- P0 时序疑点：**确认生效——hook 对「当前同一调用栈里稍后才执行的 g0」生效，第一次长按即可命中。** 实现文档 §1.4 的承诺成立，不需要「第二次长按才成功」。理由见 §一，机制层可定论，仅「内联/JIT deopt」属框架实现相关的最小不确定残留，已给出真机判据兜底。
- 其余 5 项：门禁/快照两处都正确改了 ref 取源，字段抽取逐字一致，红线未破，单测源码断言层有效。发现 **1 个 P1 隐患**（幂等标志 `g0RefHooksMounted` 在 `ok==0` 时不回滚，`probe("on")` 抢跑会锁死后续长按重试）和若干 P2 边界，均不阻塞装机。

一句话给 team-lead：**按「第一次长按即应命中」设判据；真机若首次失败，先看 `FF g0-ref mounted ... hooked=` 的值与日志顺序，不要预设「两次长按才生效」。**

---

## 一、P0 时序疑点 —— 确定结论

### 1.1 问题重述

调用链（实现文档 §1.3 / `docs/_r73_native_menu_inject.md` §2.1）：

```
View.performLongClick  [before trap: 此处 installG0RefHooks()]
  └─ 原方法 performLongClick 体执行
      └─ mOnLongClickListener.onLongClick → wg4/a.onItemLongClick
          └─ r3.clear() → onCreateContextMenu
              └─ g0(r3, view, info, h9)   ← 第一次执行，需被 hook 缓存 args[3]
                  └─ q.n → q.o → q.p  [before 门禁: cachedRef() 读缓存]
```

疑点：在 `View.performLongClick` 的 **before** hook 里才第一次 Xposed-hook 那 134 个 `g0` 方法，对「当前这一次」仍在同栈、稍后才执行的 g0 调用，到底生不生效？

### 1.2 确定结论：生效，第一次长按即可命中

**机制层可定论，无需真机才能判断。** 关键是把两个常被混淆的场景分开：

| 场景 | hook 是否对「当前这次」生效 | 原因 |
|---|---|---|
| 在方法 X 的 before hook 里 hook **X 自己** | 当前这次 X 调用**不**重新走 hook | X 已经过了入口点、正在体内执行，before 已跑完 |
| 在方法 X 的 before hook 里 hook **下游尚未 invoke 的方法 Y**（本例：X=performLongClick，Y=g0） | 当前这次 Y 调用**走 hook** | Y 此刻尚未被 invoke，还没过入口点；hook 改的就是入口点 |

实现文档 §1.4 的写法「早于同一次长按的 g0 调用，故第一次长按即可命中」**机制上成立**：

1. Xposed / LSPosed（LSPlant 内联 hook）挂载一个方法，本质是改写该方法 `ArtMethod` 的 **entrypoint**（quick-compiled / 解释入口）指向 bridge/trampoline。这个内存改写是**即时生效**的：之后发生的**每一次 invoke** 都读新入口点。
2. 本例的时间顺序是确定的：
   - `t1`：`performLongClick.before` 触发 → `installG0RefHooks()` 同步跑完 134 次 `findClass + hookAll`（`ForceForward.kt:329`），g0 的入口点此时已被改写；
   - `t2`：`performLongClick` 原方法体继续 → onLongClick 回调链 → `onCreateContextMenu` → **g0 首次被 invoke** → 走 bridge → `beforeHookedMethod` 缓存 `args[3]`（`ForceForward.kt:356-369`）；
   - `t3`：`q.p.before` 门禁 → `cachedRef()` 命中（`ForceForward.kt:456`）。
   - `t1 < t2 < t3`，g0 在挂载完成后才第一次被调用，必然走新入口。
3. 「同一个调用栈」不是障碍：Xposed 的限制只对**已经进入方法体**的调用无效；g0 在 mount 时既不在栈上、也未被 invoke，属于「挂载后的后续调用」，完全生效。这与「hook 一个方法后，下一次调用才生效」的常识并不矛盾——g0 的「下一次调用」就是本次长按稍后的那一次。

### 1.3 最小不确定残留（诚实标注）

唯一无法从静态代码 100% 排除的是 **ART JIT 内联**：若 g0 在挂载前已被 JIT 内联进调用者且 hook 引擎未及时 deopt，内联代码会绕过入口点。但：

- g0 经 `onCreateContextMenu`→item 的**多层跨类虚调用**触达（Android framework → 微信 listener → viewitems item），这种调用点几乎不会被 JIT 内联成连续机器码；
- LSPosed/LSPlant hook 时会主动令相关代码 deoptimize，这是 Xposed 框架在 ART 上能工作的基本前提，否则所有 hook 都会因内联失效。

故该残留概率极低，**不改变「第一次长按即可命中」的结论**。真机兜底判据见 §八：若首次长按仍 `ref=false`，第一看点是 `hooked=` 数值与日志先后，而不是默认「要按两次」。

### 1.4 mount 在 trap before 里的位置 + 异常对 lastPressAt/age 的影响

`ForceForward.kt:312-331` 顺序（`synchronized` 块 → mount → `lastPressAt`）：

```
:317-326  synchronized(pressLock):
            if (now - burstStartAt > 500ms):
                burstStartAt = now; burstDeepest = WeakRef(v)
                cachedG0Ref = null; cachedG0At = 0L; newBurst = true
:329      if (newBurst) installG0RefHooks()      ← mount 在 burst 刷新之后
:330      lastPressAt = now                      ← mount 在 lastPressAt 之前
```

结论：

- **mount 在刷新 `burstStartAt`/`burstDeepest`/清缓存之后、在 `lastPressAt = now` 之前。**
- **mount 不会因异常阻断 `lastPressAt` 更新，因而不影响门禁 age。** `installG0RefHooks()` 整体被 `runCatching{...}.onFailure{}` 包裹（`:349/:379`），内部 134 次 `findClass/hookAll` 又各自 `runCatching`（`:373-376`），任何抛错都被吞，函数正常返回，`lastPressAt = now`（`:330`）**必定执行**。外层 trap 的 `runCatching`（`:313`）也不会被穿透。
- 唯一理论例外：`installG0RefHooks()` 若因挂载 134 个 hook 在主线程耗时较长（见 §二），age 的「起点」`lastPressAt` 会晚几拍写入，但门禁（`q.p`）发生在 performLongClick 全流程走完之后，此时 `lastPressAt` 已是本次 `now`，`age` 仍为小值。真机铁证里 `age=127` 本就排除时间窗，不受影响。

**P0 判定：不成立（修复方案在时序上自洽），不构成立即打回理由。**

---

## 二、复核点 1：ANR 风险与挂载时机

**结论：ANR 风险量级可控（百毫秒级、一次性），不阻塞；但发现一个 P1 幂等标志隐患。**

### 2.1 开销量级

`installG0RefHooks()`（`:347-383`）在**主线程**（`performLongClick` 回调在 UI 线程）首次长按时同步执行：

- 134 次 `XposedHelpers.findClass(name, cl)`：内部走 `ClassLoader.loadClass`，有类缓存，通常几毫秒到几十毫秒量级；
- 134 次 `hookAll(cls, "g0", cb)`：反射枚举 `g0` 重载 + 改入口点 + deopt，单次约 1–5ms，合计约 0.1–0.5s 量级；
- 总计：首次长按**一次性**百毫秒级卡顿。ANR 阈值 5s，距离尚远；后续长按因幂等标志直接 return（`:348`），零开销。

判定：**可接受**。实现文档 §1.4 已诚实声明「一次性开销、实测待真机确认」，口径正确。建议（非阻塞）：mount 前后各打一行 `LogX.i(...cost=...)`，真机量一下实际值归档。

### 2.2 有没有更省的挂载时机

| 候选 | 可行性 | 评价 |
|---|---|---|
| `handleLoadPackage` 里直接挂 | 文档 §1.4 / `_r72b_mount_diagnosis.md` 已证伪：业务 dex 晚于该时机，`findClass` 抛 `ClassNotFoundError` | 不可行 |
| 现有 `turn(true)` / `probe("on")` | ForceForward 无独立 `turn(true)` hook；`probe("on")` 是 `op=forward sub=on` 诊断命令，真人不会在每次冷启主动触发 | 只能作辅触发（已做，`:1668`），不能当主触发 |
| 只 hook 实际消息用到的那一个类 | g0 是哪个 item 类要等 g0 **被调用那一刻**才知道，那时本次已经执行完、缓存不到 | 逻辑上不可能覆盖首次 |
| hook `BaseDexClassLoader.loadClass` 延迟挂载 | 能吃冷启时机，但引入新 hook 面、违背「最小改动」 | 过度工程，不建议 |

**当前「首次长按延迟挂载」是满足约束的合理折中**，无需改挂载时机。

### 2.3 幂等性确认 + P1 隐患

- 正面：`g0RefHooksMounted.compareAndSet(false, true)`（`:348`）保证只挂一次；`probe("on")` 也走 `installG0RefHooks()`（`:1668`），幂等门的二道触发不会重复挂载 ✓。
- **P1 隐患（需修，但不阻塞装机——触发条件窄）**：`installG0RefHooks()` 仅在 `loader == null`（`:352`）和外层整体抛错（`:380`）时回滚 `g0RefHooksMounted=false`；但 134 次 `findClass` 若**全部失败**（冷启 dex 未就绪时 `probe("on")` 抢跑触发），每次失败都被内层 `runCatching{}.onFailure{}` 吞掉（`:373-376`），`ok=0`，**幂等标志仍停留在 true**。后果：此后 dex 就绪、用户首次长按调用 `installG0RefHooks()` 在 `:348` 直接 return，**连重试机会都没有**，g0 缓存永远为空 ⇒ 修复彻底失效，而日志只有 `hooked=0` 一行。
  - 触发条件：`op=forward sub=on` 在冷启早期（业务 dex 未加载）被执行。真人主动诊断通常在 dex 就绪后，故概率低；但 team-lead 自动化脚本若在 `handleLoadPackage` 后立刻 `sub=on` 即可触发。
  - 修法（打通仅需一行）：在 mount 循环结束后，`if (ok == 0) { g0RefHooksMounted.set(false); LogX.w("...no g0 hooked, will retry on next long-press"); return }`。让后续长按可重试。
  - 该隐患与文档 §四判据 5「若 `hooked=0` 需退备选方案」的口径冲突——实际应先尝试重试，而非立即退方案。已写入 §八真机判据。

---

## 三、复核点 2：缓存串味与 WeakReference GC

**结论：时效校验与「新 burst 清缓存」设计正确，两种方向的串味都能防；WeakReference 在菜单显示前被 GC 的风险低。**

### 3.1 时效校验链路

- 新 burst 判定：`now - burstStartAt > BURST_GAP_MS(500ms)` 时清 `cachedG0Ref=null / cachedG0At=0`（`:318-325`）。
- 缓存写入：g0 hook 的 `before` 里 `cachedG0Ref = WeakReference(h9); cachedG0At = now`（`:363-367`）。
- 读取校验：`cachedRef()` 要求 `cachedG0At > 0 && cachedG0At >= burstStartAt`（`:1043-1052`）。

### 3.2 双向串味分析

- **「先历史、后最新」**：历史长按 g0 缓存 h9_hist（burstStart=t1）；最新长按（间隔 >500ms）trap 清缓存、burstStart=t2、g0 重缓存 h9_latest；门禁/快照 `cachedRef()` 读 h9_latest（cachedAt=t2 ≥ burstStart=t2）✓ 不串味。
- **「先最新、后历史」**：对称，同样因新 burst 清空而拿到 h9_hist ✓。
- **边界（P2）**：两次独立长按间隔 **≤500ms** 会被当成同一 burst、不清缓存，理论上会串味。但真机上一次完整长按含菜单显示+点击，间隔几乎必然 >500ms；且View链对历史消息优先返回 `msgRefOf`（快照里 `msgRefOf(v) ?: cachedRef()`），即便缓存残留也不会盖掉 View 链能取到的历史消息。风险低，可注明但不改。

### 3.3 WeakReference GC 风险

- h9 是宿主 adapter 持有的消息数据对象，菜单显示期间消息仍在列表、adapter 持强引用 ⇒ WeakReference 不会 GC。文档 §1.5「弱引用自然失效 = 安全降级」的假设成立。
- 仅当消息被**移出/删除**才失效，此时降级 null 正是防误转旧消息的安全设计 ✓。
- 残留（P2，真机观察项）：若某类 g0 传入的是**临时构造**的 h9（非 adapter 持久对象），`g0` 返回后可能立即 GC ⇒ `cachedRef()` 取 null ⇒ 白修。取证显示 g0 第 4 参来自 adapter，风险低。真机判据中加一条：若日志出现 `hooked>0` 但点击仍 `kind=UNKNOWN`，需查是否缓存弱引用解引用失败（可在 `cachedRef()` 加一行「解引用=null」日志定位）。

---

## 四、复核点 3：两处覆盖 + 字段一致性

**结论：门禁与快照两处 ref 取源都已改；`msgRefFromH9` 与原 `msgRefOf` 第 5 步逐字一致，字段无遗漏。**

### 4.1 两处覆盖核对

| 依赖点 | 改后代码 | 是否覆盖 |
|---|---|---|
| 门禁 `onShowGridMenu` | `val viewRef = v?.let{msgRefOf(it)}; val hasRef = viewRef != null \|\| cachedRef()!=null`（`:455-456`） | ✓ View 链失败降级缓存 |
| 点击快照 `snapshotOf` | `val ref = v?.let{msgRefOf(it)} ?: cachedRef()`（`:931-932`） | ✓ View 链失败降级缓存 |

两处都用「View 链优先、`cachedRef()` 兜底」的统一口径，避免「按钮出来但点下去 SKIP」。✓

### 4.2 字段一致性核对（git diff 实证）

`git diff -U3 ForceForward.kt` 的 `@@ -569,6 +1008,14 @@` 段显示：原 `msgRefOf` 第 5 步的**整段字段读取代码原样下沉**到新函数 `msgRefFromH9`，`msgRefOf` 第 5 步仅替换为 `return msgRefFromH9(h9)`（`:1011`）。逐项比对：

| 字段 | 来源 | 是否一致 |
|---|---|---|
| `msgId` | `getMsgId()` as Long | ✓ |
| `msgType` | `getType()` as Int | ✓ |
| `content` | `getContent()` as String | ✓ |
| `imgPath` | `XposedHelpers.getObjectField(h9,"field_imgPath")` | ✓ |
| `talker` | `getObjectField("field_talker")` | ✓ |
| `msgSvrId` | `getLongField("field_msgSvrId")` | ✓ |
| `kind` | `ForwardLogic.kindOf(msgType, appMsgTypeOf(content))` | ✓ |
| `raw` | `raw = h9` | ✓ |

`cachedRef()`（`:1052`）调的正是同一个 `msgRefFromH9`，所以两来源产出的快照字段完全一致 ⇒ 不会出现「缓存路径拿到 ref 但缺 kind/msgId 导致 SKIP」。✓

---

## 五、复核点 4：红线核查（git diff）

**结论：本轮 g0 改动未触碰任何红线。** 所有 g0 相关 hunk 都是纯新增；红线代码要么未变、要么属于前序 R73-L6 改动（本轮未碰）。

- `NativeMenuGate.shouldInject` 四条件（object 定义见 diff `@@ -25,14 +25,61 @@`）：`enabled && lastPressAt>0 && now-lastPressAt<=WINDOW_MS && hasRef`，**四条件保留、无 talker**；门禁调用点仍是 `shouldInject(enabled, lastPressAt, now, hasRef)`（`:457`），本轮只改了入参 `hasRef` 的**计算方式**（加 `|| cachedRef()!=null`），未改判据本身 ✓。
- `snapshotNow` 仍为 `snapshotOf(staleViewForProbe())`（`:913`）；`staleViewForProbe()` 实现未改（diff 仅改其 KDoc 注释）。这是前序 R73-L6 的既有状态，本轮未动 ✓。
- `LABEL_FORWARD = "转发（模块）"`（`:129`）：diff 显示由更早的「转发到多个（模块）」改为现值是**前序改动**，本轮 g0 hunk 未碰 ✓。
- `menu.add(GROUP_ID, ITEM_ID, ORDER, LABEL_FORWARD)` + `setIcon`（`:479-485`）：参数与顺序未在 g0 相关 hunk 出现，未改 ✓。
- 常量值：`WINDOW_MS=4000L`、`ITEM_ID/GROUP_ID/ORDER/ICON` 均与单测 `redLinesUntouched` 断言一致 ✓。

说明：工作区 `git diff` 相对 HEAD（f62a558）包含前序 R73-L6 的未提交改动，无法单靠 diff 区分「本轮 vs 前序」。但按 hunk 归属判定——本轮 g0 方案的改动点全部是新增（清单、状态、trap、mount、门禁 hasRef 行、快照 ref 行、msgRefFromH9/cachedRef、probe on），**没有任何一个 hunk 修改上述红线代码**——可确认本轮未破红线。

---

## 六、复核点 5：单测有效性（是否自证式假验证）

**结论：非纯假验证——源码断言层（7 例）对生产变异真实敏感；纯逻辑复刻层（5 例）是自证规约锁，不随生产变异。文档对其中 1 例的变异声明有夸大。**

### 6.1 12 例分层

- **源码字符串断言（真锁生产，7 例）**：`gateUsesG0CacheFallback`、`snapshotUsesG0CacheFallback`、`g0HostClassesFullyEnumeratedAndMounted`、`trapTriggersDeferredG0Mount`、`newBurstClearsG0Cache`、`cachedRefSharesH9ReflectorWithMsgRefOf`、`redLinesUntouched`。这些直接读 `ForceForward.kt` 断言字符串存在性，改回原样会真实变红 ✓：
  - M1 门禁去掉 `cachedRef()` → `gateUsesG0CacheFallback` 红；
  - M2 快照去掉 `?: cachedRef()` → `snapshotUsesG0CacheFallback` 红；
  - M3 去掉 `cachedG0Ref = null` → `newBurstClearsG0Cache` 红；
  - M4 去掉 `installG0RefHooks()` 调用 → `trapTriggersDeferredG0Mount` 红；
  - M5 改回旧文案 → `redLinesUntouched` 红。
- **纯逻辑等价复刻（自证规约锁，5 例）**：`latestMsgUsesG0CacheWhenViewChainFails`、`viewChainRefTakesPriorityOverG0Cache`、`noRefAtAllStillFailsClosed`、`staleCacheRejected`、`cacheBeforeBurstStartIsIgnored`。这些测的是测试文件内自造的 `StubRef`/`StubG0Cache`，**不读生产代码**，故**不会因生产变异变红**。它们锁的是「设计语义」而非「生产实现」，有价值但不构成对实现的回归保护。

### 6.2 文档表述夸大（P2）

实现文档 §三声称「去掉新 burst 清缓存 → `newBurstClearsG0Cache` / `staleCacheRejected` 变红」——**部分不实**：`staleCacheRejected` 测的是 `StubG0Cache.onNewBurst()`（内写死 `ref=null`），生产代码怎么改都不影响它。真正会因 M3 变红的只有 `newBurstClearsG0Cache`。建议 team-lead 知悉：防变异的有效网是那 7 个源码断言，5 个复刻例是语义文档性质。

### 6.3 未跑闸门

实现代理明确未跑 gradle，单测**尚未被执行验证**。本复核受纪律限制也不跑。**是否 12 例真绿需 team-lead 统一闸门确认**——关注两点：`g0HostClassesFullyEnumeratedAndMounted` 的正则计数（`"com.tencent.mm.ui.chatting.viewitems.X"` 应匹配 134，清单第 1 个是 `ChattingItemDyeingTemplate`、其余 133 个同类名，均在 viewitems 包下，静态看匹配成立）；`functionBody("object NativeMenuGate")` 的定位（本复核静态分析：object 块自带大括号，`depth` 从 object 的 `{` 配平到其 `}`，`shouldInject` 作为表达式体无内部大括号、不干扰配平，定位应正确）。二者均未实跑，仍建议闸门确认 `redLinesUntouched` 不误红/误绿。

---

## 七、真机判据应如何调整（给 team-lead 的口径修正）

实现文档 §四判据需按本复核结论做如下修正，否则会误导判据：

1. **首次长按即应命中（关键修正）**：长按最新一条，应见 `FF g0-ref mounted classes=134 hooked=...` **随后**出现 `FF NATIVE added ...`。**不要**把「第一次长按仍 `ref=false`」当作预期、等第二次。若首次失败，判据是：
   - 看日志里有没有 `FF g0-ref mounted ...` —— 没有 ⇒ mount 根本没触发（检查 trap 是否命中）；
   - 有但 `hooked=0` ⇒ **先按 §2.3 修法回滚幂等标志后重试**（不要直接退备选方案）；
   - `hooked>0` 但仍失败 ⇒ 查该消息类型的 g0 是否真被调用、缓存弱引用是否解引用为 null（§3.3）。
2. **点击不 SKIP**：点「转发（模块）」后应见 `FF forward clicked kind=TEXT|IMAGE ...`（非 UNKNOWN）；若 `hooked>0` 却仍 UNKNOWN，重点查 GC 解引用失败（§3.3）。
3. **不串味**：历史→最新、最新→历史两个方向各测一次；间隔若短于 500ms 不具代表性（§3.2 边界）。
4. **不崩宿主 / ANR**：首次长按一次性挂载 134 hook，量一下卡顿（建议补 `cost=` 日志）；个别类签名漂移 `hooked<134` 可接受。
5. **辅触发时序**：若走 `op=forward sub=on` 抢跑触发挂载，确认其发生在业务 dex 可见之后，否则命中 §2.3 P1 隐患。

---

## 八、最终裁决

### 裁决：**可装机**

理由：

- P0 时序疑点经机制分析**确认不阻塞**——g0 在 `performLongClick.before` 挂载完成后才首次 invoke，hook 生效，第一次长按即可命中，实现文档承诺成立；
- 门禁/快照两处 ref 取源都正确改、字段抽取逐字一致、红线未破、缓存生命周期设计正确、单测源码断言层对变异真实敏感；
- 全部发现项均为 P1/P2，无一构成「出按钮但点下去 SKIP」或「崩宿主」的功能性失败。

### 装机前/后的附带条件（不阻塞，但应落实）

1. **【P1，建议装机前修一行】** `installG0RefHooks()` 在 `ok==0` 时回滚 `g0RefHooksMounted=false`（§2.3 修法），避免 `probe("on")` 冷启抢跑锁死重试。触发窄，但修法成本极低、收益明确。
2. **【必须】** 真机判据按 §七修正：首次长按即应命中；`hooked=0` 先重试而非退方案。
3. **【P2】** 补两条真机观测日志：mount `cost=`、缓存 `cachedRef()` 解引用 `null` 计数，便于定位 GC/ANR 残留。
4. **【P2】** 文档 §三变异声明删去「`staleCacheRejected` 会因去掉清缓存变红」的错误表述（§6.2）。

### 明确的「打回条件」——若真机出现以下任一，则应打回而非继续

- 首次长按 `hooked=0` 且按 §2.3 修法回滚后，第二次及以后长按仍 `hooked=0`（说明延迟挂载时机彻底不足 ⇒ 退到「修 msgRefOf 失败点 B」备选方案）；
- `hooked>0` 但点击点击恒 `kind=UNKNOWN` 且确认非 GC 解引用问题（说明缓存路径的字段抽取在真机 h9 上不成立）；
- 首次长按出现 ANR / crash（134 个 hook 同步挂载开销超预期）。

[DONE]



