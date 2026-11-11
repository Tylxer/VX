# R49 · R-C「复用写时拦截」可行性判定探针设计（lane D · 只读）

> 只读设计产物：不实现 R-C、不写生产逻辑、不碰 R-A/R-B、不碰 adb/真机/Gradle/装包/kvset。
> 唯一目标：设计一套探针，把「R-C 可实现 / R-C 证伪 / 证据不足」落到三态判定。
> 纪律：每条结论附 `文件:行号`；不确定标 `[推断]` / `[需真机]`；不整读 >300 行文件。

## 待答清单（每答完 2 问立刻追加落盘，逐步留 `[DONE] <子步>`）
- [ ] F1 静态事实核查：R-C 定义 / 生死假设 / 已知写事件 / 效果上限（`_r49_3day_forensics.md` Q3/Q4/Q6/Q7）
- [ ] F2 挂点与入口定位（到类与方法级）：`snsBlockGuard` / `keepAliveSource` / `SnsSqlRewrite` / `attachPrefix` / `runReadOnlySql` / `snsDb` 捕获
- [ ] F3 现有探针与日志规范：dev.py 子命令 / 广播 op / TAG / 限流 / 环形覆盖 / 三档边界（`CLAUDE.md` + R42 + lane C）
- [ ] F4 前置 SQL 与 BLOB 纪律：§3.2 四条只读 `op=sql`、`hex()` 而非 `cast`、snsDb 独立库、createTime 单位
- [ ] P1 判据树 + 决定性（一票否决）判据 + 最少判死/判活探针集
- [ ] P2 探针矩阵（库 / 写事件 / 触发归因 / 界面 / 时序 / 对照 / 干扰排除，七层）
- [ ] P3 一次性装包清单 + 探针开关（默认关 / 按需开）
- [ ] P4 输出格式与日志 TAG 规范（可照抄 / 去重键 / 封顶 / 环形规避）
- [ ] P5 判定表（三态映射，明确「什么等于证死」）
- [ ] P6 三档边界表（进入 / 返回 / 复位，复位含磁盘位不回滚 / 重复源）
- [ ] P7 最小可执行步骤（dev.py 子命令 / 广播 op / logclear / 用户配合 / 前台）
- [ ] P8 证据分级 + 已知盲区

[START]

> 锚点缩写：`SnsGuard.kt` = `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsGuard.kt`；`EntryReceiver.kt` = `.../entry/EntryReceiver.kt`；`dev.py` = `tools/dev.py`；`moments.md` = `docs/conclusions/moments.md`；`forensics` = `docs/_r49_3day_forensics.md`；`wm` = `docs/_r42_wechatmagician.md`；`laneC` = `docs/_r49_revokeimg_reference.md`。
> 好友一律用昵称占位：「昵称A」=设三天可见的好友、「昵称B」=全部可见阴性对照好友、「昵称C」=自己；wxid 仅作为 SQL 参数出现，汇报时不许裸报（`CLAUDE.md:12-18`）。

---

## F1 静态事实核查（R-C 定义 / 生死假设 / 写事件 / 效果上限）

- **R-C 定义**（本轮唯一目标，不碰 R-A/R-B）：扩展 `SnsGuard.snsBlockGuard`（`updateWithOnConflict` **before**，`SnsGuard.kt:163`, `:297-298`）的判据，对三天外行把 `sourceType` 用 `SnsBitLogic.keepAliveSource`（`SnsBitLogic.kt:122-126`）写回 `ContentValues`；`execSQL` before 把 `& -9` 改写为 `& -1`（`SnsSqlRewrite.kt:42`, `:45-48`）；必要时 `attachPrefix` 同款 BLOB 写补 content（`SnsGuard.kt:716`, `:705-714`）。定义锚点 `forensics:185`（Q4 R-C 列）、`forensics:195`。
- **★ 生死假设（静态推断，本套探针要判的核心）**：三天可见是**服务端过滤**，范围外 `SnsObject` 根本不下发（`forensics:189`, `:212`, `:345`转引自 `docs/未完成项与计划.md`）；请求 `ic5`(11 字段)/响应 `jc5`(20 字段)/`SnsObject` 全无 day/limit/visible 字段（`forensics:211`, `:348`转引）；相册 SQL 本来就没有 createTime 窗口，两套 WHERE 是 `sourceType & 2 != 0` / `sourceType in (64 含 bit3 值)`（`forensics:213`, `moments.md:231`, `:248-249`）。⇒ 微信本地**根本不会**对这些行执行 `update`/`execSQL` ⇒ before 钩子没有触发机会（空转）⇒ 整条路线证伪（`forensics:189`, `:239`, `:259`, `:260`, `:272`）。
- **对比（R42 写时拦截为什么能成）**：好友删动态时微信**主动写** `sourceType=0`（真实通道是 `update("SnsInfo",{sourceType=0})`→`updateWithOnConflict`，不是 DELETE，`SnsGuard.kt:34-36`；`moments.md:1-19` 三层实测；`wm:12-24` WechatMagician 同源范式）；三天可见**没有这个写**（`forensics:189`, `:345`转引）。这一"有写/无写"对比正是判死判活的分水岭。
- **Q3 前置（未验，`[需真机]`）**：全仓找不到任何真机/库层取证证明「三天外动态本地 `SnsInfo` 仍保留行」（`forensics:113-129`, `:267`）；`未完成项与计划.md:374` 真机验 6.3 仍在待办、`派发审计台账.md:1167` 把「三天可见截断信号」列为待取证（`forensics:126-127`）。Q3 是 R-A/R-B/R-C **共有唯一前置**（`forensics:271`, `:273`）。
- **已知唯一可能有触发机会的写事件**：进好友主页**每进一次**微信执行 6 条 `& -9` 清 bit3（`moments.md:24-25`「`& -9` 不是只在删除流程执行：进一次好友主页就执行 6 条（首屏 + 分页），按 `snsId` 区间分页、`sourceType in (64 含 bit3 值)`」；`SnsGuard.kt:59-63`；`SnsSqlRewrite.kt:10-13` 原文形态）。⚠️ 这是**按好友批量清位**，R-C 唯一可能落地的写事件，必须专门观测它是否落在三天外行上（P2·W2）。
- **效果上限（已知硬事实，不得当失败）**：保住 `sourceType` ⇒ **翻页查表可能翻到，首屏仍解决不了**（`wm:40-42`；`moments.md:258-263` 混合模型：首屏/刷新走响应内存、翻页走 `d2.t5` 查表；`forensics:53`, `:222`）。库状态与服务端不一致（`wm:42`）。⇒ 探针判据必须**首屏/翻页分离**（P2·U3），不许把首屏空误读成 R-C 失败。
- **协议/数据通路底座（已取证）**：`SnsObject` 53 字段无 Source/SourceType，`sourceType` 是客户端落库位字段（`moments.md:234`）；时间线取数 SQL 一律带 `sourceType & 2 != 0`（`moments.md:231-233`）；`snsId` 无唯一约束、索引 unique 全 0（`moments.md:214-217`）⇒ 补 INSERT 会造重复行；18 列全名（`moments.md:204-209`）。

[DONE] F1

---

## F2 挂点与入口定位（到类与方法级）

宿主 = `com.tencent.wcdb.database.SQLiteDatabase`（`SnsGuard.kt:136-139`；`wm:12` 同挂点）。朋友圈是**独立库** `SnsMicroMsg.db`，不是 `EnMicroMsg.db`（`SnsGuard.kt:69-70`；`moments.md:41`）。

| 挂点（方法级） | 半场 / 回调 | 现职责 | 锚点 |
|---|---|---|---|
| `hookAllMethods(cls,"delete")` | before `deleteGuard`→`onDelete` | 物理 DELETE 旁证计数、不拦 | `SnsGuard.kt:149`, `:214-219` |
| `hookAllMethods(cls,"execSQL")` | before `sqlGuard`→`onExecSql` | 方案 B：`& -9`→`& -1` 改写点 | `SnsGuard.kt:152`, `:221-226`, `:59-63` |
| `hookAllMethods(cls,"updateWithOnConflict")` #1 | after `snsUpdateGuard`→`onSnsUpdateAfter` | 空函数（补位随乙方案删） | `SnsGuard.kt:158`, `:236-241`, `:267-268` |
| `hookAllMethods(cls,"updateWithOnConflict")` #2 | **before `snsBlockGuard`→`onSnsUpdateBefore`** ★R-C 扩展点 | 摘键/写回 `sourceType`、after 形态自证 | `SnsGuard.kt:163`, `:297-307` |
| `hookAllMethods(cls,"insertWithOnConflict")` / `insert` | after `snsInsertGuard` | 新动态首次落库通道计数 | `SnsGuard.kt:168-173`, `:249-254` |
| `hookAllMethods(cls,"rawQuery")` | before `captureHook` | **拿到 `snsDb` 句柄即自我卸载**（非常驻） | `SnsGuard.kt:986`, `:997-1018`, `:1000-1004`, `:1016-1017` |

