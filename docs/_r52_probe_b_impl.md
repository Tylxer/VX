# R52 Pro 实现 lane B · 补探针定位：被撤回图点开时 `img=null / localId=0` 溯源

> **本 lane 性质**：只加**观测**、不改行为。目标是一次装包后能在日志里看清 `img=null / localId=0`
> 是从哪个字段 / 哪条调用链来的，为下一轮「绕开 DB、直接喂本地文件」或「消息重建」提供依据。
> **独占文件**：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/AutoHdImg.kt`（只改这一个源码文件）。
> ⛔ 不碰 `feature/MediaTableGuard.kt` + `entry/EntryReceiver.kt`（lane A 独占）；不碰 adb / 不装包 /
> 不跑 gradle / 不写微信库 / 不改任何开关默认值 / **不改现有 HD 原图下载行为**。
> **昵称铁律**：好友一律 **verity** / **测试群聊** / **文件传输助手**，不写裸 wxid。
> **分级纪律**：不确定标 `[推断]` / `[需真机]`。
> **分步落盘**：每个子步完成即写盘 + `[DONE] <子步>`，末尾 `[DONE] ALL`。
> ⚠️ **行号口径**：§T1 锚点全部是**改前（现网版 / git HEAD）行号**，git 可查；改后新增代码的行号在 §T2 单独标注。
> ⚠️ **术语口径（协作修订，r52-probe-a 复核）**：本项目简称的「日志 5000 行环形覆盖」，
> 源码真实行为是 `LogX.kt:83-88` 的 `writeText` **整文件清空 + 留一行 `---- log rotated: 已达 5000 行，
> 清空重来 ----` 标记 + `lines=0`**，⛔ **不是逐行 FIFO 挤掉**。源码注释 `:81` 把两者并称，
> `docs/开发回路速查.md:111` 沿用了简称。本报告一律按真实行为描述；那行 `log rotated` 标记本身
> 是判据（见 §T3 #9 / §T4 复核 / §T5 前提）。

---

## T1 现状锚点（改前行号，全部来自 Grep + Read(offset,limit)，未整读 >300 行）

| 锚点 | 位置（`文件:行号`） | 内容 |
|---|---|---|
| TAG / 类名常量 | `AutoHdImg.kt:54`、`:57`、`:58-61` | `TAG = "HDIMG"`；`C_NETSCENE = "com.tencent.mm.modelimage.t1"`（`NetSceneGetMsgImg`）；`C_CORE=d3`、`C_SERVICE=m`、`C_FOUNDATION=b1`、`C_LISTENER=fv.e0` |
| **hook 注册点** | `AutoHdImg.kt:160-178`（`install()`），`:168` 为 `XposedBridge.hookAllConstructors(t1, ctorHook)`；`:177` 为日志点 | `HDIMG installed com.tencent.mm.modelimage.t1 ctor x${set.size} (开关 ${Keys.CHAT_AUTO_HD_IMG})` —— 注册在 **t1 全部构造函数的 `afterHookedMethod`**，不是消息入库点 |
| **`HDIMG sig`** | `AutoHdImg.kt:230-238`（`LogX.i`） | `"$TAG sig n=${args.size} type=$type o=… h=… i=… j=… q=… args=$argLocal/$argMsg"`；由 `sigSeen`（`:108`）按 `参数个数:首个Int` 去重，上限 12 条 |
| **`HDIMG fired`** | `AutoHdImg.kt:378`（`LogX.i`） | `"$TAG fired msgId=$msgId localId=$localId t=$imgT img=${imgPath?.take(36)} talker=$lastTalker"`；在 `if (r == true)`（`svc.c` 返回 true）分支内（`:376-385`）。**被撤回图 00:13:26.067 那行就是这里打的** |
| t1 构造参数取法（参数） | `AutoHdImg.kt:187-198` | 遍历 `param.args`：前两个 `Long` → `argLocal` / `argMsg`；第一个 `Int` → `type`（downloadType，落字段 `o`）。⚠️ 注释 `:199-203` 已记「撤回后点开时微信建的是 `(0, msgId)` 形态 ⇒ 第一参 localId 为 0」，**参数位置语义被真机推翻过一次** |
| t1 构造参数取法（实例字段） | `AutoHdImg.kt:204-205`（`instLong` 定义 `:281-282`） | `fldLocal = getLongField(thisObject,"h")`、`fldMsg = getLongField(thisObject,"q")`；dex dump 取证 `h/i/j`=localId、`q`=msgId |
| **字段优先、参数兜底** | `AutoHdImg.kt:212-213` | `localId = if (fldLocal>0) fldLocal else argLocal`；`msgId = if (fldMsg>0) fldMsg else argMsg`。不一致时 `:217-226` 打 `HDIMG id arg/fld mismatch` |
| `img` / `t` / `imgPath` 来源 | `AutoHdImg.kt:316-321`，`imgPathOf` 定义 `:292-302` | `img` = `ImgInfoStorage.o2(localId)`（`:316-317`，按 **localId** 查）；`t=imgT` = `getIntField(img,"t")`（`:320`）；`imgPath` = `imgPathOf(img)`（`:321`）遍历 `img` 所有 String 字段取第一个含 `://` 或长度 24..40 的值（`:296-300`）。⚠️ **localId=0 时 `o2(0)` 查不到对象 ⇒ `imgPath=null`**，这与 `HDIMG fired img=null` 吻合；`t=0` 说明 `img` 对象非 null 但无路径型字段 `[推断]` |
| `talker` 来源 | `AutoHdImg.kt:127`、`:334-335`、`talkerOf` 定义 `:434-450` | `lastTalker` 仅在 `chat.auto_hd_allow` / `chat.auto_hd_exclude` 名单**非空**时由 `talkerOf(msgId, cl)` 写入；两键默认值均 `"[]"`（`Keys.kt:120,123,445-446`）⇒ 默认空、作用域分支 `:332-368` 整段跳过 ⇒ 日志 `talker=`。与 `HDIMG fired` 同口径 |
| `localId` / `msgId` 去重与下载触发 | `AutoHdImg.kt:239-250`、`:252-274` | `msgId==0` 静默打 `HDIMG noMsgId` 后 return（`:239-249`）；`done`（`:142-152`）按 msgId 去重；`pool.execute`（`:252`）里 `fire(localId, msgId)`（`:256`） |
| **`chat.auto_hd_img` 读取方式** | `AutoHdImg.kt:183`；`Keys.kt:117`、`:442` | `ctorHook.afterHookedMethod` **第一行**：`if (!Kv.getBool(Keys.CHAT_AUTO_HD_IMG)) return`；`CHAT_AUTO_HD_IMG = "chat.auto_hd_img"`，注册默认值 `"false"` |

