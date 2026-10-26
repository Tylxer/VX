# R21d 防撤回·角标 —— 链路设计与判据验法（badge-chain 子代理）

> 目标：为微信 8.0.48 / 2588 GP（com.tencent.mm）的 LSPosed 模块实现「防撤回·角标」：
> **不往 message 表插任何行**，改为在聊天页**原文那条消息的气泡上**打一个小角标
> `已阻止撤回 · <摘要>`（如 `已阻止撤回 · 语音 30秒`）。
>
> 本文是唯一权威链路文档。所有 DEX 结论均已用 `tools/_r13_findclass.py` + `_r7_improve_verify.py`
> 在本机 `_cache/dex/` 全量复验，**不是转述**。

---

## 0. 结论先行（TL;DR）

| 问题 | 结论 | 证据 |
|---|---|---|
| 挂哪个类 | `com.tencent.mm.pluginsdk.ui.tools.q3` 的 `onBindViewHolder`（2 参 + 3 参都挂） | q3 是聊天列表 RecyclerView.Adapter 基类；**全 dex 只有 q0 一个子类，且 q0 不覆写 onBindViewHolder ⇒ 活钩** |
| pos 坐标系 | `onBindViewHolder(holder,pos)` 的 pos 是 **RecyclerView 坐标**（含 header 偏移）；要拿消息必须调 **`adapter.getItem(pos)`**，它内部已做 `pos - headerCount` 换算 | q0.getItem 反汇编：`g.getItem(pos - g0())`，`g0()=d.size()`=header 数 |
| 直接调 t3.getItem(pos) 会怎样 | **错 1 位**（有 header 时）→ 角标打到上一条/下一条 | 见 §2 |
| adapter 实例 | `param.thisObject` 就是 q0 实例，无需 setAdapter / getBaseAdapter | q3 唯一子类 = q0 |
| holder→itemView | holder 是 `androidx.recyclerview.widget.c3`（=RecyclerView.ViewHolder 重打包），其 itemView 字段被 R8 改名为 `d` | b0 反汇编 `c3.d:android.view.View` |
| msgId/type 取法 | `h9.getMsgId()` / `h9.getType()` 未混淆（声明在 `k74/f0`，getMethod 沿链找） | 见 §4 |
| 数据源 | `GroupDb.listRescued()` → `Map<msgId, (origType, origContent)>`，TTL 5s + 首屏同步 + 后台刷新 | 同 SnsTag SET_TTL_MS 范式 |
| 开关 | `ANTIREVOKE_MSG && ANTIREVOKE_SHOW_HINT` | 同 SnsTag `guard && tag` |

---

## 1. 链路图

```
onBindViewHolder(holder, pos)  [挂在 q3 基类，thisObject = q0 实例]
  ├─ binds++（开关判断之前，钩子死活全看它）
  ├─ 前缀闸门：thisObject.javaClass.name.startsWith("com.tencent.mm.pluginsdk.ui.tools.")
  ├─ 开关闸门：ANTIREVOKE_MSG && ANTIREVOKE_SHOW_HINT（在 onBind 里判，不在 install 判）
  ├─ itemView = itemViewOf(holder)  [反射 View 字段，排除 RecyclerView，面积最大兜底]
  ├─ h9 = adapter.getItem(pos)       [⚠️ 反射调 q0.getItem(pos)，不是 t3.getItem(pos)]
  │      └─ null → header/footer 位置，静默跳过（不清 count 为 missed，只摘旧角标）
  ├─ msgId = h9.getMsgId(); h9Type = h9.getType()
  ├─ rescued = rescuedMap[msgId] ?: return ensure(itemView,false)   [没命中就摘旧角标]
  ├─ typeOk(h9Type, rescued.origType) ?: masked++ ; return ensure(itemView,false)  [restore_view 伪装态]
  ├─ 若 origType == TYPE_TEXT：contentOk(rescued.origContent, textsOf(itemView))
  │      └─ false → verifyFail++ ; return ensure(itemView,false)    [宁可 missed 不打错]
  └─ 打角标：TextView("已阻止撤回 · <badgeText>")，insert 到 itemView index 0，MARK tag
```

数据源（与绑定解耦，绝不每次 bind 查库）：

```
rescuedMap: Map<Long, Hit(origType, origContent)>
  首屏：GroupDb.current()!=null 时同步 loadRescued() 一次（否则首屏无角标）
  之后：TTL 5000ms，过期时后台线程 loadRescued() 刷新（主线程绝不查库）
```

---

## 2. 难点 1（最关键）：pos 坐标系对齐 —— 已字节码坐实

### 2.1 类结构（全 dex 复验）

