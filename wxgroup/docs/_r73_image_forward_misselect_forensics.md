# R73 图片转发误选相邻文本 —— 只读取证与修法设计

[DONE]

> 状态：**根因已由主会话真机铁证收敛到 A1（View/position 错位到下一条消息）**，见 §0 / §3.5。
> 边界：只读，不改 app/src/main、不碰 adb、不装机、不跑 gradle、不 commit。
> 结论分级：【源码确定】/【真机日志确定】/【假设待验证】。

## 0 结论摘要

### 0.1 真机铁证（原样并入，主会话受控复现：图片长按菜单已开、未点击）
1. `02:43:49.327 FORWARD FORWARD SNAP kind=TEXT msgId=19725729 svrId=3148701842618129723 textLen=2 path='' exists=false chat= raw=com.tencent.mm.storage.h9`
2. `02:43:49.535 FORWARD FORWARD MSGSNAP ... list=recycler pos=12 posVia=F0 adapter=com.tencent.mm.ui.chatting.adapter.i0 h9=h9 msgId=19725729 type=1 isSend=0 imgPath=none`
3. 同 chat 只读查库：`msgId=19725728 type=3 imgPath=THUMBNAIL_DIRPATH://th_f315db38a5d17f6ec88a5c2e64eb2007`（**图片**）；`msgId=19725729 type=1 content=wxid_jv8035ypcigc11:\n妈的`（strip 后 `textLen=2`）；后续 `19725730=学老a`、`19725731=上来就卖公司是吧`。
4. `02:43:37.513 FF NATIVE added id=1464270849 size=9`；`02:43:37.554 FF CLICK bound ... text=转发（模块）`。

### 0.2 收敛判定（真机日志确定）
- **根因 = A1：`staleViewForProbe()` 取到的 View，经 `positionOf` 的 F0 解析成 adapter position 12，对应的是图片后面那条文本消息 19725729（type=1、imgPath=none），而不是图片 19725728。**
- 生产快照（`msgRefOf ?: cachedRef`）与**不走缓存**的 `msgsnapReport()` 同时得到 `msgId=19725729`，且 msgsnap 明写 `pos=12 posVia=F0 posFail=none` ⇒ 解析源是 **View→list→positionOf→getItem 这条链**，不是 `cachedRef` 串味。
- **`D+C` 被排除**：`snap.kind=TEXT && path=''` ⇒ ref 不是图片（D+C 要求 kind=IMAGE、path 有效）；本次「那句话」`text=妈的` 来自被错认的文本消息自身 `content`（`rawText` 走 `ref.content` 兜底），不是相邻 TextView 污染。
- **`A2`（纯缓存串味）被排除**：msgsnap 不用缓存也得到同一条错消息，缓存不是必要条件。
- **症状闭环**：错认 19725729 ⇒ `kind=TEXT`、`text=妈的`、`path=空` ⇒ `planFor` 返回 `RESEND_TEXT` ⇒ `TextSender` 原样发出「妈的」。即用户所见「转发图片实际发出图片后面那句话」。

### 0.3 子机制（见 §5.3 最终定案）
A1 之下「为什么 F0=12」：主会话后续拿到的完整 MSGSNAP 行（`view=FrameLayout ageMs=12266 attached=true listCls=ScrollControlRecyclerView`，见 §5.1）显示——从菜单打开到探针间隔 `ageMs=12266`（> `BURST_TTL_MS=10s`），`staleViewForProbe()` 仍返回这个陈旧 View，`positionOf` 经 F0 解析为 position 12 并命中下一条文本。主会话据此判定为 **M2：陈旧 View 被回收/重绑**。`view=FrameLayout` 为通用容器，M1（长按即抓错文本行）与 M3（itemRoot 上溯选错）未被该日志单独证伪，但 M2 是能解释 `ageMs>TTL` 且 `posFail=none` 的最小机制；三者互斥裁决树保留在 §3.5.1。**无需改生产代码**。

**支撑 A1 的源码硬事实（【源码确定】，供追溯）**：
1. **F0 语义**：`positionOf`（ForceForward.kt:1089）在 recycler 分支逐个试 `F0/G0/getChildAdapterPosition/getChildLayoutPosition`，`F0` 即混淆后的 `RecyclerView.getChildAdapterPosition(View)`，返回**该 ViewHolder 当前的 adapter position**。它不记录「长按当时这条视图对应哪条消息」——取的是「探针执行时这个 View 实例所在的位置」。
2. **生产与探针共用同一 View 源**：`snapshotNow()`（:922）与 `msgsnapReport()`（:1827）都走 `staleViewForProbe()`（:2126），后者只返回 `burstDeepest?.get()`、**忽略 `BURST_TTL_MS=10s`**。`burstDeepest` 在 `View.performLongClick(before)` 里按 `BURST_GAP_MS=500ms` 去重、只认 burst 内第一个 View（:318-327）。菜单 02:43:37 打开、探针 02:43:49 执行，间隔 ~12s > TTL，正是靠「忽略 TTL」才拿到这个陈旧 View。
3. **ref 双源**：`snapshotOf` 的 `ref = msgRefOf(v) ?: cachedRef()`（:940-941），`msgRefOf` 走的就是上面第 1 条的 `positionOf`；`msgsnapReport` **不走缓存**、独立重走同一条链。两探针 msgId 相同 ⇒ 该 View 经 F0 解析就是 19725729，与缓存无关。
4. **下游放大器（非本次根因，但解释「为什么发出的是文本」）**：`planFor`（ForwardLogic.kt:341-344）`hasText` 先判、`hasFile` 后判；错认成 TEXT 后 `text=妈的`、`path=空` ⇒ `RESEND_TEXT`。`fallbackStrategy`（ForwardNativeLogic.kt:206-207）同样 text 优先。
5. 原生转发默认关（`nativeState()` 在 `FORWARD_NATIVE` 未开时恒 `UNKNOWN`，:1341），本次 `kind=TEXT` 与本路径无关。

