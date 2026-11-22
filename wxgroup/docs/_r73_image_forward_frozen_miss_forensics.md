# R73 长按图片冻结仍取到后续文本 —— 只读取证与根因重判

> 边界：只读。不改 `app/src/main`、不碰 adb、不装机、不跑 gradle、不 commit。
> 唯一产物：本文件。结论分级：【源码确定】/【真机日志确定】/【假设待验证】。
> 旧定案 `_r73_image_forward_misselect_forensics.md`（A1：`陈旧 View + F0 TOCTOU`）保留作历史，不改。

## 0 结论摘要

### 0.1 一句话裁决
**旧定案 A1 的「时点」被真机反证推翻：错误不发生在「长按后 12 秒点击时的 TOCTOU 漂移」，而发生在「长按回调当刻的第一次 `msgRefOf(v)` 解析」——冻结机制（`src=frozen`）本身生效了，却冻结到了图片在群 adapter 内的**紧邻下一条**文本 19733134（全局 msgId 上 19733132/33 属其它会话、不在本群 adapter 中；两次复现都是「选中图片的下一条」，稳定 +1 条，非随机、非时间相关）。**

### 0.2 由真机日志直接确定（【真机日志确定】）
1. `22:26:30.385 FF ref src=frozen msgId=19733134 kind=TEXT`：`src=frozen` 证明冻结生效，且冻结发生在用户长按当刻（约 28 秒前），不是点击时刻。冻结内容已是文本，与用户所述「长按的是图片（19733131 type=3）」矛盾。
2. `22:26:30.393 FORWARD SNAP kind=TEXT msgId=19733134 textLen=21 path='' exists=false ... raw=com.tencent.mm.storage.h9`：snap 与冻结同源；`textLen=21` 与查库 19733134「名字叫红果，实际是橙果」strip 后长度吻合 ⇒ 整条链认的就是这条文本。
3. `22:26:30.578 FORWARD MSGSNAP ... view=FrameLayout ageMs=28060 attached=true list=recycler pos=44 posVia=F0 posFail=none ... msgId=19733134 type=1 isSend=0 imgPath=none`：MSGSNAP **独立走 View→F0→getItem**，不走 `cachedRef`，仍得到与 snap 同号、同为 type=1 文本 ⇒ **排除纯 `cachedRef` 串味**（旧 A2），并证明「View 链本身」在当刻就指向 19733134。
4. 用户人工确认原话「长按的就是那张图片」⇒ 排除 E3（UI 误触）。
5. 【查库确定，主会话补充】图片 19733131 与错选文本 19733134 的 `talker` 均为 `49973196994@chatroom`——**同一个群，不是跨会话串味**。
6. 【查库确定，主会话补充】中间的 `19733132` talker=`wxid_ie6dheisg7xy22`（私聊）、`19733133` talker=`56139215663@chatroom`（另一个群）——它们是全局 msgId 序列里夹进来的**其它会话消息，不在本群 adapter 里**。⇒ 在群 `49973196994@chatroom` 的 adapter 中，**19733134 就是 19733131 的紧邻下一条**，用户体感「图片后面那句话」由此而来。
7. 【真机日志确定，两次复现模式一致】上次 R73：`19725728`（图）→`19725729`（紧邻下一条文本「妈的」）；本次：`19733131`（图）→`19733134`（群内紧邻下一条文本）。**两次都是「选中图片的下一条」，稳定偏移 1 条**，不是随机漂移、不是时间相关（本次 `src=frozen` 在长按当刻就错）。
8. 【真机日志确定，第二次文本对照——谨慎采纳，不可当硬证据】长按文本 19733134 得到 `FF ref src=frozen msgId=19733178 kind=TEXT`、`SNAP textLen=2`、`MSGSNAP view=MMNeat7extView ageMs=11650 pos=45 posVia=F0 msgId=19733178 type=1`。查库 19733178 是同群文本，但与 19733134 隔 44 个 msgId、跨数分钟和多个其它会话；19733134 在该 adapter 的 pos 未知。**这次很可能是用户实际按的就是 19733178，不能作为「文本消息也偏移」的证据**；唯一确凿反证仍是图片案例。

### 0.3 源码可确定的结构事实（【源码确定】）
- 冻结取的是 **burst 内第一个 `performLongClick` 回调的 v**：`installLongPressTrap`（:320-354）在 `beforeHookedMethod` 里先算 `freshRef = msgRefOf(v)`（:329，synchronized 外、每个回调都算），仅当 `now - burstStartAt > BURST_GAP_MS`（500ms）即 `newBurst` 时执行 `burstFrozenRef = freshRef`（:332-342）。注释 :313-319 假设「最深处的先跑，认第一个」，**该假设在源码里无任何校验**。
- `resolveRefForBurst`（:1110-1135）优先级 frozen > cached > view；门禁 `onShowGridMenu`（:458-481）与快照 `snapshotOf`（:964-995）共用该函数。冻结 ref 一旦错，门禁与快照一起错，且同源纪律无法自纠。
- `msgRefOf(v)`（:1011-1059）→ `positionOf`（:1142-1168，recycler 走 F0/G0 + `parent===list` 找 itemRoot）→ `getItem(pos)` → `msgRefFromH9`。`msgsnapReport`（:1895-2141）重走同一条链，只是打印更多字段，故**不是独立的位置源**。
- **首回调 v 的「最深先跑」前提按 cell 类型而变（【源码确定】+【真机日志确定】）**：图片案例 `view=FrameLayout`（容器），文本对照 `view=MMNeat7extView`（文本内容 View）——两次首回调 v 类型不同、pos 不同（44 vs 45）。说明图片 cell 上先触发的可能是 FrameLayout 容器而非最深处内容 View；注释「最深处的先跑」在图片 cell 上**未必成立**。这使 **H4（itemRoot 从该容器沿 parent 上溯找成下一条）与 H2（每回调都算 freshRef、只冻首回调且无交叉校验）的交互成为高优先级**（见 §2.2 H4）。

### 0.4 未定案与下一步（【假设待验证】）
- 仅凭源码**无法唯一判定**「为什么第一个回调的 v 会解析成紧邻下一条」。两次复现都稳定 +1 条（adapter 内紧邻下一条），**排除随机漂移/时间相关**，但把嫌疑集中到「确定性的位置 +1」这一族：**H3（F0 偏移/HeaderViewListAdapter 或时间分隔 header 让 child position 与 adapter position 错位）** 与 **H4（itemRoot 从 FrameLayout 容器上溯找成下一条）**，并都与 **H2（首回调冻结、无校验、后续正确 ref 被丢弃）** 交互。候选 H1~H6 见 §2。
- **纠正原任务书的「图片真实 pos 应为 41」假设**：19733132/33 属其它会话、不在本群 adapter，故图片真实 position 不是按全局 msgId 推算的 41；若 19733134 在 adapter 的 pos=44，图片应为 pos=43（紧邻），偏移是 **+1 而非 +3**。此纠正由 §1.2 第 6 条查库旁证支撑。
- MSGSNAP 的 `pos=44` 与图片真实位置的关系**不能由现有日志确认**——MSGSNAP 复用了同一个 v 和同一套 F0，必须先补一个独立位置源（A0scan / getItem 扫描 / adapter item 类型 dump）才能判断 F0 是否偏移，见 §3.2。
- 不脑补真机结论：所有 H1~H6 的实际命中需主会话按 §3 探针回填；本 lane 不改任何生产代码、不实施探针。

## 1 反证链

### 1.1 旧定案回顾（历史，勿改）
旧 `_r73_image_forward_misselect_forensics.md` 定案 **A1**：用户长按图片，菜单打开后约 12 秒才点「转发」，`staleViewForProbe()` 取到的陈旧 View 被 RecyclerView 回收重绑，`positionOf` 经 F0 解析成相邻文本的 position（旧例 pos=12），`getItem(12)` 命中图片后面那条文本。修法 = 「长按当刻冻结 MsgRef + 门禁/快照同源 + 非文本不采信 viewText」，已实现装机。

