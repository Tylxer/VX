# r73 只读取证：wxgroup 转发正文混入 wxid 根因

> 视角：只读取证（不改码、不碰设备/gradle/adb）。规则：hy4flash（临时）。
> 宿主：微信 8.0.48/2588；模块：wxgroup（LSPosed）。
> 目标：查清「转发正文混入 wxid」根因，给最小修法方向。
>
> 取证原则：证据不足处明确标注「证据不足」，不编造 dex 地址/类名/日志行。

## 0. 真机铁证（主会话提供 + 本报告核实）

- 转发目标：好友「路过人间」（wxid_xlrbelf6hv0w22，无备注）—— dbg.txt:90 核实 nickname=路过人间、conRemark 空。
- 落库消息：msgId=19725726, type=1, isSend=1, talker=wxid_xlrbelf6hv0w22。
- **关键纠正（先于一切）**：content **不是**只有 `wxid_jv8035ypcigc11:` 21 字符，而是**两行**：
  - dbg.txt:171-172：`content=wxid_jv8035ypcigc11:` ⏎ `人民币暴跌明天大a肯定红`
  - dbg.txt:184-185：`content=wxid_jv8035ypcigc11:` ⏎ `人民币暴跌明天大a肯定红 | transContent=- | talker=...`
  - dbg.txt:188 直接给出 `clen=33`；:190-191 / :193-194 的 `c1=` / `c2=` 全文同此。
- **主会话 hex 终证（dbq.py 按行截断的误解彻底排除）**：
  `hex=777869645F6A763830333579706369676331313A0AE4BABAE6B091E5B881`
  → `replace(content,char(10),'<LF>') = wxid_jv8035ypcigc11:<LF>人民币暴跌明天大a肯定红`
  即 `发送者 wxid:` + 换行 + **完整正文**（用户已确认正文没丢）。dbq.py 单行显示只到 `wxid_jv8035ypcigc11:` 是因 `char(10)` 换行截断，非正文丢失。
- `wxid_jv8035ypcigc11` 经 rcontact 查得 nickname=💎FirETrAin💎（dbg.txt:182），是**另一好友**（源群消息的发送人），非目标「路过人间」。
- 时间线（dbg.txt）：
  - :11 `FF forward clicked kind=TEXT textLen=33 stale=true chat=`
  - :17 `FORWARD plan strategy=RESEND_TEXT ... kind=TEXT textLen=33`
  - :18 `TextSender sent wxid=wxid_xlrbelf6hv0w22 len=33`
  - :188 落库 `clen=33`
- **textLen=33 vs content=21 的疑点不成立**：33 = 20(`wxid_jv8035ypcigc11:`) + 1(`\n`) + 12(`人民币暴跌明天大a肯定红`)，逐字核对见 §3。抽取时、发送时、落库时**三处长度全为 33，完全一致**；主会话 prompt 只是在换行处截断了显示。真正 bug 是转发正文**多带了 `wxid_jv8035ypcigc11:\n` 前缀**。

---

## 1. extractText 对群聊消息 View 树抽取与 wxid 标签证据

**结论：核心假设「extractText 把群聊气泡里的『发送人 wxid:』标签 TextView 当正文抽走」证据不足，且被代码内嵌真机证据推翻。真实情况是 extractText 在群聊文本气泡上返回空（blank），snap.text 来自 ref.content。**