> 原 §0 曾把 D+C 列为「最可能」、A 列为「次可能」，那是**仅凭源码**的可达性排序；真机日志已将其推翻为「D+C 排除、A1 坐实」，以本节 §0.2 为准。

## 1 代码路径

### 1.1 端到端调用链（源码确定）

```
长按图片 → View.performLongClick(before)
  → installLongPressTrap: burstStartAt/burstDeepest 记最深 View，清 cachedG0Ref（:310-337）
  → installG0RefHooks: g0(r3,view,info,h9) 把第4参 h9 缓存到 cachedG0Ref（:348-392）
菜单显示 → onShowGridMenu: hasRef = msgRefOf(v)!=null || cachedRef()!=null（:442-506）
点击「转发(模块)」→ onForwardClick → snapshotNow() → snapshotOf(staleViewForProbe())（:911-962）
  → openTargetPicker(snap) → execute(snap, picked)
  → plan = ForwardLogic.planFor(snap, nativeState())（:1199）
  → runBatch: 按 plan.strategy 分发（:1223-1287）
       NATIVE → nativeForward（默认关，走不到）
       RESEND_TEXT → resendText → TextSender.send(target, snap.text)（:1614-1616）
       RESEND_FILE → resendFile → FileSender.send(IMAGE, target, snap.localPath)（:1631-1657）
       SKIP → 不计 sent，toast 带「未实际发送」后缀
```

### 1.2 `snapshotOf(v)` 逐字段来源（ForceForward.kt:936-962，源码确定）

| 字段 | 来源 | 关键行 | 与报障的关系 |
|---|---|---|---|
| `viewText` | `extractText(v)`：v 子树最长非噪声 TextView | :937 / :1119 | **图片消息本应空**；非空即可能被相邻文本污染 |
| `ref` | `msgRefOf(v) ?: cachedRef()` | :940-941 | 两条取源，任一条串味都会取错消息 |
| `kind` | `ref?.kind ?: UNKNOWN`；由 `kindOf(msgType, appMsgTypeOf(content))` 算 | :942 / :1045 | 取错 h9 ⇒ kind 跟着错 |
| `rawText` | `viewText.ifBlank { if kind==TEXT ref.content else "" }` | :944-946 | **kind=IMAGE 时，viewText 非空就直接进正文** |
| `text` | `HintTextLogic.stripSender(rawText)` | :950 | 只削 wxid 前缀，不改来源 |
| `localPath` / `fileExists` | `ref?.imgPath` + `File(path).exists()` | :951 / :956 | 取错 ref ⇒ path 空 / exists=false |
| `msgId`/`msgSvrId`/`raw` | `ref.*` | :958-960 | 判「ref 是否取错」的指纹 |

### 1.3 ref 的两条取源与风险点

- **`msgRefOf(v)`（:977-1025，源码确定）**：v 沿 parent ≤12 层找列表（按继承链 `listKindOf`，:2089）→ `positionOf(list,v)`（:1073：recycler 走 F0/G0 混淆链，listview 走 `getPositionForView`）→ `getBaseAdapter()/m()` 的 `getItem(pos)` → `msgRefFromH9`。
  - 风险：① recycler 的 `itemRoot` 定位依赖 `n.parent === list`（:1081），v 层级异常会取错项；② `getItem(pos)` 的 pos 若因 View 被回收重绑而指向相邻项，h9 即相邻消息；③ F0/G0 全失败时 `positionOf` 返回 -1 ⇒ `msgRefOf` 返回 null ⇒ 落到 `cachedRef`。
- **`cachedRef()`（:1056-1066，源码确定）**：取 `cachedG0Ref`（g0 hook 缓存的任意 h9），只校验 `cachedG0At >= burstStartAt`，**不校验该 h9 是否就是本次长按那条**。g0 被 `hookAll` 挂在 `G0_HOST_CLASSES` 所有 `g0(r3,view,info,h9)` 上（:348-392），一次长按内多次 g0 / 相邻消息的 g0 都可能覆盖缓存 ⇒ 串味风险（属【假设待验证】，需真机确认 g0 调用次数与归属）。
- **门禁与快照同源（源码确定）**：`onShowGridMenu` 的 `hasRef`（:464-465）与 `snapshotOf` 的 `ref`（:940-941）都走 `msgRefOf ?: cachedRef`。这正是 R73 为防「按钮出来但点下去 SKIP」做的统一——**修法若拆散二者会重蹈覆辙**（见 §4）。

### 1.4 `extractText` 与噪声过滤（:1119-1144，源码确定）

- 递归遍历 v 整棵子树，`TextView` 文本 `length>=2 && !isNoiseText && 更长` 即替换 best。
- `isNoiseText`：时间戳 `\d{1,2}:\d{2}`、纯数字、菜单词、长度≤2 → 滤掉；**相邻消息的一整句话不在滤除范围**。
- 结论：只要传给 `snapshotOf` 的 v 的子树里含「后面那句话」的 TextView（外层容器 / 回收重绑 / itemRoot 取大），`viewText` 就会是那句话。

### 1.5 策略分发的「text 优先」放大器（源码确定）

- `planFor`（ForwardLogic.kt:332-356）：`isCovered(kind)` → 原生可用则 NATIVE（默认关）→ **`hasText` 先判返回 RESEND_TEXT** → `hasFile` 后判返回 RESEND_FILE → 否则 SKIP。
- `fallbackStrategy`（ForwardNativeLogic.kt:206-207）：原生失败回落时同样 `snap.text.isNotBlank()` 优先。
- 后果：对 image 消息，**只要 snap.text 非空（来自污染），RESEND_FILE 永远不会被选中**，那句话被 `TextSender.send` 原样发出。