### T1 结论：探针会不会被开关挡住 —— **会，而且是完全挡住**

`:183` 的 `return` 位于 `afterHookedMethod` 最顶端，在 `seen.incrementAndGet()`（`:186`）和任何
日志之前 ⇒ 开关关时 `HDIMG sig` / `HDIMG fired` / 本轮新增 `HDIMG trace` **全部零输出**，连
「钩子有没有被调用」都看不到（与 lane A `MTG enabled=false` 时 before 第一行 return 同构）。
真机 `00:13:26.067 HDIMG fired …` 能出现，反证那次 `chat.auto_hd_img` 是**开**的。
⇒ 本轮取证**必须保持 `chat.auto_hd_img` 开启**，否则一行都拿不到（详见 §T4 第 4 条）。

[DONE] T1（现状锚点 + 开关挡探针结论）

---

## T2 实现：异常分支溯源日志（`HDIMG trace`）

> 全部改动**只在** `AutoHdImg.kt` 一个文件内。以下为**改后行号**（`git diff` 复核）。

### 2.1 改动清单

| 位置（改后） | 改动 |
|---|---|
| `AutoHdImg.kt:133` | 新增 `traceSeen`（`ConcurrentHashMap.newKeySet<Long>()`）—— 限流用去重集合 |
| `AutoHdImg.kt:264` | `ctorHook` 内、`done` 去重（`:259`）之后新增 `val ctorTrace = if (localId == 0L) ctorTraceOf(param.thisObject, param.args, type) else null` |
| `AutoHdImg.kt:267` | `fire(localId, msgId)` → `fire(localId, msgId, ctorTrace)`（**只多传一个只读 String**） |
| `AutoHdImg.kt:327-348` | 新增 `ctorTraceOf()`：拼 `t1` 全部构造参数（逐个 `a[i]=类型:值`，每值 `.take(40)`）+ 实例字段 `h/i/j/o/q` + 微信侧 3~5 帧栈，整行 `.take(600)` |
| `AutoHdImg.kt:360-381` | 新增 `readMsgImgPath()`：用现有 `AntiRevoke.msgQueryHandle()` 句柄做一次单行只读 `select imgPath from message where msgId=? limit 1` |
| `AutoHdImg.kt:392-406` | 新增 `traceIfAbnormal()`：触发判据 + 限流 + 打两行 `HDIMG trace` |
| `AutoHdImg.kt:411` | `fire()` 签名加 `ctorTrace: String?`（下载逻辑一行未动） |
| `AutoHdImg.kt:497` | `HDIMG fired`（`:482`）之后、两条 `svc.c` 分支之外，新增唯一调用点 `traceIfAbnormal(msgId, localId, imgPath, imgT, ctorTrace)` |

### 2.2 触发条件与「正常图不刷屏」

`AutoHdImg.kt:399`：`if (imgPath != null && imgPath.isNotBlank() && localId != 0L) return`
即只在 **`imgPath == null || isNullOrBlank() || localId == 0`** 时继续 —— 正是被撤回图
`HDIMG fired … localId=0 t=0 img=null` 的形态。正常未撤回图
（`localId=875524`、`img=SERVERID://1487791032802485058`）**第一行就 return，零额外日志**。

### 2.3 两行日志的形态（下一轮真机要抄的就是这两行）

```
HDIMG trace msgId=19640543 localId=0 img=null t=0 talker= | ctor=n=2 type=0 a[0]=Long:0 a[1]=Long:19640543 fld[h=0 i=0 j=0 o=0 q=19640543] stack=<微信类>.<方法>|<微信类>.<方法>|...
HDIMG trace db  msgId=19640543 imgPath=THUMBNAIL_DIRPATH://th_336477acaf3e8c069c9ba6357acbcd3e
```

- 第一行输出：`msgId` / `localId` / `img`（`.take(48)`）/ `t` / `talker`（`lastTalker`，与 `HDIMG fired` 同口径）/
  **`t1` 构造能拿到的全部参数**（`n`、`type`、逐个 `a[i]=类型:值` 带 `.take(40)` 截断）/ 实例字段
  `h/i/j/o/q` / **关键调用栈 3~5 帧**（只取 `com.tencent.*`，类名.方法名，`|` 分隔，⛔ 不打整栈）。
- 第二行输出：`message` 表里这条 msgId 的 `imgPath`；拿不到时明确打
  `no db handle` / `null cursor` / `no row` / `null col` / `query err <异常类名>`，**不会静默**。
- 用 `LogX.w`（异常分支口径，同 `noMsgId` / `mismatch`），`dev.py q 'HDIMG'` 即可一把捞到。

### 2.4 ⭐ 限流（硬性要求）