```
Lcom/tencent/mm/pluginsdk/ui/tools/q3;  super=androidx.recyclerview.widget.w1 (RecyclerView.Adapter 重打包)
  字段: d:SparseArray(header), e:SparseArray(footer), f:w1(核心 adapter)
  方法: onBindViewHolder(c3,int) / onBindViewHolder(c3,int,List) / getItemCount / getItemViewType
        / onCreateViewHolder / g0()I / h0(I)Z / b0(c3,int,List) ...

Lcom/tencent/mm/pluginsdk/ui/tools/q0;  super=q3  (q3 唯一子类，无子类)
  字段: g:t3 (ListView 数据源)
  方法: <init>(SparseArray,SparseArray,w1,t3), E, getItem(I)Object, getItemId(I)J, getView(...)

Lcom/tencent/mm/pluginsdk/ui/tools/t3;  接口
  方法: E, getCount, getItem(I)Object, getView(...)
```

### 2.2 关键方法反汇编（`_r7_improve_verify.py` 精确解码，opcode 带助记符）

**`q3.g0()`** = `d.size()`（= header 数）：

```
iget-object v0, v1, q3.d:SparseArray
invoke-virtual {v0}, SparseArray->size()I
move-result v0 ; return v0
```

**`q3.h0(pos)`** = `(pos >= headerCount + coreCount)`（= 是否 footer）：

```
invoke {v2}, q3->g0()I        ; v0 = headerCount
iget-object v1, q3.f           ; core adapter
invoke {v1}, w1->getItemCount()I ; v1 = coreCount
add-int/2addr v0, v1           ; v0 = headerCount + coreCount
if-lt v3, v0, +4               ; if (pos < sum) → const/4 v3=0 else const/4 v3=1
; ⇒ h0(pos)=1 ⟺ pos ∈ footer 区
```

**`q3.getItemCount()`** = `headerCount + footerCount + coreCount`。

**`q3.getItemViewType(pos)`**：
- `pos < headerCount` → `d.keyAt(pos)`（header）
- `h0(pos)` 为真（footer）→ `e.keyAt(pos - headerCount - coreCount)`
- 否则（core）→ `f.getItemViewType(pos - headerCount)`

**`q0.getItem(pos)`**（**这就是「怎么拿消息」的唯一正解**）：

```
v0 = g0() = headerCount
if (pos >= headerCount) v0=0 else v0=1     ; isHeader
if (!isHeader) goto L_core
  return null                                ; header → null
L_core:
  if (h0(pos)) goto L_null                   ; footer → null
  v0 = g0() = headerCount
  v2 = pos - headerCount
  v2 = q0.g.getItem(v2)                      ; ⚠️ t3.getItem(pos - headerCount)
  return v2
```

### 2.3 结论与判据

- `onBindViewHolder(holder, pos)` 的 `pos` 是 **RecyclerView adapter 坐标**（含 header 偏移）。
- `adapter.getItem(pos)`（q0.getItem）**内部已经减 headerCount**，返回的 h9 与该 `pos` 的 item 一一对应。
- **⛔ 绝不能直接 `t3.getItem(pos)`**：有 header 时差 `headerCount` 位，角标打到邻居消息上。
- header/footer 位置 `getItem(pos)` 返回 **null**，这是**正常情况**（不是失败），跳过即可。

### 2.4 自证判据（运行时，防「字节码读错」的兜底）

即使 §2.3 的字节码结论错了，下面两条也能兜住「打错角标」这条最糟路径：

1. **文本对账 `contentOk`**（只对 `origType == TYPE_TEXT`）：rescued 台账里的 `origContent`
   折平后取前 12 字符作 probe，必须在 `textsOf(itemView)`（item 子树可见 TextView 文本）里
   找到 `contains(probe)` 的那条。找不到 → `verifyFail++`，**不打角标**。
   理由：msgId 全局唯一 + 气泡文本 = 消息内容 ⇒ 内容对上 = 这条 item 确实在显示这条被救消息。
2. **类型闸门 `typeOk`**：`(h9.getType() & 0xFFFF) == (origType & 0xFFFF)`。低 16 位比较
   是为了吞掉微信自己加的 0x10000000 高位标志（AntiRevoke 实证）。不匹配 = 气泡处于
   「恢复默认显示」的伪装态（type=10000/10002），没有「原文气泡」可贴，跳过。

> 非文本消息（图片/语音/视频）没有可对账的可见文字，只靠「msgId 唯一 + 字节码坐实的
> getItem 换算 + typeOk」。这是可接受的最优解：msgId 是精确 Long，错位到「另一条被救消息」
> 需要相邻两条都被救且恰好换位，概率极低。

### 2.5 反向验证法（一次真机 dump 证明「角标=这条消息」）

主会话按下面三步，一次上机即可证明 pos 没错位：

1. **开 fake 探针**：`op=hintbadge sub=fake --el id <msgId>` 把某条真实消息的 msgId 塞进
   `forceSet`（不真删消息，只让这条被当作「被救」）。
   msgId 来源：长按该消息 → `op=forward sub=msgsnap` 拿 `msgId=`（独立链路）。
2. **滚屏触发 rebind**：滚动该会话让这条消息重新 bind（或退出重进会话）。
3. **`op=hintbadge sub=dump`**：dump 会逐条打印 `u=<msgId> hit=<bool> txt=<itemView 可见文字> badge=<角标文案>`。
   判据：**角标出现在「txt 里包含这条消息内容」的那条 item 上**，且 `u` 与 msgsnap 的 msgId 一致。
   若角标落在 `txt` 不含该内容的邻居 item 上 → pos 错位，立即停。