### 1.6 探针（只读，源码确定）

- `op=forward sub=snap` → `snapReport()`（:1763-1770）：`FORWARD SNAP kind / msgId / svrId / textLen / path / exists / chat / raw / native`。走生产 `snapshotOf`，忽略 TTL。
- `op=forward sub=msgsnap` → `msgsnapReport()`（:1826-2072）：`FORWARD MSGSNAP … list / pos / posVia / posFail / adapter / h9 / msgId / type / isSend / imgPath / menu`。独立重走消息识别链（含 A0scan 兜底），可与 snap 交叉验证 ref 是否取错。
- 日志锚点：`FF forward clicked`（:914）、`FORWARD plan`（:1201）、`FORWARD done`（:1289）、`FF NATIVE gate closed`（:471）。

## 2 候选根因排序

> **真机回填后裁决（优先读此行）**：1) D+C **已排除**；2) A → 收敛为 **A1 并坐实**；3) B 归入 A1 表象；4) C2 默认关无关；5) E 中 E3（长按错对象）被 msgsnap 的 `msgId=19725729/type=1` 正面证据排除。最终根因 = **A1**。下文排序为「仅凭源码时」的历史可达性判断，保留供追溯；以 §0.2 / §3.5 为准。

> 排序依据：源码可达性 × 与「正好是图片后面那句」这一症状的吻合度 × 现有注释里的真机证据。所有「可能性」均为【源码确定】的**可达性判断**，具体哪条实际发生为【假设待验证】。

### 1) D+C：`text` 被相邻文本污染 + `planFor` text 优先 —— **最可能**
- 机制（源码确定）：`snapshotOf` 对 kind=IMAGE 也接受 `extractText(v)` 的非空结果写进 `snap.text`（:944-946）；`planFor` 把 `hasText` 判在 `hasFile` 前（ForwardLogic.kt:341-344）⇒ 同时有 text 与合法图时**必发 text**。
- 为何吻合「后面那句」：`extractText` 不认消息边界（:1119-1136），只要 v 的子树含相邻 TextView（长按取到外层容器 / itemRoot 取大 / 旧 View 被回收重绑到下一条），最长的那句就是「图片后面那句话」。
- 成立时指纹：`sub=snap → kind=IMAGE, path 非空, exists=true, raw=图片 h9 类, msgId/svrId 有效, textLen>0 且=相邻句长`；`sub=msgsnap → type=3, imgPath 非空`；`FORWARD plan → strategy=RESEND_TEXT, kind=IMAGE, textLen>0`。

### 2) A：ref 取到相邻下一条消息 —— **次可能，且与 1 可同源**
- 机制（源码确定）：`msgRefOf` 的 `positionOf`/`getItem(pos)` 取到相邻项（View 回收重绑、itemRoot 定位偏差），或 `cachedRef` 串味（g0 缓存了相邻消息的 h9）。取到的是文本消息 ⇒ `kind=TEXT`、`content=后面那句`、`imgPath=空`，`rawText` 走 `ref.content` 兜底（:944-946）。
- 为何吻合：相邻文本消息的 content 字面就是「后面那句」，`planFor` 判 TEXT+hasText ⇒ RESEND_TEXT。
- 子型（互斥细化）：
  - **A1 position/View 错位**（`sub=msgsnap` 的 `pos/type/imgPath` 本身指向下一条文本消息，且 `snap.kind=TEXT`）；
  - **A2 cachedRef 串味**（`msgRefOf` 失败、View 链本应取到图片，但 `cachedRef` 提供了文本 h9）：此时 `sub=msgsnap` 仍显示 `type=3/imgPath 非空`，而 `sub=snap` 的 `kind=TEXT/msgId` 与之矛盾——**这个矛盾就是 A2 的硬判据**。
- 成立时指纹：`sub=snap → kind=TEXT, path 空, exists=false, textLen=相邻句长, msgId/svrId=相邻文本消息`；`FORWARD plan → strategy=RESEND_TEXT, kind=TEXT`。

### 3) B：ref 正确但 kind 误判为 TEXT —— **低**
- 机制：图片 h9 的 `getType()` 反射值错（读到 1）或 `kindOf` 映射错（ForwardLogic.kt:74-90：只有 msgType=1 或 appmsg type=19 才返回 TEXT）。
- 为何低：只要 ref 真是图片 h9，msgType=3 ⇒ kind=IMAGE；要变 TEXT 必须 h9 本身就是文本消息——那已归入 A。故 B 基本是 A 的表象，**不单列真机分支**（除非真机证实 h9=图片而 msgType 读到 1，届时单独查反射取值）。

### 4) C2：原生/native 链路误用 text —— **低（默认关闭）**
- 机制：`nativeForward` 用 msgId/svrId 驱动（ForwardNativeLogic.kt:122-141），不带 snap.text 作正文；且 `nativeState()` 在 `FORWARD_NATIVE=false`（默认）时恒 `UNKNOWN`（:1341），进不了 NATIVE 分支。只有用户手动 `kvset chat.forward_native true` 才可能。
- 佐证：即便开了，回落 `fallbackStrategy` 仍受 text 优先放大（归 1）。故不单列主分支，仅在日志 `native=true` 时才纳入。

### 5) E：其它 —— **需真机排除**
- E1 `TextSender.send` 用错会话：发到目标 wxid 由 `picked.wxids` 决定，与「转发的是哪条消息」无关，仅解释「发给谁」不解释「发的是那句话」。
- E2 `stripSender` 削前缀（:950）：只削 `wxid_:` 前缀，不会把图片变文本。
- E3 用户其实长按的是相邻文本消息（UI 误触）：非代码缺陷，靠 `sub=snap` 的 `kind/msgId` 与用户所述消息比对排除。
- E4 图片文件发送失败而文本成功：若 `plan=RESEND_FILE` 但 FileSender 返回 false，结果是「什么都没发/失败 N」，不会变成「发出那句话」——与症状不符，可排除。