- 常驻的只有 6 个（delete / execSQL / updateWithOnConflict×2 / insert×2），非 `SnsInfo` 第一句零反射短路、query 一律不挂（`SnsGuard.kt:178-181`, `:289-295`）。
- **写回判据链**：`onSnsUpdateBefore`（`SnsGuard.kt:299`）→ `SnsBitLogic.keepAliveSource(old)`（`SnsBitLogic.kt:122-126`，`old|VISIBLE_MASK`，`old` 含可见位才复活、否则降级摘键）→ after `SnsBitLogic.afterShape`（`:137-178`，四态互斥 BLOCKED_BY_REMOVE / BLOCKED_BY_REWRITE / NOT_BLOCKED / UNKNOWN）。位语义 `& -3`(清 bit1)/`& -9`(清 bit3) 正则在 `SnsBitLogic.kt:182-188`。
- **execSQL 改写链**：`onSnsSql`（KDoc `SnsGuard.kt:62`）→ `SnsSqlRewrite.CLEAR_BIT3_ASSIGN`（`:45-48`，只捕获到 `-9` 常量前片段）+ `NOOP_MASK="-1"`（`:42`）；`SNSID_GT`/`SNSID_LT` 正则（`:50-51`）可抠 `snsId > A` / `snsId < B`。`& -3` 只识别不拦（`:33`）。
- **补 content 链**：`attachPrefix(db,values,api,id,author)`（`SnsGuard.kt:716`）在 before 把 `[已删除]\n` 前缀写进**同一个** ContentValues（`:705-714`），`SnsPrefixProbe.buildPrefixedBlob`（`:722`）；仅两个 BLOCKED 分支调用、幂等（`:713-714`）。⚠️ 本轮**不实现**，仅作可行性边界。
- **只读 SQL 入口**：`EntryReceiver.kt:652-662`（`--es op sql --es q "select …" [--ei n 60]`）→ `SnsGuard.runReadOnlySql(sql,maxRows)`（`:1268`）：白名单 `select`/只读 pragma（`:1271`, `:1253-1262`）、拒 `with`/内部 `;`（`:1255-1262`, `:1277-1279`）、`snsDb` 空返回 `SQLX no sns db yet`（`:1280`）、反射 `rawQuery`（`:1281`）、`clampProbeN` 钳 60 行（`:1282`）。`cellOf`（`:1322-1332`）对 BLOB 只报 `<blob NB>`（`:1328`），`hex()` 返回 TEXT 走 `getString` 输出完整 hex（`:1330-1332`）。
- **事件账本（现成只读探针）**：`op=snsg sub=stat|block|blockstat|trace`（`EntryReceiver.kt:342-386`）→ `SnsBlockProbe`（四路独立计数 W2、纯内存不建表不写盘、RING=50、HIT_LOG_GAP_MS=5s、STAT_GAP_MS=60s、SQL_SAMPLE_MAX=60、SQL_LOG_CHARS=1500、trace 30s×60，`SnsBlockProbe.kt:19-36`, `:43-62`, `:64-76`）；`blockstat` ≤16 行（`:45-46`, `EntryReceiver.kt:373`）。
- **snsDb 独立库边界（lane C 新发现）**：`op=sql` 走 `runReadOnlySql`→`snsDb` 朋友圈独立库，**查不到 `ImgInfo2` 等聊天库表**；`kvget` 走模块自己的配置库、`op=sql` 开的是微信 sns.db（`dev.py:265-266`）；`moments.md:41` 独立库实证。⇒ 库层探针只能查 `SnsInfo`/`SnsComment`，别指望跨库 join。

[DONE] F2

---

## F3 现有探针与日志规范（硬规则 + 踩坑，设计必须规避）

| 约束 | 内容 | 锚点 |
|---|---|---|
| 一次重启抓完 | 取证要**一次重启抓完所有问题**；改动攒齐再上机（改一处上一轮最贵） | `CLAUDE.md:26`, `:62` |
| 限行读取 | Grep 定位 → `Read(offset,limit)`，禁止整读 >300 行文件 | `CLAUDE.md:28` |
| 日志环形覆盖 | LogX **5000 行环形覆盖**（不是停写）；测前必须 `op=logclear`（不用重启微信）；上机三件事：日志有 `installed` / `op=logclear` / 微信前台 | `SnsBlockProbe.kt:31`；`CLAUDE.md:73-79`；`dev.py:124-127`, `:250-253` |
| K7 perf 刷屏 | `K7 perf` 每 250ms 一条会刷屏；**日志字节数增长不能证明探针执行**（刷屏也会涨），真正判据是「目标 TAG 有没有出现」 | `dev.py:292-299`；题面硬约束 |
| 探针必须自带限流 | 同 snsId 5s 只记一条、stat 60s 一条、SQL 原文去重上限 60、**计数不去重** | `SnsBlockProbe.kt:31-36`, `:49-58` |
| 广播投递 | action `com.lx.wxgroup.action.PROBE -p com.tencent.mm`；**微信不在前台 AMS 照回 `Broadcast completed` 但不投递**；判据用 `dbg_size` 增长 + 目标 TAG，不能用 completed | `EntryReceiver.kt:63`；`dev.py:95-96`, `:100-103`, `:22-23`, `:275-289` |
| kvget/kvset | `kvget` 用 `--es key`（读 extra `key`）；`kvset` 用 `--es key --es val`（参数名 key/val，不是 k/v）；`--el` **仅收 signed long**，无符号 snsId 会静默丢弃 | `dev.py:225-226`, `:265-268`；`EntryReceiver.kt:180` |
| 二次解析 | 设备 sh 二次解析 ⇒ 值须再包一层单引号（`select count(*)` 不包直接 syntax error）；`dev.py probe` 的 `bc()` 用 `_sq()` 自动包；**写完回读复核** | `dev.py:20`, `:66-81`, `:84-96` |
| 旧代码自证 | `logclear` 正确判据 = 文件出现 `logclear` **且不含** `unknown op=logclear`；防整轮跑旧代码 | `dev.py:138-152` |
| snsDb 捕获时机 | `snsDb` 需微信先刷一次朋友圈才被捕获，否则 `SQLX no sns db yet`（先让微信刷一次朋友圈）；冷启后句柄丢失需重捕获 | `SnsGuard.kt:1280`, `:1016-1017`；`moments.md:263`；`forensics:173`, `:266` |
| lane C 独立库 | `op=sql`→`snsDb` 朋友圈独立库，别指望查聊天库表 | `dev.py:265-266`；`laneC` 头；`moments.md:41` |
| 三档边界 | 进入 / 返回 / 复位**逐档写**，复位档不许写「同返回」（R42 实证漏掉唯一复位路径会出事故）；复位含库位磁盘态不回滚、对方改回全可见重复源 | `forensics:230-245`；题面硬要求 |
| 判据纪律 | 对着**用户能看见的事实**写，别对着内部计数器；互斥判据别写成并列；好友一律用昵称 | `CLAUDE.md:12-18`；`CLAUDE.md:48-49`(D4)；题面 |
| 过滤工具 | `python tools/dev.py q <正则> [n]`（只拉日志过滤、不编译）、`probe`（清日志→广播→拉→过滤一条龙）、`loop <场景>`（编译装包跑场景）、`ui <文本>`（文本断言替截图） | `dev.py:5-18`, `:10-11`, `:29-33`, `:58`, `:220-299` |
| TAG 大小写 | 只读 SQL 的 TAG 是 `SQLX`（不是 sql），配置读是 `PROBE kv`；grep 大小写敏感 | `dev.py:298-299`；`EntryReceiver.kt:659` |
| 反向对照 | 真机必须做反向对照（关开关再复现一次） | `CLAUDE.md:29` |

[DONE] F3

---

## F4 前置 SQL 与 BLOB 纪律（Q3 §3.2 四判据 + 校准）

`forensics:131-173` 已给四条**只读 `op=sql`** 判据，本套库层探针（P2·L 组）直接复用、不新造：

```sql
-- ① 列/类型核对（只读 pragma，白名单允许），期望 moments.md:204-209 的 18 列
pragma table_info(SnsInfo)
-- ② 三天外行是否还在 + 内容完整性（核心判据；判空一律 length()/hex()）
select snsId, userName, createTime, sourceType, localFlag, type,
       length(content) as clen, length(attrBuf) as alen,
       hex(substr(content,1,16)) as chead, hex(substr(attrBuf,1,16)) as ahex
from SnsInfo where userName='<wxidA>' and createTime < <T0> order by createTime desc
-- ③ 与界面可见条数对照
select count(*) as total, sum(case when createTime < <T0> then 1 else 0 end) as older3d,
       min(createTime), max(createTime) from SnsInfo where userName='<wxidA>'
-- ④ 位状态（决定要不要修位）
select snsId, createTime, sourceType, (sourceType & 2) as bit1, (sourceType & 8) as bit3
from SnsInfo where userName='<wxidA>' and createTime < <T0>
```

- **⛔ BLOB 硬规矩**：`cellOf` 对 `FIELD_TYPE_BLOB` 只打印 `<blob NB>` 不打印内容（`SnsGuard.kt:1322-1329`）；KDoc 记「本轮就因此误判过 `attrBuf` 全为 null，实际两条都是 174 字节」（`:1320`）。唯一正确做法是 SQL 内显式 `hex()`（`forensics:137-138`）。**禁用 `cast(content as text)`**：SQLite `cast(BLOB as TEXT)` 遇 protobuf `0x00` 在首个 NUL 截断，`instr(cast(...),...)` 会假阴性（`forensics:138` 引 `docs/conclusions/moments_cdn.md:350-352` 明令 BLOB 一律 `hex()`；同类 `docs/conclusions/notify_mute.md:63` 用 `instr(lower(hex(roomdata)),...)`）。
- **判定标准**（`forensics:167-171`）：② 返回行数>0 且 `clen>0`/`chead!=''`/`alen>0` ⇒ Q3 成立；② 返回 0 行或 `clen=0`/`chead=''` ⇒ 前提证伪，整题收口；③ `older3d>0` 而界面不显示 ⇒ 坐实「服务端挡界面、本地行还在」；④ `sourceType=0` 或丢 bit1/bit3 ⇒ 才需要修位（R-C 才有意义）。
- **createTime 单位校准 `[需真机]`**：`<T0>` 由三天可见边界的 `createTime` 阈值给出，单位（秒/毫秒）需真机用界面最新一条反推校准（`forensics:140`, `:259`, `:262`）。单位错 ⇒ 整个「三天外集合 S」错 ⇒ 后面所有「是否命中三天外行」的归因全不可信（P1.2）。
- **只读保证**：四条全是 `select`/只读 pragma（`SnsGuard.kt:1268-1282`），**零写库**；`snsDb` 未捕获时按 `SnsGuard.kt:1280` 返回提示，不是探针坏了。
- **独立库边界**：查 `SnsInfo`（朋友圈独立库），别 join 聊天库表（`dev.py:265-266`, `moments.md:41`）；`snsId` 无唯一约束（`moments.md:214-217`）⇒ 任何补 INSERT 观测都要带 `group by snsId having count(*)>1` 查重复。