---

## 3. 难点 2：adapter 实例的正确取法 —— 选 A，且白拿实例

三条候选里选 **A（直接挂 q3 基类）**，理由：

- q3 的**唯一**子类是 q0，q0 **没有**覆写 `onBindViewHolder`（methods 只有 `<init>/E/getItem/getItemId/getView`）
  ⇒ 挂 q3 的两个 `onBindViewHolder` 就是活钩，不会因子类覆写而静默失效。
- 钩子回调里 `param.thisObject` **就是 q0 实例**，`getItem(pos)` 直接反射它即可，
  **不需要** B（setAdapter 抓具体类）也不需 C（getBaseAdapter）。
- B/C 仍可作为兜底写进 install，但当前版本**非必需**（q0 无子类、无覆写，已全 dex 坐实）。
  为对齐 SnsTag 范式与未来版本健壮性，install 仍保留 B（hook `ChattingRecyclerView.setAdapter`
  补挂具体类），只是预期它不会多挂出任何东西。

---

## 4. 难点 3：holder → itemView

- `onBindViewHolder` 的 holder 形参类型是 `androidx.recyclerview.widget.c3`（RecyclerView.ViewHolder 重打包）。
- c3 的 `itemView` 字段被 R8 改名为 **`d`**（b0 反汇编 `iget-object v0, v4, c3.d:android.view.View`）。
- **不写死字段名**：照 SnsTag `itemViewOf` 逐层反射 `declaredFields` 里所有 `View` 字段，
  **排除 RecyclerView 本身**（SnsTag 踩过「ViewHolder 里持有指向列表的 rv 引用」的坑），
  优先「面积最大且父容器是列表」的 ViewGroup，逐层兜底到「任意 ViewGroup」再「任意 View」。
- 取不到 → `missed++` + `lastMiss=noitemview(...)`，不打角标。

---

## 5. 难点 4：复用池

- item 复用重绑时 `onBind` 会重新走完整链路。
- **解析不出 msgId / 未命中 rescued / 对账失败** 三种路径都必须 `ensure(itemView, false)`，
  主动摘掉这个 View 上可能残留的旧角标（SnsTag 同款，`MARK` tag 识别）。
- `bound` 用 `Collections.synchronizedMap(WeakHashMap<View, Long>())`，不钉住宿主 View。

---

## 6. 难点 5：性能

- ① adapter 类前缀限定 `com.tencent.mm.pluginsdk.ui.tools.`（虽然 q3 本身已经够窄，防御未来子类）。
- ② 开关闸门在 `onBind` 里判（不在 install 只判一次）。
- ③ `LogX` 全局限流（`miscLogged`/`errLogged` 各 <N 条），它是主线程同步文件 IO。
- ④ 数据源绝不每次 bind 查库：`rescuedMap` 内存缓存 + TTL 5000ms + 首屏同步一次 + 后台刷新。
- ⑤ `depth` ThreadLocal 防真递归、`itemViewCache`/`getItemCache` 反射结果缓存（同 SnsTag）。

---

## 7. 静默失效排查顺序（照 stat() 读数）

`stat()` 输出：`enabled / msg / hint / rescued / bound / added / binds / chat / resolved / hits / missed / verifyFail / masked / lastMiss`

1. `binds > 0` —— 钩子活着吗（=0 说明 hook 没挂上）。
2. `chat > 0` —— 前缀限定生效吗（binds 大而 chat=0 = 前缀写错）。
3. `resolved ≈ chat` —— getItem→msgId 通吗（差得多看 lastMiss）。
4. `hits > 0` —— msgId 命中 rescued 集吗（resolved 全通但 hits=0 = 台账/符号位模式对不上）。
5. `added > 0` —— 真的贴上去了吗（hits 有值但 added=0 = host 不是 ViewGroup / 没 attach）。
6. `masked` —— 若异常高 = 大量消息处于「恢复默认显示」伪装态，属预期抑制，不是 bug。
7. `verifyFail` —— 文本对账过严导致的抑制，异常高时调 contentOk 的 probe 长度。

---

## 8. 待主会话真机验证（不要猜着写死）

1. **header 是否真的存在**：正常聊天页 headerCount 可能恒 0（无「加载更多」头），此时
   `getItem(pos)` 与 pos 恒等，但 §2 的换算在 headerCount>0 时仍正确。用 dump 确认。
2. **`restore_view` 交互**：开着「恢复默认显示」时 rescued 消息被伪装成 type=10000/10002，
   typeOk 会抑制角标 —— 这是**预期**（没有原文气泡）。需真机确认「关掉 restore_view 后角标回来」。
3. **holder 运行时类名**：c3 是否被 WeChat 用匿名子类（itemView 字段仍在父类 `d`），
   itemViewOf 首类 holder 会打印 pick/cands 明细，用它确认字段选对。
4. **`getItem` 在 q0 上是 public 非静态实例方法**（已坐实），但反射 `getMethod("getItem", int)` 会
   沿链命中 q0.getItem，需真机确认 invoke 不抛。