### 1.2 新证据逐条（原样，来自 team-lead 转达的真机采集）
1. 用户长按图片库中 `msgId=19733131 type=3 imgPath=THUMBNAIL_DIRPATH://th_1c3174888e89652f4ea185e4372d94f3`（发送者 SpiscesS，content 为 `<img>` XML），菜单弹出后**停住未点转发**，28 秒后抓探针——本次**没有点击时刻**，只有长按当刻的状态。
2. `22:26:30.385 FF ref src=frozen msgId=19733134 kind=TEXT`
3. `22:26:30.393 FORWARD FORWARD SNAP kind=TEXT msgId=19733134 svrId=5027512465710598063 textLen=21 path='' exists=false chat= raw=com.tencent.mm.storage.h9 ...`
4. `22:26:30.578 FORWARD FORWARD MSGSNAP talker= view=FrameLayout ageMs=28060 attached=true list=recycler listCls=com.tencent.mm.pluginsdk.ui.tools.ScrollControlRecyclerView pos=44 posVia=F0 posFail=none adapter=com.tencent.mm.ui.chatting.adapter.i0 adapterVia=getBaseAdapter h9=h9 msgId=19733134 type=1 isSend=0 imgPath=none menu=`
5. 同 chat 只读查库：`19733131 type=3 图片`；`19733132 "赵工，最近比较忙吗"`；`19733133 "我在雀庄等你"`；`19733134 "名字叫红果，实际是橙果"`（strip 后 textLen=21 正好吻合）；`19733135/36` 也是文本。
6. 用户人工确认原话：「长按的就是那张图片」。
7. 【查库确定，补充】图片 19733131 与错选文本 19733134 的 `talker` 均为 `49973196994@chatroom`——**同一群，不是跨会话串味**。
8. 【查库确定，补充】`19733132` talker=`wxid_ie6dheisg7xy22`（私聊）、`19733133` talker=`56139215663@chatroom`（另一个群）——是全局 msgId 序列里夹进来的其它会话消息，**不在本群 adapter 中**。⇒ 群 adapter 内 19733134 是 19733131 的紧邻下一条。
9. 【真机日志确定，补充】两次复现：上次的 19725728(图)→19725729(紧邻文本「妈的」)、本次 19733131(图)→19733134(群内紧邻文本)，**都是「图片的下一条」，稳定 +1 条**，非随机、非时间相关；本次 `src=frozen` 在长按当刻就错。
10. 【真机日志确定，补充，谨慎采纳】第二次文本对照（用户长按文本 19733134）：`FF ref src=frozen msgId=19733178 kind=TEXT`、`SNAP kind=TEXT msgId=19733178 textLen=2 path=''`、`MSGSNAP view=MMNeat7extView ageMs=11650 pos=45 posVia=F0 msgId=19733178 type=1`。查库 19733178 是同群文本、strip 后 textLen=2 吻合，但与 19733134 隔 44 个 msgId、跨数分钟与多个其它会话，19733134 的 adapter pos 未知。**很可能是用户实际按的就是 19733178，不能作为「文本也偏移」的硬证据**。
11. 【时序校正，主会话提示】`ageMs=28060` 是「抓探针时距菜单弹出」的时间，**不是距长按的时间**；`freshRef` 在 `performLongClick.before`（:329）里取，理论上更早。此校正强化「冻结当刻就错」，不涉及点击。

### 1.3 反证推理链（每一步都可追溯到上面某条证据 + 源码）
```
E2(src=frozen) + E6(用户按的是图片)
  ⇒ 冻结机制生效，但冻结对象错误。
  ⇒ 错误不在「点击时漂移」，而在「长按当刻的首次解析」。[推翻旧 A1 时点]

E3(snap msgId=19733134) + E2(frozen msgId=19733134)
  ⇒ snap 与 frozen 同源，符合 resolveRefForBurst 优先 frozen 的设计（源码 :1119-1123）。

E4(msgsnap 不走缓存、独立 View→F0→getItem，仍得 19733134/type=1)
  ⇒ View 链本身在当刻就指向文本消息 ⇒ 排除「纯 cachedRef 串味」(旧 A2/H 缓存论)。

E4(pos=44 posFail=none) + E5(19733134 是 type=1 文本，图片是 19733131)
  ⇒ F0 返回的 position 44 处 getItem 就是文本，且定位「成功」——不是定位失败降级，
     而是成功定位到了错位的目标。

E1(菜单弹出未点击) + E4(ageMs=28060，且 ageMs 是距菜单弹出、非距长按)
  ⇒ 没有点击重算；28 秒只证明探针延迟抓，不能证明冻结延迟发生。
     src=frozen 证明冻结在长按当刻。 ⇒ 旧 M2「长按与点击间被回收重绑」不构成必要条件。

E7/E8(同群 + 中间 19733132/33 属其它会话不在本群 adapter)
  ⇒ 排除「跨会话串味」；在本群 adapter 内 19733134 是 19733131 的紧邻下一条。
  ⇒ 纠正原假设「图片真实 pos=41」——该假设按全局 msgId 推算，忽略了 32/33 不在本群 adapter；
     若 19733134 在 pos=44，图片应约在 pos=43，真实偏移是 +1（下一条）而非 +3。

E9(两次复现都选中图片下一条，稳定 +1)
  ⇒ 排除随机漂移/时间相关；把嫌疑集中到「确定性的位置 +1」：F0 偏移(H3) 或 itemRoot 找成下一条(H4)，
     且都与 H2(首回调盲冻结、无校验) 交互。
```
**结论（【真机日志确定】）**：旧定案 A1 的「时点/TOCTOU」被反证。冻结机制修对了「点击时别重算」，但没修对「冻结来源本身是否是被按住的那条」。问题前移到 `installLongPressTrap` 的**首回调取源**。

### 1.4 本反证链不能直接得出的东西（【假设待验证】）
- 不能得出「第一个回调的 v 是文本行」——`view=FrameLayout` 是通用容器，源码与日志都不足以判定它的归属；两次首回调 v 类型不同（FrameLayout vs MMNeat7extView），只能说明「最深先跑」按 cell 类型而变。
- **不能得出「F0 固定偏移 +3」——原「图片真实 pos=41」是按全局 msgId 的错误推算；19733132/33 不在本群 adapter，真实偏移是 +1（紧邻下一条）**。但 +1 的具体成因（F0 返回值偏移 vs itemRoot 找成下一条 vs getItem 映射错位，以及 HeaderViewListAdapter / 时间分隔 header 是否让 child position 与 adapter position 错位）仍未定案。
- 不能得出 pos=44 是否等于图片真实位置——缺独立位置源（见 §3.2）。
- 不能把文本对照（19733134→19733178）当「文本也偏移」的硬证据——用户很可能实际按的就是 19733178。
- 这些正是 §2 的 H1~H6，需探针区分，不得脑补。

## 2 候选根因排序

> 排序依据 = 与「冻结当刻就错」这一新事实的吻合度 × 源码可达性 × 反证难度。
> 「可能性」是【源码确定】的可达性/结构判断；哪条实际命中为【假设待验证】，须按 §3 探针裁决。
> 现象统一：首回调 v 经 `msgRefOf → positionOf(F0) → getItem(44)` 得到 19733134/type=1。

### 2.1 排序总表（可能性高→低）

> 新旁证把现象从「任意相邻漂移」收敛为「**adapter 内紧邻下一条、稳定 +1**」，故排序重排：确定性「位置 +1」族（H4/H3）升到最高优先级；H2 是贯穿结构因；随机/时间相关族（H1/H6）降级。