- `AutoHdImg.kt:400`：`traceSeen` **按 msgId 去重**，上限 64 条；重复点开同一张被撤回图不再打。
- LogX 自身还有 `DEDUP_MS=1000`（连续同内容 1 秒去重，`LogX.kt:21,72-74`）；
  `lines >= MAX_LINES(5000)` 时 `writeText` **整文件清空重来**并留一行 `---- log rotated ----`
  标记、`lines=0`（`LogX.kt:83-88`，⛔ 非逐行 FIFO），叠加 `K7 perf` 每秒刷屏 ⇒
  无节流会把旧证据几分钟内整批冲掉，故本探针不打满 64 条就够取证。
  ⭐ 两 lane 观测日志叠加会加快触顶：lane A 口径为独立 `obsLogAt` + `table+reason` 60s 节流 +
  `MAX_LOG_KEYS=64` 封顶 ⇒ 最坏约 64 行/60s（≈1 行/秒）另加 4 个固定 key；lane B 为 2 行/异常
  msgId、上限 64 ⇒ 两侧都受限流，但**合计仍比单 lane 更快到 5000 行**，测前清日志见 §T4 复核第 5 条。
- DB 只读查询 `readMsgImgPath()` 挂在 `traceSeen` 闸门**之后**：只有真正要打这一条时才跑一次，
  ⛔ 不是每次点击都跑，正常图连这行代码都进不来。
- 抓栈 `Throwable().stackTrace` 只在 `localId == 0`（`:264`）时于微信线程跑一次，正常图不碰。

### 2.5 DB 那一行怎么拿到的（廉价性论证）

`readMsgImgPath()` 用的是 `AntiRevoke.msgQueryHandle()`（`AntiRevoke.kt:851-855`）返回的
**已有** `msgDb`（写库 hook 里抓到的那个，`AntiRevoke.kt:833-838`）+ **已缓存**的
`rawQuery` Method（`AntiRevoke.kt:1061-1075`）：

- ⛔ **不新开 DB 连接**（不 `openDatabase`、不碰 `GroupDb`、不碰 `snsDb`）；
- ⛔ **不写库**（纯 `select`，带 `AntiRevokeHint.BYPASS = "/*w*/"` 绕过模块自己的诊断 SQL 改写，
  同 `AntiRevoke.kt:897-901` / `RevokeRescue.kt:269-275` 既有口径）；
- ⛔ **不引入耗时反射/扫类**（`rawQueryMethod` 在 AntiRevoke 内已缓存，这里只 `Method.invoke`
  一次 + 3 次 `XposedHelpers.callMethod` 读 cursor，与 `RevokeRescue.auditImage` 同款且更轻，
  后者自述「跑在微信 DB 线程上，必须便宜」）；
- 全程 `runCatching`，句柄/查询任一失败只回一句说明，**绝不抛到 `fire()` 外层影响下载**；
- 句柄为 null（`msgDb` 尚未捕获）时明确打 **`no db handle`**。
- ⚠️ `bind` 按 `rawQuery` 第二参类型构造（`String[]` / `Object[]` 两个 WCDB 重载都认），
  与 `AntiRevoke.bindArgs`（`:1024-1029`）同一写法，`m.invoke(db, sql, bind)` 不展开数组。

### 2.6 「只加观测」自检（逐条）

- ⛔ **不改 `t1` 参数**：`ctorTraceOf` 只读 `param.args` / 实例字段，无任何 `param.args[i] = …`；
- ⛔ **不改下载触发逻辑**：`msgId==0` 早退（`:248`）、`done` 去重（`:259`）、`selfFiring`、
  `pool.execute`、`fire()` 内 `already`/名单/`svc.c(localId,msgId,TYPE_HD,…)` 全部原样；
- ⛔ **不碰 `param.result`**：全文件原本就没有任何 `param.result` 赋值点，本轮**未新增**
  （`hookAllConstructors` 走的是 `afterHookedMethod`，不是 before 回调）；
- ⛔ **不新增任何写操作**：唯一新增的 SQL 是 `select`，无 `insert/update/delete/execSQL`，
  不写文件、不 `kvset`、不清集合；
- ⛔ **不改任何开关默认值**：`Keys.kt` 一行未动，`chat.auto_hd_img` 默认仍 `"false"`；
- ⛔ **不改现有 HD 原图下载行为**：`fired/ok/already/skipDup/fail` 计数与 `svc.c` 调用完全不变。

### 2.7 已知边界 / 不确定项（不藏）

- `[需真机]` `imgT==1`（`:426 already`）与名单拦截（`:463-470 skipScope`）的提前 `return`
  不走 `:497`。allow/exclude 两键默认 `"[]"`（`Keys.kt:445-446`）⇒ 默认设置下被撤回图必到 `:497`；
  若现场名单配成非空且把这条拦下，则 `HDIMG trace` 不出现，只能退看 `HDIMG sig` / `HDIMG fired`。
- `[推断]` `ctorTrace` 只在 `localId==0` 时构造。若出现「`img=null` 但 `localId!=0`」的异常形态，
  第一行会打 `ctor=n/a(localId!=0)`（拿不到构造参数/栈），但 `HDIMG trace db` 那行照打。
- `[需真机]` 栈帧只保留 `com.tencent.*`；若该构造是由非 `com.tencent` 包装类发起，
  会打 `stack=no-host-frame`，此时需放宽过滤再装一轮（本轮不改）。
- `[需真机]` `t=0 img=null` 的成因：`o2(0)` 查不到对象 ⇒ `img=null`；但 `t=0` 又说明
  `img` 非 null（`Int?` 才会是 0 而非 null），推测是 ImgInfo 对象在但无路径型 String 字段
  （`imgPathOf` 判据 `://` 或长度 24..40，`AutoHdImg.kt:292-302`）。**这正是本轮要证伪的点之一**，
  不在此下定论。
- `[需真机]` 未跑 gradle / 未装包（本 lane 红线）；改动为纯 Kotlin 追加 + 一处函数签名与调用点，
  新增符号 `AntiRevoke.msgQueryHandle()` / `AntiRevokeHint.BYPASS` 与 `AutoHdImg` 同包
  `com.lx.wxgroup.feature`、同模块 `internal`，无需 import；`ifBlank` 在仓库既有代码
  （`ForceForward.kt:496`）已使用。