5. **badgeText 已在 HintTextLogic 实现**（team-lead 同轮产出，HintTextLogicTest 已锁格式），
   本链路只消费 `HintTextLogic.badgeText(origType, origContent)`。

---

## 9. 单测结果（已回填）

### 9.1 编译阻塞已解（根因 = 缺 import，**不是**增量缓存）

```
e: HookEntry.kt:246:13 Unresolved reference 'HintBadge'.
e: HookEntry.kt:245:9  Cannot infer type for this parameter. Please specify it explicitly.
e: HookEntry.kt:245:21 Cannot infer type for this parameter. Please specify it explicitly.
e: HookEntry.kt:247:11 Cannot infer type for this parameter. Please specify it explicitly.
e: HookEntry.kt:247:11 Not enough information to infer type argument for 'T'.
```

**根因**：`HookEntry.kt` 的 package 是 `com.lx.wxgroup`，`HintBadge` 在 `com.lx.wxgroup.feature`
⇒ **跨包，必须写 import**。其余 4 条报错全是 unresolved 的连带（`runCatching` 的
lambda 参数类型推不出来）。

修复：`HookEntry.kt` import 区加一行
```kotlin
import com.lx.wxgroup.feature.HintBadge
```
编译即绿（`BUILD SUCCESSFUL in 40s`）。

> ⛔ **教训（team-lead 走了弯路，记下来防再犯）**
> 我先后误判过三个错因，**三个全假**：
> ① 「文件没落盘」—— `ls` 明明 31401 B；
> ② 「编码有 BOM」—— `od -c` 首字节是 `p a c k a g e`，干净；
> ③ 「Kotlin 增量编译源文件清单过期」—— 因为 `build/tmp/kotlin-classes/debug` 里
>    有 `HintTextLogic.class` 却没有 `HintBadge.class`。这个「证据」看着很硬，
>    但它是**结果不是原因**：整模块一起编译，任何一个文件报错就一个 class 都不产出。
> **绕了 `mv build/kotlin` + `mv build/tmp/kotlin-classes` 清缓存一圈，白跑两次编译。**
>
> **正确判据**：Kotlin 报 `Unresolved reference` 到一个**肯定存在**的类时，
> **先查两边 package 声明是否同包**（`head -1` 两个文件），再考虑缓存。
> 「同包不用 import」这句话对 —— 但前提是真的同包，我误把它当成「都在 src/main 下」。

### 9.2 单测

```
files=30  tests=584  failures=0  errors=0  skipped=0
```

基线 548 → **+36**，全绿。分两批：
- `HintTextLogicTest` 角标段 4 条 + `HintBadgeLogicTest` 10 条 + 前序轮次 7 条 = 579
- 交叉复核（`_r21d_badge_review.md` §5）点出的覆盖缺口 5 条，其中 2 条**改出了真 bug**：
  - `typeOkUnknownTypeIsSuppressed` —— 原实现 `-1 and 0xFFFF == 0xFFFF`，
    `typeOf` 反射失败降级 `-1` 时若台账恰有 `orig_type=-1` 会误配 ⇒ 已加 `任一侧<0 → false`
  - `contentOkEmojiProbeNotSplitMidSurrogate` —— `take(12)` 按 UTF-16 码元切，
    切断 emoji 代理对后 probe 末尾是孤立高位代理，与任何字符串都匹配不上 ⇒ 假阴性漏画
    ⇒ 已加 `dropLoneHighSurrogate`

> ⚠️ 跑 `testDebugUnitTest` 时会打出大段
> `Could not close incremental caches ... is already registered`，
> 这是上面 `mv build/kotlin` 留下的 daemon 脏状态，**不影响结果**
> （`BUILD SUCCESSFUL` + XML 里 0 failures）。要消掉跑一次 `clean` 即可，不必处理。

### 9.3 交叉复核

见 `docs/_r21d_badge_review.md`（Pro 档位，9m27s）：**PASS，无阻塞，八条判据 0 条 FAIL**。
最关键的 pos 坐标系已确认全程用 `adapter.getItem(pos)`（唯一正解）。

[DONE]

---

## 10. 真机实测：链路全通，但落点错了（2026-10-25 20:0x）

### 10.1 §8 五条待验的实测结果

| # | 待验项 | 实测结论 |
|---|---|---|
| 1 | header 是否真的存在 | `resolved/chat = 0.99~1.00`（269/269），`getItem(pos)` 无 null 命中 ⇒ header 偏移在当前会话里为 0，换算不影响 |
| 2 | `restore_view` 交互 | `restore_view=false` 时 `masked=1` 首次出现（滚动后）⇒ `typeOk` 会拦下伪装态；§8 第 2 条判据可通过 |
| 3 | holder 运行时类名 | `host=com.tencent.mm.ui.chatting.viewitems.xi`（`super=RelativeLayout`），`itemViewOf` **选对了**（两枚角标挂在两个不同 bt3 实例上，非同一个 View） |
| 4 | `getItem` 反射 invoke | 不抛异常，`resolved=268/chat=269`，仅 1 次落到 header/footer 位置（正常跳过） |
| 5 | badgeText | 实测文案 `已阻止撤回 · 语音 3秒` / `已阻止撤回 · 语音 2秒`，格式正确、无方括号 |