**排序结论（已被真机改写）**：原计划「先按 D+C 与 A 取证」——真机日志一次裁决：D+C 排除、A2 排除、**A1 坐实**。B 是 A1 的表象（ref 本身就是文本消息，非 kindOf 误判）；C2 因原生默认关无关；E 仅作排除项且 E3 已被正面证据否定。A1 之下 M1/M2/M3 的细分见 §3.5.1，按 §3.5.3 两时点对照裁决。

## 3 真机判据与决策树

### 3.1 需要采集的四组日志（全部只读探针，不需改代码）
1. 长按**出问题的那张图片**、菜单出来后（点转发前）执行 `op=forward sub=snap` → 取 `FORWARD SNAP` 行。
2. 紧接着 `op=forward sub=msgsnap` → 取 `FORWARD MSGSNAP` 行（同一长按，ageMs 会 >10000 属正常）。
3. 点「转发(模块)」→ 日志 `FF forward clicked`。
4. 选完目标后 → 日志 `FORWARD plan ...` 与 `FORWARD done ...`。
> 对照组：对一张**正常**图片重复 1-4，记录正常基线（预期 `kind=IMAGE, textLen=0, plan=RESEND_FILE`）。

### 3.2 字段→路径硬判据表

| 观察字段 | D+C（text 污染） | A1（pos/View 错位） | A2（cachedRef 串味） | 正常基线 |
|---|---|---|---|---|
| `SNAP kind` | **IMAGE** | **TEXT** | **TEXT** | IMAGE |
| `SNAP msgId/svrId` | 图片消息（有效） | 相邻文本消息 | 相邻文本消息（与 MSGSNAP 矛盾） | 图片消息 |
| `SNAP path` | **非空**（图片路径） | 空 | 空 | 非空 |
| `SNAP exists` | true | false | false | true |
| `SNAP textLen` | **>0**（=相邻句长） | >0（=相邻句长） | >0（=相邻句长） | 0 |
| `SNAP raw` | 图片 h9 类 | 文本 h9 类 | 文本 h9 类 | 图片 h9 类 |
| `MSGSNAP type` | 3 | **1** | **3**（与 SNAP 矛盾） | 3 |
| `MSGSNAP imgPath` | 非空 | none | 非空 | 非空 |
| `MSGSNAP pos/posFail` | 有效 | 有效但指向下一条 | 有效（指向图片） | 有效 |
| `FF clicked kind/textLen` | IMAGE / >0 | TEXT / >0 | TEXT / >0 | IMAGE / 0 |
| `plan strategy` | **RESEND_TEXT** | RESEND_TEXT | RESEND_TEXT | RESEND_FILE |
| `plan kind/textLen` | IMAGE / >0 | TEXT / >0 | TEXT / >0 | IMAGE / 0 |

### 3.3 最小可机读决策树（互斥分支，自上而下第一个命中即出口）

```
INPUT: snap{kind,msgId,svrId,textLen,path,exists,raw}, msgsnap{type,imgPath,pos,posFail}, clicked{kind,textLen}, plan{strategy,kind,textLen}

# L0 没发出去 → 不是本报障
IF plan.strategy == SKIP OR clicked.kind == UNKNOWN:
    -> L0 "门禁/SKIP 问题（R73 旧病），非「误发那句话」" ; STOP

# L1 kind 维度分水岭（互斥）
IF snap.kind == IMAGE:
    # 图片 ref 正确 —— 看 text 从哪来
    IF snap.textLen > 0 AND plan.strategy == RESEND_TEXT:
        IF msgsnap.type == 3 AND msgsnap.imgPath != none:
            -> L1a "D+C：ref 正确(kind=IMAGE/path 有效)但 text 被相邻 TextView 污染，planFor text 优先把那句话发了"
        ELSE:
            -> L1b "混合：snap 指向 IMAGE 但 msgsnap 指向别的(type!=3)，重查 msgsnap 链路，优先按 A 处理"
    ELSE IF snap.textLen == 0 AND plan.strategy == RESEND_FILE:
        -> L1c "正常基线：图片按文件重发；用户若仍收到文本，另查用户长按对象(E3)" ; STOP
    ELSE:
        -> L1d "kind=IMAGE 但 strategy 异常(非 TEXT/FILE)，查 planFor/native 日志"

ELSE IF snap.kind == TEXT:
    # ref 已是文本消息 —— 区分 pos 错位 vs cachedRef 串味（互斥）
    IF msgsnap.type == 1 AND msgsnap.imgPath == none:
        -> L2a "A1：View 链/position 取到了相邻文本消息(长按对象或回收重绑成文本消息)"
    ELSE IF msgsnap.type == 3 AND msgsnap.imgPath != none:
        -> L2b "A2：cachedRef 串味 —— msgsnap 证明 View 链那条是图片(type=3)，但 snap 的 ref/kind/msgId 来自文本 h9"
    ELSE:
        -> L2c "msgsnap 链路也失败(type=none)，先按 A 处理并补采 MSGSNAP"

ELSE: # kind 为 VIDEO/VOICE/FILE/LINK/... 
    -> L3 "与「图片误转发」不符，查是否长按错对象(E3)或 msgType 映射(B)"
```

### 3.4 说明：为何这样互斥
- **先按 `snap.kind` 二分**是唯一能把 D+C 与 A 分开的判据：D+C 的 ref 必须是图片（kind=IMAGE，path 有效），A 的 ref 必须是文本消息（kind=TEXT，path 空）。二者不可能同时成立。
- A 内部用 **`msgsnap.type/imgPath` 与 `snap.kind/msgId` 是否矛盾**区分 A1（两者都指向文本）与 A2（msgsnap 指向图片而 snap 指向文本，矛盾证明走了 `cachedRef` 串味），避免把二者写成并列。
- L0 作为前置出口，防止把「没发出去的 SKIP」误当成本报障。