[DONE] T2（异常分支溯源日志 + 限流 + 只加观测自检）

---

## T3 三档边界表（进入 / 返回 / 复位）

> 三档齐全；② 的 **TTL 时间维度** 与 **冷启进程维度** 各自单列、⛔ 不合并；③ 逐条写
> 「清除条件 + 残留」，⛔ 不写「同返回」。锚点均为只读取证，未改任何这些状态的代码。

### ① 进入（探针从零开始能观测到的三个时刻）

| 时刻 | 发生了什么 | 探针输出 |
|---|---|---|
| **冷启 `install()`** | `AutoHdImg.kt:169-186`：`XposedBridge.hookAllConstructors(t1, ctorHook)` 注册到 `com.tencent.mm.modelimage.t1` 全部构造函数（`afterHookedMethod`），`hostCl=cl`、`installed=true`，打 `HDIMG installed com.tencent.mm.modelimage.t1 ctor x2 (开关 chat.auto_hd_img)` | 只有这一行 `HDIMG installed`。⚠️ **注册时完全不读开关**，开关在 `afterHookedMethod` 第一行 `:192` 才读 ⇒ hook 在但开关关时零业务日志 |
| **撤回消息刚到达** | **verity** 撤回：`AR blocked revoke`（`message` 行保住）、`RRV record` + `imgAudit`、`VFG protectMedia ttl=600000ms pool=1`（`th_` 缩略图入 10min 保护池）、界面角标「已阻止撤回 · 图片」 | ⛔ **本探针此刻零输出**：此时还没人点开，微信没建 `t1`。题目给的已证事实「被撤回图这次没有 `HDIMG sig` 行」正说明撤回路径不走 `t1`。`HDIMG sig` 只在正常图到达时由微信自己下压缩图触发（`:242`） |
| **首次点开被撤回图**（题目 00:13:26.067） | 微信建 **`(0, msgId)` 形态** `t1` → `:192` 开关放行 → `:204-213` 字段优先得 `localId=0 / msgId=19640543` → `:259` `done` 收录 → `:264` 抓 `ctorTrace` → `pool.execute` → `fire()` 里 `o2(0)` ⇒ `imgPath=null` → `svc.c` → `HDIMG fired … localId=0 t=0 img=null` | 紧跟 `HDIMG fired` 打 **两行** `HDIMG trace`（构造参数/字段/微信栈 + `HDIMG trace db imgPath=…`）。这是本轮唯一的新证据入口 |

**进入的前提**：`chat.auto_hd_img` 必须是**开**的（`:192`），否则三档全塌成零输出（见 §T4 第 4 条）。

### ② 返回（用户再操作，探针还打不打）

三个「再操作」场景，先给共性再分别列：

| 场景 | 是否重打 `HDIMG trace` | 说明 |
|---|---|---|
| **撤后几分钟再点开** | ⛔ 同一进程内**不重打** | `traceSeen` 按 msgId 去重（`:400`）+ `done` 按 msgId 去重（`:259`）双重占位。⛔ 这是**刻意设计**（防刷屏），不是「探针失效」。要看新证据只能换新 msgId 或冷启 |
| **切页回来再点开** | ⛔ 不重打 | 同上，进程没死 ⇒ 集合还在。`HDIMG fired` 是否再出现取决于微信是否再建 `t1`（`done` 会挡成 `skipDup`） |
| **下拉刷新后再点开** | ⛔ 不重打 | 同上。下拉刷新只刷会话列表，**不清**进程内 `traceSeen` / `done` / 保护池 |

#### ②-A TTL 时间维度（单列，⛔ 不与冷启合并）

| 维度 | 常量 / 锚点 | 过了窗口后对本探针的影响 |
|---|---|---|
| **保护池 10min** | `VoiceFileGuard.PROTECT_MEDIA_MS = 600_000L`（`VoiceFileGuard.kt:112`），`protectedMedia`（`:109`），`anyAlive` 判活（`:301`） | 10min 后 `hasMedia=false` ⇒ `VFG` 不再拦 `MEDIA delete` ⇒ **磁盘 `th_` 缩略图可能被删**。但 `message.imgPath` 是 **DB 行值**，不随保护池过期 ⇒ `HDIMG trace db` 仍打 `THUMBNAIL_DIRPATH://th_…`。⇒ 这一维把「**库里有路径 / 磁盘没文件**」分开，是下一轮「直接喂本地文件」路线的关键判据 |
| **imgAnchor 30min** | `AntiRevoke.IMG_ANCHOR_TTL_MS = 30 * 60_000L`（`AntiRevoke.kt:494`），`imgAnchor`（`:229`） | ⚠️ **不是定时器硬过期**：只有 `imgAnchor.size > 256` 时才 `removeIf { now-at > 30min }`（`:468-470`），`imgAnchorOf()` 本身不按年龄过滤（`:247`）。淘汰后 `RevokeRescue.auditImage` 的 `svr=0`、只按 `msglocalid=?` 查 ⇒ `RRV imgAudit row=0` 更必然。**本探针不读 imgAnchor**（直接按 `message.msgId` 查 imgPath）⇒ `HDIMG trace db` **完全不受这一维影响** |

**②-A 与 ②-B 的本质差别**：TTL 只让「保护/锚点」失效，**进程内去重集合与账本都还在** ⇒ 不重打；
冷启则把去重集合整体清零 ⇒ 重打。两者不许合并成一句「过一会儿就好了」。

#### ②-B 冷启进程维度（单列，⛔ 不与 TTL 合并）