[DONE] F4

---

## P1 判据树（把「R-C 可实现」拆成必须同时成立的前提）

### 1.1 依赖树（全部 AND；`[需真机]`=本套探针要定的，`[推断]`=静态推理）

```
R-C 可实现  (⇔ P1.1 ∧ P1.2 ∧ P1.3 ∧ P1.4 ∧ P1.5 ∧ P1.6 全成立)
│
├─ P1.1 三天外行本地仍在 + content/attrBuf BLOB 完整   [需真机] ★一票否决（共性前置）
│    └─ P1.2 createTime 单位/T0 边界能正确校准          [需真机]  (P1.1 的测量前提)
│         锚点 forensics:167-169, :271；moments.md:204-209
│
├─ P1.3 三天外集合 S 上「存在」微信本地写库事件        [需真机] ★★决定性·一票否决
│    │    (updateWithOnConflict / execSQL / delete / insertWithOnConflict / insert 任一)
│    └─ 唯一可能来源：进主页 6 条 `& -9` 是否命中 S 的 snsId 区间
│         锚点 forensics:189, :195, :239, :260, :272；moments.md:24-25；SnsGuard.kt:61
│
├─ P1.4 写事件发生时 before 钩子确实被回调/签名匹配     [需真机] ★决定性·一票否决
│    └─ 与 P1.3 互斥归因：区分「压根没写」vs「有写没拦到/挂错类/签名不匹配」
│         锚点 SnsGuard.kt:149-180, :297-298；laneC:55-61(wcdb 重载)
│
├─ P1.5 before 写回 sourceType 后不被二次覆盖/自愈      [需真机]（R42 机制复用验证）
│         锚点 SnsBitLogic.kt:122-126, :137-178；wm:37-42；SnsBlockProbe.kt:22(W1 trace)
│
└─ P1.6 保住位 ⇒ 翻页用户可见（效果上限：首屏仍空）    [需真机]
          锚点 wm:40-42；moments.md:258-263；forensics:222
```

### 1.2 决定性（一票否决）判据，明确点名

- **P1.1 = 0 行 / `clen=0`**：共性前置证伪，R-A/R-B/R-C 一起收口（`forensics:169`, `:271`, `:273`）。
- **★ P1.3 = 三天外集合 S 上零写事件**：这是用户题面点名的**生死假设**坐实 —— 服务端不下发 ⇒ 本地无写 ⇒ before 空转 ⇒ **R-C 证死**。尤其若进主页 6 条 `& -9` 的 `snsId` 区间与 S 求交为空（`moments.md:24-25`），即唯一可能有触发机会的写事件也不落在三天外行上。这是**最核心、最省的一票否决判据**。
- **P1.4 = 有写事件但 before 从未回调**：注意定性 —— 这是**工程挂点问题（挂错类/签名不匹配/钩子未注册）**，不是路线证伪，判「证据不足·需修挂点」，⛔ 不得误记成「没有写事件」。判据纪律：对着事实写、互斥判据别并列（`CLAUDE.md:48-49`、题面）。

### 1.3 最少只要跑哪几条探针就能判死 / 判活

- **判死（最省，2 条）**：`L1/L3`（Q3 §3.2 四条 `op=sql` 建立并校准「三天外集合 S」）+ `W1/W2`（`op=snsg sub=blockstat` 全通道事件账本 + `& -9` SQL 采样，按 `snsId∈S` 离线 join）。
  ⇒ 若 S 非空、但全时序窗口（进入/返回/复位，含进主页 6 条 `& -9`）在 S 上 `rawMethodCnt=0`、`hitS=0` ⇒ **R-C 证死**。再补 `C2/C4` 阴阳/阳性对照（同样 0 装包）即可升格为「强证据证死」，排除探针失效假阴性。
- **判活（4 条，缺一不可）**：`L1/L3`（S 成立）+ `W1/W2`（S 上有写、能定位方法与 SQL 原文）+ `A1/A2`（归因确认 before 在这些事件上确实回调、签名匹配、六钩子齐全）+ `U1/U3`（翻页用户可见、首屏/翻页分离）。缺任一只能判「证据不足/前提成立待验」，⛔ 不许判「可实现」。
- **结论**：判死比判活省得多；判死只需「库层快照 + 写事件账本」，判活还要叠加归因层与界面层。

[DONE] P1

---

## P2 探针矩阵（七层，尽可能全面）

> 通用约定：每行探针**默认只读不写微信库**（账本纯内存 `SnsBlockProbe.kt:28`，`op=sql` 白名单 `SnsGuard.kt:1271`）。输出统一走 P4 的 `RCP_*` TAG、单窗口前 `op=logclear`、窗口后 `dev.py q 'RCP_|SQLX|SNSG|SNS '` 拉回本地。下表「只读」列除标注外均为「只读·不写微信库」。「复用」列指与现成探针的关系，**现成探针=零编译零装包**。

### ① 库层 L（P1.1 / P1.2 / P1.3 的基准集合 S）

| 编号 | 观测什么 | 挂点/入口（类·方法） | 只读 | 输出字段 | 采样与限流 | 复用关系 | 前提 |
|---|---|---|---|---|---|---|---|
| L1 | 三天外行存在性+BLOB 完整性 | `EntryReceiver.kt:654-662`→`SnsGuard.runReadOnlySql:1268`（§3.2 四 SQL） | 只读 | `SQLX cols/rows` + `snsId,createTime,sourceType,clen,alen,chead` | 每档位各 1 次、不轮询；`n≤60`(`SnsGuard.kt:1282`) | 复用现成 `op=sql`，零改码 | P1.1 |
| L2 | createTime 单位/T0 校准 | 同 `op=sql`，`select snsId,createTime,datetime(createTime,'unixepoch')...` 对照界面最新一条 | 只读 | 单位(秒/毫秒)、`T0` 值、`max(createTime)` | 仅在 S 建立前 1 次 | 复用 `op=sql` | P1.2 |
| L3 | **目标集合 S 快照**（给写事件层做命中 join 的基准） | 同 `op=sql`，`where createTime<T0` 取 `snsId,createTime,(sourceType&2),(sourceType&8),clen,alen,hex(substr(content,1,16))` | 只读 | 每三天外行一行 `RCP_L3 ... snsId=... bit1=.. bit3=.. clen=..` | 每窗口 1 份、按 `snsId` 去重、≤60 行 | 复用 `op=sql`，**离线 join**（不新改源码） | P1.1/P1.3 |
| L4 | 列核对/位状态 | `pragma table_info(SnsInfo)`（§3.2①）与 §3.2④ | 只读 | 18 列名、`bit1/bit3` | 1 次 | 复用 | P1.1 |
| L5 | 重复行风险（补 INSERT 副作用旁证） | `op=sql`：`select snsId,count(*) c from SnsInfo group by snsId having c>1` | 只读 | 重复 `snsId` 清单 | 复位档 1 次 | 复用 | P1.5 |

### ② 写事件层 W（核心 · 方法级事件账本）

| 编号 | 观测什么 | 挂点/入口 | 只读 | 输出字段 | 采样与限流 | 复用关系 | 前提 |
|---|---|---|---|---|---|---|---|
| W1 | **五方法事件账本**：`updateWithOnConflict`/`execSQL`/`delete`/`insertWithOnConflict`/`insert` 的 before/after 事件 | 六常驻钩子 `SnsGuard.kt:149-180`；具体 `snsBlockGuard:297-298`、`sqlGuard:221-222`、`deleteGuard:214-215`、`snsInsertGuard:249-254` | 只读（内存账本） | `ts,phase,meth,half,table,SQL原文/ContentValues键集,snsId,user,hitS(是否∈S),want,keep,shape,caller,thread` | 同 `snsId` 5s 一条(`:49`)、stat 60s 一条(`:52`)、SQL 去重≤60(`:55`)、截 1500 字符(`:58`)、RING=50(`:43`)、**计数不去重**(`:36`)；**探针开关默认关按需开** | 复用 `SnsBlockProbe` + `op=snsg sub=blockstat`（`EntryReceiver.kt:378-380`），W1 的 `hitS` 由 L3 离线 join 得出，零改码 | P1.3/P1.4/P1.5 |
| W2 | **`& -9` 专项**：进主页 6 条清位是否落在三天外行 | `execSQL` before `onExecSql`/`onSnsSql`；`SnsSqlRewrite.SNSID_GT/LT:50-51` 抠 `snsId>A`/`<B`；`SnsSqlRewrite:10-13` 形态 | 只读 | `seq(1..6),mask,userName,gt,lt,inList,hitS(与 S 求交数),sqlHash` | 每进主页记一组、去重≤60、`SQL_LOG_CHARS=1500` 保住尾部 `IN(...)`(`SnsBlockProbe.kt:57-58`) | 复用 `SnsSqlRewrite` 正则 + `SnsBlockProbe` SQL 采样，离线解析 | **P1.3 决定性** |
| W3 | ContentValues 键集 + 写回形态 | `snsBlockGuard` before/after → `SnsBitLogic.afterShape:173-178` | 只读 | `keys,hasContent,hasAttr,want,keep,shape(四态互斥)` | 随 W1 节流 | 复用 `SnsBlockProbe` keys(`:25`) + `afterShape` 四计数器(`:133-178`) | P1.4/P1.5 |
| W4 | **调用者签名/调用栈**（区分写来自进主页/下拉/同步/冷启） | 同 W1，before 抓 `Thread.currentThread().stackTrace` 顶 2 帧 | 只读 | `callerSig(类#方法),thread` | 按 `(meth,callerSig)` 去重、每类≤K 条、默认关 | **可选增强**：需给 `SnsBlockProbe` 加 1 字段（否则用 T2 档位标签降级替代） | P1.4/时序 |
| W5 | snsId 重复/补行副作用 | `op=sql`（同 L5）+ W1 的 `insert` 通道 | 只读 | 重复计数 | 复位档 | 复用 | P1.5 |