### 1.1 项目内有无群聊消息 View 树真机 dump
- 证据不足：grep 全部 `_cache/*.xml`、`_u*.xml`、`*.tsv`、`*.txt`、根 `_*`：**没有任何 `wxid_xxx:` 标签存在于 UI dump**（`grep -rnE "wxid_[A-Za-z0-9_]+:" --include="*.xml"` 零命中）。
- 被引用的取证文档**均不在 docs/**（docs/ 仅本报告一个文件）：`取证结论.md`、`_r16_g2_msgsnap.md`、`_r29_g2_msg_object.md`、`_r73_native_menu_inject.md`、`_r21c_hint_content.md`、`_r29_g1_forward_forensics.md` 全部 MISSING。故 dex 级锚点（如 NeatTextView 的类继承）本报告只能引用**代码内嵌的既往真机取证记录**，不自行编造 dex 地址。

### 1.2 直接反证：气泡不是 TextView，extractText 抽不到它
`HintBadge.kt:364-369`（真机根因修正注释，可信度：代码内嵌取证记录）：
> ① 气泡是 `NeatTextView extends View`，`v is TextView` 恒 false；
> ② 微信群消息的 `content` 自带 `wxid_xxx:\n` 前缀（真机现场 `orig=[wxid_0mnuc8zqglzt21:\n现在节目效果…]`），而气泡上显示的正文**不含前缀**。

`extractText`（ForceForward.kt:1114-1131）判定条件是 `if (v is TextView)`（:1119）。气泡正文是 NeatTextView（非 TextView），**永远进不了 best**。`isNoiseText`（:1133-1139）只排时间戳/纯数字/菜单词/长度<=2——即便有昵称 TextView，也远短于 33；不会产出 33 字符的 wxid 前缀串。

### 1.3 逻辑反推：viewText 必为空
- 若 viewText = 干净气泡正文（12 字）→ snap.text 应为 12 字，textLen=12。实测 33。
- 若 viewText = wxid 标签（20 字）→ snap.text 应为 20 字，textLen=20。实测 33。
- 若 viewText = 33 字带前缀全文 → 要求气泡 TextView 含前缀，与 HintBadge.kt:367「气泡正文不含前缀」矛盾。
- **唯一自洽解释：viewText 为空**（NeatTextView 非 TextView + 其余 TextView 均为噪声/过短），触发 `viewText.ifBlank { ref.content }` 降级到 h9.getContent（带前缀的 33 字原文）。
- 诚实标注：**没有日志直接打印 viewText 的值**（`snapReport` 只记 textLen、`msgsnapReport` 不记文本，见 §3），此步为「代码 + 长度 + 前缀归属」三重交叉的高置信度推断，非直接日志命中。

---

## 2. wxid: 格式来源：群聊发送人标签 vs 转发前缀

**结论：`wxid_jv8035ypcigc11:` 是微信群聊消息 `content` 字段自带的「发送者 wxid 前缀」，格式 `wxid_xxxxxxxx:\n正文`。既不是 View 上的标签 TextView，也不是转发消息的「原发送者:」前缀。证据充分（项目源码 + 真机 DB 双向印证）。**

### 2.1 项目内既有取证定论
`HintTextLogic.kt:149-170`（面向 R30 用户报障「昵称不要 wxid」的根因修正）：
> 微信在**群聊**里给每条消息的 `content` 都加了发送者 wxid 前缀（`wxid_xxxxxxxx:\n正文`），**私聊没有**。
> `SENDER_PREFIX_RE = Regex("""^\s*wxid_[0-9A-Za-z_\-]+:\s*""")`
> `stripSender(content)` = `content.replaceFirst(SENDER_PREFIX_RE, "")`，只剥开头一处。

`MuteAtLogic.kt:40` 同类记录：「`message.content` 存的是 `wxid_xxx:\n@所有人`」。
`HintBadge.kt:366-367` 真机现场：`orig=[wxid_0mnuc8zqglzt21:\n现在节目效果…]`。

### 2.2 本案 DB 证据闭合
- 落库 `content` = `wxid_jv8035ypcigc11:\n人民币暴跌明天大a肯定红`
- `wxid_jv8035ypcigc11` 的 rcontact nickname=💎FirETrAin💎（dbg.txt:182）——即**源群消息的发送人**，与既定格式（前缀=发送者 wxid）完全吻合。
- 目标「路过人间」wxid_xlrbelf6hv0w22 出现在 talker 列（接收方），不在 content 前缀——排除「目标 wxid 混入」。
- 无「原发送者:」「转发自」等中文字样 → 排除转发引用前缀假设。

---

## 3. TextSender.send 实现与 textLen=33 vs content 差异

**结论：TextSender 原样发送，无 split/切分。textLen=33、len=33、落库 clen=33 三处一致，不存在差异；所谓「21 字符」是 prompt 在换行处截断显示所致。**

### 3.1 实现（TextSender.kt:57-84）
```
fun send(wxid, content):
  校验 blank → r1=findClass("hl0.r1"); q1=callStaticMethod(r1,"b",wxid)
  callMethod(q1,"e",content); callMethod(q1,"b")
  LogX.i("TextSender sent ... len=${content.length}")   // :79
```
- 全程 `content` 原样传给 `q1.e(content)`，**无任何 split/substring**；日志 `len` 就是入参 `content.length`（:79）。
- resendText（ForceForward.kt:1608-1610）：`TextSender.send(target, snap.text)` —— 原样传 snap.text。

### 3.2 逐字核对（python 实测）
```
s = 'wxid_jv8035ypcigc11:\n人民币暴跌明天大a肯定红'
len(s) = 33 ; prefix=20 ('wxid_jv8035ypcigc11:') ; \n=1 ; body=12 ('人民币暴跌明天大a肯定红')
20+1+12 = 33  ✓  == textLen(33) == TextSender len(33) == 落库 clen(33)
```
- 链路日志三处等长：dbg.txt:11/17 `textLen=33`（抽取/计划）、:18 `len=33`（发送）、:188 `clen=33`（落库）。**无差异可解释，bug 不在长度而在内容正文被前缀污染。**

### 3.3 结论对假设的影响
「抽取时与落库时有差异」「TextSender 内部切分」两条猜想**均被推翻**：差异不存在，全文从头到尾就是 33 字符的带前缀原文。

---

## 4. snap.text 优先 viewText 而非 ref.content 的根因

**结论：恰恰相反——本案 snap.text 用的就是 ref.content（h9.getContent），因为 viewText 为空。「优先用 View 抽文本而弃用 h9 content」不是本案根因；把优先顺序改成 ref.content 优先也修不好（见 §5）。根因是：h9.getContent 返回的群聊原文自带 `wxid_xxx:\n` 前缀，被原样转发。**

### 4.1 代码链（ForceForward.kt）
- `snapshotOf`（:935-945）：
  - `viewText = v?.let{ extractText(it) }.orEmpty()`（:936）—— 群聊气泡 NeatTextView ⇒ 空。
  - `ref = msgRefOf(v) ?: cachedRef()`（:939-940）—— 本案走 g0 入口缓存（dbg.txt:6-7：`FF hook s0.g0 failed` 但 `g0-ref mounted classes=134 hooked=133`），两者都经 `msgRefFromH9` 取同一口径字段。
  - `text = viewText.ifBlank { if(kind==TEXT) ref?.content.orEmpty() else "" }`（:943-945）—— viewText 空 ⇒ **取 ref.content**。
- `msgRefFromH9`（:1026-1041）：`content = h9.getContent()`（:1029），`kind = kindOf(msgType,...)`（:1040）。本案 msgType=文本 ⇒ kind=TEXT。
- `resendText`（:1608）→ `TextSender.send` 原样发。

### 4.2 根因链（一句话）
> 群聊消息的 `h9.getContent()` 原文自带 `wxid_发送者:\n` 前缀（§2 证据）；`extractText` 因气泡非 TextView 返回空（§1），`snapshotOf` 遂用 `ref.content` 作 snap.text（:943-945），`resendText` 原样发出（:1608）⇒ 转发正文含 wxid 前缀。

### 4.3 为什么既有功能没暴露
- 角标/提示链路早遇到同款问题（R30），已在 `HintTextLogic.stripSender()` / `HintBadge` 里剥前缀并改用 `h9.getContent()` 对账——**它们是“取 h9 原文后剥前缀”**，而转发链是“取 h9 原文后直接发”，独缺剥离一步。
- 私聊消息 `content` 无此前缀（§2.1），故私聊转发一直正常，不易发现。

---

## 5. 最小修法方向与边界评估（只描述，不改码）

**推荐：在文本进入发送前剥掉群聊发送者前缀，复用项目已有的 `HintTextLogic.stripSender()` / 正则 `^\s*wxid_[0-9A-Za-z_\-]+:\s*`。**

### 5.1 三个候选方案评估
| 方案 | 对本案有效性 | 边界/风险 | 结论 |
|---|---|---|---|
| A. `extractText` 的 `isNoiseText` 加 `^wxid_\w+:$` 过滤 | **无效**：本案 snap.text 来自 ref.content，viewText 本就为空，过滤 extractText 不触及污染源 | 对私聊文本无影响，但白改 | 排除 |
| B. 改 `snap.text` 优先级为「ref.content 优先，仅 ref 为 null 才用 viewText」 | **无效/有害**：ref.content 正是带前缀的原文；当前因 viewText 空其实已经在用 ref.content，改优先级结果不变 | 还会丢掉「h9 取不到时用 View 文本」的兜底（图片/系统消息 viewText 是唯一文本来源时反而更糟：ref.content 对 type≠1 是 XML/空串，见下） | 排除 |
| C. 对群聊/文本消息在取 `h9.content` 后剥前缀（= 对最终 snap.text 应用 `stripSender`） | **有效**：33 字带前缀 → 12 字干净正文，实测正则可剥 | 需保证只匹配 wxid 字面量+冒号（现成正则已收紧），不误剥用户正文里的 `abc:`；私聊不命中 | **推荐** |

### 5.2 推荐落点（二选一，按 blast radius）
- **最小改动**：在 `resendText`（:1608）发前一行 `TextSender.send(target, HintTextLogic.stripSender(snap.text))`。仅影响降级重发文本这一条路径，私聊/图片完全不碰。
- **更一致**：在 `snapshotOf`（:943-945）对最终 `text` 套 `stripSender`，使 snap.text 全局干净（注意 `planFor` 的 `hasText` 判据仍在剥离前/后均可，12 字非空不影响 RESEND_TEXT 决策）。
- 勘误：原生转发**同样**把 `snap.text` 喂给 `q1.e(content)`（ForceForward.kt:1435），只是多挂 ForwardInfo + `q1.h=4`；并非「只用 msgId/svrId、不依赖 text」。故在 snapshotOf 剥离会让原生/降级两条路都受益；仅改 resendText 则只修降级路径。
- 不建议动 `isNoiseText`/`extractText`（方案 A）或改优先级（方案 B）。

### 5.3 边界核对：ref.content（getContent）对各类消息是什么
依据 `ForwardLogic.kindOf`（:74-90）+ 微信惯例，**只strip 文本**：
- **私聊文本**：content 无 wxid 前缀 → `stripSender` 不命中，原样，安全。
- **群聊文本**：命中前缀 → 剥掉，修复本案。
- **正文以 `abc:` 开头**（用户真发）：正则要求字面量 `wxid_` + 合法字符集 + `:`，不误剥（HintTextLogic:161-162 已论证）。
- **图片/视频/语音/文件/名片/位置**：kind≠TEXT ⇒ `snapshotOf` 的 text 分支给 `""`（:943-945），走 RESEND_FILE/SKIP，不经 resendText，`stripSender` 不参与；ref.content 对它们是 XML/索引串，**不会因本修法被改动**。
- **appmsg type=49 聊天记录 → Kind.TEXT**（:86）：ref.content 为 XML，不以 wxid 开头 → 不命中，行为不变（其“当文本发”是既有独立问题，与本 bug 无关）。
- **emoji/表情（APP_TYPE_EMOJI→Kind.IMAGE）**：非 TEXT，同上不参与。

### 5.4 验证建议（供主会话，本次未执行）
1. 真机重复群聊消息强制转发到私聊，落库 `content` 应为 `人民币暴跌明天大a肯定红`（clen=12），无 wxid 前缀。
2. 私聊文本转发回归：content 不变。
3. 图片转发回归：仍走 RESEND_FILE，不受影响。

### 5.5 textLen 33 → 12 的可测性
- 本案 strip 后应为 `人民币暴跌明天大a肯定红`，length=12；python 实测 `stripSender` 结果正是该 12 字串（§3.2）。
- 单测可直接断言：`assertEquals(12, stripped.length)` 且 `!stripped.contains("wxid")`。现成参考：`HintTextLogicTest.kt:224-249`（`badgeStripsGroupSenderPrefix` / `stripSenderOnlyMatchesWxidPrefix`）。

---

## 6. 微信原生转发 vs 我方降级重发：content 是否同口径？（team-lead 新增焦点）

**结论：项目内无「微信原生转发对端 content」真机样本（G1/R71/R72 文档缺失、原生链路默认关且标注未真机验证）；但我方代码证明——原生与降级把【同一份 snap.text】喂给同一个 `q1.e(content)`，区别只在转发标记。因此「原生不带前缀、只有我方带」这一差异在本仓库内无法证实；前缀是源消息 content 自带的，两条路都会原样带。**

### 7.1 代码铁证：同一 content 源
- 原生 text（ForceForward.kt:1410-1465）：`q1 = r1.b(target)` → `XposedHelpers.callMethod(q1, "e", snap.text)`（:1435）→ `attachForwardInfo` + `q1.h=4`（:1441-1442）→ 入队。
- 降级 text（:1608 → TextSender.kt:77）：`q1.e(content=snap.text)` → `q1.b()`，**不设 h（=0 普通文本）、不挂 ForwardInfo**。
- ⇒ 两者正文字符串**逐字相同**（均含 `wxid_jv8035ypcigc11:\n`），只有 `q1.h` 和 ForwardInfo 不同。前缀不是降级路径独有。

### 7.2 原生链路本身的取证状态（诚实声明）
- ForceForward.kt:100-102：「**转发标记对端是否可见仍需真机复核**（G1 附②：文本转发的源 msgId 在 `q0` 构造器体内未被引用）」。
- Keys.kt:512：`FORWARD_NATIVE` 开关默认 `"false"`，描述「**未真机验证，默认关；关着走降级重发**」。
- 本案走的就是 RESEND_TEXT（dbg.txt:17 `reason=原生转发不可用，降级重发文本`），无原生成功样本。
- 被引 G1 文档 `docs/_r29_g1_forward_forensics.md`（R71/R72）在 docs/ **MISSING**，无对端截图/落库 content 可比对。

### 7.3 判断与取舍
- **无法从现有证据断言**「原生转发不带 wxid 前缀」。原生若也带 ⇒ 这是群聊消息 content 的固有格式，剥前缀属「我方额外清洗」，偏离原样；用户需取舍（要干净正文 vs 与微信原生一致）。
- 但用户诉求明确：转发后正文别带 wxid。无论原生口径如何，**剥前缀都能满足诉求且对私聊/图片零影响**（§5.3），风险集中在「用户真以 wxid_ 开头发消息」的极少数场景（§7）。
- 若必须区分「原生是否也带」：**证据不足，需真机 A/B**——用微信自带「转发」把同一条群消息发到私聊，dbq.py 用 `hex()`/length 读对端 content，与本案 hex 对比。本次未执行（只读取证纪律，不碰设备）。

---

## 7. 前缀范围与剥前缀安全性（team-lead 新增焦点）

### 8.1 `wxid_xxx:` 前缀是不是只在群聊/转发出现？
- **群聊文本有**：HintTextLogic.kt:150-155 明定「微信在**群聊**里给每条消息的 content 都加发送者 wxid 前缀，**私聊没有**」；真机现场 `orig=[wxid_0mnuc8zqglzt21:\n现在节目效果…]`（HintBadge.kt:366-367、HintTextLogicTest.kt:220-226）。
- **语音（type=34）同族但不同形**：`wxid_xxx:时长:标志`（无 \n），见 HintTextLogic.kt:110、HintTextLogicTest.kt:15/68、`dbg.txt:178 content=wxid_xlrbelf6hv0w22:1942:0`。本修法正则要求冒号后换行，不会误伤语音串（语音也不走 resendText）。
- **私聊普通文本无前缀**：`dbg.txt:177 content=【模块自检】群发链路验证，可忽略`、:180 `content=123`，均无 wxid 前缀（这两条为 type=1 isSend=1，未见群聊前缀；chat 字段未直接标注私聊，但结合上条「私聊没有」的代码定论，可信）。
- ⇒ 前缀是**群聊来源消息**的固有存储格式；私聊普通文本不带。本案源消息来自群聊（发送人💎FirETrAin💎 ≠ 目标），故带前缀。

### 8.2 剥前缀安全性评估（是否需 rcontact 校验）
- **现成实现**：`HintTextLogic.stripSender()` = `replaceFirst(^\s*wxid_[0-9A-Za-z_\-]+:\s*, "")`（:164-170），已用于 R30 角标修复，有单测（HintTextLogicTest.kt:224-249）：
  - `stripSender("abc: 你好") == "abc: 你好"`（普通冒号不误剥）；
  - `stripSender("wxid_abc_123:\n你好") == "你好"`（前缀剥）；
  - `stripSender("看 wxid_abc: 这个") == "看 wxid_abc: 这个"`（中间出现不动，只剥开头一处）。
- **误剥风险窗口**：仅当用户**自己输入的正文以 `wxid_<合法字符>:` 开头**时会被剥（如真发了 `wxid_abc123: 我的微信号`）。概率极低，且微信 wxid 一般 8+ 位随机后缀、普通用户不会以它开头发消息。
- **是否需要限定「wxid 在 rcontact 存在且是群聊原发送人」才剥？—— 不推荐**，理由：
  1. 发送热路径（后台线程，TextSender.send 前）查 rcontact 增加 DB 依赖与失败面；群成员可能未存通讯录，查不到就漏剥（本会说话的 wxid_jv8035ypcigc11 **恰好**在 rcontact，但不可依赖）。
  2. 格式判据（`wxid_` 字面量 + 紧跟冒号）已是强特征，项目既有 R30 修复即以此为准，未做 rcontact 校验且线上无新报障。
  3. 加 rcontact 会让单测无法纯函数覆盖（HintTextLogic 是零依赖纯函数，易锁）。
- **推荐的更稳判据（可选增强，零依赖）**：把正则收紧为要求冒号后**必须有换行** `^\s*wxid_[0-9A-Za-z_\-]+:\s*\r?\n`（本案 hex `...3A0A` = `:` + `\n`，三处真机样本均带 \n）。这样用户手打 `wxid_abc:正文`（无换行）不会被误剥；代价是若某版本前缀用空格而非换行则会漏剥（现有证据均为 \n，风险低）。`stripSender` 现版用 `\s*` 已覆盖 \n，可直接复用；如需极致安全再叠 `\r?\n` 约束。
- **结论**：直接复用 `HintTextLogic.stripSender()`（已验证、零依赖、边界清晰）即可；不必引 rcontact。若主会话追求「绝不误剥用户原文」，再加「冒号后必须 \n」的收紧版正则。

---

## 8. 结论：根因排序

1. **【根因·确定】** 群聊消息 `h9.getContent()` 原文自带 `wxid_发送者:\n` 前缀（本案 `wxid_jv8035ypcigc11:\n`，hex 终证），`snapshotOf` 在 viewText 空时取 ref.content（ForceForward.kt:943-945），`resendText` 原样发（:1608），TextSender 原样投递（TextSender.kt:77）⇒ 转发正文被前缀污染。**正文完整未丢**（用户确认 + hex）。
2. **【触发条件·高置信】** `extractText` 因气泡为 `NeatTextView extends View`（非 TextView）返回空，是走「ref.content 兜底」而暴露前缀的直接原因（HintBadge.kt:365 + ForceForward.kt:1119）。
3. **【推翻·非根因】** ①「textLen=33 vs content=21 有差异/TextSender 切分」——不成立，三处均 33（§3，差异源于 dbq.py 按 \n 截断）；②「extractText 把 wxid 标签抽成正文」——不成立，viewText 为空（§1.3）；③「优先 View 文本而弃用更准的 h9 content」——相反，本案正用 h9 content（§4）。
4. **【原生对比·证据不足】** 原生与降级喂同一 `snap.text`（:1435 vs TextSender:77），仅转发标记不同；G1 文档缺失、原生默认关未真机验证 ⇒ 无法证实「原生不带前缀」。剥前缀满足用户诉求，但与「是否与微信原生一致」需真机 A/B（§6）。
5. **【最小修法】** resendText 发前 `TextSender.send(target, stripSender(snap.text))`（或在 snapshotOf 对最终 text 剥离使两条路都干净）；复用 HintTextLogic 正则，不引 rcontact；textLen 33→12 可单测断言（§5/§7）。

### 证据强度声明
- 强（代码+真机 DB 双向）：§0 hex 终证（正文完整）、§2 前缀格式、§3 长度闭合（33 三处一致）、§4.1 取 ref.content 的代码路径、§6.1 原生与降级同一 content 源、§7.2 stripSender 现成实现与单测。
- 中（高置信推断，缺直接日志）：§1.3「viewText 为空」——无日志打印 viewText 值，由长度+气泡非 TextView+前缀归属三重交叉推出。
- 弱/证据不足：项目内无群聊 View 树 XML dump 显示 wxid 标签；被引 forensics 文档（G1/R71/R72）在 docs/ 缺失，**无法证实微信原生转发是否也带 wxid 前缀**（需真机 A/B）；dex 级锚点仅引用代码注释内嵌的既往真机记录，未自行反汇编 dex。

[DONE]