| 排名 | 假设 | 一句话机制 | 定位 |
|---|---|---|---|
| 1 | **H2**（结构性根因） | `freshRef` 每回调都算，但只有首回调被冻结；首回调解析错则全盘错，后续回调即便解析对也被丢弃，且无交叉校验——**放大所有 H，尤其 H4** | 设计缺陷 |
| 2 | **H4**（itemRoot 找成下一条）【高优先级】 | 图片 cell 首回调 v 是 `FrameLayout` 容器（不是最深处内容 View）；从它沿 parent 上溯到 `n.parent === list` 时取到了**下一行的 cell 根**，F0 对该根返回下一条 pos，`getItem` 命中文本。与 H2 交互：首回调盲冻结使该错位无法被后续回调纠正 | 定位错误（高） |
| 3 | **H3**（F0 返回值 +1 / header 错位）【高优先级】 | F0 返回的 adapter position 比图片真实 position 大 1：`HeaderViewListAdapter` 或**时间分隔 header** 让 child position 与 adapter position 错位，`getItem(pos)` 取到下一条 | 位置错误（高） |
| 4 | **H5**（getItem 映射错位） | F0 正确（图片 pos），但 `getItem(pos)` 实际绑定的不是该 position 对应的消息（adapter 内部索引基与 ViewHolder position 不同） | 映射错误（中） |
| 5 | **H1**（首回调 v 根本不是图片 cell） | 「最深先跑」在图片 cell 不成立，首回调 v 属别的 cell（若恰好是下一条，会造成 +1 假象）——但 +1 的稳定性更支持位置族，H1 优先级下调 | 取源错误（中） |
| 6 | **H6**（当刻重绑） | 图片 View 在长按回调执行那一瞬已被回收/重绑为文本（比旧 M2 更早）——但 +1 稳定、非时间相关，与「当刻随机重绑」不符，降级 | 时序（低） |

> 说明：H2 不是与 H4/H3 并列的「另一个根因」，而是让首回调错误无法被自纠的**结构性缺陷**，与 H4 强交互。决策树（§3.3）把 H4/H3/H5/H1/H6 作为互斥的「v/position 成因」分支，H2 单独判定。

### 2.2 逐条源码证据与反证条件

#### H4 — itemRoot 从 FrameLayout 容器上溯找成下一条【高优先级，与 H2 强交互】
- **源码证据**：
  - `positionOf` :1145-1155：recycler 分支 `itemRoot` = 从 v 沿 parent 上溯到 `n.parent === list` 的那一层；F0 对该 itemRoot 取 pos。
  - **图片案例首回调 `view=FrameLayout`（容器），文本对照首回调 `view=MMNeat7extView`（内容 View）**——两次 v 类型不同、pos 不同（44 vs 45）。这说明「最深处的先跑」（:313-319 注释）在图片 cell 上**未必成立**：图片 cell 可能先触发 FrameLayout 容器。
  - 若该 FrameLayout 不是 cell 根（其 parent 不是 list），上溯会在 12 层内命中「某个 parent === list 的祖先」；若该祖先恰是**相邻下一条 cell 的根**，则 itemRoot 取成下一条，F0 返回下一条 pos，`getItem` 命中文本——方向与「稳定 +1」一致。
  - **与 H2 交互（重点）**：`:329` 每回调都算 `freshRef`，`:331-343` 只冻首回调且无交叉校验 ⇒ 即便后续回调解析到图片，也被丢弃，itemRoot 错位**无法被自纠**。首回调盲冻结 + itemRoot 找错 = 冻结当场就错。
- **反证条件**：探针打印首回调 v 的类/层级、`itemRoot` 的类/id/childCount，与 §3.2 A0scan 得到的「图片 cell itemView」做身份比对。若 `itemRoot === 图片 cell itemView` ⇒ H4 出局。

#### H3 — F0 返回值 +1 / HeaderViewListAdapter 或时间分隔 header 错位【高优先级】
- **源码证据**：
  - `positionOf` :1158-1162：F0 = 混淆后的 `RecyclerView.getChildAdapterPosition(itemRoot)`，返回**该 ViewHolder 当前 adapter position**；`getItem(pos)`（:1052-1055）按同一 pos 取消息。
  - 若 adapter 含 **HeaderViewListAdapter** 包裹或**时间分隔 header 行**，而 ViewHolder 的 child position 与 `getItem` 的索引基不一致，F0 返回值可比「图片真实 pos」大 1 ⇒ `getItem(pos)` 命中紧邻下一条。
  - **真机吻合**：两次复现都稳定 +1；原任务书「图片真实 pos=41」是按全局 msgId（31→41、32→42、33→43、34→44）推算的**错误假设**——19733132/33 属其它会话不在本群 adapter，故图片真实 pos 约为 43，偏移 **+1**。
- **削弱点**：若 header/分隔行存在，应同样影响文本对照；但文本对照不确定（用户可能实际按的就是 19733178），不能据此证伪，只能用探针确认。
- **反证条件**：§3.2 独立 A0scan 得到图片真实 P。若首回调 v 确属图片 cell、`itemRoot` 正确，但 `cb.pos == P+1` ⇒ F0 偏移坐实（H3）；若 `cb.pos == P` ⇒ H3 出局，转 H5（getItem 映射）。

#### H5 — getItem 映射错位【可能性：中】
- **源码证据**：`getItem(pos)` 直接按 adapter 下标取消息对象（:1052-1055）。若 adapter 内部索引基与 ViewHolder position 不同（例如返回的是「过滤掉 header/分隔行后的消息列表」），即使 F0 正确也可能取到下一条。
- **反证条件**：探针确认 `itemRoot` 正确、`cb.pos == P`，但 `getItem(cb.pos).msgId != 19733131` ⇒ H5 坐实；若 `getItem(P)==19733131` ⇒ H5 出局。

#### H2 — 首回调冻结、无校验、后续正确 ref 被丢弃【结构性根因，与 H4 强交互】
- **源码证据**：
  - `:329` `freshRef = msgRefOf(v)` 在 `synchronized` **外**、对**每一个** `performLongClick` 回调都计算。
  - `:331-343` 只有 `newBurst`（首回调）写 `burstFrozenRef = freshRef`；同一 burst 内后续回调的 `freshRef` 算完即丢，不与冻结值比对。
  - `:1119-1123` `resolveRefForBurst` 一旦认领 frozen 就直接返回，无「frozen 与其他回调是否一致」的校验。
  - ⇒ 若首回调 v/位置错、后续回调对，系统**手握正确答案却丢弃**，冻结到错的。这正是 H4 错位无法自纠的结构原因。
  - 注：`freshRef` 的 `v` 与应用冻结值的 `v` 是同一回调的 v（synchronized 内赋值的 `burstFrozenRef = freshRef` 与 `burstDeepest = WeakReference(v)` 成对），不存在两回调 ref 张冠李戴；问题在「首回调本身错」。
- **反证条件**：探针打印 burst 内每个回调的 `(seq, vClass, freshMsgId, newBurst)`——若**所有**回调都解析成 19733134（没有出现 19733131），则「后续正确 ref 被丢弃」不成立，H2 退化为伴生因素，主因在 H4/H3/H5 找「为何全部都错」。

#### H1 — 首回调 v 根本不是图片 cell 的子 View【可能性：中，降级】
- **源码证据**：
  - `:313-319` 注释假设「最深处的先跑，认第一个」，源码无校验（不检查 v 是否含 ImageView）。
  - `:351` `hookAll(View::class.java, "performLongClick", cb)` 同时 hook 无参/带坐标两个重载；若两个重载或父/子 View 触发顺序与注释相反，首回调就可能不是被按住的图片内容 View。
  - 真机图片案例首回调 `view=FrameLayout`——可能确属图片 cell 的容器（此时归 H4），也可能是非图片 cell；源码无法判定。
- **降级理由**：两次复现稳定 +1，更像「确定性位置偏移」而非「随机取到别的 cell」；若 H1 成立，首回调需**系统性地恰好是下一条 cell**，概率低于位置族。
- **反证条件**：探针 `vHasImg/vImgPath` 若命中图片路径 ⇒ 首回调 v 确属图片 cell，H1 出局（转 H4/H3/H5）。