> ⚠️ W 组「是否命中三天外行」一律用 **L3 的 S 集合离线 join**（`dev.py q` 拉回本地比对），**不改 `SnsGuard` 生产判据、不往钩子塞 S**，保证只读与零改码。

### ③ 触发归因层 A（互斥判据 · 区分「没写」vs「有写没拦到」）

| 编号 | 观测什么 | 挂点/入口 | 只读 | 输出字段 | 采样与限流 | 复用关系 | 前提 |
|---|---|---|---|---|---|---|---|
| **A1** | **互斥归因判据**：底层方法进入计数 vs before 回调计数 vs S 上命中数 | 底层计数用 `SnsGuard.stat()`/`SnsBlockProbe.stat()` 四路独立计数（W2 `:24`）；before 计数同账本 | 只读 | `window,rawMethodCnt,beforeCnt,onSCnt,verdict` | 每窗口 1 条汇总(60s 一条) | 复用现成 `stat()`；⛔ 四路计数**不合成总数**(`:24-25`)，否则看不出哪条通道 | **P1.3/P1.4** |
| A2 | 六钩子加载自证 | `SNS installed hooks=... blockEnabled=...`（`SnsGuard.kt:186-188`；`CLAUDE.md:75-76` 上机三件事之一） | 只读 | 钩子清单齐全与否 | 每重启 1 次 | `dev.py q 'SNS installed'` | P1.4 |
| A3 | 签名/重载核对 | before 记 `param.method.name`+`args` 类型/长度；对照 wcdb 五参 `updateWithOnConflict` 与 `execSQL` 三重载（`laneC:55-61`） | 只读 | 实际签名 | 按方法去重 | 可选增强（同 W4） | P1.4 |
| A4 | 日志投递/执行自证 | `dbg_size` 回读 + 目标 TAG 出现（`dev.py:273-299`） | 只读 | `chk,ok,detail` | 每广播 1 次 | `dev.py probe` 内置三道闸 | 干扰排除 |

**A1 互斥判据（⛔ 不许写成并列，必须二选一定性）**：
- `(a) rawMethodCnt=0 ∧ beforeCnt=0` ⇒ **压根没有写事件** ⇒ 真·空转 ⇒ **R-C 证死**（P1.3 不成立）。
- `(b) rawMethodCnt>0 ∧ beforeCnt=0` ⇒ **有写事件但 before 没拦到**（挂错类/签名不匹配/钩子未注册/`SNS installed` 缺项）⇒ **证据不足·工程挂点问题，非路线证伪**，修挂点后重测，⛔ 不得记成「没有写事件」。
- `(c) rawMethodCnt>0 ∧ beforeCnt>0 ∧ onSCnt=0` ⇒ 有写但全落三天内行 ⇒ 对 R-C 等同 `(a)` **证死**（写没落目标行，P1.3 仍不成立）。
- 只有 `(d) onSCnt>0` 才进入 P1.5/P1.6 继续判活。

### ④ 界面层 U（对着用户能看见的事实，不对内部计数器）

| 编号 | 观测什么 | 挂点/入口 | 只读 | 输出字段 | 采样与限流 | 复用关系 | 前提 |
|---|---|---|---|---|---|---|---|
| U1 | **翻页能否翻到三天外动态** + 各场景界面可见条数 | `python tools/dev.py ui <文本>` 文本断言（`dev.py:29-33`, `:200-217`），不截图(`CLAUDE.md:59`) | 只读(uiautomator，不写库) | `scene,uiVisible,older3d,older3dShown,firstScreenHasOlder` | 每场景 1 次 | 复用 `dev.py ui` | P1.6 |
| U2 | 界面条数 vs `older3d` 对照 | U1 结果 + L1 ③ 的 `older3d` 离线比对 | 只读 | `差值`、是否「库有界面无」 | 每档 1 行 | 复用 | P1.1/P1.6 |
| U3 | **首屏/翻页分离** | U1 分 `首屏`/`翻页` 两行 | 只读 | 首屏条数（预期永不含三天外）、翻页条数 | 每档 | 复用 | P1.6/效果上限 |

> 判据纪律：U 组对着「用户能看见几条、有没有那条动态」写；⛔ 首屏空**不得**当 R-C 失败（`wm:40-41`、`moments.md:259-260` 已知上限）。

### ⑤ 时序层 T（进入 / 返回 / 复位逐档时间戳对齐）

| 编号 | 观测什么 | 挂点/入口 | 只读 | 输出字段 | 采样与限流 | 复用关系 | 前提 |
|---|---|---|---|---|---|---|---|
| T1 | 统一时基事件轴（W/L/U/C 全部打同一时钟） | `SystemClock.elapsedRealtime`（`SnsBlockProbe.kt:5` import） | 只读 | `ts,phase,mark,event` | 每行事件自带、无额外轮询 | 复用账本时间戳 | 全 |
| T2 | **逐档子场景**观测点 | 见 P6 三档边界表 | 只读 | `phase,mark,预期,实测` | 每子场景 1 窗口 | 与 W/L/U 拼档 | P1.3/P1.4/P1.6 |
| T3 | 冷启后 `snsDb` 重捕获 | `SNS sns db captured via rawQuery`（`SnsGuard.kt:1016`）时间戳 | 只读 | 捕获时刻 | 每冷启 1 次 | 复用 | P1.4/干扰 |

## ⑥ 对照层 C

| 编号 | 观测什么 | 挂点/入口 | 只读 | 输出字段 | 采样与限流 | 复用关系 | 前提 |
|---|---|---|---|---|---|---|---|
| C1 | **反向对照**：关 `sns_block` + 关 `antirevoke.sns`（题面硬结论「临时全放行必须两个都关」） | `op=snsg sub=block --es sub2 off`（`EntryReceiver.kt:375-377`）；`antirevoke.sns` 用 `kvset --es key --es val`（`dev.py:225`） | ⚠️ 见标红 | 开关态、关后写事件基线 | 1 次 | 复用现成开关 | P1.3 排除「写事件是模块自造」 |
| C2 | **阴性对照**：「昵称B」(全部可见) 同套 W1/W2，建正常写事件基线 | 同 W 组，`userName='<wxidB>'` | 只读 | 基线写事件数 | 1 次 | 复用 | 排除探针失效 |
| C3 | **self-exclude 对照**：「昵称C」(自己) 动态写应被放行 | `isSelfAuthor`（`SnsGuard.kt:50-54`, `:721`） | 只读 | 自己行是否被保位 | 1 次 | 复用 | P1.5/竞态 |
| C4 | **阳性对照**：请「昵称A」删一条动态，确认账本能抓到写 `sourceType=0` | 同 W 组（`moments.md:1-19`, `wm:12-24`, `SnsGuard.kt:34-36`） | 只读 | 是否抓到 `want=0` 事件 | 1 次 | 复用 | **A1 归因有效性自证**（抓不到=探针坏，不得判死） |

## ⑦ 干扰排除层 X

| 编号 | 观测什么 | 挂点/入口 | 只读 | 输出字段 | 采样与限流 | 复用关系 | 前提 |
|---|---|---|---|---|---|---|---|
| X1 | LogX 环形覆盖规避（5000 行） | 每窗口前 `op=logclear`（`dev.py:124-127`, `:250-253`） | 只读(清模块日志) | `chk=logclear,ok` | 每窗口 | `dev.py probe --clear` | 全 |
| X2 | K7 perf 刷屏隔离 | 专属 `RCP_*` TAG + `dev.py q '<TAG>'`；用「TAG 出现」而非字节增长判执行（`dev.py:292-299`） | 只读 | `TAG_present` | 每窗口 | `dev.py q` | 全 |
| X3 | 微信前台自证 | `am start -n com.tencent.mm/.ui.LauncherUI`（`dev.py:144`, `:275-289`）+ `dbg_size` 回读 | 只读(拉起) | `前台,dbg_growth` | 每广播 | `dev.py probe` 三道闸 | 全 |
| X4 | `snsDb` 捕获时机 | 先刷一次朋友圈；确认 `SNS sns db captured`(`SnsGuard.kt:1016`) 再跑 `op=sql`；冷启重捕获 | 只读 | `snsDb_captured` | 每重启 | T3 | L1 可用性 |
| X5 | IME/UI 干扰 | `dev.py ui` 仅在界面稳定后取、避免输入法遮挡致条数误读 | 只读 | — | — | `dev.py ui` | U1 |
| X6 | 广播二次解析自证 | `dev.py probe --q/--val` 自动 `_sq` 包单引号(`dev.py:20,66-81`)、写完 `dev.py q 'SQLX'` 回读；`--el` 仅 signed，无符号 snsId 走 `--es` 字符串(`dev.py:226`) | 只读 | `ok` | 每条 SQL | `dev.py probe` | L1 正确性 |
| X7 | 旧代码/加载自证 | `logclear` 判据（不含 `unknown op=logclear`,`dev.py:141`）+ A2 六钩子齐全 | 只读 | `installed,not_stale` | 每重启 | `dev.py q` | 全 |

