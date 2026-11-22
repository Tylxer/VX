# R73 图片转发第三层缺口：图片「入队 true 但对端没收到 / 本机发送失败」根因只读取证

> lane：r73-image-send-fail-forensics｜批次 R73-image
> 边界：**只读取证**——未改 `app/src/main` 任何源码、未跑 adb/装机、未跑我方转发、未跑 gradle、未 git commit。
> 目标：判定 `FileSender.send()` 收图片走 `l1.a()` 入队 `ok=true`（`FORWARD resend-file ok=true`），但对端没收到、本机 UI 提示「发送失败」的根因，给唯一可落地修法。
> 证据等级：【源码确定】= 本项目源码 / dex 字面；【真机确定】= 已有真机日志实录；【强推测】= 有完整调用链但缺最后一环实证；【假设】= 未取证，必须给装机验证方法；【取证缺口】= 现有材料无法回答。
> 纪律：所有建议的 hook / 反射改动必须 `runCatching` 全包，单步失败只 `warn`，绝不把异常抛回微信线程；所有「返回值/入队成功」都不作为「对端收到」的证据。

## 目录

- [1 「发送失败」到底是哪一层失败](#1-发送失败到底是哪一层失败)
- [2 FileSender q1 字段序列 vs 微信原生 qf4/o4.O8 逐字段比对](#2-filesender-q1-字段序列-vs-微信原生-qf4o4o8-逐字段比对)
- [3 th_ 缩略图能否作为发送源](#3-th_-缩略图能否作为发送源)
- [4 为什么 R71 原生链「落库 type=110」而这里对端收不到](#4-为什么-r71-原生链落库-type110-而这里对端收不到)
- [5 唯一推荐修法 / 最小改动清单 / 主会话真机验收判据](#5-唯一推荐修法--最小改动清单--主会话真机验收判据)
- [DONE 标记在文末](#done)

## 进度

- [x] 建文件 + 目录
- [x] Q1 失败层级判定
- [x] Q2 O8 逐字段比对
- [x] Q3 th_ 缩略图
- [x] Q4 native vs FileSender 对比
- [x] Q5 唯一推荐修法 + 最小改动 + 真机验收
- [x] [DONE]

---

## 1 「发送失败」到底是哪一层失败

### 1.1 三种可能的互斥判定

| 族 | 判定 | 证据等级 | 依据摘要 |
|---|---|---|---|
| **(a) NetScene 上传/发送真的失败（errType/errCode 非零）** | **主因族**（最贴近「入队 true + 本机 UI 失败 + 对端没收到」三者同时出现） | 【强推测】，需探针落锤 | 见 1.2 |
| **(b) q1 字段构造不全/错误，或 th_ 缩略图非法发送源** | **触发源（b'发送源）**；独立的「字段错误」子假设已基本排除 | 源=th_ 缩略图【真机确定】；因果【强推测】；字段错误【源码确定】否定 | 见 1.3 / 第 2、3 题 |
| **(c) 目标 wxid 格式问题（含 `@openim` 企业微信好友）** | **非主因，但必须排除** | 【假设】 | 见 1.4 |

### 1.2 为什么主因是 (a)：入队成功 ≠ 发送成功

1. 【源码确定】`hl0/l1.a()` 的字节码只有一件事：`modelbase/p1.g(k1, 0)` 把 NetScene 塞进发送队列（`docs/_r29_g1_forward_forensics.md` §4、`_cache/_r28fw_hl0_l1.txt:26-47`）。返回 `true` **仅代表入队**，与 `FileSender.kt:118` KDoc「`true=已成功入队（≠ 对端已收到）`」完全一致。
2. 【源码确定】图片真正的发送体是 `com.tencent.mm.modelimage.j2`（`NetSceneUploadMsgImg`，`getType()=110` @ `classes7.dex 0x2ae5d0`：`const/16 v0, #110`），它要读原图 → 压缩 → CDN 上传 → server 回包，整个异步结果落在回调：
   - **hook 锚点：`com.tencent.mm.modelimage.j2.onGYNetEnd:(IIILjava/lang/String;Lcom/tencent/mm/network/t0;[B)V`，`classes7.dex code_off=0x2b0284`**（`_r11_dex7_dis.txt:2126307`）。
   - 判据：j2 自身日志 `"cdntra onGYNetEnd errtype:" + <第2个int> + " errcode:" + <第3个int>` @ `0x2b02a4-0x2b02de`；失败分支日志 `"onGYNetEnd failed errtype:"` @ `0x2b0572`；`0x2b031c-0x2b032c` 显示 `errType==4` 特判、`errType==0 && errCode!=0` 走失败路径。
   - **建议探针同时打 3 个 int 形参 + errMsg(String)，不要预设哪一个是 errType**（j2 自己的日志把第 2/3 个 int 标成 errtype/errcode，但混淆后的形参序号容易错位）。
3. 【真机确定】R71 的 FNP 探针只验过 **TEXT**：`q0 ctor ... type=522` → `l1.a queued=true` → **对端收到**（只是无转发标记）（`docs/_r71_g1_native_forward_verdict.md` §3-4）。这证明「TEXT 入队 true → 对端收到」成立；但**图片链（j2/type=110）从 R29 静态取证到本次之前从未真机成功过**（`ForceForward.kt:2094` 注释原文「A 档已接通 2026-10-20 静态取证；待真机」，本次是首次真机跑）。
4. 【真机确定 + 强推测】本次日志三件套：`FORWARD resend-file kind=IMAGE ok=true ×2` + `FORWARD done 已发送 2 个目标`，但**本机 UI 提示发送失败、对端没收到**。微信消息气泡的「发送失败」红叹号由消息表状态 / NetScene 回调渲染——入队 true 而 UI 失败，说明失败发生在**入队之后的异步 NetScene 阶段**，即 (a)。

### 1.3 (b) 的拆分：字段错误已排除，发送源可疑

- 【源码确定】**字段错误子假设否定**：FileSender 的 q1 字段序列与 O8 在「决定 NetScene 构造与取文件路径」的字段上逐项一致（`q1.i=4 / q1.j=path / q1.k=0 / q1.l=null / q1.n=0 / q1.p=true / q1.o=resId / q1.h=13`），`constructorId=13` 正是图片转发分支（`t81/g.a @ 0x8bba04-0x8bba72`）。详见第 2 题。
- 【真机确定】**发送源子假设成立**：本次 `pick=th_216b1f7ccd59b1268349fd0eb3c46801hd`——桶内没有 tier-1 大图 `<md5>.jpg/.png`，退到了 HD 缩略图；R73 第二层取证已钉死「缩略图不能当转发源，对端收到模糊图」（`wxgroup/docs/_r73_image_path_forensics.md` §4.2）。这是本次与「正常发图」唯一可观测的实质差异。详见第 3 题。
- 【强推测】HD 缩略图文件名无图片扩展名（尾缀 `hd`），作为 `q1.j`(ori) 喂给 j2 上传器，可能让「按原图路径取图/判格式」环节出错 → onGYNetEnd 报错，这是把 (b') 与 (a) 串起来的最可能因果链。

### 1.4 (c) openim 目标：弱假设，须装机排除

- 【源码确定】`25984982871986551@openim` 是企业微信好友形态；`@openim` 联系人在 `EnMicroMsg.db` 的 `rcontact` 表真实存在（真机 254 条），是正常好友而非无效号（`core/ContactSqlSource.kt:19-35`、`data/ContactFilterLogic.kt:14-26`）。
- 【真机确定】本次 `ok=true ×2`，即**两个目标都成功入队**；若「openim 不支持图片发送」成立，应表现为「个别目标失败」，而非「整体 UI 发送失败 + 两边都没收到」。故 (c) 不是主因。
- 【假设，装机验证方法】同一张图分别发给「文件传输助手」/ 普通 `wxid_` 好友 / `@openim` 好友，比对 `INP ... errType/errCode` 与人眼接收；**只有 @openim 那一路失败**才让 (c) 成立。

### 1.5 一句话定性

> **主因族 = (a)：图片 NetScene（j2/type=110）在入队后的异步上传/发送环节失败，errType/errCode 非零；最可疑触发源 = (b')：桶内无大图 → 拿 `th_<md5>hd` 无扩展名 HD 缩略图当原图上传。(c) 弱假设待排。**

## 2 FileSender q1 字段序列 vs 微信原生 qf4/o4.O8 逐字段比对

【源码确定】反汇编源：`_cache/_r28fw_qf4_o4.txt:433-499`（`qf4/o4.O8` 提取）+ `_cache/_r11_dex7_dis.txt:1698488-1698522`（原始 dex dump 交叉核对）。O8 签名：
`(Context, String toUser, String fileName, int msgType, String arg4, String arg5, hl0/m, ScanCodeInfo)V`；寄存器 `v3=this, v4=Context, v5=toUser, v6=fileName, v7=msgType, v8=arg4, v9=arg5, v10=m, v11=ScanCodeInfo`（由入口 `if-eqz v5` / `if-nez v6` 与错误串 `toUser[%s], fileName[%s]` 双重确认）。

### 2.1 逐字段对照表

| O8 地址 / 字节码 | O8 写入 | FileSender.kt | 一致？ | 是否影响发送 |
|---|---|---|---|---|
| `84579a-84579e` `sget o1.e` → `r1.a(o1)` | 造 IMAGE builder q1 | `:146-150` findClass `hl0.o1/r1` + `pickEnum("IMAGE")` + `r1.a(o1)` | ✅ | 决定走图片工厂 |
| `8457a8` `iput q1.i=4` | i=4 | `:157 setIntField("i",4)` | ✅ | uploadsrc |
| `8457ac` `q1.g(v4)` → `q1.b` | b=文件名（O8 传 `vj0/b2.r()` 串） | `:158 callMethod("g", f.name)` | ✅ 语义同（b 只是 `from` 展示名，见 j2 log `from:%s`） | 低 |
| `8457b2` `q1.h(v5)` → `q1.a` | a=toUser | `:159 callMethod("h", wxid)` | ✅ | **目标会话** |
| `8457b8` `iput q1.j=v6` | **j=完整路径** | `:160 setObjectField("j", path)` | ✅ | **ori 原图路径（命门）** |
| `8457bc` `iput q1.k=v7` | k=msgType（O8 来自 N8 第 4 参，本次为 0） | `:161 setIntField("k",0)` | ✅ | 压缩策略 |
| `8457c0` `iput q1.l=null` | l=null | `:162 setObjectField("l",null)` | ✅ | t0 回调 |
| `8457c6` `iput q1.n=0` | n=0 | `:163 setIntField("n",0)` | ✅ | rotate |
| **`8457ca` `iput q1.r=v8`** | **r=arg4（j2 的 `cdn:` 参数）** | **未设**（`q1.<init>` 默认 `""`） | ❌ **差异** | 见 2.2 |
| **`8457ce` `iput q1.s=v9`** | **s=arg5（j2 的 `thumb:` 参数）** | **未设**（默认 `""`） | ❌ **差异** | 见 2.2 |
| `8457d4` `iput-boolean q1.p=1` | p=true | `:164 setBooleanField("p",true)` | ✅ | chatt |
| `8457de` `iput q1.o=0x7f0803d6` | o=资源 id（int） | `:165 setIntField("o", Q1_O_RES_ID)` | ✅ | res |
| `8457e2-8457f2` `q1.f(m)`（m 可 `new` 默认） | 挂 ForwardInfo | `:168-171 new hl0.m()` + `q1.f` | ✅（O8 也允许 m=null 时 new 默认） | 转发标记（m.a 恒 -1，见第 4 题） |
| `8457fc` `iput q1.h=13` | **constructorId=13=图片转发** | `:173 setIntField("h",13)` | ✅ | **决定 j2 构造分支** |
| `845800` `iput q1.D=v11` | D=ScanCodeInfo（本次 null） | `:174` 注释省略 | ✅ 等价（null） | 无 |
| `845804` `q1.a() -> l1` | 造 NetScene | `:176 callMethod("a")` | ✅ | — |
| `84580c` `MsgRetransmitUI.g6(l1)` | 上报转发 msgId（只读 l1） | `:184-189 callStaticMethod g6` | ✅ | 不影响 execute |
| `845812` `l1.a() -> boolean` | 入队 | `:192 callMethod("a")` | ✅ | **只到入队为止** |

### 2.2 关于 r/s 两处差异的定性

【源码确定】`t81/g.a` 的 `q1.h==13` 分支（`_cache/_r28fw_t81_g.txt:190-218`，`8bba04-8bba72`）把 q1 字段搬进 j2 的 19 参构造器 `method@1e15`。j2 构造器自己的日志串（`_r11_dex7_dis.txt:2af0b2`）逐项命名了这些入参：

```
dkupimg init uploadsrc:%d from:%s to:%s ori:%s cmptype:%d prog:%s rotate:%d cdn:%s thumb:%s chatt:%b , res:%d run:%b [%s], scene: %d, longtitude: %f, latitude: %f
```

映射：`q1.j → ori:`、`q1.r → cdn:`、`q1.s → thumb:`、`q1.i → uploadsrc:`、`q1.b → from:`、`q1.a → to:`。

- 【强推测】`q1.r/q1.s` 为空（FileSender 留默认 `""`）=「没有预置 CDN/缩略图路径，请从 `ori` 重新取图上传」的普通发图形态。R71 文本链验证的也是「重发而非复用 CDN」。这两处差异**不是**「入队后失败」的充分原因。
- 【假设，装机验证方法】加 j2 构造器只读探针（`0x2aef10`），真机打出 `ori:/…/th_<md5>hd cdn: thumb:` 一行，确认 FileSender 实际传值；若 `ori` 路径确为 th_ 文件，则坐实第 3 题的发送源问题。

### 2.3 确定性结论

> **【源码确定】FileSender 没有漏设/设错任何「决定 NetScene 构造与取图路径」的字段；`constructorId=13`、`q1.i=4`、`q1.j=path` 全部正确。唯一两处与 O8 不同（未设 q1.r/q1.s）只影响 CDN/thumb 预置路径，留空是合法的「重传」形态。⇒ 问题不在「字段构造错」，而在「q1.j 指向的文件本身」+「入队后的 NetScene 异步结果不可见」。**

⚠️ 命名陷阱复述（团队已知，别踩）：`:159 callMethod(q1,"h",wxid)` 是**方法 h**（写 q1.a 目标会话）；`:173 setIntField(q1,"h",13)` 是**字段 h**（constructorId）。同名不同物。

## 3 th_ 缩略图能否作为发送源

### 3.1 本次到底挑了什么

【真机确定】`FORWARD resolveImage md5=216b1f7ccd bucket=21/6b hit=11 pick=th_216b1f7ccd59b1268349fd0eb3c46801hd exists=true`——`pickImageFile`（`ForwardLogic.kt:329-351`）的 tier-1（含 md5、非 `th_` 前缀、图片扩展名、字节最大）**未命中**，退到 tier-2 HD 缩略图 `th_<md5>hd`。

【源码确定】`pickImageFile` 优先级（`ForwardLogic.kt:312-351`）：
1. `contains(md5) && !startsWith("th_") && ext∈{.jpg,.png,.jpeg,.webp}` → 大图（首选）；
2. `th_<md5>hd` / `th_<md5>hd_tmp.jpg` → HD 缩略图（次选）；
3. `th_<md5>` → 缩略图兜底（warn）。
代码注释 `:322` 已自警「第 1 档必须排除 `th_` 前缀，否则 `maxByOrNull` 会挑到缩略图 ⇒ 对端收到模糊图（§4.2 事故）」。**但 tier-2/3 仍会把缩略图交给发送器**——这正是本次路径。

### 3.2 三档文件的真机事实

【真机确定】`wxgroup/docs/_r73_image_path_forensics.md` §1.2/§4.1：

| 档 | 形态 | 真机大小 | 作为转发源 |
|---|---|---|---|
| 大图/压缩图 | `<md5>.jpg/.png` | 40~194KB | ✅ 首选，对端收到清晰图 |
| HD 缩略图 | `th_<md5>hd` / `th_<md5>hd_tmp.jpg` | ~16KB | ⚠️ 优于缩略图但非原图；**文件名无 `.jpg` 扩展名** |
| 缩略图 | `th_<md5>` | 3~8KB | ❌ 对端收到模糊小图（`§4.2` 已定性为事故） |
| 原图 | `<md5>hd` | 未见真机实例 | ❌【假设】原图常从未落盘，不可依赖 |

### 3.3 喂给 O8 同构链会发生什么

- 【源码确定】j2 上传器把 `q1.j`（本次即 `th_<md5>hd` 路径）当作 `ori:`（原图入参）读取、压缩后走 CDN 上传（`t81/g.a @ 0x8bb794-0x8bb800`、`j2.<init> @ 0x2af0b2` 日志）。`t81/g.a` 先用 `a8.F0(q1.j)` 判路径再用 `o6.l(q1.j)` 取大小决定「是否当大图不压缩」——**说明微信是按 ori 路径直接读文件**，不是按 imag 表里的 md5 去找大图。
- 【强推测】把无扩展名的 `th_<md5>hd` 当 `ori` 有两条风险：
  1. **语义风险（确定会发生）**：即便发送成功，对端拿到的是 16KB 级 HD 缩略图而非 40~194KB 大图 → **模糊图**，与 R73 §4.2 事故同类；
  2. **失败风险（本次疑似命中）**：文件名无图片扩展名，`q1.b=f.name` 也无扩展名，微信按 ori 取图/判格式时若依赖扩展名，可能直接上传失败 → onGYNetEnd 非零 → 本机「发送失败」、对端没收到。
- 【假设，装机验证方法】hook j2 构造器（`0x2aef10`）打 `dkupimg init ... ori:` 实际值 + hook `onGYNetEnd`（`0x2b0284`）打 errType/errCode；再用 `op=forward sub=fileb` 之类只读探针**分别**用大图与 `th_<md5>hd` 发到文件传输助手，比对两路 `ori:` 与回调码。若只有 th_ 那一路 errType≠0，则坐实「HD 缩略图非法发送源」。

### 3.4 「必须是大图」的判据与安全降级

**判据（【真机确定】选型口径，写死在发送器入口）：**
- `q1.j` 指向的文件必须满足：`contains(md5)` **且** `!startsWith("th_")` **且** 扩展名∈`{.jpg,.jpeg,.png,.webp}` **且** `length()>0`（字节数明显大于同 md5 的 `th_` 3~8KB，真机大图 40~194KB 可交叉验证）。
- 不满足 ⇒ **不可作为发送源**。

**桶里没大图时的安全降级 = `SKIP`，不要「仍发缩略图」。** 理由：
- R73 §4.2 已把「发缩略图」定性为事故（对端模糊图）；本次又疑似直接导致 NetScene 失败；
- 缩略图当发送源**没有任何收益**（既不清楚也不保证成功），只会把「二选一事故」变成「必现事故」；
- 正确产品行为：`planFor` 安全 `SKIP` + 一行 warn（带 md5/bucket/候选数），**不发空壳、不崩微信**；用户「先点开图片让大图下载」后再转即可（大图落盘后 tier-1 必命中）。
- 【取证缺口】「是否要主动触发 `DownloadImgService` 拉大图」超出只读取证范围，且 R73 路径文档已定「不引下载」；本 lane 不建议引入。

## 4 为什么 R71 原生链「落库 type=110」而这里对端收不到

### 4.1 先纠正题设：R71 从未真机落库过 type=110

【真机确定】R71 的 FNP 探针铁证是 **TEXT**：`FORWARD plan strategy=NATIVE kind=TEXT`，`FNP q0 ctor forwardMsgId=0 newMsgId=19724386 type=522`，`l1.a queued=true`，**对端收到但无转发标记**（`docs/_r71_g1_native_forward_verdict.md` §3-4）。文中「type=110 图片」是**dex 静态对照**（`g6` 里 `getType()==110` 判分支），**不是真机图片成功样本**。

⇒ 「R71 原生链能落库 type=110」这个说法的前提不成立：R71 只证明了 TEXT（q0/522）的「入队 → 对端收到」；图片链（j2/110）当时受限于「图片长按抓不到 View / snapshot 恒 TEXT」（R71 §5），**根本没跑到真机图片发送**。本次才是图片链第一次真机跑，且走的是 `RESEND_FILE`（`strategy=RESEND_FILE`），不是 NATIVE。

### 4.2 两份字段序列的差异（【源码确定】）

`nativeForwardImage`（`ForceForward.kt:1968-2054`）与 `FileSender.send`（`FileSender.kt:120-202`）**逐字段同构**：

| 步骤 | nativeForwardImage | FileSender | 差异 |
|---|---|---|---|
| 工厂 `r1.a(IMAGE)` | `:2001` | `:150` | 无 |
| `q1.i=4 / g(f.name) / h(target) / j=path / k=0 / l=null / n=0 / p=true / o=resId` | `:2008-2016` | `:157-165` | **无** |
| `q1.f(m)` | `:2022 attachForwardInfo`：填 `m.a=-1 / b=msgId / c="" / e=svrId / f=talker` | `:168-171 new m()` 全默认 | **唯一差异：ForwardInfo 填没填** |
| `q1.h=13` | `:2023` | `:173` | 无 |
| `q1.a() → g6(l1) → l1.a()` | `:2030-2040` | `:176-192` | 无 |
| 失败分阶段码 | MATERIAL/CLASS/CONSTRUCT/METHOD/SEND | 只有 boolean | 日志粒度不同，**但两者在 `l1.a()` 后都返回 ok=true** |

关键：`m.a`(forwardFromImgId) 在两条链里**都恒 -1**（`ForceForward.kt:1965-1966`、`AttachForwardInfo :2065`）——即两条链都是「重传，不复用 CDN」。R71 §4 已定案：对端无转发标记是微信 host 链约束（`m.a=-1` 的必然结果），**不是「对端收不到」的原因**。

### 4.3 差异能否定位 FileSender 的缺口

> **【源码确定】定位结果：FileSender 的缺口不在「字段序列」——它与 nativeForwardImage 完全同构，而后者也没真机验证过图片成功。两者共享同一个真正的缺口：都只拿 `l1.a()` 的入队布尔值，没有任何 NetScene（`j2.onGYNetEnd`）异步回调判据，因此都无法区分「入队成功但对端失败」与「对端真收到」。**

- 换用 `nativeForwardImage` **不会修复**本问题（字段相同、同样 `m.a=-1`、同样只看到入队 true）。它唯一多填的 `m.b/e/f` 只影响转发标记，而 R71 已证标记缺失不影响「对端收到」。
- 换用 `FileSenderB`（`qf4/r5` 普通文件 appmsg type=6，`core/FileSenderB.kt:16`）**不适用**：那是发普通文件，不是图片，且 `FileSender.kt:63-73` 明确它「尚未取证完整，A 档图片真机通过前不接」。
- ⇒ 缺口二选一：**(i) 发送源（th_ 缩略图，第 3 题）+ (ii) 结果判据缺失（无 NetScene 回调，第 1 题）**，两者都必须处理；不存在「某一份字段抄漏了」这种更省事的答案。

## 5 唯一推荐修法 / 最小改动清单 / 主会话真机验收判据

### 5.1 唯一推荐修法（一句话）

> **不要改 FileSender 字段序列、不要换 nativeForwardImage/FileSenderB：先加一个只读探针 hook `com.tencent.mm.modelimage.j2.onGYNetEnd`（classes7 `0x2b0284`）把 errType/errCode 落锤；同时把「可发送源」收紧为「桶内必须命中 `<md5>.jpg/.png` 大图，否则安全 SKIP」，彻底不发 `th_<md5>hd` 缩略图。** 发送器仍复用现有 FileSender（字段已与 O8 同构，无需改）。

（若探针证实 errType/errCode 非零且 `ori:` 正是 th_ 文件 ⇒ 发送源为因；若探针为 `0/0` 而对端仍没收到 ⇒ 转入 (c) openim 目标 / 网络侧排查，不再动字段。）

### 5.2 最小改动清单（本 lane 一行未动，均为交主会话的建议）

| # | 文件 | 改动 | 行数级 | 证据 |
|---|---|---|---|---|
| 1 | `feature/ForwardLogic.kt` | 新增纯函数 `pickSendableImageFile(files, md5)`：只认 `contains(md5) && !startsWith("th_") && ext∈{.jpg,.jpeg,.png,.webp} && length>0`，取字节最大者；**不再把 tier-2 `th_<md5>hd` / tier-3 `th_<md5>` 当发送源**。保留原 `pickImageFile`（含三档）仅供日志/调试，避免波及别的 lane | ~15 | 【真机确定】§3.2 三档事实 + §3.4 判据 |
| 2 | `feature/ForceForward.kt` | `resolveImagePath()` 命中 tier-1 才回填 `localPath`；tier-1 miss ⇒ `warn + null`（保留 rawPath 供日志），使 `planFor` 走 SKIP；现有 `runCatching` 包裹不动 | ~6 | 【源码确定】`snapshotOf :1276` 衔接点 |
| 3 | 新增只读探针 `probe/ImageNetSceneProbe.kt`（独立 tag 如 `INP`，**默认关，仅真机诊断开**） | hook `j2.onGYNetEnd:(IIIL...;...)V` @ `0x2b0284`：打 3 个 int 形参 + errMsg + `getType()`(=110) + `j2.S().getMsgId()`；可选 hook `j2.<init>` @ `0x2aef10` 打 `ori:`/`cdn:`/`thumb:` 实际入参。**全程 runCatching，只 LogX，不改消息表/不下载/不模拟 UI** | ~40 | 【源码确定】§1.2 / §2.2 锚点 |
| 4 | `core/FileSender.kt` | **不改字段序列**。可选（低优先级）：把 `q1.r/q1.s` 显式 `setObjectField("")`，仅为日志同构，行为无变化 | 0~2 | 【源码确定】§2.2 |
| 5 | `docs/_r73_image_send_fail_forensics.md` | 本文件（已落盘） | — | — |

⛔ 不改：`ForwardNativeLogic` 字段、`chat.forward_native` 默认值、`AntiRevoke`/`RevokeRescue`、任何 Key、任何消息状态；不跑 adb/装机/gradle/commit（交主会话）。

### 5.3 主会话真机验收判据（**必须人眼对端收到清晰图，禁止只用 ok=true**）

**准备**：目标图片先**点开大图一次**（让 `<md5>.jpg/.png` 落盘）；先发「文件传输助手」排除 openim 变量。

1. **主判据（发送源）**：默认开关下转发 → 日志 `FORWARD resolveImage ... pick=<md5>.jpg exists=true`、`FORWARD resend-file kind=IMAGE ok=true`；**人眼到对端确认收到清晰图片**，长按保存后字节数/分辨率 ≈ 源大图（40~194KB），明显大于 `th_<md5>` 的 3~8KB。若拿到模糊图 ⇒ tier-1 未被选中，回改动 #1。
2. **判据（失败层）**：同一次发送看探针 `INP ... errType=<n> errCode=<m>` / j2 日志 `cdntra onGYNetEnd errtype:0 errcode:0`；**全 0 才算 NetScene 成功**。非零 ⇒ 按 errType/errCode 查 CDN/网络/账号，而不是改 q1 字段。
3. **排除 openim**：同一张图再发给普通 `wxid_` 好友、再发给 `25984982871986551@openim`，三路都人眼确认。**只有 @openim 一路失败**才让 (c) 成立，届时对该目标 SKIP + 提示，不全局改发送器。
4. **安全降级**：找一张**未点开、桶里只有 `th_` 没有任何大图**的图片 → 应 `planFor SKIP` + 一行 warn（带 md5/bucket/候选数），**无空消息、无 FATAL EXCEPTION、无 ANR、本机不出现红叹号**（因为根本没入队）。这是与现状已有日志的关键区别。
5. **反向对照**：把 `ref.imgPath` 临时退回原 URI（不应用本改造）复现同一张图 ⇒ 仍 `FORWARD plan ... SKIP ... kind=IMAGE`，证明 1 的 PASS 确由本改造带来。

**红线复述**：任何新增 hook/反射用 `runCatching` 全包，单步失败只 warn，绝不抛异常到微信线程；日志只打 md5 前 10 位 / 路径尾段，避免刷屏；探针默认关，按 `op` 显式开。

---

## [DONE]

R73 图片第三层缺口取证完成：主因族 = NetScene 异步发送失败（j2 `onGYNetEnd` errType/errCode 非零，hook 锚点 `0x2b0284`），最可疑触发源 = 桶内无大图导致 `th_<md5>hd` 无扩展名 HD 缩略图被当 ori 上传；FileSender 字段序列与 O8 同构、无漏设，换发送器无效；唯一推荐修法 = 只读探针落锤 errType/errCode + 发送源收紧为「必须是 `<md5>.jpg/.png` 大图，否则 SKIP」，发送器仍用 FileSender；人眼对端收到清晰图为最终验收。