#### H6 — 当刻重绑【可能性：低】
- **源码证据**：旧 M2 是「长按→点击 12s 间重绑」，本例无点击且冻结在当刻 ⇒ 旧 M2 不成立；「当刻重绑」（列表滚动惯性、数据刷新、`notifyDataSetChanged`）理论上仍可能，但**与「稳定 +1、非时间相关」的观测不符**。
- **反证条件**：两时点对照（§3.3 L6）——若回调内紧接着再读同一 v 的 ref 就从图片变文本 ⇒ H6 成立；若始终文本且 v 子树无图片内容 ⇒ 归 H1/H4，非 H6。

### 2.3 排除项（已有证据，不再列为候选）
- **跨会话串味**：图片 19733131 与错选文本 19733134 同为 `49973196994@chatroom`，19733132/33 属其它会话且不在本群 adapter ⇒ 排除「取到别的会话消息」。
- **纯 `cachedRef` 串味（旧 A2）**：MSGSNAP 不走缓存仍得 19733134 ⇒ 排除。
- **D+C（viewText 污染）**：`src=frozen`、`kind=TEXT`、`textLen=21` 来自 `ref.content`（:975-977 TEXT 分支），不是 `extractText`；且非文本 kind 已被门禁置空 ⇒ 排除。
- **E3（UI 误触）**：用户明确确认长按的是图片 ⇒ 排除。
- **旧 M2（长按→点击间重绑）**：本例无点击、冻结在当刻 ⇒ 不构成必要条件（但「当刻重绑」作为 H6 保留）。

## 3 最小取证探针与决策树

> 本节只给**探针设计 + 期望字段 + 决策树**，由主会话决定是否实施。本 lane 不改任何生产代码、不装机、不跑 gradle。

### 3.1 探针 A：`performLongClick.before` 逐回调可观测（定位 H1/H2/H4）
**挂载点**：`installLongPressTrap` 的 `beforeHookedMethod`（:323-349），在算出 `freshRef` 后、同步块前后各打一条；同一 burst 内每个回调都打（用 `seq` 区分）。
**期望字段**（每行一条 JSON-ish 可机读串）：
```
FF LCP cb seq=<n> newBurst=<0|1> vCls=<类全名> vId=<id>
   args=<0|2> x=<float> y=<float>
   freshMsgId=<long> freshType=<int> freshKind=<KIND> freshPath=<imgPath|none>
   pos=<int> posVia=<F0|G0|...> itemRootCls=<类> itemRootId=<id>
   vHasImg=<0|1> vImgPath=<图片 ImageView 的 field_imgPath|none>
   parentChain=<v→...→list 逐层 类名#id，带 * 的那层 parent===list>
FF LCP frozen seq=<n> msgId=<long> kind=<KIND>   # 仅 newBurst=1 那次，确认到底冻了谁
```
> **字段对账（r73-probe-impl 最终实现）**：上表与实现逐字段一致。`freshType` 由 `freshRef.raw.getType()` 反射得到（MsgRef 未存 msgType）。`vDepth`/`itemRootChildren` 未实现，决策树也不依赖；`parentChain` 由 team-lead 明确要求作为 **H4 辅助观察**保留——但**决策树 L3 不依赖它**，仍以 `itemRootCls/Id` 与 A0MAP 的 `ivCls/ivId` 身份比对裁决。
- `vHasImg/vImgPath`：递归看 v 子树是否有 `ImageView` 且其 `field_imgPath` 命中 `THUMBNAIL_DIRPATH://th_1c3174...`——这是判断「v 是否图片 cell」的硬标志。
- `x/y`：来自 2-arg `performLongClick(float,float)` 的 `param.args`；可结合 `getLocationOnScreen` 反查触点落在哪个 cell，独立验证 H1。
- `pos/posVia`：对该 cb 的 v 单独跑一次 `positionOf(list, v)` 并打印（生产 `msgRefOf` 内部也算，但不外露，探针需外露）。
- **只读**：只加日志，不改 `freshRef`/冻结逻辑、不改门禁/快照。

### 3.2 探针 B：独立位置源（回答「MSGSNAP pos=44 是否偏移」）
**关键认知（【源码确定】）**：`msgsnapReport()` 用的是 `staleViewForProbe()==burstDeepest`，即**与冻结同一个 v**，且重走**同一套 F0 算法**。它不是独立位置源——现有 MSGSNAP 的 `pos=44` 无法自证 F0 是否偏移。要确认偏移，必须补一个**不依赖 F0-on-该-v** 的位置源：

> **实现状态对账（r73-probe-impl 最终实现，全部就位）**：
> - `FF LCP A0 first=.. count=..`
> - `FF LCP A0MAP pos=.. msgId=.. type=.. path=.. vt=<itemViewType> ivCls=<类> ivId=<id>`（长按 pos±3、最多 8 行，失败打 `err=..`）。A0MAP 已给出 **P（图片 msgId=19733131 所在 position）**、getItem 映射、`ivCls/ivId`（**区分 H4/H3 的关键，已就位**）与每行 viewType。
> - `FF LCP A0HDR wrap=<0|1> headers=<n> pos=<p> vt=<t> vtM1=<t> vtP1=<t>`（HeaderViewListAdapter 包裹 + header 数 + 相邻 itemViewType）
> - `FF LCP A0HDRITEM pM1=<id:type:path> p=<...> pP1=<...>`（以 F0 pos 为基准的 getItem 三项核对）
> - 失败打 `FF LCP A0HDR err=..`。**所有判据所需字段现已齐备**。
1. **A0scan（身份比对）**：在可见区遍历 `i`，`vh = chatList.A0(i)`，`iv = XposedHelpers.getObjectField(vh,"d")`（itemView），与「已知是图片 cell 的 View」（由 §3.1 的 `vHasImg/vImgPath` 或触点坐标确认）做 `===` 比对，得到**图片真实 adapter position P**；A0MAP 的 `ivCls/ivId` 供与探针 A 的 `itemRootCls/itemRootId` 做身份比对（**区分 H4/H3 的关键**）。
2. **getItem 映射扫描**：A0MAP 给出 `getItem(i).getMsgId()/getType()/field_imgPath`，建立 `position → msgId` 映射；A0HDRITEM 专门打印 F0 pos 的 `p-1/p/p+1` 三项。**核对是否「图片 / 图片 / 紧邻文本」**，判定 +1 发生在 F0 还是 getItem。
3. **header/分隔行探测（针对 H3，已实现）**：`A0HDR` 给出 `wrap/headers` 及 `pos±1` 的 `vt/vtM1/vtP1`。若 `wrap=1` 或某行 viewType 是「时间分隔/header」而其 `getItem(i)` 返回消息对象，则 child position 与 adapter position 索引基不同 ⇒ **F0+1 的直接证据**；若 `wrap=0` 且 viewType 全同，header 机制基本出局。
4. **判据**：
   - 若 `P == 44` 且 `getItem(44)==19733131` ⇒ F0 没偏移，问题在别处（H5 否决，转 H1/H4：冻结的 v 根本不是图片 cell，才会在 pos=44 取到文本）。
   - 若 `P == 44` 但 `getItem(44)==19733134` ⇒ adapter 映射错（H5）。
   - 若 `P == 43` 且首回调 v 确属图片 cell、`itemRoot` 正确，而 `cb.pos == 44 == P+1` ⇒ **F0 偏移 +1 坐实（H3）**；若同时探测到 header/分隔行 ⇒ H3 机制锁定为 header/分隔行索引错位。
   - 若 `P != cb.pos` 但 `itemRoot != 图片 cell itemView` ⇒ **itemRoot 找成下一条（H4）**.
   - 若冻结 v **不**属图片 cell ⇒ 无论 P 为何，先归 H1/H4。