### ⛔ 标红：本质上必须写微信库 / 写配置库的探针（及更省的只读替代）

| 探针 | 为什么本质要写 | 本轮处理 | 只读替代（推荐） |
|---|---|---|---|
| **C1 反向对照的 `kvset antirevoke.sns`** | `kvset` 写**模块配置库**（非微信库，但本 lane 硬约束禁 kvset/`CLAUDE.md` 真机纪律串行项） | ⛔ **本 lane 不执行**，仅在 P7 列出「需用户/主 agent 关的 key」；`sns_block` 的 `--es sub2 off` 是模块内开关、非写微信库，可用 | 由主 agent 在装包轮统一关；本设计只消费关后的 `stat()` 结果 |
| **「验证保位后能否翻页 / before 写回后是否被覆盖」** | 要证 P1.5/P1.6 的完整闭环，须真启用 R-C 生产逻辑（before 写回 `sourceType` / `attachPrefix` 补 content）⇒ **写微信库** | ⛔ **本轮不实现 R-C、不写生产逻辑**（硬约束） | ① 读 L3 现有行的 `&2!=0` 位状态；② 用 **C4 阳性对照**（R42 删动态场景已真机跑通保位，`moments.md:1-19`, `wm:37-42`）作同机制间接证据；③ 用 W3 `afterShape=BLOCKED_BY_REWRITE`（`SnsBitLogic.kt:142,175`）只读观测写回形态。三者均只读，但**只给间接证据** ⇒ 这是 P5 的 V9「证据不足」来源，不粉饰 |
| **补 content 后界面能否渲染** | 需 `attachPrefix` BLOB 写（`SnsGuard.kt:716`） | ⛔ 不碰 | `hex(substr(content,1,16))` 只证完整性，**不证可解析/可渲染** ⇒ 列入 P8 盲区 |

> 其余全部探针均为「只读·不写微信库」：账本纯内存（`SnsBlockProbe.kt:28`）、`op=sql` 白名单（`SnsGuard.kt:1271`）、`dev.py ui/q` 只读界面与日志。

[DONE] P2

---

## P3 一次性装包清单（按「能否合并进一次 adb install」分组）

> 前提硬规则：`CLAUDE.md:26` 取证一次重启抓完所有问题、`:62` 改动攒齐再上机、`dev.py:1248-1251` 通用只读 SQL 入口「一次投入、之后取证零编译零重启」、`forensics:271-273` 最小第一步即四条 `op=sql` + 抓现成事件账本。
> ⚠️ 本 lane 为**只读设计 lane**，硬约束禁止装包 ⇒ 下列装包次数是「交给真机执行阶段」的规划，本轮一次都不装。

### 3.1 分组

| 组 | 探针 | 是否需改源码/装包 | 理由 |
|---|---|---|---|
| **A · 零改码（现成探针，决定性判定全覆盖）** | L1-L5（`op=sql`→`runReadOnlySql`）、W1-W3（`op=snsg sub=blockstat` + `SnsBlockProbe` 账本 + `SnsSqlRewrite` 正则）、A1/A2/A4（`stat()`/`installed`/`dbg_size`）、U1-U3（`dev.py ui`）、T1-T3、C2/C3/C4、X1-X7（`logclear`/`q`/前台/二次解析） | **否，0 装包** | 全部落在现成 `op=sql`（`EntryReceiver.kt:654-662`）、`op=snsg`（`:342-386`）、`dev.py` 子命令；`hitS` 命中判定由 L3 的 S 集合**离线 join** 得出，不往生产钩子塞数据（`P2·W 组说明`）。`forensics:271-272` 的最小第一步正是这一组 |
| **B · 可选增强（非决定性，攒齐后 1 次装包）** | W4 调用者签名（给 `SnsBlockProbe` 加 1 字段）、A3 签名/重载核对 | 是，最多 **1 次** | 二者只是把「调用者/签名」补进事件行；**可降级**：用 T2 档位标签 + W2 的 SQL 区间替代，不影响判死/判活（P1.3）。按 `CLAUDE.md:62` 若确需，与任何其它改动**攒齐成一次** `adb install` |
| **C · 本轮禁止 / 不实现** | R-C 生产逻辑（`snsBlockGuard` 写回 / `SnsSqlRewrite` 改写 / `attachPrefix` 补 content）、`kvset` 执行、`adb install` 本身 | — | 硬约束：不实现 R-C、不写生产逻辑、不碰 adb/装包/kvset（`CLAUDE.md:73-79` 真机纪律串行项由主 agent 承担） |

### 3.2 最少装包次数与理由

- **决定性判定（判死/判活）最少 `adb install` = 0 次**。理由：P1.3 一票否决所需探针 = `L1+L3`（建 S）+ `W1+W2`（全通道事件账本 + `& -9` 命中 S）+ `A1+A2`（互斥归因 + 六钩子自证）+ `U1/U3`（翻页可见）+ `C2/C4`（阴/阳性对照）——**全部在组 A**，零编译、零重启、零装包（`forensics:271-273` 已把「四条 `op=sql` + `blockstat`」定为最小第一步）。
- 若后续需要 W4/A3 的调用栈级归因（把「证据不足」进一步收敛），**最多再 1 次**，且按 `CLAUDE.md:62` 攒齐所有改动一次装完，不为单探针多装一轮。
- **本设计 lane 实际装包 0 次**（不碰 adb/Gradle/装包）。

### 3.3 探针开关（默认关 / 按需开，避免常驻开销）

| 开关 | 默认 | 打开方式 | 关闭/理由 |
|---|---|---|---|
| `sns_block`（写时拦截开关） | 依现状（用 `SNSG stat` 读，`EntryReceiver.kt:375-377`） | `dev.py probe snsg block on`（`--es sub2 on`） | 反向对照时 `off`；题面硬结论「临时全放行必须两个都关」⇒ 还要 `antirevoke.sns`（C1，交主 agent） |
| 新增探针总开关 `probe.rc.enabled`（W4/A3 若实现） | **false** | 按需 `kvset`（主 agent） | 六常驻钩子在**所有表**热路径，放开常驻会刷屏/挤掉判据（`SnsGuard.kt:178-181`, `:289-295`；`SnsBlockProbe.kt:31`） |
| 账本采样器 `trace` | `id=0` 停（`EntryReceiver.kt:374`） | `dev.py probe snsg trace --el id <snsId>` | 30s×60=30 分钟自动停（`SnsBlockProbe.kt:60-62`），用完即关 |
| 每窗口 `op=logclear` | 每窗口前必做 | `dev.py probe ... --clear`（`dev.py:250-253`） | 防 LogX 5000 行环形覆盖（`CLAUDE.md:78`） |

[DONE] P3

---

## P4 输出格式与日志 TAG 规范（可直接照抄）

### 4.1 TAG 规范

- 统一前缀 `RCP_`（RC-Probe），分层：`RCP_L`(库) / `RCP_W`(写事件) / `RCP_A`(归因) / `RCP_U`(界面) / `RCP_T`(时序) / `RCP_C`(对照) / `RCP_X`(干扰排除)。
- **关键判据行**另打高优先 TAG `RCP_KEY`（A1 verdict / W2 hitS / L1 rows / U1 older3dShown），窗口内即时 `dev.py q 'RCP_KEY'` 先拉走，防环形覆盖丢失。
- 过滤照抄：`python tools/dev.py q 'RCP_|SQLX|SNSG|SNS '`；单类：`dev.py q 'RCP_W2'`。⛔ grep 大小写敏感，只读 SQL 的 TAG 是 `SQLX` 不是 `sql`（`dev.py:298-299`）。

### 4.2 可照抄日志行格式（单行、key=value、竖线可选、便于离线 join）