### 3.5 用已给日志直接裁决（真机日志确定，无需新探针）

把 §3.1 四组日志代入 §3.3 决策树：
```
snap.kind = TEXT
snap.msgId = 19725729, path='', exists=false, textLen=2
msgsnap.type = 1, imgPath=none, pos=12, posVia=F0, posFail=none
DB: 19725728=图片(type=3), 19725729=文本(type=1)「妈的」
```
- 不命中 L0（kind=TEXT，plan 必为 RESEND_TEXT，菜单/点击均正常，非 SKIP）。
- 不命中 L1（`snap.kind != IMAGE`）⇒ **D+C 出局**。
- 命中 **L2**：`msgsnap.type==1 && imgPath==none` ⇒ **叶子 L2a = A1（View 链/position 取到了相邻文本消息）**。
- L2b（A2 纯缓存串味）要求 `msgsnap.type==3`，与实测 `type=1` 矛盾 ⇒ **A2 出局**。

**结论：按已给日志，根因唯一为 A1，不需等待任何新探针。** 上面这条裁决本身就是互斥的：D+C、A2 分别被 `snap.kind=TEXT/path空` 与 `msgsnap.type=1` 两个字段独立排除。

#### 3.5.1 A1 之下「为何 F0=12」的互斥子机制（假设待验证）
A1 已定案，但 F0=12 的成因仍未唯一。三个候选**互斥**，裁决只读、不改生产代码：

| 子机制 | 机制（源码） | 判别信号 |
|---|---|---|
| **M1 抓错 View** | 长按当下 `burstDeepest` 就是文本消息 19725729 的根 View（触摸落在文本行 / burst 内首个 performLongClick 来自文本行）。`BURST_GAP_MS=500ms` 很小，若无 500ms 内连续长按则排除「旧 burst 残留」这一支。 | `MSGSNAP.view=` 是文本行/文本气泡类而非图片类；**立即探针**（见下）仍显示 `type=1`。 |
| **M2 View 被回收重绑** | 抓的是图片 View，但从长按（02:43:37）到探针（02:43:49）的 ~12s 内被 RecyclerView 回收并重新绑定到 position 12。F0 返回的是**当前**位置 12，`getItem(12)` 即文本消息。这是 F0 语义的必然风险。 | **立即探针显示图片（type=3）、延迟探针显示文本（type=1）**；`attached=true` 但绑定已变。 |
| **M3 itemRoot 上溯选错** | `positionOf` 找 `n.parent === list` 时，选中的祖先是「跨多条消息的容器」而非单条 item 根，F0 落在该容器的 position（恰为下一条）。 | `MSGSNAP.view=` 与 `itemRoot` 不同层；`listCls=` 显示异常外层；F0 与可见行错位。 |

#### 3.5.2 字段：主会话已补齐（见 §5.1 完整行）
`msgsnapReport()` 的返回串（ForceForward.kt:2066-2071）已打印这些字段，主会话在 §5.1 给出了完整值，无需再补探针、不改代码：
```
view=FrameLayout  ageMs=12266  attached=true
list=recycler  listCls=com.tencent.mm.pluginsdk.ui.tools.ScrollControlRecyclerView
pos=12  posVia=F0  posFail=none  adapter=...i0  adapterVia=getBaseAdapter  h9=h9  type=1  imgPath=none
```
- `ageMs=12266 > BURST_TTL_MS=10000` 且 `attached=true` ⇒ 该 View 是陈旧但仍挂载的 View，符合「被回收重绑」信号（M2）。
- `view=FrameLayout` 为通用容器，不足以区分它原本是图片行还是文本行；若需对 M1/M3 做最终切除，用 §3.5.3 两时点对照。

#### 3.5.3 若首轮仍不能分，做一次「非侵入两时点对照」（只读探针，不改生产代码）
- 长按同一张图片、菜单打开后**立刻**跑 `sub=snap` + `sub=msgsnap`（`ageMs` 尽量 <1s），再按现有延迟 ~12s 再跑一次。
- 互斥判据：
  - 立即=`type=3/path非空`（图片）、延迟=`type=1/path空`（文本） ⇒ **M2 坐实**（陈旧 View 被回收重绑到相邻 position，F0 语义风险）。
  - 立即与延迟**都**=`type=1` ⇒ **M1**（长按当下就抓成文本行）；此时若 `view=` 为文本行类，进一步确认。
  - 立即=`type=3` 但 `pos` 与可见行错位、`view=` 非 item 根 ⇒ **M3**。
- 该对照只调用现成 `op=forward sub=snap|msgsnap` 两个只读探针，不触碰生产代码。

## 4 修法草案与红线

> 本节是**修法设计草案，不改任何代码**（本 lane 只读）。真机已定案 A1，故 §4.0 为**主修法**；§4.1 为同类风险加固（D+C 本次已被排除，但机制仍在，保留防回归）。

### 4.0 主修法：A1 —— 消除「陈旧 View + F0 位置解析」的时间差（草案）
根因本质是 **time-of-check/time-of-use 错位**：长按当下 View 属于图片，但 `onForwardClick` 在 ~12s 后用这个陈旧 View 调 `positionOf`，F0 返回的是「此刻该 ViewHolder 所在 position 12」，于是 `getItem(12)` 取到下一条文本。修法必须让「消息身份」在长按当刻冻结，而非点击时重算：