### 3.3 最小可机读决策树（自上而下，第一个命中即出口，互斥不并列）
```
INPUT:
  cb[]      = 探针A逐回调记录 {seq,newBurst,vCls,vHasImg,vImgPath,freshMsgId,pos,posVia,itemRootCls,itemRootId,x,y,parentChain(辅助)}
  A0        = 探针B {imageTruePos=P(A0MAP), getItemMap(A0MAP), ivCls/ivId(A0MAP), headerDetected(A0HDR wrap/headers/vt), item3(A0HDRITEM)}
  TRUTH     = 图片 msgId=19733131, imgPath=THUMBNAIL_DIRPATH://th_1c3174...

# L1 H2 结构因（独立标志，不占主因出口）
IF EXISTS cb WHERE cb.freshMsgId==19733131 AND cb.newBurst==0:
    -> H2 坐实(结构因): 后续回调拿到图片 ref, 但首回调已冻结且无校验, 正确 ref 被丢弃
       ; 继续 L2~L6 找首回调为何错(主因)

# L2 H1 前置门：首回调 v 是否根本不是图片 cell
IF cb[0].vHasImg == 0:
    -> H1 坐实: 首回调 v 子树无图片 ImageView(不是图片 cell), 冻结错源 ; STOP

# L3 H4：v 确属图片 cell, 但 itemRoot 不是图片 cell 的 itemView
#   判据：cb[0].itemRootCls/Id 与 A0MAP 中 P 行的 ivCls/ivId 比对（均已就位）；parentChain 仅辅助
IF cb[0].itemRootId/class != A0 中 P 行 itemView 的 id/class:
    -> H4 坐实: 从 FrameLayout 容器沿 parent===list 上溯找成了下一行 cell 根 ; STOP

# L4 H3：v/itemRoot 都对, 但 F0 pos 与独立来源不符
IF A0.imageTruePos(P) != cb[0].pos:
    IF cb[0].pos == P+1 AND A0.headerDetected:
        -> H3 坐实: header/时间分隔行让 child position 与 adapter position 错位, F0 返回 +1 ; STOP
    ELSE:
        -> H3 坐实(机制待细分; 若 A0HDR wrap=0 且 vt 全同则 header 机制出局): F0 把正确 itemRoot 解析成错 position ; STOP

# L5 H5：position 对, 但 getItem 绑定的不是该消息
IF A0.getItemMap[cb[0].pos].msgId != 19733131:
    -> H5 坐实: adapter position→消息映射错位 ; STOP

# L6 H6：当刻重绑(两时点)
IF 回调内连续两次读同一 v 的 ref: 第一次.msgId==19733131 且 第二次.msgId==19733134:
    -> H6 坐实: 长按回调执行当刻 View 已被回收/重绑 ; STOP

-> L7 "未覆盖机制: 保留全部 cb/A0 日志交主会话, 不脑补"
```
> 互斥性说明：L2→L3→L4→L5→L6 是嵌套排除——L2 先判「v 是否图片 cell」（H1 前置门），其后每层都以前一层「正确」为前提，命中即停，不会并列两个根因。H4/H3 是「位置族」高优先级机制，但因需先确认 v 属图片 cell（L2），故在树上位于 L2 之后。H2 作为结构因独立记录，不占主因出口。

### 3.4 验收标准（实施 lane 完成后）
- 能回答：**首回调 v 到底是不是图片 cell**（H1），**后续回调有没有正确 ref 却被丢弃**（H2），**F0 是否偏移**（H3），**itemRoot 是否找错**（H4），**getItem 映射是否错位**（H5），**是否当刻重绑**（H6）。
- 任一探针若导致宿主卡顿/崩溃即停——全部 runCatching、只打日志。
- 不采信「仅凭 `view=FrameLayout` 类名」判定 H1；必须靠 `vHasImg/vImgPath` 或触点坐标。

## 4 修法草案与红线

> 本节是**设计草案，不改任何代码**。针对最可能根因（H2 结构因 + H1 触发机制：首回调盲冻结、冻结来源本身错），目标 = 保证冻结到「真被按住的那条消息」。

### 4.0 主修法：从「盲信首回调」改为「识别被按住消息 + 校验后冻结」
1. **修点 1（核心，press-time 识别）**：`installLongPressTrap` 不再把首回调 `freshRef` 直接当权威。改用「真被按住消息」判据确定冻结对象：
   - 首选 **触点坐标法**：2-arg `performLongClick(float,float)` 的 `(x,y)` 在当刻用 `RecyclerView.findChildViewUnder(x,y)`（或等价遍历）定位触点所在 cell，取该 cell 的 itemRoot → `positionOf/getItem` → MsgRef。此法与「哪个回调先跑」完全无关，直接对准用户按住的位置。
   - 降级 **子树标志法**：若坐标不可用，在 burst 的所有回调里优先挑 `vHasImg=1 && imgPath` 有效（即 v 子树含图片 ImageView）的候选作为图片消息身份；文本消息则挑最深/最贴合触点的候选。
   - **不要**「等整个 burst 结束」才冻结——门禁 `onShowGridMenu` 在长按后很快触发，可能早于后续回调；必须在首回调当刻就用坐标/子树判据定住正确消息，而不是靠时间窗口收集。
2. **修点 2（冻结前身份校验）**：冻结前做一致性校验——冻结对象的 `kind/path` 必须与「被按住 cell」的渲染内容一致：
   - 被按住 cell 含图片 ImageView 且 imgPath 有效，却算出 `kind=TEXT/path=''` ⇒ **拒绝该 ref**（不采信、安全降级），并打 WARN（`FROZEN reject kind=TEXT but pressedCellHasImage msgId=...`）。本例 19733134 应在此步被拦。
   - 用 A0scan 复验：`getItem(pos).msgId` 必须等于待冻结 ref 的 msgId，否则拒绝（覆盖 H3/H5）。
3. **修点 3（后续回调复核，针对 H2）**：同一 burst 内后续回调的 `freshRef` 不再直接丢弃——若其 msgId 与冻结值不一致，打 WARN 并触发重评（以坐标/子树判据为准），避免「手握正确答案却丢弃」。
4. **修点 4（保留 F0 取位手段）**：`F0/G0` 链、`parent===list` 找 itemRoot、A0scan 兜底都保留；问题不在「取不到 position」，而在「取到了不属于被按住消息的 position」。
5. **对「+1 族」一网打尽**：无论 +1 来自 H3（F0/header 错位）、H4（itemRoot 找成下一条）还是 H5（getItem 映射），只要坚持「身份按 `msgId+imgPath` 认、冻结前用触点/子树判据核对被按住 cell、A0scan 复验 `getItem(pos).msgId == 冻结 msgId`」，+1 一律被拦。**因此不必先判定 H3/H4/H5 哪一个命中，即可设计出稳健修法**；探针的价值在于把具体机制钉死、避免修法漏项。

### 4.1 门禁/快照同源（红线，防重蹈「按钮出来点下去 SKIP」）
- `onShowGridMenu`（:458-481）的 `hasRef` 与 `snapshotOf`（:964-995）的 `ref` **必须共用 `resolveRefForBurst` 这一唯一入口**。
- **所有新增校验（修点 1/2/3）必须内聚在 `resolveRefForBurst` 内部**，使其拒绝时两处同时生效：门禁 `hasRef=false` ⇒ 不注入按钮；快照也拿不到该 ref ⇒ 不会出现「按钮已出来、点下去变 UNKNOWN→SKIP」。
  - ⚠️ 反面教材：若把「身份校验」只加在 `snapshotOf` 而不在门禁，则门禁放行（按钮出来）→ 快照被拒 → `planFor` 判 SKIP ⇒ 正是 R73 旧病复现。