| 清零对象 | 锚点 | 冷启后的表现 |
|---|---|---|
| `AutoHdImg` 全部进程内状态 | `seen/fired/ok/already/skipScope/skipDup/skipOther/noMsgId/fail`（`:86-93`）、`done`（`:142-152`）、`sigSeen/missSeen/mismatchSeen`（`:108-121`）、**`traceSeen`（`:133`）**、`bucketsAt/Groups/Labels`（`:393-395`）、`hostCl/installed`（`:72,79`） | 全部回初值 ⇒ `HDIMG installed` 重打；**同一 msgId 的 `HDIMG trace` 可以再打一次**（取证窗口重开，这是冷启唯一「有用」的一面） |
| `MediaTableGuard` 账本（lane A 域，只读引用） | `seen/matched/blocked/skipped/events/logAt/cachedTokens/gen/lastIssuedMs` 整体清零（`docs/_r52_revokeimg_rootcause.md:153`；`docs/_r51_media_table_guard_impl.md:231`） | 冷启后首撤 `cachedTokens` 恒空 ⇒ `onBefore` 的 `tokens.isEmpty()` 闸必放行；`enabled` 由 `install()` 重新 `refresh()` 读入 |
| `VoiceFileGuard.protectedMedia` 保护池 | `VoiceFileGuard.kt:109` | 整体空 ⇒ `VFG` 不再保任何 `th_`；若微信趁进程死做了 GC，`th_` 已不在盘上 |

**残留（冷启不清的）**：Kv 里三个开关值、`message` 表的行与 `imgPath` 列值、磁盘上尚未被删的
`th_` 文件、已落盘的 `wxgroup_dbg.txt` 日志内容 —— 都**不随进程死而清**。

### ③ 复位（逐条：清除条件 + 残留，⛔ 不写「同返回」）

| # | 复位项 | 清除条件 | 残留（⛔ 每条单独写） |
|---|---|---|---|
| 1 | **关 `chat.auto_hd_img`** | `kvset chat.auto_hd_img=false`（或 UI 关）；`afterHookedMethod` 第一行 `:192` `if (!Kv.getBool(...)) return` 立即生效，无需冷启 | hook **仍注册**（`HDIMG installed` 不重打也不卸）、`Kv` 值 false、已打的日志行仍在、`traceSeen`/`done`/`sigSeen` 内存集合**不清**（只是不再有人进来）⇒ 回到「零观测」但非「零挂载」；下次开回来同一 msgId 仍被 `traceSeen` 挡住 |
| 2 | **关 `antirevoke.msg`** | `kvset antirevoke.msg=false`；`AntiRevoke` 在 kvset 刷新清单内（`EntryReceiver.kt:176-200`）⇒ 内存即时刷新，下一张撤回即不拦 | **已保住的 `message` 行不会被反向删掉**（无回滚写）⇒ `HDIMG trace db` 仍查得到 `imgPath`；但**新撤**的图不再保行 ⇒ 那条会打 `no row`。`HDIMG` 探针本身不读这个开关，照常打 |
| 3 | **关 `antirevoke.media_delete`** | `kvset antirevoke.media_delete=false`；⚠️ `MediaTableGuard.refresh()` **不在** kvset 刷新清单（`EntryReceiver.kt:176-200`），内存 `enabled` 要等 `install()`（冷启）或下一次 `invalidate()`（撤回 `record()`）才刷（`MediaTableGuard.kt:121,146-148`） | 已拦下的删除**不回滚**；关后到刷新生效之间仍可能拦（同源「关开关后仍可能拦」，方向相反）；新撤的图媒体表 `delete` 不再被拦 ⇒ `ImgInfo2` 行更可能被删。`HDIMG trace db` 查的是 `message` 表，**不直接受这个开关影响** |
| 4 | **保护池 10min TTL** | `now > protectedMedia[token]`（`PROTECT_MEDIA_MS=600_000L`，`VoiceFileGuard.kt:112`）；`anyAlive` 变 false ⇒ `hasMedia=false`（`:301`） | 仅**磁盘 `th_` 文件**失去 `VFG blocked MEDIA delete` 保护、可能被微信删；`message.imgPath` 列值、`ImgInfo2` 行、`traceSeen` 去重集合全部不受影响 ⇒ `HDIMG trace db` 仍打得到路径，形成「库里有 / 盘上没」的对照 |
| 5 | **imgAnchor 30min TTL** | `imgAnchor.size > 256` 时 `removeIf { now-at > 30*60_000L }`（`AntiRevoke.kt:468-470,494`）；⚠️ 非定时器、不满 256 条永不淘汰 | 淘汰后 `imgAnchorOf(msgId)` 返回 null ⇒ `auditImage` 退化成只按 `msglocalid=?` 查、`RRV imgAudit row=0` 更必然；**本探针不读 imgAnchor** ⇒ `HDIMG trace` 两行完全不受影响。残留的是「审计通道依旧查不到」这个假象，别拿它当「行被删」的证据 |
| 6 | **账本冷启清零** | 微信进程冷启 / `am force-stop` 后重拉起（进程内存整体重建） | `AutoHdImg` 全部计数器 + `done`/`sigSeen`/`missSeen`/`mismatchSeen`/**`traceSeen`**/`bucketsAt` 清零；`MediaTableGuard` 账本 4 计数 + `events`/`logAt`/`cachedTokens`/`gen`/`lastIssuedMs` 清零；`protectedMedia` 池清空。残留 = Kv 开关值、`message` 表行/列值、未删的 `th_` 文件、落盘日志内容 ⇒ **同一 msgId 可再打一次 `HDIMG trace`** |
| 7 | **hook 随进程死才卸** | 微信进程死亡 / LSPosed 模块重载 / 强制停止；`install()` 没有 unhook 路径（`hookAllConstructors` 返回的 set 未保存也未 `unhook`，`AutoHdImg.kt:168`） | 无残留 hook（完全消失），但 Kv 值、日志文件、`message` 表行都在；下次冷启 `install()` 重新注册并再打一行 `HDIMG installed`。⛔ 没有「关开关卸 hook」这回事 |
| 8 | **清缓存** | 主动 `LogX.reset()` 广播 / 删除 `wxgroup_dbg.txt`（`LogX.kt:108-117` 清空落盘日志并把 `lines/lastMsg/lastMs` 归零），或微信「设置 → 存储空间 → 清理缓存」删磁盘图片缓存 | `traceSeen`/`done` 内存集合**不因清日志而清** ⇒ 同一 msgId 仍不打 `HDIMG trace`（要冷启才重开窗口）；微信清缓存会删 `th_` 缩略图**但不删 `message` 表行** ⇒ `HDIMG trace db` 仍能打出行里的 `imgPath`。⛔ 与 #9 的区别：这是**主动清**，#9 是**被动溢出** |
| 9 | **日志 5000 行环形覆盖导致旧证据消失**（项目简称；真实行为＝满 5000 行 `writeText` 整文件清空重来 + 留 `log rotated` 标记 + `lines=0`，⛔ 非逐行 FIFO） | `lines >= MAX_LINES(5000)` ⇒ `writeText` **整文件清空** + 只留一行 `---- log rotated: 已达 5000 行，清空重来（最新日志始终保留）----` + `lines=0`（`LogX.kt:83-88`）；叠加 `K7 perf` 每秒刷屏几分钟即触发；`DEDUP_MS=1000` 只挡连续同内容，不挡刷屏 | **清空前的全部业务行永久消失且不可重放**（撤回当秒的 `RRV record` / `VFG blocked MEDIA delete` / `AR blocked revoke` / 首条 `HDIMG trace`），只剩轮转点之后的新行；`traceSeen` 内存集合不受影响 ⇒ 不会自动重打 ⇒ 想再取证只能冷启或换新 msgId。⭐ **那行 `log rotated` 标记本身是排除项判据**：`dev.py q` 拉不到业务行时，**有标记＝日志确实打过但被整批清空**，可直接排除「钩子没被调用 / 广播没投递 / 模块没加载」；无标记且无业务行才需要怀疑探针全哑。⛔ 本轮按 msgId 去重 + 只打 2 行，就是为不加速触顶（`docs/_r52_device_log_snapshot.md:4-6` 已吃过这个亏） |
| 10 | **已被删的 DB 行 / 文件不可回滚** | **没有复位动作**（不适用） | 图片/视频分支只有 `protectMedia` + `auditImage`，**无 backup/restore**（`RevokeRescue.kt:121-129`），语音才有三层；`VFG` 只覆盖 Java `File.delete`，拦不到 native `unlink`。一旦 `message` 行真被删 / `th_` 被 unlink，`HDIMG trace db` 只能打 `no row`，**任何开关、TTL、冷启都救不回**。⛔ 别拿 23:55 那张旧图当验收样本（`docs/_r52_device_log_snapshot.md:66-67`） |