- **修点 1（核心）**：在 `installLongPressTrap` 认出 burst 首个 View 时，**当场**解析并冻结该消息身份（`msgId/msgSvrId/kind/imgPath/content`，即一个 `MsgRef`），存为 burst 的权威 ref。`snapshotOf` 与 `openTargetPicker`/`execute` 一律用这份冻结 ref，不再于点击时从陈旧 View 重跑 `positionOf/getItem`。这样 F0 无论怎么变都不会串到下一条。
- **修点 2（身份校验网）**：若仍需用 `positionOf` 兜底，须把 `getItem(pos).getMsgId()` 与冻结的 `msgId` 比对；不一致即判定 View 已被回收/重绑，**拒绝用该 ref**（安全降级），而不是照单全收。本次若加此校验，19725729≠冻结的 19725728 ⇒ 拦截，避免误发。
- **修点 3（改用身份而非位置）**：彻底方案是不以 `getChildAdapterPosition` 作消息身份，改由 g0 hook 缓存的 h9（自带 msgId）或按冻结 msgId 在 adapter 内查找来定位消息；View 位置只作辅助。
- **修点 4（不动 F0 的语义依赖）**：`F0/G0` 混淆名链、`itemRoot` 的 `n.parent===list` 判定、A0 扫描兜底都保留——它们是 8.0.48 真机已跑通的取 position 手段，问题不在「取不到」而在「取到的是陈旧 View 的当前位置」。

**必须同步的门禁/快照点**：冻结 ref 必须同时供 `onShowGridMenu` 的 `hasRef` 与 `snapshotOf` 使用（抽 `resolveRefForBurst(): MsgRef?`），否则门禁放行而点击时冻结 ref 为空 ⇒ 重蹈「按钮出来但点下去 SKIP」。时效校验 `cachedAt>=burstStartAt`、新 burst 清缓存保留。

> ⚠️ 仅把 `planFor` 的 text/file 优先级对调**不能**修好本报障：那只会把「误发那句话」变成「path 空 ⇒ SKIP/失败」（不再误发，但也没转发成图片）。真正要恢复的是**正确的消息身份**，不是发送策略顺序。

> **与主会话落地的对齐（见 §5.5）**：主会话采纳为本次主修的是 §4.2 的 A1 两条——① `msgRefOf(v)` 取到 ref 后复核 `ref.msgId` 与当前 `positionOf` 对应项是否一致，不一致则不采信错位链；② 对 `staleViewForProbe()` 的 View 做 attached/回收校验，失效则安全降级。这与本节修点 2（身份校验）一致；本节修点 1「长按当刻冻结 ref」是更彻底的可选加固，可在实施 lane 评估，二者不互斥。

### 4.1 针对 D+C 的修法草案（次要加固，本次已排除但保留防回归）
- **修点 1（核心，策略优先级）**：`ForwardLogic.planFor` 改为「有文件原料的非文本消息优先 `RESEND_FILE`」。具体：把 `hasFile && canResendFile(kind)` 的判定提到 `hasText` 之前；`hasText` 仅对「本就以文本为正文的 kind（TEXT，以及 LINK/CARD 取标题）」生效。使 `image + path有效` 永远选 `RESEND_FILE`，`snap.text` 不再能劫持图片发送。
- **修点 2（正文来源门禁）**：把「哪些 kind 允许用 `extractText` 的 viewText 当正文」抽成纯函数 `allowsViewText(kind)`（仅 TEXT 等允许）。`snapshotOf` 的 `rawText` 改为：仅 `allowsViewText(kind)` 时才取 `viewText`，否则对 IMAGE/VIDEO/VOICE/FILE 等直接置空（图片正文只能来自 `ref.content`，而图片 h9 的 content 通常非正文，故置空更安全）。这样即便 v 子树混入相邻文本也不会写进 `snap.text`。
- **修点 3（收敛 extractText 边界）**：`extractText` 的 root 从「整个 v」收敛到「单条消息 itemRoot」——复用 `positionOf` 里找 `n.parent === list` 的那个 itemRoot，禁止跨越消息边界取 TextView；对 kind=IMAGE 直接跳过 `extractText`。
- **修点 4（可观测）**：当 `kind=IMAGE && text 非空 && path 有效` 时打一条 WARN（`SNAP conflict kind=IMAGE textLen>0 path=... msgId=...`），让污染在真机日志里可见，而非静默误发。

### 4.2 针对 A（ref 取错相邻）的修法草案 —— A1 为本次定案采纳的主修
- **A1（View/position 错位）**（本次主修采纳）：点击快照不再信任「长按瞬间缓存的 View 在点击时仍代表同一条」。在 `snapshotOf` 里用 `msgRefOf(v)` 取到 ref 后，复核 `ref.msgId` 是否等于当前 `positionOf` 对应项；不一致则放弃 `viewText`、只用 ref。或对 `staleViewForProbe()` 返回的 View 做 attached/回收校验（`isAttachedToWindow`），失效则强制走 `cachedRef` 并重新按 g0 归属校验。
- **A2（cachedRef 串味）**：给 g0 缓存加身份校验——缓存时同时记录 `g0` 第 2 参 `view`，`cachedRef()` 仅在「缓存 view 与 `burstDeepest` 同属一个 itemRoot（或其 id 一致）」时才采纳，否则返回 null（安全降级 SKIP，比误转好）。时效校验 `cachedAt >= burstStartAt` 保留。

### 4.3 必须同时改的门禁/快照点（防「按钮出来但点下去 SKIP」重蹈）
- **同源纪律**：`onShowGridMenu` 的 `hasRef`（:464-465）与 `snapshotOf` 的 `ref`（:940-941）必须**一字不差地共用同一个 ref 解析函数**（建议抽 `resolveRef(v): MsgRef?`）。任何新增的一致性校验（A1/A2 的复核）必须同时作用于门禁与快照，否则出现「门禁放行（按钮出来）→ 快照因新校验变 UNKNOWN → planFor SKIP（点下去没发）」。
- **残留兜底**：若修点 2 把 image 的 text 置空，必须保证 `planFor` 因此走 `RESEND_FILE`（`canResendFile(IMAGE)=true` 已满足，ForwardLogic.kt:390-394）而非 SKIP；单测须锁住「image + path有效 + text空 ⇒ RESEND_FILE」。
- **时效/生命周期保留**：`cachedAt >= burstStartAt`、新 burst 清 `cachedG0Ref`（:322-324）两条不可删，否则退化成跨消息串味。
- **sent==0 文案纪律保留**（:1292-1305）：修法不得绕过 toast 的「未实际发送」后缀。