- 时效/清理纪律保留：`cachedAt >= burstStartAt`（:1098）、新 burst 清 `cachedG0Ref`（:339-340）、`BURST_GAP_MS=500`、`BURST_TTL_MS` + `staleViewForProbe`（:2195）均不动。`resolveRefForBurst` 的 frozen > cached > view 优先级与认领条件（`burstFrozenAt==burstStartAt && startAt>0`，:1120）不动。

### 4.2 红线（不得越界）
1. 不改 `ForwardLogic.planFor` / `canResendFile` / `ForwardNativeLogic`，不默认打开 `FORWARD_NATIVE`——本 bug 是**消息身份**问题，不是发送策略问题；改序只会把「误发文本」变成「SKIP/失败」，没转对图片。
2. 不得让 `extractText` 的文本成为 IMAGE/VIDEO/VOICE/FILE 的发送正文（`allowsViewText` 门禁 :947-950 保留）。
3. 不得拆散门禁与快照的 ref 同源（见 4.1）。
4. 全程 `runCatching` 兜底，任何反射失败只降级/日志，绝不崩宿主。
5. 不硬编码「偏移 +N」——两次复现都是 adapter 内紧邻下一条（+1），但 +1 的成因（F0 偏移 vs itemRoot 找成下一条 vs getItem 映射）未定案；应修身份识别与一致性校验（按 msgId+imgPath 认消息，不按 position 数值猜），不写死偏移量。
6. 本 lane 不改 `app/src/main`、不实施探针；探针由主会话决策，落地另起实施 lane 并补真机验收。

### 4.3 单测锁点（JVM 纯逻辑层，落地时补）
| 锁点 | 断言 |
|---|---|
| **H2 回归锁** | 模拟 burst 内回调1 v=文本 19733134、回调2 v=图片 19733131 ⇒ 冻结必须是 19733131，不得是首回调文本。 |
| **触点识别锁** | 给定触点 (x,y) 落在图片 cell ⇒ 冻结图片 ref，即使首回调是文本 view（坐标判据优先于回调顺序）。 |
| **身份一致性锁** | `frozen.kind=TEXT/path=''` 但被按住 cell 含图片 imgPath ⇒ `resolveRefForBurst` 拒绝该 ref（返回 null/降级），不得发出文本；同时门禁 `hasRef=false`（同源锁）。 |
| **A0scan 校验锁** | `getItem(pos).msgId != frozen.msgId` ⇒ 拒绝冻结 ref（覆盖 H3/H5）。 |
| **门禁/快照同源锁** | `onShowGridMenu` 与 `snapshotOf` 断言调用同一 `resolveRefForBurst`；校验内聚其一处，拒绝时两处一致（防 SKIP 复现）。 |
| 既有保留 | `allowsViewText` 门禁表、IMAGE+有效 path ⇒ RESEND_FILE、`cachedAt>=burstStartAt`、新 burst 清缓存、`kindOf` 全表（msgType=3⇒IMAGE）。 |

### 4.4 已知风险与取舍
- **坐标法依赖** `findChildViewUnder` 或等价 API 在该 WeChat/RecyclerView 上可用；不可用时的降级路径（子树标志法）必须单测覆盖，否则又回到「靠回调顺序」。
- **校验失败一律安全降级（SKIP）** 而非「猜一个发出去」——误转比不发更糟；但须靠 4.1 同源纪律保证降级时门禁也不出按钮，避免用户看到按钮却没反应。

## 5 待真机回填（主会话决定实施）

### 5.1 无法仅靠源码定案的部分（【假设待验证】，不脑补）
| 未知 | 现状 | 需要什么才能定案 |
|---|---|---|
| 首回调 v 是不是图片 cell | 图片案例 `view=FrameLayout`、文本对照 `view=MMNeat7extView`，类型不同；「最深先跑」按 cell 类型而变 | §3.1 `vHasImg/vImgPath` 或触点坐标 |
| 后续回调有没有正确 ref 却被丢弃 | 源码「每回调都算、只冻首个」确定，但实际有没有出现 19733131 未知 | §3.1 逐回调 `freshMsgId/newBurst` |
| **itemRoot 是否从 FrameLayout 找成下一条** | 图片案例首回调是容器，上溯 `parent===list` 可能落到下一条 cell 根 | §3.1 `itemRootCls/Id` + §3.2 A0MAP 的 `ivCls/ivId`（已就位）身份比对；`parentChain` 辅助 |
| **图片真实 adapter position P 是否≈43（+1 确认）** | 原「pos=41」假设按全局 msgId 推算、忽略了 19733132/33 属其它会话；纠正后图片约 pos=43、偏移 +1 | §3.2 A0scan 得到 P，并核对 `getItem(P-1/P/P+1)` |
| **是否存在 header / 时间分隔行导致 child↔adapter position 错位** | 未知；这是 H3（F0+1）的具体机制 | §3.2 `A0HDR wrap/headers` + `vt/vtM1/vtP1`（已实现） |
| F0 是否偏移 | MSGSNAP 复用同一 v+同一 F0，`pos=44` 不能自证 | §3.2 独立 A0scan + `cb.pos vs P` |
| getItem 映射是否错位 | 未知 | §3.2 `getItem(P-3..P+3)` 映射 |
| 是否当刻重绑 | 旧 M2（12s 后）已被无点击场景排除，但当刻重绑未排除；与「稳定 +1」不符 | §3.3 L6 两时点对照 |
| 文本对照是否也可作为证据 | 19733134→19733178 隔 44 msgId、跨数分钟与其它会话，**很可能用户实际按的就是 19733178** | 不可作硬证据；仅图片案例确凿 |

### 5.2 回填表（探针已就位，待主会话真机抓取后填写）
```
【日志来源 tag】LCP cb / LCP frozen / LCP A0 / LCP A0MAP / LCP A0HDR / LCP A0HDRITEM
图片真实 adapter position P = ____  (A0MAP 里 msgId=19733131 那行的 pos; 预期≈43)
A0HDR wrap=___ headers=___ pos=___ vt=___ vtM1=___ vtP1=___
A0HDRITEM pM1=<id:type:path>___ p=___ pP1=___
H1 首回调 v 非图片 cell      : [ ]成立 [ ]排除   证据(cb vHasImg/vImgPath): ______
H2 正确 ref 被丢弃(结构因)   : [ ]成立 [ ]排除   证据(逐回调 freshMsgId/newBurst): ______
H4 itemRoot 找成下一条       : [ ]成立 [ ]排除   cb.itemRoot=______ vs A0MAP P行 ivCls/ivId=______
H3 F0 偏移(+1)               : [ ]成立 [ ]排除   cb.pos=____ P=____ wrap/header=____
H5 getItem 映射错位          : [ ]成立 [ ]排除   A0HDRITEM p/pM1/pP1=______
H6 当刻重绑                  : [ ]成立 [ ]排除   证据: ______
最终主因(唯一)               : __________________
文本对照(19733134→19733178) : [ ]确为偏移 [ ]用户实际按的就是 19733178(不可作硬证据)
```

### 5.3 给主会话的裁决建议
- 本 lane 的裁决止于：**冻结当刻取源错误，旧 A1 的 TOCTOU 时点被反证、纯 cachedRef 串味与跨会话串味均被排除**；现象收敛为「**群 adapter 内紧邻下一条、稳定 +1**」。
- 上线修法前建议先跑 §3.1+§3.2 探针拿到 H1~H6 的唯一命中（重点确认 `P≈43`、`cb.pos` 是否 `P+1`、有没有 header/分隔行、`itemRoot` 是否下一条 cell 根），再按 §4 对应修点落地，避免「按最可能假设改完仍未命中」。
- 若资源不允许探针：按 §4.0 修点 1（触点/子树识别）+ 修点 2（身份一致性校验）+ 修点 5（+1 族一网打尽）+ §4.1 同源纪律做**对 H1~H6 均稳健**的通用修法，并以 §4.3 单测锁住，但须在真机验收中确认首回调来源。
- **文本对照不可当硬证据**：19733134→19733178 很可能是用户实际按的就是 19733178；唯一确凿反证是图片案例（用户明确确认长按图片 19733131、`src=frozen` 拿到 19733134）。