[DONE] T3（三档边界表 · TTL 与冷启维度分列 · 复位 10 条逐条清除条件+残留）

---

## T4 回滚路径 + 装包前复核要点

### 4.1 回滚：只 revert 一个文件即可回到现网零观测状态

本轮**唯一源码改动**就是 `wxgroup/app/src/main/java/com/lx/wxgroup/feature/AutoHdImg.kt`：

```
git checkout -- wxgroup/app/src/main/java/com/lx/wxgroup/feature/AutoHdImg.kt
```

- 已用 `git status --short` 复核：改前该文件**不在** modified 列表里（HEAD 干净），
  单文件 checkout 精确还原到装包前版本，不会带进/带出任何别的东西。
- revert 后：`HDIMG trace` 两行**完全消失**；`HDIMG installed` / `HDIMG sig` / `HDIMG fired`
  恢复原文；`fire()` 恢复 2 参签名；`traceSeen` + `ctorTraceOf` / `readMsgImgPath` /
  `traceIfAbnormal` 三个 helper 全消失；那条只读 `rawQuery` 也消失。**零观测、零行为变化**。
- 报告 `docs/_r52_probe_b_impl.md` 是新建未跟踪文档，删掉即可，与源码无关。

⛔ **严禁** `git checkout .` / `git reset --hard` / `git clean -fd`：工作区里还有 lane A 独占的
`entry/EntryReceiver.kt`（已 M）、`feature/MediaTableGuard.kt`（未跟踪）、以及 `Keys.kt`、
`RevokeRescue.kt`、`HookEntry.kt` 等**大量在途未提交改动**，全量回退会直接毁掉别人的工作。
回滚只针对上面那**一个**路径。

### 4.2 探针会不会拖慢点击热路径

| 路径 | 新增开销 | 结论 |
|---|---|---|
| **正常图（绝大多数）** | `:264` 一次 `if (localId == 0L)` 比较（false ⇒ 不抓栈、不拼串）；`:497` → `traceIfAbnormal` 第一行 `:399` 直接 return（3 次比较）；**零 DB 查询、零 `LogX.w`** | 可忽略。`HDIMG fired` 那行照打，不多一行 |
| **被撤回图（异常，本轮目标）** | 微信线程上 `Throwable().stackTrace`（约 1ms 级）+ 5 次 `XposedHelpers` 字段读 + 一个 `StringBuilder.take(600)`；`pool` 线程上 2 行 `LogX.w` + **一次单行只读 `rawQuery`**（`message where msgId=? limit 1`，走索引） | 一次点击约一次 1ms 级主线程开销 + 一次池线程单行读；**均不在下载/渲染判定链上**，且被 `done`（`:259`）与 `traceSeen`（`:400`）双重去重 ⇒ 同一 msgId 只付一次 |

- ⚠️ 唯一落在主线程的新增动作是 `Throwable().stackTrace`，已被 `localId == 0L` 闸死，
  正常图完全不碰；`pool.execute` 之后的重活仍全在池线程（与原有 `fire()` 一致）。