### 4.4 红线
1. 不得改 `ForwardLogic.canResendFile` 放宽 VIDEO/FILE/VOICE——放宽 = 对未取证反射赌微信不崩（源码注释已明确禁止）。
2. 不得靠默认打开 `FORWARD_NATIVE` 来规避——原生非本 bug 正解，且大幅提高风险。
3. 不得让 `extractText` 的文本成为 IMAGE/VIDEO 等文件型消息的发送正文（哪怕能发出去也是错的）。
4. 不得拆散门禁与快照的 ref 同源（见 4.3）。
5. 本 lane 及修法草案均不得改动 `app/src/main`；落地需另起实施 lane 并补真机验收。

### 4.5 单测锁点（JVM 纯逻辑层，落地时补）
- **A1 主修法回归锁**：模拟「长按冻结 ref=图片 msgId=19725728；随后 adapter 重排/回收使同一 View 的 F0 指向 position 12=文本 19725729」⇒ 快照必须仍取冻结的 19725728（kind=IMAGE、path=图片路径），**不得**随 F0 漂移到 19725729。含身份校验分支时，`getItem(pos).msgId != frozen.msgId` 必须走拒绝路径。
- **门禁/快照同源锁**：`resolveRefForBurst()` 为唯一入口，`onShowGridMenu` 与 `snapshotOf` 断言调用同一函数（防止「按钮出来但点下去 SKIP」复现）。
- `planFor`：IMAGE + 有效 path + text 非空 ⇒ **RESEND_FILE**（新增，当前会返回 RESEND_TEXT，正是回归锁）；TEXT + text ⇒ RESEND_TEXT；UNKNOWN ⇒ SKIP；IMAGE + path 空 ⇒ 不误发 text（应 SKIP 或明确失败）。
- `fallbackStrategy`（ForwardNativeLogic.kt:206）：IMAGE + text + path ⇒ RESEND_FILE（与 planFor 同序）。
- `allowsViewText(kind)`（新增纯函数）：IMAGE/VIDEO/VOICE/FILE ⇒ false；TEXT ⇒ true。
- `kindOf`/`appMsgTypeOf` 全表锁定（ForwardLogic.kt:74-105）：msgType=3 ⇒ IMAGE，确保 B 不发生。
- `canResendFile ↔ resendFile` 的 kind 映射一致性（计划放行 = 发送层能发）。
- `resolveRef` 门禁/快照共用同一实现（可通过导出该纯函数并断言两处调用同一路径来锁）。

## 5 真机回填与定案

> 本节由主会话真机证据回填，结论为【真机日志确定】。本 lane 只读消费证据文本，未自行操作 adb/真机、未装机、未跑 gradle、未 commit。定案遵循 §3.3/§3.5：自上而下第一个命中的叶子即唯一根因，不并列多个结论。

### 5.1 真机证据清单（原样）
1. 采集前置：报障后主会话 `adb logcat -c` 清屏，确认微信前台，请用户长按图片，菜单出现后**停住未点击**（故本次为长按快照，无 `FORWARD plan/done`）。
2. `02:43:49.327 FORWARD FORWARD SNAP kind=TEXT msgId=19725729 svrId=3148701842618129723 textLen=2 path='' exists=false chat= raw=com.tencent.mm.storage.h9 native=ForwardInfo(fromImgId=-1, msgId=19725729, snsLocalId=, uuid=null, svrId=3148701842618129723, talker=)`
3. `02:43:49.535 FORWARD FORWARD MSGSNAP talker= view=FrameLayout ageMs=12266 attached=true list=recycler listCls=com.tencent.mm.pluginsdk.ui.tools.ScrollControlRecyclerView pos=12 posVia=F0 posFail=none adapter=com.tencent.mm.ui.chatting.adapter.i0 adapterVia=getBaseAdapter h9=h9 msgId=19725729 type=1 isSend=0 imgPath=none menu=`
4. `02:43:37.513 FF NATIVE added id=1464270849 size=9 group=1464270848`；`02:43:37.554 FF CLICK bound view=TextView layers=3 text=转发（模块）`
5. 同 chat 只读查库：`msgId=19725728 type=3 imgPath=THUMBNAIL_DIRPATH://th_f315db38a5d17f6ec88a5c2e64eb2007 content=wxid_jv8035ypcigc11:\n<?xml...`（图片本体）；`msgId=19725729 type=1 content=wxid_jv8035ypcigc11:\n妈的`（strip 后正好 `textLen=2`）；`msgId=19725730 content=学老a`；`msgId=19725731 content=上来就卖公司是吧`。
6. 用户人工确认原话：「是，长按的是图片」。

### 5.2 §3.3 决策树逐叶子执行（唯一命中）
- **L0**（`plan.strategy==SKIP || clicked.kind==UNKNOWN`）：本次为长按快照采样、用户未点击，无 plan/done；症状「实际转发了那句话」由用户报障陈述 + 查库 content 与 `textLen=2` 吻合佐证，非 SKIP 类，不在此出口 STOP。
- **L1**（`snap.kind==IMAGE`?）：`snap.kind=TEXT` ⇒ 不满足，走 `ELSE IF snap.kind==TEXT`。**D+C 所在的整条 L1 分支出局。**
- **L2**（TEXT 分支，互斥）：
  - `IF msgsnap.type==1 AND msgsnap.imgPath==none`：`type=1` ✓、`imgPath=none` ✓ ⇒ **命中 L2a**。
  - `ELSE IF msgsnap.type==3 AND imgPath!=none`：`type=1` ⇒ 不满足，**L2b（A2）出局**。
  - `ELSE`：不满足，**L2c 出局**。