```
RCP_KEY ts=<elapsedMs> phase=<进入|返回|复位> kind=<L1rows|A1verdict|W2hitS|U1shown> val=<...>

RCP_L1  ts=<...> phase=<...> sql=<①②③④> rows=<n> cols=<csv> r1=<snsId|createTime|sourceType|clen|alen|chead|...>
RCP_L2  ts=<...> unit=<s|ms> T0=<int> maxCt=<int> uiNewestCt=<int> calibrated=<0|1>
RCP_L3  ts=<...> phase=<...> S_SET snsId=<id> createTime=<ct> bit1=<0|1> bit3=<0|1> clen=<n> alen=<n> chead=<hex>
RCP_W1  ts=<...> phase=<...> meth=<updateWithOnConflict|execSQL|delete|insertWithOnConflict|insert> half=<before|after> table=<SnsInfo|..> keys=<csv|-> want=<int|-> keep=<int|-> shape=<BLOCKED_BY_REMOVE|BLOCKED_BY_REWRITE|NOT_BLOCKED|UNKNOWN> snsId=<id|-> user=<userName|-> hitS=<0|1> caller=<类#方法|-> thread=<name> sql=<原文或keyset|-> sqlHash=<前16>
RCP_W2  ts=<...> phase=<进入-进主页> seq=<1..6> mask=<-9|-3|other> userName=<...> gt=<A> lt=<B> inList=<64值|-> hitS=<命中S的snsId数> sqlHash=<前16>
RCP_A1  ts=<...> window=<id> rawMethodCnt=<n> beforeCnt=<n> onSCnt=<n> verdict=<NO_WRITE|WRITE_NO_HOOK|WRITE_ON_S|WRITE_OFF_S>
RCP_A2  ts=<...> installed=<delete,execSQL,updateWithOnConflict,updateWithOnConflict.before,insertWithOnConflict,insert> blockEnabled=<0|1> allSix=<0|1>
RCP_U1  ts=<...> phase=<...> scene=<首屏|翻页|下拉|后台同步|冷启> uiVisible=<n> older3d=<n> older3dShown=<n> firstScreenHasOlder=<0|1>
RCP_T1  ts=<...> phase=<进入|返回|复位> mark=<设三天可见|进主页|切页|下拉|后台同步回来|冷启|关开关|改回全部可见|清缓存|阳性删动态> note=<...>
RCP_C1  ts=<...> ctl=<sns_block|antirevoke.sns|阳性删动态|阴性全可见|self> state=<on|off|-> note=<...>
RCP_X1  ts=<...> chk=<logclear|前台|dbg_growth|installed|snsDb_captured|TAG_present|not_stale> ok=<0|1> detail=<...>
```

### 4.3 去重键

| 对象 | 去重键 | 策略 |
|---|---|---|
| W1 事件行 | `meth|half|table|snsId|want|callerSig` | 同键 5s 内只打一条（`SnsBlockProbe.kt:49`），**计数仍累加不去重**(`:36`) |
| W2 SQL 原文 | `mask|userName|gt|lt|inListHash` | 去重后 ≤60 条(`:55`)，单条截 1500 字符保住 `IN(...)` 尾部(`:58`) |
| A1 汇总 | `window` | 60s 一条(`:52`) |
| L3 S 集合 | `snsId` | 每窗口一份、按 snsId 去重 |
| T1 时间轴 | `phase|mark` | 每档一条 |

### 4.4 封顶行数

- 单窗口总计 **≤ 200 行**（远低于 LogX 5000 环形）；`RCP_KEY` 不计入上限、优先保留。
- 各探针沿用现成上限：L 组 `n≤60`（`SnsGuard.kt:1282` clampProbeN）、W1 账本 `RING=50`（`SnsBlockProbe.kt:43`）、`blockstat ≤16 行`(`:45-46`)、W2 SQL 采样 ≤60(`:55`)、trace ≤60(`:61-62`)、`dev.py q` 本地 `--n` 截断(`dev.py:10-11`)。

### 4.5 环形覆盖规避策略

1. **每观测窗口前 `op=logclear`**（`CLAUDE.md:78`, `dev.py:250-253`），窗口结束**立刻** `dev.py q 'RCP_|SQLX|SNSG|SNS '` 拉回本地持久化 —— 不依赖设备端 5000 行留存。
2. **关键判据行 `RCP_KEY` 先拉**：A1 verdict / W2 hitS / L1 rows / U1 older3dShown 单独高优先 TAG，窗口内即时取走。
3. **判「探针执行」用「目标 TAG 出现」而非日志字节增长**（`dev.py:292-299`）：`K7 perf` 每 250ms 刷屏会让文件一直变大，字节增长是假阳性；`Broadcast completed` 也只证明 AMS 收下（`:275-278`）。
4. **探针开关默认关**（P3.3），避免六常驻钩子在所有表热路径常驻刷屏把判据行挤掉（`SnsGuard.kt:178-181`, `:289-295`）。

[DONE] P4

---

## P5 判定表（探针结果组合 → 三态）

> 三态：**R-C 可实现 / R-C 证伪 / 证据不足**。判据一律对着「用户能看见的事实」+ A1 互斥归因 verdict 写，不对内部计数器（`CLAUDE.md:48-49`、题面判据纪律）。

| # | 触发条件（探针输出组合） | 判定 | 依据 / 说明 |
|---|---|---|---|
| **V1** | `L1` ② 返回 0 行，或行在但 `clen=0`/`chead=''`（P1.1 不成立） | **R-C 证伪**（共性前置证伪，整题收口） | `forensics:167-169`, `:271`, `:273`；无米之炊，R-A/R-B 同收口 |
| **V2** | `L1/L3` S 非空且 `L2` 单位已校准，但 `A1 verdict=NO_WRITE`：`rawMethodCnt=0`、`W2` 进主页 6 条 `& -9` 的 `hitS=0`，且覆盖进入/返回/复位全窗口（P1.3 不成立） | **★ R-C 证死**（决定性） | 题面生死假设坐实：服务端不下发⇒本地无写⇒before 空转（`forensics:189`, `:239`, `:260`, `:272`） |
| **V3** | 同 V2，且 `C2`(昵称B 全可见) / `C4`(昵称A 删动态) **都抓到写事件**、`A4` 投递自证 ok | **R-C 证死（强证据）** | 对照层排除「探针坏了」的假阴性；`dev.py:292-299` |
| **V4** | `A1 verdict=WRITE_ON_S` ∧ `W3 shape∈{BLOCKED_BY_REWRITE,BLOCKED_BY_REMOVE}` ∧ `U1/U3` 翻页 `older3dShown>0`（P1.1+P1.3+P1.4+P1.5+P1.6 全成立） | **R-C 可实现** | `wm:40-42`；`SnsBitLogic.kt:142,175` |
| **V5** | `A1 verdict=WRITE_NO_HOOK`（`rawMethodCnt>0` 但 `beforeCnt=0`） | **证据不足（工程挂点问题，非路线证伪）** | 挂错类/签名不匹配/钩子未注册；修挂点重测，⛔ 不得记成「没有写事件」（P1.2 A1 互斥(b)） |
| **V6** | `A1 verdict=WRITE_OFF_S`（有写、before 也回调，但 `onSCnt=0`，全落三天内行） | **R-C 证死**（等同 V2） | 写事件没落在目标行上，P1.3 仍不成立 |
| **V7** | `X` 层任一自证失败（前台/投递/`TAG_present`/`snsDb_captured`/`not_stale`/`installed` 缺项）致数据不可信 | **证据不足** | 先修干扰排除层，⛔ 不对着过期数据下结论（`dev.py:275-299`, `CLAUDE.md:73-79`） |
| **V8** | S 有写、before 命中，但 `U1` 翻页仍看不到（`older3dShown=0`）或把首屏空当失败 | **证据不足 / 部分**（P1.6 未定） | 效果上限：首屏解决不了是已知事实（`wm:40-41`, `moments.md:259-260`）；保位≠呈现 |
| **V9** | 任何「必须写库才能闭环」的验证未做（真 before 写回后是否被覆盖、补 content 后界面渲染） | **证据不足** | 本轮不实现 R-C，只读替代只给间接证据（P2 标红、P8 盲区），不粉饰 |

### 5.1 ⛔ 特别写清：什么结果等于 R-C 被证死

- **主判死 = V2 / V3 / V6**：在 `L2` 正确校准的「三天外集合 S」上，进入 / 返回 / 复位**全时序窗口**内，`updateWithOnConflict / execSQL / delete / insertWithOnConflict / insert` 任一方法对 S 的写事件数 = 0（`rawMethodCnt=0` 或 `onSCnt=0`），尤其**进主页 6 条 `& -9` 的 `snsId` 区间与 S 求交为空**（`W2 hitS=0`，`moments.md:24-25`）；且 `C2/C4` 对照证明探针本身能抓到写事件、`A2` 六钩子齐全、`A4` 投递正常。
  ⇒ 唯一可能有触发机会的写事件也不落三天外行 ⇒ **before 钩子没有触发机会（空转）⇒ 整条路线证伪**。
- **前置证死 = V1**：S 本身为空 / `content` 空 ⇒ 连同 R-A/R-B 一起收口（`forensics:169`）。
- **不得误判为证死的两种**：`V5`（有写没拦到，是工程挂点问题）、`U1` 首屏空（已知效果上限）、`V7`（干扰致数据不可信）。这三类一律落到「证据不足」。

[DONE] P5

---

## P6 三档边界表（进入 / 返回 / 复位，逐档写，复位≠返回）

> 档位定义按本轮 P6：①进入 = 刚设三天可见 / 刚进好友主页；②返回 = 切页 / 下拉刷新 / 后台同步回来 / **冷启**；③复位 = 关开关 / 对方改回全部可见 / 清缓存。
> ⚠️ `forensics:232` 把「冷启」归复位档、本轮归返回档 —— **两处都覆盖、不留缺口**；冷启对「库磁盘位」的影响在复位档备注补写（R42 实证：漏掉唯一复位路径会出事故）。
> 「预期」列一律写用户能看见的事实；「残留」列写状态，不与返回档混同。