- `LogX.w` 内部 `synchronized(lock)` 写文件，但只 2 行 / msgId、上限 64 个 msgId，
  ⛔ 不会造成 `K7 perf` 那种每秒刷屏，反而是为不加速「满 5000 行整文件清空重来」才限流的
  （与 lane A 的 `obsLogAt` 60s 节流叠加后仍会加快触顶，见 §T2.4）。
- ⛔ 未跑 gradle / 未装包（本 lane 红线），以上为静态推导，性能量级标 `[推断]`，`[需真机]` 复核。

### 4.3 会不会与同方法上其它 callback 冲突（⛔ 不新增 `param.result` 赋值点）

- 本 hook 是 `XposedBridge.hookAllConstructors(t1, ctorHook)` 的 **`afterHookedMethod`**
  （`AutoHdImg.kt:168,181`）：构造函数是 void，回调在**原构造执行完之后**跑，
  不存在「before 里设 `param.result` 跳过后续 before callback」的语义。
- `AutoHdImg.kt` **改前、改后都零个 `param.result` 赋值点**（`Grep 'param\.result'` 全仓复核，
  本文件 0 命中；命中都在 `AntiRevoke` / `MediaTableGuard` / `VoiceGuard` 等**别的方法**上）。
  ⇒ ⛔ **未新增任何 `param.result` 赋值点**，不会影响任何同方法上的其它 callback 顺序。
- 全仓 `Grep 'modelimage.t1|hookAllConstructors'` 只有 `AutoHdImg.kt:57,168` 一处引用，
  模块内**没有第二个** hook 挂在 `t1.<init>` 上。`x2` 那两个构造函数回调都是本对象自己的。
- lane A 的 `MediaTableGuard` 设 `param.result` 是在 `SQLiteDatabase.delete/execSQL` 上
  （`MediaTableGuard.kt:245,248`），与 `t1.<init>` 是**完全不同**的方法，无交互。
- `selfFiring`（`:105`）/`done`（`:142-152`）递归防护原样保留；`ctorTrace` 特意放在 `done`
  去重（`:259`）**之后**，我们自己 `fire()` 触发的二次 `t1` 构造会被 `skipDup` 挡掉，
  不会重复抓栈、也不会触发第二次 `HDIMG trace`。
- `[推断]` 若设备上有**外部** LSPosed 模块也 hook 了 `t1.<init>` 并在 before 里设 `param.result`，
  那不属于本模块可控范围；我们的 `after` 回调仍会执行，且不写 `param.result`，不会加剧冲突。

### 4.4 `chat.auto_hd_img` 关闭时探针还能不能观测到东西 —— **不能，一行都没有**

`afterHookedMethod` 第一行就是 `if (!Kv.getBool(Keys.CHAT_AUTO_HD_IMG)) return`（`:192`），
在 `seen.incrementAndGet()`（`:195`）、`HDIMG sig`（`:242`）、`HDIMG fired`（`:482`）、
`HDIMG trace`（`:497`）**全部之前** ⇒ 开关关时 `HDIMG installed` 仍旧在冷启打一行（注册不读开关），
但**业务日志零输出**，连「这次点开有没有建 `t1`」都看不到。
且该键默认值就是 `"false"`（`Keys.kt:442`）⇒ **新装包后若现场没手动打开，本轮直接白装**。
这是装包前**第一复核项**，也是 §T1 结论的落点。

### 4.5 装包前复核清单（给 team-lead 统一装包用）

1. ✅ `chat.auto_hd_img` **保持开**（`kvset true` + `kvget` 回读 true）；默认 false，别假设它开着。
2. ✅ `antirevoke.msg` 开（保 `message` 行，`HDIMG trace db` 才有得查）；`antirevoke.media_delete`
   现场值记下来（只影响磁盘 `th_` 在不在，不影响 `message` 表查询）。
3. ✅ `chat.auto_hd_allow` / `chat.auto_hd_exclude` 保持默认 `"[]"`，否则名单分支（`:463-470`）
   可能提前 `return`，`:497` 的 `HDIMG trace` 就不出现。
4. ✅ 请 **verity** 在「**测试群聊**」重发一张**新**图，撤后隔 3~5 分钟**点开**；
   ⛔ 别拿 23:55 / 00:13 那两张旧图当样本（已不可逆，`docs/_r52_device_log_snapshot.md:66-67`）。
   点开前记下 msgId，方便对日志。
5. ✅ **取证窗口两头收口**：测前先 `op=logclear`（`EntryReceiver.kt:165-167` → `LogX.reset()`，
   清掉旧日志并归零 `lines/lastMsg/lastMs`）缩短窗口；点开后**立刻**拉 `dev.py q 'HDIMG'`
   （必要时 `'HDIMG|RRV|VFG|AR'`）：`K7 perf` 每秒刷屏 + **两 lane 观测日志叠加**会更快触发
   「满 5000 行整文件清空重来」，别等。⚠️ 若 `dev.py q 'log rotated'` 只看到那行轮转标记而无业务行
   ⇒ 证据被整批清空（**可排除「钩子没被调用 / 广播没投递」**），⛔ 别据此下「探针全哑」结论，
   重清后再测一次。`[需真机]` 兜底：窗口实在抢不到时可用 lane A 新增的 `op=mtg sub=stat` 读账本
   （账本在进程内存，**不受日志清空影响**），但账本只作定位、⛔ 不作判据。
6. ✅ 冷启后确认日志里有 `HDIMG installed com.tencent.mm.modelimage.t1 ctor x2 (开关 chat.auto_hd_img)`；
   若出现 `HDIMG class missing com.tencent.mm.modelimage.t1 (disabled)` ⇒ 类名漂移，本轮探针全哑。
7. ✅ 复核 `git diff -- wxgroup/app/src/main/java/com/lx/wxgroup/feature/AutoHdImg.kt` 只有 §T2.1
   那 6 处改动；确认**没有**误碰 `MediaTableGuard.kt` / `EntryReceiver.kt`（lane A 独占）。