**唯一命中叶子：L2a → A1（View/position 取到了相邻下一条文本消息）。STOP。** 与 §3.5 独立裁决一致；不把 D+C 与 A1/A2 并列。

### 5.3 最终根因（唯一）
**A1：用户长按图片（19725728，type=3）后菜单已开但未点击；从菜单打开（02:43:37）到探针（02:43:49）间隔 `ageMs=12266`（> `BURST_TTL_MS=10s`），`staleViewForProbe()` 仍返回被回收/重绑的陈旧 View（`attached=true`、`view=FrameLayout`），`positionOf` 经 `F0` 解析为 adapter position 12，`getItem(12)` 命中紧邻的下一条文本消息 19725729（`type=1`、`imgPath=none`、content「妈的」）。** 于是 `snapshotOf` 的 ref 为文本 h9 ⇒ `kind=TEXT / msgId=19725729 / path='' / exists=false / text=strip("妈的")=2`；`planFor` 对这条文本消息返回 `RESEND_TEXT` ⇒ `TextSender` 原样发出「妈的」，即用户所见「转发图片实际发出图片后面那句话」。`posFail=none` 说明 F0 定位本身「成功」——成功定位到了错位后的相邻项，这正是 A1 而非门禁失败。

### 5.4 排除项及理由（逐条，【真机日志确定】）
- **D+C（text 污染）排除**：D+C 硬前提是 ref 正确（kind=IMAGE、path 非空、exists=true），实测 `SNAP kind=TEXT / path='' / exists=false`，ref 根本不是图片。`textLen=2` 来自 `ref.content`=「妈的」（`rawText` 的 `kind==TEXT → ref.content` 兜底分支），**不是 `extractText` 污染**；相邻 TextView 也没有写进快照。D+C 被真机证伪，不与 A1 并列。
- **A2（纯 cachedRef 串味）排除**：A2 硬判据是「msgsnap 指向图片（`type=3/imgPath!=none`），而 snap 因 `cachedRef` 指向文本，二者矛盾」。但 `msgsnapReport()` **不走缓存**、独立重走 `View→list→positionOf→getItem` 同一条链，它得到的仍是 `msgId=19725729 / type=1 / imgPath=none`——**与 snap 同号、同为文本**。这证明 View 链本身已指向相邻文本消息，snap 的 TEXT 来自该链而非 `cachedRef`；不存在矛盾 ⇒ 纯 `cachedRef` 串味不是必要条件，A2 排除。
- **E3（UI 误触，长按的是文本）排除**：用户人工确认原话「是，长按的是图片」；且查库图片为 19725728、文本「妈的」为 19725729，`msgsnap.pos=12` 错位命中的是后者——是代码链把图片错认成下一条文本，非用户误触。
- **B（kind 误判）排除**：查库 19725729 本就是 `type=1` 文本消息，`kindOf` 判 TEXT 是正确映射，不是图片 h9 读错 msgType。
- **C2（native 链路）排除**：`native=ForwardInfo(...)` 只是 snap 的信息字段；`FF NATIVE added` 是探针注册日志，非 `FORWARD plan` 走 NATIVE；根因在 ref 取错，与 native 无关。
- **E1/E4 排除**：E1 只解释「发给谁」不解释「发的是那句话」；E4（文件发送失败）不会变成「发出那句话」，与症状不符。

### 5.5 对 §4 修法草案的收敛
- **采纳为本次主修 —— §4.2 的 A1 修法**：核心是「点击快照不再信任长按瞬间缓存的 View 在点击时仍代表同一条」。落地取 §4.2 A1 两条：① 快照用 `msgRefOf(v)` 取到 ref 后，复核 `ref.msgId` 与当前 `positionOf` 对应项是否一致，不一致则不采信错位链；② 对 `staleViewForProbe()` 返回的 View 做 attached/回收校验（`isAttachedToWindow`），失效则安全降级（SKIP 或改用长按当时固化的 ref），避免把下一条文本当作转发对象。
- **随主修同步 —— §4.3 同源纪律**：门禁 `hasRef` 与快照 `ref` 必须共用同一 ref 解析（`resolveRef(v): MsgRef?`），A1 的一致性校验必须同时作用于两处，否则重蹈「按钮出来但点下去 SKIP」。
- **不再作为本次主修（降为防御性/辅助，保留记录）**：
  - §4.1 修点 1（`planFor` 的 `text` 优先改序）：治的是 D+C；本案 snap 已是 TEXT、无合法图片文件可被劫持，改序对本案无效。
  - §4.1 修点 2（`allowsViewText`）/ 修点 3（`extractText` 边界）：本案 text 来自 `ref.content` 而非 `extractText`，且对 TEXT kind 本就允许，改了不影响本案。
  - §4.1 修点 4（WARN 可观测）：保留为辅助增强，非本次主修。
  - §4.2 的 A2（cachedRef 身份校验）：本案已排除 A2，保留为防御，非本次主修。
- **红线与单测**：§4.4 红线全部保留；§4.5 单测本次收束为两条必加——① 「陈旧 View 被回收重绑到相邻 position ⇒ 快照 ref.msgId 不等于固化 msgId 时不采信」（A1 回归锁）；② 「门禁 ref 与快照 ref 为同一解析路径/同一 msgId」（同源锁）。其余单测保留为防御。

### 5.6 边界复核
本 lane 全程只读；未修改 `app/src/main`，未自行使用 adb（证据由主会话采集），未装机，未跑 gradle，未 commit。唯一产物为本文件。

[DONE]