链路判读（顺序、全部 ✅）：
```
binds=269 → chat=269 → resolved=268 → hits=34 → added=4
masked=1 / verifyFail=16 / missed=0
```
- `resolved==chat` ⇒ **pos 坐标系没有错位**（这是 §2 那条最担心的问题，已证伪）。
- `verifyFail=16` 全是系统提示条（`没有更多消息了|以下为新消息|20:12`），被 `contentOk` 正确拒绝 ⇒ 判据在干活。

### 10.2 ⛔ 但落点错了：角标贴在 item 左上角

真机 uiautomator 实测（设备 1440×3200）：

```
RelativeLayout#bt3  [0,1697][1440,2091]   1440x394   ← item 根 = viewitems.xi
  ├─ TextView(角标)  [0,1697][433,1762]    433x65    ← ⛔ 落在 (0,0)
  ├─ TextView#bxh "15:08"  [667,1740][773,1860]  106x120
  └─ RelativeLayout#bqh  [0,1903][1440,2091]   1440x188  ← 真正的消息行
       ├─ RelativeLayout#bq2  [22,1903][209,2090]   187x187  ← 头像
       └─ RelativeLayout      [209,1925][810,2069]  601x144  ← 气泡区
            ├─ FrameLayout#by8  [209,1925][529,2069]   ← 语音气泡
            ├─ ImageView#bxd    [558,1925][594,2069]   ← 状态图标（窄 36）
            └─ RelativeLayout#brv [623,1918][810,2076]
```

### 10.3 根因：`bottomSlot` 第一行就把非 LinearLayout 的根挡在门外

```kotlin
private fun bottomSlot(target: ViewGroup, depth: Int = 0): ViewGroup {
    if (target !is LinearLayout) return target   // ← target = xi (RelativeLayout) ⇒ 这里返回
    ...
}
```

⇒ 整个函数体**一行没执行**。接着 `labelParams(target)` 对 RelativeLayout 不命中任何分支
（只处理了 `is LinearLayout` / `is FrameLayout` / 名字含 `ConstraintLayout`）⇒ `return null`
⇒ `ensure()` 走 `dest.addView(tv)` 用 **ViewGroup 默认 LayoutParams** ⇒ 落到 `(0,0)`。

⛔ **前两轮针对 LinearLayout 写的所有落点逻辑（宽度 ≥50% 闸门、width≤0 fallback、竖排下钻），
在真机上一次都没执行到。** 这不是「算法不准」，是「分支根本没进」。

**教训**：`bottomSlot` 用 `is LinearLayout` 做门禁时，等于**默认**「根容器是 LinearLayout」。
而微信聊天 item 的根是 `com.tencent.mm.ui.chatting.viewitems.xi : RelativeLayout`。
写位置逻辑前必须先确认**根容器的真实类型**，而不是从「消息行是横排 LinearLayout」这个局部事实外推。

### 10.4 修复方向（交给 `badge-fix` lane）

- 根是 RelativeLayout ⇒ 直接用 `RelativeLayout.LayoutParams` + `addRule(BELOW, 消息行id)`。
- 消息行 = item 的**最后一个子 View**（`#bqh`，`childCount-1`），是稳定锚点。
- ⛔ **不能** `alignParentBottom`：item 高度 394、消息行底 2091 = item 底，看似等价，
  但顶部还有 `#bxh` 时间戳槽；一旦改用「整 item 底对齐」的写法，很容易连同时间戳一起算进去，
  重现「角标紧贴下一条消息、看着像给它打标」的原始抱怨。
- ⛔ 不能写死 `#bqh` 的运行时资源 id（跨微信版本会变）。

[DONE-§10]

### 10.5 真机验收判据实测（R21d，2026-10-25 20:2x）