8. ✅ 本 lane 未跑 gradle / 未装包 / 未 `kvset` / 未改任何开关默认值，装包由 team-lead 统一执行。

[DONE] T4（回滚路径 + 热路径开销 + callback 冲突 + 开关关闭时零观测 + 装包前复核清单）

---

## T5 判据建议（≤5 行，对着用户能看见的事实写）

> 下一轮真机统一拉 `dev.py q 'HDIMG'`；⛔ 判据只对着「**用户点开能不能看到大图**」这个界面事实 +
> 下面这几行日志，⛔ 绝不拿 `seen/fired/row=0/blocked` 等内部计数当结论。

1. **前提**：先 `dev.py q 'log rotated'` 确认**没有**轮转标记（有标记＝业务行被整批清空、判不了，⛔ 不能当成「没被调用」）；无标记且点开后**连 `HDIMG fired` 都没有**（也无 `HDIMG trace`、只有冷启的 `HDIMG installed`）⇒ **大图组件压根没被构造**，用户看不到大图。
2. 有 `HDIMG fired` 但 `HDIMG trace db imgPath=no row` / `null col` ⇒ **微信从 `message` 表拿不到 imgPath**，用户看不到大图；若打 `no db handle` 只是探针没抓到主库句柄、**判不了**，先 `op=label sub=on` 补抓 `[需真机]`。
3. 有 `HDIMG trace db imgPath=THUMBNAIL_DIRPATH://th_…`（库里有，00:13 已手工 SQL 证过）却仍 `HDIMG fired … img=null localId=0` + `HDIMG trace … ctor=a[0]=Long:0 fld[h=0 q=<msgId>]` ⇒ **拿到了但被上层丢弃**（微信建的是 `(0,msgId)` 形态 t1，没把路径/localId 喂进大图链路），用户点开**仍看不到大图** `[推断]`。
4. 对照正常未撤回图：`HDIMG sig` 有、`HDIMG fired … localId≠0 img=SERVERID://…`、**无** `HDIMG trace` ⇒ 用户**能看到大图**；被撤回图缺 `sig` 且多出两行 `trace` ⇒ 差别锁定在「上层没喂路径」而不是「CDN 下载失败」 `[需真机]`。
5. 三支互斥的读法：无 `fired`＝没构造；有 `fired` 且 `db=no row`＝库里没有；有 `fired` 且 `db` 有路径却 `img=null localId=0`＝上层丢弃。三者都以「界面能否看到大图」收口，⛔ 不以任何计数器收口；**均须先过第 1 条的 `log rotated` 前提**。

[DONE] T5（判据建议 ≤5 行）

---

## 交付小结

- **改动文件**：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/AutoHdImg.kt`（唯一源码文件）。
- **新增日志 TAG**：`HDIMG trace`（两行：`HDIMG trace … | ctor=…` 与 `HDIMG trace db … imgPath=…`）。
- **报告**：`docs/_r52_probe_b_impl.md`（本文件，分步落盘）。
- **红线复核**：未碰 `MediaTableGuard.kt` / `EntryReceiver.kt`；未碰 adb / 未装包 / 未跑 gradle /
  未写微信库 / 未改开关默认值 / 未改 HD 原图下载行为 / 未新增 `param.result` 赋值点 /
  未新增任何写操作（唯一新增 SQL 为只读 `select`）。

## 协作修订补记（r52-probe-a 反馈）

- r52-probe-a 复核 `LogX.kt:22,79-88,99-100` 确认：项目简称「环形覆盖」的真实行为是
  `:83-88` 的 `writeText` **整文件清空 + 留一行 `---- log rotated: 已达 5000 行，清空重来 ----`
  标记 + `lines=0`**，⛔ **非逐行 FIFO 挤掉**；源码注释 `:81` 把两者并称、
  `docs/开发回路速查.md:111` 沿用了简称。本报告已把全部「环形覆盖」措辞精确化
  （头部术语口径 + §T2.4 + §T3 #9 + §T4 4.2 / 复核第 5 条）。
- ⭐ 采纳其发现的 **`log rotated` 标记判据**：`dev.py q` 拉不到业务行时，有标记＝日志确实打过但
  被整批清空，可直接排除「钩子没被调用 / 广播没投递 / 模块没加载」。已补进 §T3 #9、
  §T4 复核第 5 条，并**修掉 §T5 第 1 条原有漏洞** —— 原写法「无 `HDIMG fired` ⇒ 大图组件压根
  没被构造」在日志被整批清空时会误判，现加「先确认无轮转标记」前提，第 5 条同步收口。
- 采纳其节流叠加口径：lane A = 独立 `obsLogAt`（与 `logAt` 分离）+ `table+reason` 60s 节流 +
  `MAX_LOG_KEYS=64` 封顶 ⇒ 最坏约 64 行/60s（≈1 行/秒）另加 4 个固定 key；lane B = 2 行/异常
  msgId、上限 64。两 lane 叠加确实更快触顶，预防＝测前 `op=logclear`（已核实
  `EntryReceiver.kt:165-167` → `LogX.reset()`）+ 缩短取证窗口 + `[需真机]` `op=mtg sub=stat`
  读进程内存账本兜底（账本**不受日志清空影响**，但只作定位、⛔ 不作判据）。
  ⚠️ 以上均为**报告措辞 / 装包前复核 / 判据口径**修订，**源码 `AutoHdImg.kt` 一行未改**，
  T1~T5 实现结论、改动范围、回滚路径全部不变。
- 双方确认无源码冲突：lane A 全程未碰 `AutoHdImg.kt`；本 lane 的 `HDIMG trace db` 是只读
  `select`、不写库，不影响 `op=mtg sub=stat/reset` 账本语义；lane A `when(sub)` 有显式只读
  `else`，sub 拼错只打一行日志、不掉进写库分支。

[DONE] 协作修订（措辞精确化 + `log rotated` 判据 + T5 第 1 条漏洞修复）

[DONE] ALL