### 5.4 边界复核
本 lane 全程只读：未修改 `app/src/main`，未使用 adb，未装机，未跑 gradle，未 commit，未实施任何探针。唯一产物为本文件。旧定案 `_r73_image_forward_misselect_forensics.md`、`_r73_image_freeze_review.md` 均未改动。

探针实现侧（r73-probe-impl）报告：六类 LCP tag 已落盘、单测源码断言全绿、本地文本仿真 61/61 通过、未跑 gradle——此为本 lane **转述其报告**，未独立验证；落地验收以 team-lead 真机抓取 LCP 日志为准。

## 6 第 3 次真机复现定案

> 输入：第 3 次真机复现日志（22:52 抓 cb/frozen，22:53 触发 snap/msgsnap 抓 A0/A0MAP；清日志后长按图片、菜单停住未点转发）。
> 本轮 TRUTH（查库 + 日志互证）：图片 **msgId=19733550 / type=3 / `THUMBNAIL_DIRPATH://th_216b1f7ccd59b1268349fd0eb3c46801`**；紧邻下一条文本 **msgId=19733551 / type=1**（`content=wxid_4krmxebor2gm22:\n今晚！有一个`，strip 后 textLen=6 吻合）；同群紧邻，群 adapter 内 **pos 26→27**。
> 全部结论按【源码确定】/【真机日志确定】/【假设待验证】分级，不脑补。

### 6.1 决策树逐层跑（§3.3，自上而下，第一个命中即出口）

> 决策树 L1 的 TRUTH 由 19733131 替换为本轮 19733550。团队口径「L0：所有回调都没解析出图片」在本树中对应 **L1（H2 结构因检查）**——本轮 L1 命中条件不成立，故 H2 不作命中根因（详见 6.2(1)）。

| 层 | 判据（§3.3，TRUTH=19733550） | 本轮日志取值 | 结果 |
|---|---|---|---|
| **L1（H2）** | `EXISTS cb: freshMsgId==19733550 且 newBurst==0` | seq=3(newBurst=1)=19733551；seq=4(newBurst=0)=19733551；**无任何回调出现 19733550** | **不命中** ⇒ H2（后续正确 ref 被丢弃）不成立 |
| **L2（H1 前置门）** | `cb[0].vHasImg == 0` | cb[0]=seq=3：`vHasImg=0 vImgPath=none` | **命中** ⇒ H1 坐实，**STOP** |
| L3（H4） | 未到达（L2 已 STOP） | cb.itemRootCls=`xi` / itemRootId=`2131299732`；A0MAP 的 P=26 行 ivCls=`xi`/ivId=`2131299732`（**与 cb 相同**） | 即便到达，也因 `ivCls/ivId` 各行全同而**无法命中** |
| L4（H3） | 未到达（L2 已 STOP） | P=26（A0MAP 图片行），cb.pos=27=**P+1**；本轮**无 A0HDR 日志** ⇒ `headerDetected` 未知 | 若跳过 L2：`P!=cb.pos` 命中、`pos==P+1` 成立，但 header 机制无从确认 ⇒ H3 坐实（机制待细分） |
| L5（H5） | 未到达 | `getItem(27).msgId=19733551 == frozen 19733551`（自洽） | 即便到达也不命中 |
| L6（H6） | 未到达 | 无「两时点读同一 v」对照日志 | 无从判定 |

**唯一命中叶子 = L2 → H1**（首回调 v 子树无图片 ImageView、冻结错源）。L3~L6 因 STOP 未达，逐层取值见表备查。
【真机日志确定】本轮确凿事实：冻结到的 ref 是 pos=27 文本 19733551（`frozen seq=3` + SNAP textLen=6 + MSGSNAP pos=27/type=1/imgPath=none 三源同源），图片在 pos=26（vt=76）；所有 cb 同号 19733551；`vHasImg=0`。
> 注：`vHasImg` 由 `probeImgPathInSubtree(v)`（ForceForward.kt:520-541）取 v 子树**任意非空 imgPath** 判定，**未硬编码本轮/上轮图片路径**，故 `vHasImg=0` 不是假阴性；但该方法限深≤6、限节点≤80，且 v 是 itemRoot 内第 3 层 FrameLayout，图片 ImageView 若在与 v 平行的兄弟分支仍可能漏扫——此点列入 6.2(2) 的未互斥边界。

### 6.2 五个关键分析点裁决

**(1) H2（后续正确 ref 被丢弃）是否成立 —— 不成立。**
【真机日志确定】本轮两个回调（seq=3 newBurst=1、seq=4 newBurst=0）的 `freshMsgId` **全是 19733551**，图片 19733550 从未在任何回调里出现过。H2 的命中前提是「后续回调拿到正确图片 ref 却被首回调挤掉」，本轮没有正确 ref 出现，**没有可被丢弃的对象**，故 H2 作为命中根因不成立——与旧 §2.1 把 H2 列为首位相反，本次证据要求把 H2 从「主因」降级。
【源码确定】需区分两层：`freshRef` 每回调都算、只冻首回调且无交叉校验（:329、:331-343、:1119-1123）这一**结构性缺陷仍在代码里**；但它本轮未被触发（无正确 ref 可供比较），故只是「潜伏结构因/防御短板」，不是本 bug 的命中根因。
⇒ 对 §5.2 回填表：H2 一行应勾「排除（本轮无正确 ref 出现）」，而非「成立」。

**(2) H4 与 H3 能否由现有日志互斥 —— 不能，属同一位置族。**
【真机日志确定】cb[0] 的 `itemRootCls/Id = xi/2131299732`；A0MAP 的 pos=26、27、28 三行 `ivCls/ivId` **全部是 `xi/2131299732`**（微信复用 layout id）⇒ **ivCls/ivId 无法区分行**，决策树 L3 的身份比对因此失效。
【真机日志确定】唯一能区分行的是 `vt`：pos=26（图片）=**76**，pos=27/28（文本）=**0**。但 `vt` 只挂在 A0MAP 的「A0(i) 行」上，**没有挂到 cb 的 v/itemRoot 上**——日志没告诉我们「cb 的 itemRoot 究竟属于 vt=76 的图片行还是 vt=0 的文本行」。
【假设待验证】据此：
- 若 cb 的 itemRoot 实为 **pos=27 文本 cell**（vt=0），则 v∈文本 cell、F0 返回 27 对该 cell 正确、freshMsgId=19733551 对该 cell 正确——整条链自洽，问题是「被冻结的 v 本就是下一条的 cell」（H1/H4 族）。
- 若 cb 的 itemRoot 实为 **pos=26 图片 cell**（vt=76），则 F0 把正确 itemRoot 解析成 27=P+1（H3，F0 偏移），`getItem(27)` 再取到文本。
两种情形都能解释 vHasImg=0 与 +1，现有字段无法排除任一。
⇒ **照口径明确写：位置族，H3/H4（含 H1）未能由现有日志互斥，但三次复现稳定 +1，均属同一族**，不硬判某一个。

**(3) 根因收敛：「取下一条 cell 的 v」(H1/H4) vs「F0 偏移」(H3) vs 无法区分 —— 无法区分。**
【真机日志确定】三次复现（19725728图→19725729文本、19733131图→19733134文本、本轮 19733550图→19733551文本）一致呈现「图片→群 adapter 内紧邻下一条、稳定 +1」，且本轮所有回调同号、`vHasImg=0` ⇒ 排除随机漂移/时间相关，把根因锁定在**确定性位置 +1 这一族**。
【真机日志确定】边界内可定：错误发生在**长按回调当刻的首次解析/冻结**（`frozen` 与 cb 同源、菜单未点、无点击时刻），旧 A1 的「点击时 TOCTOU」时点被反证；跨会话串味、纯 cachedRef 串味、viewText 污染均已排除（§1.3/§2.3）。
【假设待验证】边界外未定：+1 的具体成因（H1/H4 的「错 cell 的 v」 vs H3 的「正确 cell 上 F0+1」）无法由现有日志区分，三次稳定 +1 只说明二者同族、都需靠「身份校验」而非「数值偏移」来挡。
⇒ 分界：**【真机日志确定】=错在冻结当刻、冻结对象是下一条文本、稳定 +1、H2 未命中；【假设待验证】=+1 落在 H1/H4 还是 H3。**