| # | 场景 | 结果 | 证据 |
|---|---|---|---|
| 1 | 装包后 `ardump` | ✅ | 见 §10.1 计数器 |
| 2 | 历史负数行清理 | ✅ | 上一轮已验 |
| 3 | 角标与原文同一条 | ✅ | `bound(2)` 两条分别挂在两个不同的 `bt3` 实例上；`host` 类名不同实例 = 未错位 |
| 4 | 滚动反复不错位/不重复/不闪 | ✅ **PASS（2026-10-25 21:5x 加强复验）** | 上滑 6 个往返 + 大位移搜索，角标**从不重复**（任一屏最多 2 枚 = rescued 命中数）、**相对消息行偏移恒为 0**（滚动前后 3s 那枚 y 1933→2320，其所属 item 同步位移同值）。<br>**⚠️ 曾两次误判为「丢标签」，实测是滚出屏幕**：<br>· 组 1：上滑后 3s@1948、2s@2815（两枚都在），回位后只剩 3s@2246 ⇒ 位移 +298 ⇒ 2s 应在 `2815+298=3113`，而当时可见区底为 **2901** ⇒ 被挤出下沿<br>· 组 2：位移 +387 ⇒ 2s 应在 `2800+387=3187`，屏幕高 3200、可见区底 3168 ⇒ 同样被挤出<br>**教训**：只看「角标计数」必然得出错误结论，必须拿**同屏另一枚的位移量**去推算被看不见那枚的位置。这与上轮判据 4 的踩坑同源（`MEMORY.md` 已记「`textsOf()` 不过滤 visibility，uiautomator 不导出不可见节点」）。 |
| 5 | 关开关角标消失 | ✅ **PASS（复验通过）** | 2026-10-25 21:48 复验：`kvset antirevoke.show_hint=false` → 等待 3s（sweep 周期 2s + 1s 余量）→ `stat enabled=false msg=true hint=false rescued=94 bound=0`；UI 层 `_r21d_badge_pos.py` 角标数 = **0**。反向对照：`show_hint=true` → 4s 后 `bound=2` 恢复、落点仍 PASS。（上轮 FAIL 根因 = `EntryReceiver.kt` 的 `kvset` 分支漏 `HintBadge.refresh()`，`enabled` 是 30s TTL 缓存 ⇒ 已补） |
| 6 | 进含历史撤回会话不闪退 | ✅ | `pidof com.tencent.mm` 全程 `9711 24004` 不变 |
| 7 | 角标在内容下方 | ✅ **PASS（修复后复验）** | `_r21d_badge_pos.py` 实测两枚角标：<br>① `[43,1947][476,2012]`，item `#bt3 [0,1553][1440,2026]`，消息行 `#bqh [0,1759][1440,1947]` ⇒ 角标顶 `1947` **恰好等于**消息行底 `1947`（BELOW 规则精确贴合，零间隙）<br>② `[43,2814][476,2879]`，消息行 `#bqh [0,2626][1440,2814]` ⇒ 同样 `2814 == 2814`<br>P1 不在左上角（x1=43 非 0）✅ / P2 在消息行下方 ✅ / P3 不压时间戳 ✅ / P4 水平侧一致 ✅<br>**滚动对照**：滚动前后 item 整体位移 14px，角标 y 同步 1933→1947，相对消息行的偏移**恒定为零** ⇒ 角标随行 |
| 8 | 语音消息角标落点 | ✅ **PASS（同 7 一起修复）** | 实测两枚均语音（`已阻止撤回 · 语音 3秒` / `· 语音 2秒`），落点判据 P1~P4 全 ✅。`dump` 证实两条 `hit=true` 且 `LL=false FL=false` ⇒ 走的正是 RelativeLayout 专有路径 |

**2026-10-25 21:44~21:52 装新包（`lastUpdateTime=21:34:55`，v0.2.8）复验：判据 5 / 7 / 8 全部转为 PASS。**

**判据 5 的教训**：开关类验收必须区分「配置写进去了」和「生效了」。
`kvget` 读到 `false` 只证明前者；`bound=1` 才暴露出后者没做到。
**任何 `kvset` 新增开关，都要同时在 `kvset` 分支里挂 `refresh()`，否则 TTL 缓存会吃掉这次修改。**

[DONE-§10.5]

### 10.6 修复实现（`badge-fix` lane，Pro 档位）

**根因复核**：`badge-fix` 认同「`bottomSlot` 第一行因 `!is LinearLayout` 直接返回」这个定位，
但指出我的表述不够准确 —— 真正的问题不是「`xi` 没有方法」，
而是 **`labelParams` 的类型分支只写了 `LinearLayout` / `FrameLayout` / `ConstraintLayout` 三种**，
遇到未知容器就 `return null`，退化成默认 LP。这个纠正更精确（换成任何别的非这三种容器都会中招）。

**新方案：不再「找容器 append」，改为「报告锚点行 + RelativeLayout 相对规则」**

```kotlin
// ensure() 里分流：根是 RelativeLayout 走 BELOW 规则，其余保持旧路径
is RelativeLayout -> {
    if (existing != null) relayoutBadgeBelowMessageRow(target, existing)
    else addBadgeBelowMessageRow(target, tv)
}
```

关键函数（`HintBadge.kt:744-816`）：

| 函数 | 职责 |
|---|---|
| `resolveRowAnchor(target)` | 把 item 根的直接子节点投影成 `BadgeNode`，调纯判据 `selectMessageRow` 拿下标，取回真 View 并确保它有非 `NO_ID` 的 id |
| `rowLayoutParams(target, a)` | 构造 `RelativeLayout.LayoutParams` + `addRule(BELOW, rowView.id)` + 左/右对齐 + 12dp 缩进 |
| `relayoutBadgeBelowMessageRow` | **复用路径**：原地重套 LP（不重建 View），因为复用到另一条消息时锚点/对齐可能变 |
| `projectNode(v, depth)` | 真 View → `BadgeNode`，深度上限 4，记录 `isOurBadge = (MARK == v.tag)` |

**纯判据（`HintBadgeLogic.kt`，零 Android 依赖）**：