| 档 | 子场景 | 探针该观测什么 | 预期（用户可见事实） | 状态残留 | 锚点 |
|---|---|---|---|---|---|
| **①进入** | 刚设三天可见 / 刚进「昵称A」主页 | `L1+L2+L3+L4` 建并校准 S；`W2` 记 6 条 `& -9` 的 `gt/lt/inList` 与 S 求交；`U1/U3` 首屏 vs 翻页条数；`X3/X4` 前台 + snsDb 捕获；`A2` 六钩子 | 首屏只三天内；翻页**理论上**可能带出 S 中 `&2!=0` 的行（`[推断]`）；6 条 `& -9` 按 `snsId` 区间分页 | 模块钩子/账本内存态、`snsDb` 句柄（进程内常驻，拿到即卸载 captureHook） | `forensics:53`, `:222`, `:232`；`wm:40-41`；`moments.md:24-25`；`SnsGuard.kt:1016-1017` |
| **②返回** | 切页再进 | `W2` 每轮重记 6 条 `& -9`、`T1` 打档标 | 每进一次主页 6 条清位写（与 R-C 是否命中 S 相关） | 账本内存态重建、库磁盘态不变 | `moments.md:24-25`；`forensics:238` |
| **②返回** | 下拉刷新 | `W1` 看 S 上是否新增写；`U1` 首屏/翻页条数 | 新响应覆盖内存列表 ⇒ 首屏回三天内；翻页查表仍可能带出库里的行 | 内存列表被覆盖、库行不变 | `forensics:238`；`moments.md:259-260` |
| **②返回** | 后台同步回来 | `W1` 看 `NetSceneSnsSync`(getType=214) 是否带来清位写；`U1` | 响应不含三天外 id ⇒ 界面又只剩三天内 | 内存列表被覆盖 | `forensics:238`；`MomentsAuto.kt:37`；`MomentsSyncTrigger.kt:76` |
| **②返回** | 冷启 | `T3` 记 `SNS sns db captured` 时刻；重跑 `L3`+`W1`；`A2` 重装自证 | 钩子 reinstall、`snsDb` 句柄丢失需重新捕获；但**库里的位是磁盘态、冷启后仍在** ⇒ 反而可能比刷新后更容易翻到 | 钩子/账本内存态重建；**库磁盘位不回滚** | `SnsGuard.kt:1280`；`moments.md:263`；`forensics:242`, `:266` |
| **③复位** | 关开关（`sns_block` off + `antirevoke.sns` off，**两个都关**） | `C1` 记开关态；`L3` 重查 S 的 `sourceType`/`chead` | 判据停、界面恢复微信原生（只剩三天内） | ⚠️ **已写进微信库的 `sourceType` 保底值 / `[已删除]\n` content 前缀是磁盘状态，不随开关/冷启回滚**；R42 已删 `undoInsert/rollbackStep/restore/show`，**当前源码无任何回滚路径** ⇒ 库与服务端状态不一致 | `forensics:239`, `:243`；`wm:42`；`SnsPrefixProbe.kt:67`；`_r42_purge_report.md:51-56` |
| **③复位** | 对方（昵称A）改回全部可见 | `U1` 看界面是否出现重复条；`L5/W5` `group by snsId having count(*)>1` | 服务端恢复下发 ⇒ 被保位的旧行与真实响应**重复 / 顺序异常** `[推断]`（引入重复源） | 重复行风险（`snsId` 无唯一约束）、库与服务端不一致 | `forensics:244`, `:263`；`moments.md:214-217`；`_review_r31h_sns.md:115-119`(R31h 先例转引) |
| **③复位** | 清缓存 | `L1` 重跑看 S 是否还在 | 若 `SnsInfo` 行被清 ⇒ 三天外行整个消失（`[需真机]`，无取证） | `[需真机]` | `forensics:245`, `:262` |
| **③复位**（备注） | 冷启对磁盘位的影响（补 forensics 归复位档那一面） | `L3` 冷启前后位状态对比 | 冷启后库里的位/前缀**仍在**， unlike 下拉刷新（内存覆盖） | 库磁盘位/前缀持久 | `moments.md:263`；`forensics:242` |

### 6.1 复位档最容易漏、必须保留的三点

1. **关开关 ≠ 恢复原状**：R-C 写进微信库的是磁盘状态，开关只关判据、不回滚；R42 删除后已无 `undoInsert/rollbackStep`（`forensics:243`, `_r42_purge_report.md:51-56`）。
2. **对方改回全部可见会引入新的重复源**：服务端恢复下发后，保位旧行与真实响应并存 ⇒ 需去重判据 `[推断]`（R31h 事故先例，`forensics:244`）。
3. **清缓存是唯一可能把「本地行还在」前提整个抹掉的动作**，但微信清缓存对 `SnsInfo` 的实际行为**无取证** ⇒ `[需真机]`（`forensics:245`）。

[DONE] P6

---

## P7 最小可执行步骤（真机操作员照做；本设计 lane 不执行）

> 纪律：`CLAUDE.md:26` 一次重启抓完所有问题、`:62` 改动攒齐再上机；**每次观测前微信必须在前台**（`am start -n com.tencent.mm/.ui.LauncherUI`，`dev.py:144`, `:275-289`）；`Broadcast completed` 不算投递，用 `dbg_size` 增长 + 目标 TAG 出现双重确认（`dev.py:275-299`）；**每个观测窗口前 `op=logclear`**（`dev.py:124-127`, `:250-253`）；所有值经 `dev.py probe` 自动 `_sq` 包单引号、写完 `dev.py q` 回读复核（`dev.py:20,66-81`）。
> ⚠️ 提及好友一律用昵称，wxid 仅作 SQL 参数（`CLAUDE.md:12-18`）。`dev.py probe` 展开的广播 action 均为 `com.lx.wxgroup.action.PROBE -p com.tencent.mm`（`dev.py:95-96`）。

### S0 · 需要用户配合做什么（必须先做）

1. 请好友**「昵称A」**把朋友圈设为「允许查看的范围 = 最近三天」，且设之前相册里已有**三天以外的历史动态**（否则 S 为空，直接 V1）。
2. 请好友**「昵称B」**保持「全部可见」，作阴性对照。
3. 确认**「昵称C」= 自己**，作 self-exclude 对照。
4. 用 `python tools/dev.py probe label sql --q "select username,nickname,conRemark from rcontact where nickname like '%<昵称>%'" --grep PROBE` 拿 wxid↔昵称映射（`CLAUDE.md:17`）；汇报时只写昵称，不裸报 wxid。

### S1 · 上机三件事 + 加载自证（`CLAUDE.md:73-79`）

```
python tools/dev.py restart                      # 双次 force-stop，模块重载、LogX 归零(dev.py:110-121)
python tools/dev.py q 'SNS installed'            # A2：确认 6 个钩子齐全(SnsGuard.kt:186-188)
python tools/dev.py probe logclear --clear       # 或任意 probe 带 --clear；X1
python tools/dev.py q 'logclear'                 # 判据：出现 logclear 且不含 unknown op=logclear(dev.py:141-152)
am start -n com.tencent.mm/.ui.LauncherUI       # 每次广播前微信前台
```
先刷一次朋友圈让 `snsDb` 被捕获（X4），`dev.py q 'sns db captured'` 确认 `SNS sns db captured via rawQuery`（`SnsGuard.kt:1016`）；若报 `SQLX no sns db yet`（`:1280`）再刷一次。

### S2 · 窗口①（进入档）：建 S + 抓写事件（决定性判据在这里收）

```
# L2 单位校准 + L1/L3/L4 四条只读 SQL（每条自带 --clear 清日志）
python tools/dev.py probe sql --q "pragma table_info(SnsInfo)" --grep SQLX --clear
python tools/dev.py probe sql --q "select snsId,userName,createTime,sourceType,length(content) clen,length(attrBuf) alen,hex(substr(content,1,16)) chead from SnsInfo where userName='<wxidA>' and createTime < <T0> order by createTime desc" --grep SQLX --n 60 --clear
python tools/dev.py probe sql --q "select count(*) total,sum(case when createTime < <T0> then 1 else 0 end) older3d,min(createTime),max(createTime) from SnsInfo where userName='<wxidA>'" --grep SQLX --clear
python tools/dev.py probe sql --q "select snsId,createTime,sourceType,(sourceType&2) bit1,(sourceType&8) bit3 from SnsInfo where userName='<wxidA>' and createTime < <T0>" --grep SQLX --clear
python tools/dev.py probe sql --q "select snsId,createTime,datetime(createTime,'unixepoch') from SnsInfo where userName='<wxidA>' order by createTime desc limit 3" --grep SQLX --clear   # L2 对照界面最新一条定单位
```
> ⛔ BLOB 只用 `hex()`，禁 `cast(content as text)`（F4）。`--q` 值含空格/引号由 `dev.py probe` 自动包单引号（`dev.py:20`）；每条完 `dev.py q 'SQLX'` 回读复核。

```
python tools/dev.py probe snsg block on          # 按需开写时拦截开关(EntryReceiver.kt:375-377)
# 微信前台 → 进入「昵称A」主页 → 逐屏翻页；窗口前已 --clear
python tools/dev.py probe snsg blockstat --grep SNSG --clear        # W1 四路计数 + 最近 10 事件(EntryReceiver.kt:378-380)
python tools/dev.py q 'RCP_W2|SNSG|SQLX'                            # W2 六条 & -9 的 gt/lt/inList、与 S 离线 join
python tools/dev.py ui <昵称A>                                       # U1 界面条数（文本断言，不截图）
python tools/dev.py ui <三天外动态文本片段>                          # U3 首屏/翻页分离
python tools/dev.py q 'RCP_|SQLX|SNSG|SNS '                         # 窗口末：全部拉回本地持久化
```
> 判死最省即止于此：S 非空但 `blockstat` 全通道 0 写、`& -9` 的 `hitS=0` ⇒ V2 证死（P5）。

### S3 · 窗口②（返回档，逐子场景各清一次日志）