**(4) 对 §4 修法草案的收敛取舍。**
- **主修（必须靠它挡住本 bug）= §4.0 修点1「press-time 触点坐标法」+ §4.0 修点2 第一个 bullet「冻结当刻身份校验」**：
  - 本轮「正确 ref 从未出现」⇒ **§4.0 修点3（后续回调复核）救不了**：burst 内所有回调一致认 19733551，复核比对不到任何分歧，触发不了重评。**修点3 降为防御（defense-in-depth）**：仅对「后续确实出现正确图片 ref」的原始 H2 场景有效，不得作为本 bug 的主修。
  - 唯一能锚定 ground truth 的是「用户究竟按住了哪条」——必须在长按当刻用**触点原始坐标 + `findChildViewUnder`** 独立定位被按住 cell（与哪个回调先跑、F0 落在哪个 v 完全无关），再据该 cell 的渲染内容（图片 cell 应含有效 imgPath / vt=76）做冻结前身份校验：被按住 cell 是图片却算出 `kind=TEXT/path=''` ⇒ **拒绝该 ref、安全降级**（本轮 19733551 应在这一步被拦）。
- **辅助但必要、非充分 = §4.0 修点2 第二个 bullet（A0scan `getItem(pos).msgId == 冻结 msgId`）**：本轮 `getItem(27).msgId=19733551 == frozen 19733551` **自洽、校验会通过**，说明该检查只能挡「position 与 ref 不一致」的 H3/H5 变体，**挡不住本轮「position 与 ref 一致地都错」的情形**；保留为覆盖 H3/H5 的防御，但不可单靠它。
- **保留 = §4.0 修点4（F0/G0/A0scan 取位手段不动）、修点5（+1 族按 msgId+imgPath 身份认，不写死偏移）、§4.1 门禁/快照同源红线、§4.2 全部红线、§4.3 单测锁点**；其中 §4.3「身份一致性锁」「A0scan 校验锁」「触点识别锁」需按上面主修/辅助分工重新理解权重。
- **必须修订 §4.3「H2 回归锁」措辞**：本轮 H2 未命中，该锁仍应保留为结构回归防护，但不能再当作本 bug 的针对性锁。
- **一句话收敛**：主修 = 冻结当刻「触点坐标定位被按住 cell + 身份一致性校验 + 不符即拒并降级」；修点3 与 A0scan 一致性检查降为防御；F0 取位与 H2 回归锁保留。

**(5) 触点 `x=220 y=330` 是相对哪个 View？能否独立验证触点落在图片 cell？—— 不能，别强用。**
【源码确定】`x/y` 来自 `probeLongClickCallback` 读 `performLongClick(float,float)` 的 `param.args[0]/[1]`（ForceForward.kt:392-393），是 Android 传给 `performLongClick` 的**相对 v（本轮 `FrameLayout#2131299633`）的 view-local 触点坐标**，不是屏幕/窗口原始坐标；seq=4 走无参重载故 x=y=-1。
【真机日志确定】本轮没有记录 v 的屏幕位置（`getLocationOnScreen`/`getGlobalVisibleRect`），也记录不到 A0 各 cell 的屏幕矩形 ⇒ 无法把 (220,330) 反算成全局点再判断落在哪个 cell。
【真机日志确定】且 v 的 parentChain 终止于 `xi#2131299732*`，A0MAP 中该 id 在 pos=26/27/28 全同，但 cb 自报 `pos=27`、freshMsgId=文本 ⇒ v 归属文本 cell 分支；这组 local 坐标本身就在「文本 cell 的子 View」里，对「触点是否落在图片 cell」不提供独立信息。
⇒ **不能用现有 x/y 独立验证触点落在图片 cell**；要验证需补「raw 触点坐标 + v/各 cell 屏幕矩形」或下条的身份字段（见 6.3）。不强用。

### 6.3 能唯一定案的最小真机判据 / 缺失字段

现有字段**不足以**唯一定案 H1/H4 vs H3，也**不足以**独立验证触点落点。差的是「cb 的 v/itemRoot 与各 A0 行的**对象身份/类型锚点**」。最小补充（二选一即可）：

1. **对象身份桥（首选）**：cb 行加 `irHash=System.identityHashCode(itemRoot)`；A0MAP 行加 `ivHash=System.identityHashCode(iv)`。因 `ivCls/ivId` 全同行相同，只有 identityHashCode 能把 cb 的 itemRoot 与某一 A0 行对齐：
   - `irHash == A0(pos=26).ivHash`（vt=76 图片行）⇒ v∈图片 cell、F0 返回 27=P+1 ⇒ **H3 坐实**；
   - `irHash == A0(pos=27).ivHash`（vt=0 文本行）⇒ v∈下一条文本 cell ⇒ **H1/H4 坐实（取到了下一条 cell 的 v）**。
2. **类型锚点（备选）**：cb 行直接打印其 itemRoot 所属 cell 的 `viewType`（从该 itemView 的 viewholder/tag 取，**不得用 cb.pos 反查**，否则循环论证）。等于 76⇒图片行，等于 0⇒文本行，判据同上。

附加（可选，仅用于钉 H3 机制）：本轮缺 A0HDR 日志（`wrap/headers/vt±1`），若要确认 +1 是否来自 header/分隔行索引错位，需补抓 `FF LCP A0HDR`。
【源码确定/假设待验证分界】这些字段是**实施探针后**才能拿到；本 lane 不改生产代码、不补探针。若主会话不再补探针：现有字段已**足够实施 6.2(4) 的主修**（冻结当刻触点定位 + 身份校验 + A0scan 一致性 + 安全降级对 H1/H4/H3 均稳健），但**不足以在事后再宣称命中了 H3 还是 H4**——二者只能留作【假设待验证】。

### 6.4 结论分级汇总

- 【源码确定】：冻结逻辑每回调算 `freshRef`、只冻首回调、无交叉校验（结构性 H2 潜伏）；`vHasImg` 为通用 imgPath 扫描、非硬编码；x/y 为 view-local 触点；A0MAP 的 vt 来自 `getItemViewType`、ivCls/ivId 为 itemView 身份。
- 【真机日志确定】：本轮冻结/快照/msgsnap 同源认 pos=27 文本 19733551；图片在 pos=26（vt=76）；所有 cb 同号、无图片 ref；`vHasImg=0`；三次稳定 +1；ivCls/ivId 全同行相同、vt 可分但只挂 A0 行；`getItem(27).msgId==frozen msgId`（自洽）。
- 【假设待验证】：+1 的落点（H1/H4 错 cell 的 v vs H3 正确 cell 上 F0 偏移）；header/分隔行是否参与；触点原始落点是否确在图片 cell；修点1 坐标法在本机型的实际有效性（须真机验收）。
- 最终主因（唯一可宣称）：**冻结当刻取源错——冻结到群 adapter 内图片的下一条文本（稳定 +1）；H2 未命中；+1 的具体机制（H1/H4 vs H3）现有日志无法互斥，属位置族**。

### 6.5 边界复核

本节全程只读：未改 `app/src/main`，未碰 adb，未装机，未跑 gradle，未 commit，未实施任何探针。唯一产物为本文件，改动仅限新增 §6 并保留末尾完成标记。旧定案与其它取证/实现文档均未改动。

[DONE]