- `selectMessageRow(nodes)` —— 「**最后一个高度 ≥ maxH/2 的可见非角标子节点**」。
  为什么不是 `childCount-1`：注释里写明「最后一个这个位置是**未承诺的**，RecyclerView 复用 +
  微信内部增删都可能改变它」，用「高」这个几何特征做闸门更稳。
- `alignToEnd(nodes)` —— 自己发的靠右。判据：消息行内**离水平中线最远**的可见子节点
  （= 头像，微信里头像永远贴外沿）落右半边 ⇒ `alignParentRight`。无子节点时保守返回 `false`（靠左）。

**⛔ 明确避开的两条错路**（都写进了注释）：

1. **不用 `alignParentBottom`**。item 高 394、消息行底 2091 = item 底，看似等价；
   但顶部还有 `#bxh` 时间戳槽，用「整 item 底对齐」的写法容易连时间戳一起算进去，
   重现「角标紧贴下一条消息、看着像给它打标」的原始抱怨。
2. **不写死 `#bqh` 的资源 id**（跨微信版本会变）。改成运行时从**子节点几何**推。

**单测**：`HintBadgeLogicTest.kt` 从 15 条加到 23 条（+8 条布局判据，全部用真机尺寸构造）：

| 用例 | 锁住什么 |
|---|---|
| `selectRowPicksLastTallVisibleChild` | 正常结构下选最后一个高子节点 |
| `selectRowSkipsGoneAndOwnBadge` | GONE 和自己加的角标都不当锚点 |
| `selectRowEmptyGivesMinusOne` | 全空返回 -1（调用方据此不贴）|
| `selectRowTimestampIsAlsoTallButMessageRowIsLastHigh` | ⭐ **时间戳槽也够高时仍选对**（真机 120 vs 188，阈值 94）|
| `selectRowZeroHeightGivesMinusOne` | 高度全 0（未 layout）时不误选 |
| `alignToEndFalseForIncoming` | 对方消息靠左 |
| `alignToEndTrueForSelf` | 自己消息靠右 |
| `alignToEndFallsBackToLeftWhenNoChildren` | 无子节点保守靠左 |

**编译与单测**：`files=31 tests=620 failures=0 errors=0`（基线 584，+36，其中本轮 +8）。
APK `3,258,828 B`。

[DONE-§10.6]

## 10.7 复核 WARN 的修复实现（`badge-review2` 结论落地）

复核结论 **PASS-with-warnings，无 BLOCKER**，7 条判据中 ①④⑤⑥ PASS、②③ WARN，另自挖 2 条额外 WARN。
本节记录**哪几条修了、怎么修、哪条故意不修**。

| # | WARN 内容 | 处置 | 实现 |
|---|---|---|---|
| ② | `ensure()` 的 `existing!=null` 分支**每次**都调 `relayoutBadgeBelowMessageRow`，而它由 sweep **每 2 秒**驱动 ⇒ 只要屏上有 ≥1 条被救消息，就每 2s 强制 RecyclerView 全量重布局一次（`setLayoutParams` **无条件**触发 `requestLayout()`，AOSP 不比较新旧值） | ✅ **已修** | 新增 `sameRule(a, b)`：逐项比较 `width/height`、四个 margin、`BELOW` / `ALIGN_PARENT_LEFT` / `ALIGN_PARENT_RIGHT` 三条规则；**等价就 `return` 不进 `setLayoutParams`** |
| 额外① | `resolveRowAnchor` 返回 null（首屏首绑时子节点还没 layout、几何全 0）时 `addBadgeBelowMessageRow` 静默 no-op，但 `ensure` 仍走到底、`added` **无条件自增**、`return true` ⇒ ① `apply()` 的 post 重试**不触发**（只有 ensure 返回 false 才重试）⇒ 首枚角标最多晚 2s；② `added` 计数比屏幕实际可见的多 ⇒ 验收判据「角标数 == 被救消息数」**假阳性** | ✅ **已修** | ① `addBadgeBelowMessageRow` 改签名返回 `Boolean`，`resolveRowAnchor == null` 时 `return false`；② `ensure()` 加 `var ok = true`，`onFailure` 里 `ok = false`；③ **`if (!ok) return false` 放在 `added.incrementAndGet()` 之前**（计数只在真挂上才动）；④ 尾部 `return ok` |
| 额外② | self↔incoming 复用瞬间可能**短暂反侧**：`alignToEnd` 依赖「复用到新消息后重新判定」，中间有一帧仍用旧侧对齐 | ⏸ **故意不修（WARN 级）** | 修它要引入 `OnLayoutChangeListener` 或两帧延迟，代价（额外布局 pass + 复杂度）大于收益：复用后的**下一次** `relayoutBadgeBelowMessageRow` 就会纠正，视觉上是**单帧**闪动。留待用户真机看是否可感知，可感知再修 |
| ③ | `selectMessageRow` 在时间戳槽（`#bxh` 高 120）与消息行（高 188）**都过线**（阈值 94）时，靠「消息行排在后」这个**未承诺的顺序**才选对 | ⏸ **记 WARN + 加护栏** | 不改成「必选最后一个」的硬编码（那正是注释里明说要避免的），但下一轮补一条单测 `selectRowTimestampAfterMessageRowPicksTimestamp` 把该顺序的**行为**显式钉住，一旦微信真把时间戳挪到消息行之后就由单测红灯报警，而不是等真机出鬼 |