```
# 每个子场景前：am start ...（前台）+ dev.py probe ... --clear
# (a) 切页再进      → dev.py probe snsg blockstat --grep SNSG --clear  （验证每进一次 6 条 & -9）
# (b) 下拉刷新      → 同上 + dev.py ui <文本>                           （新响应覆盖内存列表）
# (c) 后台同步回来  → dev.py probe moments watch（MomentsSyncTrigger, EntryReceiver.kt:483-495）+ blockstat
# (d) 冷启          → python tools/dev.py restart → 重跑 S2 的 L3/blockstat + dev.py q 'sns db captured'（T3）
python tools/dev.py q 'RCP_|SQLX|SNSG|SNS '
```

### S4 · 窗口③（复位档）

```
python tools/dev.py probe snsg block off         # 关 sns_block；⚠️ 题面硬结论「两个都关」⇒ 另需 antirevoke.sns
#                                              ⛔ antirevoke.sns 的 kvset 本 lane 禁执行，交主 agent：kvset --es key antirevoke.sns --es val 0(dev.py:225)
python tools/dev.py probe sql --q "<L3 位状态 SQL>" --grep SQLX --clear   # 看库位/前缀是否残留（磁盘态不回滚,forensics:243）
# 请「昵称A」改回全部可见 → 重进主页 → dev.py ui 看重复条 + dev.py probe sql --q "select snsId,count(*) c from SnsInfo group by snsId having c>1" --grep SQLX   （重复源,L5）
# 请用户清缓存 → 重跑 S2 四条 L1 SQL 看 S 是否被抹（[需真机],forensics:245）
python tools/dev.py q 'RCP_|SQLX|SNSG|SNS '
```

### S5 · 对照窗口（与上面同一重启内完成，仍 0 装包）

```
# C4 阳性对照：请「昵称A」删一条动态 → 确认 blockstat 抓到 want=0 写事件（探针有效性自证,A1归因前提）
python tools/dev.py probe snsg blockstat --grep SNSG --clear
# C2 阴性对照：对「昵称B」(全部可见) 重跑 S2 的 blockstat + W2，建正常写事件基线
# C3 self-exclude：看「昵称C」(自己) 动态上的写是否被放行(SnsGuard.kt:50-54)
dev.py q 'RCP_|SQLX|SNSG|SNS '
```

### S6 · 收口（不进入阶段 2 实现）

把全部 `dev.py q` 结果按 **P5 判定表**映射三态（R-C 可实现 / 证伪 / 证据不足），把「①S 是否成立 + ②S 上是否有写事件」贴回 Q4 路线对照表交用户拍板（`forensics:273`, `CLAUDE.md:42-43` D2）。**本 lane 到此为止，不实现 R-C、不写生产逻辑、不碰 R-A/R-B。**

[DONE] P7

---

## P8 证据分级 + 已知盲区

### 8.1 三栏证据分级

| ① 静态已证事实（带锚点） | ② 静态推断 `[推断]` | ③ 必须真机才能定 `[需真机]` |
|---|---|---|
| 六常驻钩子落点/before-after（`SnsGuard.kt:149-180`, `:297-298`, `:221-222`） | **生死假设本身**：服务端不下发⇒无写⇒空转（`forensics:189`, `:259` 标「最大风险」）——本套探针要定的核心，静态不可坐实 | **P1.1 三天外行是否在 + BLOB 完整性**（Q3 判据②，`forensics:129`, `:257`, `:271`） |
| `keepAliveSource`/`& -9→& -1`/`attachPrefix` 位置（`SnsBitLogic.kt:122-126`；`SnsSqlRewrite.kt:42,45-48`；`SnsGuard.kt:716`） | createTime 单位与 T0 边界（`forensics:140`, `:259`） | **★ P1.3 三天外行上是否存在写事件**（决定 R-C 生死，`forensics:260`, `:272`） |
| 进主页每进一次 6 条 `& -9`（`moments.md:24-25`, `SnsGuard.kt:61`, `SnsSqlRewrite.kt:10-13`） | 翻页能带出三天外行（`forensics:53`, `:222`） | P1.4 写事件是否落在 before 钩子可见的方法/签名上 |
| 删动态写 `sourceType=0`（`SnsGuard.kt:34-36`, `moments.md:1-19`, `wm:12-24`） | 对方改回全可见重复/顺序异常（`forensics:244`, `:263`） | P1.6 各场景实际可见条数（`forensics:261`, `moments.md:259-263`） |
| 服务端过滤/ic5·jc5·SnsObject 无 day·limit/相册 SQL 无 createTime 窗（`forensics:189`, `:211-215`, `moments.md:231,248-249`） | 清缓存对 `SnsInfo` 行行为（`forensics:245`, `:262`） | snsDb 捕获时机、冷启重捕获（`forensics:266`） |
| `op=sql` 只读白名单 + BLOB `hex()` 纪律（`SnsGuard.kt:1268-1282`, `:1322-1332`；`forensics:135-138`） | R-C 代码量/竞态估算（`forensics:186-189`） | 点开单图/详情是否被服务端拒（`forensics:263` 自留实测项） |
| 18 列 / `snsId` 无唯一约束（`moments.md:204-217`） | 「保位后能否翻到」的完整闭环需写库才能证（P2 标红） | 任一探针 `X` 层自证是否通过（前台/投递/TAG/加载） |
| 效果上限：翻页能翻到、首屏解决不了、库与服务端不一致（`wm:40-42`, `moments.md:258-263`） | — | — |
| 三档复位：库位磁盘态不回滚、无回滚能力（`forensics:239,243`, `_r42_purge_report.md:51-56`, `wm:42`） | — | — |
| 日志/广播/前台/二次解析规范（`CLAUDE.md`, `dev.py`, `SnsBlockProbe.kt:31-36`） | — | — |
| **无任何真机取证证明三天外行本地仍在**（`forensics:267`）⇒ Q3 `[需真机]` | — | — |

### 8.2 已知盲区（本套探针**仍然测不到**，不粉饰）

| 盲区 | 为什么测不到 | 对判定的影响 / 缓释 |
|---|---|---|
| **native 层写库** | 六个钩子全在 Java `com.tencent.wcdb.database.SQLiteDatabase` 方法上；若微信走 native `SQLiteConnection`/`nativeExecute` 直写，账本抓不到（同类：lane C 记 `java.io.File.delete` 拦不到 native `unlink()`，`laneC:32`, `:140` 引 `VoiceFileGuard.kt:35-38`） | 可能把「有 native 写」误判成 `NO_WRITE`。缓释：L3 库快照前后 diff 可间接发现磁盘态变化，但**归因不到方法** ⇒ 落 V7 证据不足 |
| **服务端响应组装 / 首屏内存对象** | 首屏由网络响应重建、全方法无 `d2` 调用（`moments.md:259-260`, `wm:41`）；模块无网络响应注入入口（`forensics:49`, `:258`），探针挂 DB 层 | 测不到「服务端到底少下发了哪些 id」，只能从「界面条数 vs `older3d`」反推；首屏永远空是已知上限，非探针缺陷 |
| **预编译 `SQLiteStatement.executeUpdateDelete` 直调** | 若对 `SnsInfo` 的写走预编译语句而非六个方法，账本抓不到（`laneC:33`, `:67` `RevokeHuntProbe` 故意不挂 statement 层；`docs/_r27_clear_api_forensics.md:8` 曾一次挂满 46 hooks 才覆盖） | 同 native 盲区：用库快照 diff 间接，不能方法级归因 |
| **触发器 / `changes()` / 级联隐式变化** | 不在方法级事件账本里 | 库快照 diff 兜底，归因缺失 |
| **多进程 / 多开 snsDb** | `installCapture` 拿到一个 `snsDb` 句柄即自我卸载（`SnsGuard.kt:1016-1017`）；微信 `:tools` 等进程另开库不覆盖 | 可能漏掉另一进程的写；`snsDb` 仅单句柄（`SnsGuard.kt:69-70`） |
| **微信版本漂移** | 8.0.48/2588 GP 特定混淆下方法签名；换版本 wcdb 重载形态变（`laneC:55-61`） | 本结论只对该版本有效 |
| **「保位后是否真被翻页带出」完整闭环** | 须真启用 R-C 生产逻辑（before 写回 `sourceType` / `attachPrefix` 补 content）⇒ 写微信库，本轮硬约束不实现 | 只读替代（L3 位状态 + C4 阳性对照 + W3 afterShape）只给**间接证据** ⇒ P5 的 **V9 证据不足**，不得粉饰成已证 |
| **content/attrBuf 可解析 / 可渲染性** | `hex()` 只证字节完整性（`SnsGuard.kt:1330-1332`），不证 protobuf 可解析、不证补 content 后界面能渲染 | 「补 content 后用户看得见」测不到，列 `[需真机]`/盲区 |
| **用户侧时序精度** | 界面条数靠 `dev.py ui` + 人工记录，无法毫秒级对齐写事件与 UI 刷新 | 时序层 T1 只能档位级对齐，不承诺帧级因果 |

[DONE] P8

> 本 lane 复核：全程只读，未修改任何源码 / 测试 / 配置 / 其它报告；未触碰 adb / 真机 / Gradle / 装包 / kvset；唯一写入文件为本 `docs/_r49_rc_probe_design.md`。所有未坐实项已标 `[推断]` / `[需真机]`，每条结论附 `文件:行号`。交付物 = 判据树 + 探针矩阵 + 判定表 + 三档边界表 + 最小步骤 + 盲区表，用于把 R-C 判到「可实现 / 证伪 / 证据不足」三态；不实现 R-C、不写生产逻辑、不碰 R-A/R-B。

[DONE] ALL