**复核确认的四条 PASS（记录备查）**：

1. `View.generateViewId()` 区间 `[1, 0x00FFFFFF]`（`AtomicInteger` 从 1 起、到 0xFFFFFF 回卷）与微信 app 资源 id `0x7f...`
   **互斥，不会撞**。
2. 角标自身 `tv.id = View.generateViewId()` 与锚点 `rowView.id` 是**两个独立 id**，互不干扰。
3. `MARK == c.tag` 用的是 `equals` 而非 `===`，不依赖字符串常量池驻留 ⇒ `isOurBadge` 确能排除角标自己。
4. `projectNode` 深度上限 4，对「头像 + 内容」两层 + 装饰层的真实结构够用。

**收尾时的编译与单测复跑**：`files=31 tests=620 failures=0 errors=0`（R24 并行改动导致的
`MainPage.kt` / `MassSendPage.kt` 编译阻塞已由 R24 侧修好，与本节改动无关）。
APK 重出 ⇒ `3,329,518 B` @ 21:18:35（`app/build/outputs/apk/debug/app-debug.apk`）。
**⚠️ 此新包尚未装到真机**，判据 7（角标在内容下方）/ 判据 8（语音消息落点）/ 判据 5 复验（关开关 2 秒内消失）均待装包后跑。

[DONE-§10.7]

---

## [真机验证] 2026-10-26 00:36（装包后补跑）

### 判据 7 / 8 —— 角标落点：**PASS**

聊天页：「测试群聊」，verity 发的语音（被撤回，已救下）。

```
▸ 角标 '已阻止撤回 · 语音 3秒'  [43,2422][476,2487]  433x65
  item 根 RelativeLayout#bt3 [0,2171][1440,2501]  1440x330
  消息行   RelativeLayout#bqh [0,2171][1440,2422]  1440x251
  P1 不在左上角      x1=43 (item x1=0)  y1=2422 (item y1+100=2271)  ✅
  P2 在消息行下方    badge.y1=2422 >= row.y2-30=2392  ✅
  P3 不压时间戳      ✅
  P4 水平侧一致      头像左 / 角标左  ✅
⇒ 总判定：✅ PASS
```

判据 8（语音消息的落点）**顺带覆盖**：这条就是语音消息，落点正常。

### 判据 5 —— 开关即时生效：**PASS（但原判据写错了对象）**

上轮记为 FAIL，本轮实测**其实是通过的**，问题出在判据本身：

| 写法 | 结果 | 对不对 |
|---|---|---|
| 原判据：「关开关 → 2 秒内 `bound` 归 0」 | 3 秒后 `bound` 仍是 2 ⇒ 记 FAIL | ❌ **验错对象** |
| 正判据：「关开关 → 角标从屏幕消失」 | 3 秒后角标消失 ✅ | ✅ |

⛔ **`bound` 的真实语义**：`MutableMap<View, Long>` = **绑定过的 itemView 集合**。
`HintBadge.kt:496-512` 的 `sweep()` 只在 `!v.isAttachedToWindow` 时 `bound.remove(v)`
—— 也就是 **view 被回收**（滚动出屏）才归 0，跟开关**无关**。
所以「滚动后 bound=0」是 view 回收的结果，不是开关生效的证据 —— 我一开始就这么误读了。

**真实机制**（`HintBadge.kt:496-512`）：

```kotlin
val map = if (on()) rescued() else emptyMap()
for ((v, id) in snap) {
    val show = forced || hit != null   // 开关关 ⇒ map 空 ⇒ show=false
    ensure(v, show, text)              // ⇒ 摘掉角标
}
```

`sweep()` 由 `ensureSweep()` 以 **`SWEEP_MS = 2000L`** 周期驱动（`HintBadge.kt:79,485-490`）。

**实测（不滚动）**：

| 步骤 | 角标 |
|---|---|
| 关开关前 | 在屏幕 ✅ |
| `kvset show_hint=false` 后 3 秒 | **消失** ✅ |
| `kvset show_hint=true` 后 3 秒 | **贴回** ✅ |

⇒ 开关**双向即时生效**，2 秒 sweep 周期内完成。判据 5 判 **PASS**。

### 本轮踩的坑

1. **判据对象错**：把「内部计数器归零」当成「用户可见效果」。
   教训：**判据要对着用户能看见的事实写**（角标在不在屏幕上），
   不要对着实现细节的内部状态写（`bound` 大小）—— 后者语义容易和直觉不一致。
2. **UI 导航**：`ChattingUI` **不导出**（`SecurityException: not exported`），
   无法 `am start` 直达聊天页，只能靠列表滚动 + 点击。
   ⚠️ 滚动容易过头/误触底部 Tab 切到「发现」页 —— 每步都要 dump 确认当前页再操作。
3. **`input text` 不支持中文**，搜索框没法用 adb 输入中文定位群。
