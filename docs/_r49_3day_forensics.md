# R49 只读取证：突破「好友三天可见」（本地行补回）可行性

> 只读取证 lane 产出。全程未修改任何源码/测试/配置/结论文件，未接触 adb / 真机 / Gradle / 装包 / kvset。
> 锚点格式：`文件:行号` 或命令输出。`[推断]` / `[需真机]` 表示未坐实，不作为事实。

## 待答问题清单
- Q1 现状清点：存活的「本地朋友圈动态补进 UI」相关 hook 点逐类列（类名 / 挂的宿主方法 / 文件:行号 / 读库 or 吃响应），明确回答现在还有没有任何可用于注入/补回的存活入口。核对 `SnsGuard.kt` / `HookEntry.kt` / `MomentsAuto.kt` / `MomentsExport.kt` / `feature/` 下所有 `Sns*` 类。
- Q2 数据通路：8.0.48 好友个人主页 / 时间线数据来源，取证文档定位（`docs/conclusions/`、`docs/_r27*`、`docs/_r30*`、`docs/_r42*`），列出「响应类 / 写库方法 / 读库渲染方法 / SQL 谓词」，区分 R42 删除后「仍有效」vs「已失效」。
- Q3 本地行是否还在：有无真机/库层取证证明「三天可见范围外的动态本地 SnsInfo 仍保留行」；若无明确写 `[需真机]`，并给一条只读 `op=sql` 判据（查什么表/列、如何判定三天外；BLOB 判空禁止 `cast(... as text)`，用 `hex()`）。
- Q4 路线候选 2~3 条：每条给「核心动作 / 预估代码量 / 是否额外写微信库 / 竞态风险 / 失效场景 / 验证成本」，必须含至少一条不写库路线 + 一条复用 R42 写时拦截思路的路线。
- Q5 D4 优先：有无「阻止服务端过滤发生」或「无需补回即达成诉求」的路线；若无，指名具体机制说明为何做不到。
- Q6 三档边界：每条候选路线在 ①进入 ②返回 ③复位 分别发生什么、哪些状态残留；复位档不许写成「同返回」。
- Q7 证据分级：把结论分「已取证事实（带锚点）/ 静态推断 / 必须真机才能定」三栏，并写出「若要动手，最小可执行第一步」（≤3 条动作）。

## 交付表格（随 Q4 / Q6 / Q7 落盘）
- 路线对照表、三档边界表、证据分级表。

[START]

---

## Q1 现状清点（答完）

取证手法（均为只读）：`Glob wxgroup/app/src/main/java/com/lx/wxgroup/feature/Sns*.kt`（7 命中）、`Glob .../feature/Moments*.kt`（5 命中）、`Grep findAndHookMethod|hookAllMethods|XC_MethodHook|fun install|rawQuery|execSQL|updateWithOnConflict|onGYNetEnd`（Sns*/Moments* 分组）、`Grep install|Sns|Moments|hook`（HookEntry.kt）、`Grep c3.N|h3.N|k3.N|onGYNetEnd|zb5|SnsObject|NetSceneSns|211|212|5203`（app/src/main 全量）。

### 1.1 存活 hook 点逐类清点

| 类 | 挂的宿主方法（宿主 = `com.tencent.wcdb.database.SQLiteDatabase`，锚点 `docs/_r20b_sns_live_review.md:28`） | 读库 / 吃响应 / 其它 | 与「把本地行补进 UI」的关系 |
|---|---|---|---|
| `SnsGuard.kt` | `install()` `SnsGuard.kt:132`：`delete`→`hookAllMethods` `:149`（before，deleteGuard `:214-215`）、`execSQL` `:152`（before，sqlGuard `:221-222`）、`updateWithOnConflict` **after** `:158`（snsUpdateGuard `:236-237`）、`updateWithOnConflict` **before** `:163`（snsBlockGuard `:297-298`）、`insertWithOnConflict` `:168` / `insert` `:171`（snsInsertGuard `:249-250`）、`rawQuery` `:986`（captureHook，**拿到 snsDb 句柄即卸载** `:994/:1016`）；常驻钩子自述 `:178` | **写时拦截 + 读库捕获**，全部落在 wcdb 的 DB 方法上，**零网络响应** | 唯一能改变显示内容的能力 = 保住 `sourceType` 位（防删除，`SnsGuard.kt:257-263`），**是「保留已有行」不是「补回不显示的行」** |
| `SnsSql.kt` | 零 hook；`execSQL(sql)` 纯字符串识别删 `SnsInfo` `:31` | 其它（纯字符串） | 无 |
| `SnsSqlRewrite.kt` | 零 hook；`UPDATE SnsInfo SET sourceType = sourceType & -9` 改写成 `& -1`（恒等）`:4,:10-11,:46` | 其它（纯字符串改写，R42 方案 B） | 只保住 bit3，不注入行 |
| `SnsBitLogic.kt` | 零 hook；`keepAliveSource(old)` `:122`、位语义 `& -3`/`& -9` `:182/:186` | 其它（纯判据） | 无 |
| `SnsBlockProbe.kt` | 零 hook（被 `SnsGuard` 调用）；只读点查 `SnsInfo` `:654` | 读库（点查） | 无（账本/采样器，`:19`） |
| `SnsPrefixProbe.kt` | 零 hook；`rawQuery` 反射 `:370-383`；`attachPrefix` 生产路径说明 `:207-219` | 读库（探针） | 无（写前缀探针） |
| `SnsVideoDecProbe.kt` | 零 hook；`run()` `:55`，视频解密 `MMIMAGEENCJNI` `:13,:38` | 其它 | 无，与补位无关 |
| `MomentsAuto.kt` | `install()` `:194` → `WxRecyclerAdapter`（`:68`）的 `onBindViewHolder` `:602-612` / `onCreateViewHolder` `:618-624` / `setAdapter` `:634-653`，回调为 `after`（`bindHook` `:400-401`、`createHook` `:419-420`、`:653-654`）；附加 `MomentsSyncTrigger.kt:76`（NetSceneSnsSync，getType=214）与**临时** `x2.onGYNetEnd` 6 参监听 `:295-319` | **吃 UI 绑定回调 / 后台投递 sync 的回包状态**，不吃 `SnsObject` 列表 | 用途是自动刷新/自动加载（`:34,:308`），**不增删列表元素** |
| `MomentsExport.kt` | `install()` `:416`，**零 hook**（自述 `:415`，`HookEntry.kt:329` 同述） | 读库（`SnsGuard.listCurrentSns` → 文本文件导出） | 只落盘到文本文件，**不回写微信 UI** |
| `MomentsLogic.kt` / `MomentsNotify*.kt` | 无 `install`（`Grep` 未命中） | 其它（导出/通知逻辑） | 无 |
| `HookEntry.kt` | 接线：`SnsGuard.install` `:241`、`MomentsAuto.install` `:319`、`MomentsExport.install` `:331`；评论域整族移除 `:304-305` | — | 无注入 install 存活 |

补充：用户背景提到的写时拦截三件套落点已核实 —— 摘 `sourceType` 键的 before（`SnsGuard.kt:163/:297-298`）、`keepAliveSource`（`SnsBitLogic.kt:122`，`SnsGuard.kt:308` 引用）、`attachPrefix` 把 `[已删除]\n` 写进 `content` 的 protobuf field 5（`SnsGuard.kt:716`，调用 `:552/:605`，KDoc `:474-475/:702`；`SnsPrefixProbe.kt:14,:31,:35` 记 12 字节）。三者都在 `updateWithOnConflict` before 内，**属写库路径，不是 UI 注入**。

### 1.2 明确回答：现在还有没有任何可用于注入/补回的存活入口？

**没有。** 依据：
1. `Glob feature/Sns*.kt` 只剩 7 个文件（`SnsSql` / `SnsSqlRewrite` / `SnsBitLogic` / `SnsBlockProbe` / `SnsGuard` / `SnsPrefixProbe` / `SnsVideoDecProbe`），`SnsAlbumMerge` / `SnsTimelineMerge` / `SnsTimelineSourceFix` / `SnsTag` / `SnsRestoreLogic` / `SnsSqlSpy` / `SnsDeletedTag` 全部不在源码。
2. 全树 `Grep SnsAlbumMerge|SnsTimelineMerge|SnsTimelineSourceFix` 命中全部在 `docs/` 历史文档与 `tools/dev.py`，`app/src/main` **零命中**；删除清单见 `docs/_r42_purge_report.md:33`（feature 删 14 个、单测删 7 个）、`:66-69`（HookEntry 删 6 import / 7 install）、`:51-56`（SnsGuard 删归档恢复/写库整段）。
3. `Grep c3.N|h3.N|k3.N|zb5|SnsObject|NetSceneSns` 在 `app/src/main/java` 仅 6 行命中，全是 `MomentsSyncTrigger` / `MomentsAuto` / `MomentsLogic` 的**注释或字符串**，无任何 hook 注册到网络响应回调（`c3.N` / `h3.N` / `w5.onSceneEnd`）上。
4. `docs/conclusions/moments.md:27-32` 明列废弃清单；`:34` 说明甲方案让 `sourceType` 永不为 0，一切以「库里出现 0」为触发条件的子系统同时失去触发条件。
5. `MomentsExport` 是唯一「读本地 SnsInfo 全表」的存活通路（`_r42_purge_report.md:91-96` 已把归档分支删到只剩 `Src.CURRENT`），但产物是文本文件而非 UI。

> `[推断]` 从 R27「混合模型」看（`docs/conclusions/moments.md:258-263`：首屏/刷新走响应内存对象、翻页才查表），**若**本地三天外的行仍在且 `sourceType & 2 != 0`，翻页路径理论上可能带出它们，首屏不会。这是 Q3/Q4 的推理起点，**未经真机证实**。

---

## Q2 数据通路（答完）

### 2.1 既有取证文档定位

| 文档 | 状态 / 锚点 |
|---|---|
| `docs/conclusions/moments.md` | **主结论**。`:1-38` R42 订正段（甲乙方案、废弃清单、仍有效清单）；`:226-274` R27「时间线可见性 = `sourceType & 2`」；`:258-263` 混合模型；`:41` 库结构 |
| `docs/conclusions/moments_cdn.md` | CDN / 图相关（`:297` 宿主 ClassLoader） |
| `docs/_r27_plan_a.md` | 响应类定义：`:21-25` `c3.N` 链路、`:29-31` `h3`(212)/`k3`(5203) 订正、`:43-49` `attrBuf`=`SnsObject` 序列化 |
| `docs/_r27_m3_timeline_source.md` / `_r27_srcfix_impl.md` / `_r27_srcguard_refix.md` / `_r27_clear_api_forensics.md` / `_r27_backfill_cursor.md` | R27 时间线来源位 / 补位（**依赖已删代码**） |
| `docs/_r42_purge_report.md` | R42 删除清单；`docs/_r42_block_impl.md` / `_r42_bit_paths.md` / `_r42_bit_b_report.md` / `_r42_prefix_verify.md` 等 20+ 份为 R42 写时拦截取证 |
| `docs/未完成项与计划.md` | §6.2 `:315-338`、§6.3 `:339-351`、路线表 `:287-288/:373` |
| `docs/_r30_lane2_album_page.md`、`docs/_r30_62_album_gate.md` | ⚠️ **当前工作树不存在**：`Glob docs/_r30*` 与 `Glob docs/**/*r30*` 均 `No files found`；仅被 `_r27_plan_a.md:32`、`未完成项与计划.md:320` 引用。其结论只能经 `未完成项与计划.md:320-329` **转述**引用，不能当一手证据 |

### 2.2 四类通路清单

| 类别 | 具体内容 | 锚点 |
|---|---|---|
| **响应类 / 回包** | `c3.N(IILjava/lang/String;Lq54/zb5;)V` = `NetSceneSnsTimeLine`（211），遍历 `zb5.f` = `LinkedList<SnsObject>` → `l5.o` 写库 → `SnsTimelineRespListNotifyEvent` → UI | `docs/_r27_plan_a.md:21-25` |
| | 个人相册 `h3.N(Lq54/jc5;Ljava/lang/String;)V`（212），响应类 `q54/jc5`（与 `zb5` 的 `f` 同名同型） | `docs/未完成项与计划.md:320-321` |
| | `k3`(5203) = `NetSceneSnsWwUserPage`（CGI `mmsnswwuserpage`，ww=WeWork）= **企业微信**朋友圈，**订正：不是个人相册** | `docs/_r27_plan_a.md:30-31`、`docs/未完成项与计划.md:335` |
| | `w5.onSceneEnd` = 响应分发中枢；`i2.onGYNetEnd`(NetSceneSnsClassifyTimeLine) `addSourceFlag(2)` | `docs/conclusions/moments.md:248,:259` |
| **写库方法** | `l5.o`(SnsInfoStorageLogic.replace)：`SnsObject.toByteArray()` → `SnsInfo.attrBuf`；读回 `l5.f/l5.e` = `new SnsObject().parseFrom(attrBuf)` | `docs/_r27_plan_a.md:45-48` |
| | `update("SnsInfo", ContentValues{sourceType=0})` → `updateWithOnConflict`（**不是 DELETE**） | `SnsGuard.kt:34-36,:231-232` |
| | `execSQL "UPDATE SnsInfo SET sourceType = sourceType & -9 WHERE ..."`（进好友主页一次执行 6 条） | `SnsGuard.kt:61`、`SnsSqlRewrite.kt:10-11`、`docs/conclusions/moments.md:24-25` |
| | `d2.S7` 清位 | `docs/conclusions/moments.md:249` |
| **读库渲染方法** | 首屏 / 刷新：列表由**响应内存对象**渲染，`w5.onSceneEnd` 全方法无一次 `d2`（SnsInfo 存储层）调用，`SnsInfo` 只是旁路缓存 | `docs/conclusions/moments.md:259-260` |
| | 翻页：`d2.t5` 带 `stringSeq` 游标 —— **查表**；`c3.N` 内另有 `select snsId from SnsInfo where (sourceType & 2 != 0) limit 10` | `docs/conclusions/moments.md:232-233,:261` |
| | 好友相册：`qg3/i` 的 `d:List` 元素是 `SnsInfo` 存储实体，SQL 来自 `SnsInfoStorage.getUserMediaSql(...)`，执行者 `k2.k`；**相册是读库渲染，不是吃响应对象** | `docs/未完成项与计划.md:322` |
| | adapter `qg3/i` 层零过滤；唯一逐条过滤在 `qg3/k.b()`（`type` 必须 ==1 或 ==15）与 F1 `isDieItem()` | `docs/未完成项与计划.md:329` |
| **SQL 谓词** | 时间线 `... where (sourceType & 2 != 0) AND stringSeq < '…' order by createTime desc` | `docs/conclusions/moments.md:231`、`docs/未完成项与计划.md:324` |
| | 相册 `WHERE SnsInfo.userName="wxid_…" AND (sourceType in (8,264,10,…64 个全含 bit3 的值)) AND type in (1,2,3,…,101,50)` ⇒ **无 createTime 时间窗** | `docs/未完成项与计划.md:324`、`SnsSqlRewrite.kt:11` |
| | ⛔ `localFlag` 白名单（(16,2064,18,…)）为旧版臆断，8.0.48 不成立 | `docs/conclusions/moments.md:251-256` |

### 2.3 R42 删除代码后的有效性分界

**仍有效（数据通路本身没变，可继续引用）**，且 `docs/conclusions/moments.md:35-36` 自己点名「仍然有效、可继续引用的部分」：
- 独立库 `SnsMicroMsg.db` / `SnsInfo` 18 列（`docs/conclusions/moments.md:41`）与列名白名单；
- `sourceType` 位语义：`bit1(2)` 时间线 / `bit3(8)` 相册 / `bit5(32)` 广告 / `bit7(128)` 折叠（`docs/conclusions/moments.md:235-247`）；
- 两套 WHERE 谓词与「相册白名单 64 值全含 bit3、无 createTime 窗口」（`docs/未完成项与计划.md:324`）；
- 「混合模型」首屏内存渲染 / 翻页查表（`docs/conclusions/moments.md:258-263`）；
- `& -9` 每次进好友主页执行（`docs/conclusions/moments.md:24-25`）、adapter 绑定回调落点（`docs/conclusions/moments.md:36`）；
- **三天可见 = 服务端截断**：`SnsObject` 52 字段、请求 `ic5`(11)、响应 `jc5`(20) 全都没有 day/limit/visible 字段 ⇒ 客户端数据通路**完全不按天过滤**（`docs/未完成项与计划.md:345,:348`）。这一条与 R42 删代码无关，今天仍成立。

**已失效（依赖被删的注入/补位代码）**：
- `SnsAlbumMerge` / `SnsTimelineMerge` 的 `c3.N` / `h3.N` before 响应注入链路（`docs/conclusions/moments.md:29`、`_r42_purge_report.md:15-16`）；
- `SnsTimelineSourceFix` 事后补位 `|10` / `fixAlbum`（`docs/conclusions/moments.md:28`、`_r42_purge_report.md:12`）；
- `sns_archive` / `SnsRestoreLogic` 归档恢复（`docs/conclusions/moments.md:31`、`_r42_purge_report.md:83-86` GroupDb 删 T_SNS/T_SRC）；
- `SnsTag` / `SnsDeletedTag` 灰标、`SnsSqlSpy` 旁听、`srcmap/dump/one/show` 等探针（`docs/conclusions/moments.md:30-32`、`_r42_purge_report.md:73-76`）；
- 一切以「库里出现 `sourceType=0`」为触发条件的子系统（甲方案后 0 永不落地，`docs/conclusions/moments.md:34`）；
- ⚠️ `docs/未完成项与计划.md:373` 仍记「6.2+6.3 代码已交付 `SnsAlbumMerge.kt` + 已接线 HookEntry」——**与 R42 物理删除直接矛盾**，属未随 R42 订正的过时结论，不可再引用。

[DONE] Q1+Q2 现状清点+数据通路

---

## Q3 本地行是否还在（答完）

### 3.1 结论：没有。标 `[需真机]`

全仓 `Grep 三天可见|三天|时间范围|三天外|不下发|本地.*保留`（docs 全目录）后，**找不到任何一条真机/库层取证**证明「设了三天可见后，范围外的动态本地 `SnsInfo` 里仍保留行」。逐条甄别几个容易误当成证据的擦边材料：

| 材料 | 它实际证明了什么 | 为什么不算 Q3 的证据 |
|---|---|---|
| `docs/未完成项与计划.md:345` | 「超出范围的对象根本不下发」= **架构结论** | 是推理/定案，不是库层行数实测；且它恰恰暗示本地**可能没有**新字节 |
| `docs/未完成项与计划.md:348` | `SnsObject` 52 字段 / 请求 `ic5`(11) / 响应 `jc5`(20) **全无 day/limit/visible 字段** ⇒ 客户端不按天过滤 | **协议层**取证，与「本地 `SnsInfo` 有没有行」无关；同段自己还留了「剩下只有一条实测项：三天可见时点开单图/详情是否被服务端拒」未做 |
| `docs/_r27_m3_scene_facts.md:35-39`（断网冷启实验） | 断网冷启后时间线照常完整渲染 ⇒ 「渲染所需数据在本地」 | 语境是**好友删动态**（`:25-29` 对比 `localFlag=18` / `localFlag=2` 的同一行），证明「被删动态本地行还在」，**不是**「三天可见范围外行还在」 |
| `docs/_r27_m3_timeline_source.md:27` | 同上，「列表数据在本地可渲染」 | 同上，删动态场景 |
| `docs/_r27_srcguard_refix.md:109` | 「台账里的行永远保持可见 ⇒ 三天可见/分组可见之类由服务端收缩的内容也会留在本地时间线」 | 旧 srcfix **补位方案的语义边界 `[推断]`**，非实测，且该代码已随 R42 删除 |
| `docs/未完成项与计划.md:374` | 「真机验 6.2/6.3（需 verity 配合删一条 + **设三天可见**）」 | **仍在待办**，即该真机场景从未执行 |
| `docs/派发审计台账.md:1167` | R30-2 任务清单里包含「**三天可见的截断信号**」 | 列为**待取证任务**；且产物 `docs/_r30_lane2_album_page.md` 当前工作树不存在（Q2 §2.1 已记） |

⇒ **Q3 判定 `[需真机]`**：必须实机设三天可见后用 `op=sql` 查库才能定。这也是 Q4 全部路线的前置事实（若三天外行根本不在库里，所有「补回」路线无米之炊，见 `docs/未完成项与计划.md:347`）。

### 3.2 只读 `op=sql` 判据（不碰 adb / 不写库，符合本 lane 硬约束）

探针入口：`--es op sql --es q "select …" [--ei n 60]`（`docs/…/EntryReceiver.kt:652-662`），落到 `SnsGuard.runReadOnlySql(sql, maxRows)`（`SnsGuard.kt:1268`）。**只读保证**：白名单只允许 `select` 与只读 `pragma`（`:1253-1262, :1271`）、拒绝 `with`（`:1255-1257`）、拒绝内部 `;`（`:1261-1262, :1277-1279`）、`snsDb` 为空直接返回（`:1280`）、走反射 `rawQuery`（`:1281`）、行数受 `clampProbeN` 钳制（`:1282`）。

⚠️ **BLOB 判空的硬规矩（本仓已踩过坑）**：
- `SnsGuard.cellOf` 对 `FIELD_TYPE_BLOB` 只打印 `<blob NB>`（字节数），**不打印内容**（`SnsGuard.kt:1322-1329`）；其 KDoc `:1320` 记「本轮就因此误判过 `attrBuf` 全为 null ⇒ 正文永久丢失，实际两条都是 174 字节」。⇒ 直接 `select content` 拿不到字节证据。
- **唯一正确做法：在 SQL 里显式 `hex()`**。`hex(content)` 返回 TEXT，`cellOf` 会走 `getString` 分支输出完整 hex（`SnsGuard.kt:1330-1332`）。
- ⛔ **禁止 `cast(content as text)` 判空/检索**：SQLite 的 `cast(BLOB as TEXT)` 遇到 protobuf 里的 `0x00` **在第一个 NUL 处截断**，`instr(cast(content as text), "1370298124") = 0` 会假阴性（`docs/conclusions/moments_cdn.md:350-352` 明令「BLOB 列一律 `hex()` 导出后本地解析，禁止 `cast as text` 检索」）。同类先例：`docs/conclusions/notify_mute.md:63` 用 `instr(lower(hex(roomdata)),'6d757465')` 而非 cast。

**三条判据 SQL**（`<wxid>` / `<T0>` 由真机操作员填；`T0` = 三天可见边界的 `createTime` 阈值，`createTime` 单位需真机用界面最新一条反推校准 —— `[需真机]`）：

```sql
-- ① 先确认列名/类型（只读 pragma，白名单允许）
pragma table_info(SnsInfo)
-- 期望：moments.md:205-206 的 18 列，含 createTime / userName / sourceType / content / attrBuf

-- ② 三天外行是否还在 + 内容是否完整（核心判据）
select snsId, userName, createTime, sourceType, localFlag, type,
       length(content) as clen, length(attrBuf) as alen,
       hex(substr(content,1,16)) as chead, hex(substr(attrBuf,1,16)) as ahex
from SnsInfo
where userName='<wxid>' and createTime < <T0>
order by createTime desc
-- 判空用 length()/hex() 结果，不用 cast(... as text)

-- ③ 与界面可见条数对照
select count(*) as total,
       sum(case when createTime < <T0> then 1 else 0 end) as older3d,
       min(createTime), max(createTime)
from SnsInfo where userName='<wxid>'

-- ④ 顺带看位状态（决定 Q4 要不要修位）
select snsId, createTime, sourceType, (sourceType & 2) as bit1, (sourceType & 8) as bit3
from SnsInfo where userName='<wxid>' and createTime < <T0>
```

**判定标准**：
- ② 返回行数 > 0 且 `clen>0`（`chead` 非 `''`）、`alen>0` ⇒ 「三天外动态本地行仍在且 `content`/`attrBuf` 完整」**成立**，补回有米之炊；
- ② 返回 0 行，或行在但 `clen=0` / `chead=''` ⇒ 前提**不成立**，任何注入/补位路线直接证伪，只能回到「从没在本地加载过的补不出来」（`docs/未完成项与计划.md:347`）；
- ③ 的 `older3d` 与同时刻界面可见条数对比：`older3d>0` 而界面不显示 ⇒ 坐实「服务端把三天外行挡在界面外、但本地行还在」；
- ④ 若这些行 `sourceType=0` 或丢 bit1/bit3 ⇒ 需要额外修位（Q4 的 R-C 才有意义）。

全部为 `select` / 只读 `pragma`，**零写库**。`snsDb` 需微信先刷一次朋友圈才被捕获（`SnsGuard.kt:1280` 的 `SQLX no sns db yet`），这是 `[需真机]` 的操作前提。

---

## Q4 路线候选 + 路线对照表（答完）

> D2 要求「动手前必须出路线对照表」；下表即对照表，**不自评最优**，留给用户拍板。三条路线**全部以 Q3 判据成立为前提**。代码量为静态估算，标 `[推断]`。

### 路线对照表（核心动作 / 预估代码量 / 是否额外写微信库 / 竞态风险 / 失效场景 / 验证成本）

| 项 | **R-A 纯展示 / 只读导出扩展**（不写库） | **R-B 读库·渲染层过滤旁路**（不写库） | **R-C 复用 R42 写时拦截**（写微信库） |
|---|---|---|---|
| **核心动作** | 复用 `MomentsExport` 已有的 `SnsScanCursor` + `SnsGuard.listCurrentSns` 键集分页（`MomentsExport.kt:104,:759-779,:966-976`；数据源自述 `:27,:34`），按 `createTime < T0` 筛出三天外行，渲染成模块自己的可浏览页/文件；不碰微信 UI、不改微信进程任何 hook | 把 `SnsGuard.installCapture` 的 `rawQuery` 临时钩子（`SnsGuard.kt:986-994`，原本「拿到句柄即卸载」）改成**常驻**，在 before 里按 `SnsSqlRewrite` 的窄正则范式（`SnsSqlRewrite.kt:30-35,:46`）改写 `d2.t5` / `getUserMediaSql` 的 SQL（放宽 `stringSeq` / 补 UNION 本地行），或 hook `qg3/i` 数据集把本地 `SnsInfo` 行补进列表（落点 `docs/未完成项与计划.md:322,:329`） | 扩展 `snsBlockGuard`（`SnsGuard.kt:297-298`）的 before 判据：对三天外行把 `sourceType` 用 `SnsBitLogic.keepAliveSource`（`:122`）写回 `ContentValues` 保底值，`execSQL` before 继续把 `& -9` 改写为 `& -1`（`SnsSqlRewrite.kt:46`）；必要时用 `attachPrefix` 同款 BLOB 写（`SnsGuard.kt:716`）补 content |
| **预估代码量** `[推断]` | 小：判据 + 渲染约 100~200 行，复用现成扫描/导出管线，零 Xposed 新增 | 中~大：常驻 rawQuery 钩子 + SQL 改写判据 + 单测约 200~400 行；若走 `qg3/i` 内存补行还要额外构造 `SnsInfo` 实体 | 小~中：约 150~300 行，6 个钩子与 `keepAliveSource` / `attachPrefix` 现成，主要扩展判据 |
| **是否额外写微信库** | **否**（只读 `SnsInfo`，产物写模块自己的导出目录，`MomentsExport.kt:415-420` 自述零 hook 纯后台落盘） | **否**（只改传给 `rawQuery` 的 SQL 字符串 / 改内存列表，不 INSERT/UPDATE 微信库） | **是**（before 里写回 `ContentValues.sourceType`；若补行还会 INSERT，且 `snsId` 无唯一约束 `docs/conclusions/moments.md:214-215`） |
| **竞态风险** | **低**：后台单航班线程 + tmp/rename 原子落盘（`docs/conclusions/moments.md:74`），与微信 UI 无并发写 | **高**：`rawQuery` 是微信**所有表**的热路径（`SnsBlockProbe.kt:31` 明记 LogX 5000 行环形限流）；首屏走响应内存不查库，与查库路径不同步；分页 `stringSeq` 游标可能被打乱 | **中高**：before 半场与微信写库线程并发；`self-exclude` 例外（`SnsGuard.kt:50`）需按 `userName` 排除自己；幂等与回滚；`& -9` 裸 SQL 是第二通道（`SnsGuard.kt:60-61`） |
| **失效场景** | 用户要的是「微信原生界面里补回」，另开页面不满足诉求；导出依赖开关/名单/定时器（`MomentsExport.kt:424-440`）；`snsDb` 未捕获时 `listCurrentSns` 返回空；点开单图/详情可能被服务端拒（`docs/未完成项与计划.md:348` 留的实测项） | **首屏/刷新走响应内存对象、全方法无 `d2` 调用**（`docs/conclusions/moments.md:259-260`）⇒ 钩子只在翻页/相册查表时生效，**首屏依然空**；相册 SQL **本来就没有 createTime 窗口**（`docs/未完成项与计划.md:324`）⇒「去时间窗」无的放矢，真正缺的是响应列表里没有这些 id；`qg3/k.b()` 的 `type∈{1,15}` 与 F1 `isDieItem()` 会把补进去的行再挡掉（`:329`）；微信 SQL 形态一变窄正则即失效 | **最大风险：服务端不下发 ⇒ 微信本地根本不会对这些行执行 `update`/`execSQL` ⇒ before 钩子没有触发机会**。这与「防删除」本质不同：删动态时微信会主动写 `sourceType=0`（`SnsGuard.kt:34-36`），三天可见时**没有这个写**（`docs/未完成项与计划.md:345`）；三天外行若已被物理 DELETE 或 `content` BLOB 被清空（`docs/conclusions/moments_cdn.md:350-352`）⇒ 无米之炊；`snsId` 无唯一约束 ⇒ 补 INSERT 可能造重复行 |
| **验证成本** | **低**：一次真机导出 + 界面条数对照，无需新增 hook、无需 dex 核对 | **高**：需 dex 反汇编核对真实 SQL 形态（R30 的 `in (64)` 就是静态推断翻车，`docs/未完成项与计划.md:323,325`），且要覆盖进入/下拉/翻页/冷启/切页多场景 | **中高**：需真机设三天可见 + 抓 `updateWithOnConflict`/`execSQL` 事件账本（`SnsBlockProbe.kt`）+ 冷启 + 对方改回全可见；**若 Q3 判据显示这些行上没有任何写库事件，本路线直接证伪** |

### 逐条要点补充
- **R-A** 是「纯配置/纯展示」档：完全满足「至少一条不写库」，且是唯一**不触碰微信进程 hook** 的路线；代价是产物不在微信原生界面。
- **R-B** 是「读库/渲染层过滤层旁路」档：也不写微信库，但需新增常驻 `rawQuery` 钩子；`docs/conclusions/moments.md:36` 把「adapter 绑定回调落点」列为仍有效事实，`MomentsAuto.kt:602-624` 已有 `onBindViewHolder` 挂载先例可借鉴。
- **R-C** 是「复用 R42 写时拦截思路」档：沿用真机已跑通的「wcdb DB 方法 before 半场改参数」范式（`docs/conclusions/moments.md:3-13`、`docs/_r42_wechatmagician.md:37`），但必须先在 Q3 真机确认「三天外行上有写库事件」才有活路。

[DONE] Q3+Q4 本地行判据+路线对照表

---

## Q5 D4 优先（答完）

> D4 原文：`CLAUDE.md:48-49`「能阻止事件发生，就不要先费力检测事件是否发生。只有无法阻止时才退到『观测 + 补救』。⛔ 需要判据本身就说明你在补救。」

### 5.1 有没有「阻止服务端过滤发生」的路线？——没有，且要指名机制

不是泛泛的「服务端限制」，而是下面 5 条**具体机制**决定了本地无从下手：

| # | 机制 | 锚点 |
|---|---|---|
| 1 | **请求/响应里根本没有可放宽的参数**：`SnsObject` 52 字段、请求 `ic5`(11 字段)、响应 `jc5`(20 字段) **全都没有 day/limit/visible 字段** ⇒ 没有「时间窗闸门」这个参数可以改大或删掉 | `docs/未完成项与计划.md:348` |
| 2 | **过滤发生在服务端组装响应列表，不在客户端**：`c3.N` 遍历的 `q54/zb5.f` 就是服务端下发的 `LinkedList<SnsObject>`，范围外对象**根本不在这个 LinkedList 里**；客户端不是「收到了再按时间过滤」，而是「没收到」⇒ 本地**不存在一个可以旁路的过滤层** | `docs/_r27_plan_a.md:21-22`；`docs/未完成项与计划.md:345` |
| 3 | **相册 SQL 本来就没有 createTime 窗口**：两套 WHERE 是 `sourceType & 2 != 0` 与 `sourceType in (64 个含 bit3 值)`，无时间谓词 ⇒ 连「去时间窗」这个动作都无的放矢；真正挡住的是响应列表里没有这些 id | `docs/未完成项与计划.md:324`、`docs/conclusions/moments.md:231` |
| 4 | **服务端连「拒绝信号」都不下发**：好友删动态场景实测响应体 `q54/zb5` 无 `deletedIds` / tombstone，靠「少返回」而非「多下发一条删除记录」⇒ 没有标记可拦、可伪造 | `docs/conclusions/moments.md:265-268` |
| 5 | **换接口也拿不到**：列表本身要服务端按权限给；`k3`(5203) 经订正是 `NetSceneSnsWwUserPage`（企业微信朋友圈），不是绕过口子；Xposed 只能改本地进程，改不了远端响应组装 | `docs/未完成项与计划.md:345`、`docs/_r27_plan_a.md:30-31` |

⇒ 「阻止服务端过滤」= 让服务端多下发字节，本地模块**没有任何落点**。

### 5.2 有没有「不需要补回就能达成用户诉求」的路线？——严格意义没有

- 用户诉求 = 在微信里看到三天外动态；唯一数据源是本地残留的 `SnsInfo` 行，必须把它们**呈现出来**（注入/补回/另开页面展示）才看得到，否则界面只有服务端给的三天内对象（`docs/未完成项与计划.md:345-347`）。R-A 虽不写微信库、不进微信原生 UI，但本质仍是「把本地数据呈现出来」的替代形态，不满足字面诉求。
- **弱意义上的例外（要标 `[推断]`/`[需真机]`）**：若 Q3 判据成立且这些行 `sourceType & 2 != 0`，**翻页查表路径本来就可能带出它们** —— `docs/_r42_wechatmagician.md:40` 明记「本地行保持 `&2!=0` ⇒ **翻页能翻到**」、`docs/conclusions/moments.md:261` 翻页走 `d2.t5` 查表。这算「不用注入、只要走查表路径」。但同一份文档 `:41` 立刻限定「**首屏解决不了**（首屏由网络响应重建）」，且 `:42` 承认库状态与服务端不一致。⇒ 最多覆盖翻页，首屏/下拉刷新后依然空；是否真能带出、带出多少受 `stringSeq` 游标约束，`[需真机]`。

### 5.3 D4 在本场景的裁定

「上游事件」= 服务端按好友权限裁剪响应列表，**发生在远端、本地不可控** ⇒ D4 的「控制优先」在此**无落点**。唯一能在本地「控制」的是「本地行别被清位/别被删」——R42 甲方案已经在做（`docs/conclusions/moments.md:34`「`sourceType` 永不为 0」），但它阻止不了服务端不下发新对象。因此只能退到 D4 后半句「观测 + 补救」；而 `CLAUDE.md:49` 说「需要判据本身就说明你在补救」——**Q3 的 `op=sql` 判据正是这个补救标志**。这也是 Q7 把「最小第一步」定为只读取证而非写码的依据。

---

## Q6 三档边界表（答完）

> ①进入 = 刚设三天可见 / 刚进好友主页；②返回 = 切页、下拉刷新、后台同步回来；③复位 = 关开关 / 冷启 / 清缓存 / 对方改回全部可见。
> **复位档不是「同返回」**：返回时模块仍启用、钩子仍在、库位仍在，只是内存列表被新响应覆盖；复位时模块能力关闭或外部条件归零，但**已写进库的位是磁盘状态，不随开关/冷启回滚**。

| 路线 | ①进入 | ②返回 | ③复位 | 残留状态 |
|---|---|---|---|---|
| **R-A 纯展示** | 定时器/手动触发扫 `SnsInfo`、按 `createTime<T0` 导出三天外行；须 `snsDb` 已捕获（`SnsGuard.kt:1280`）；微信首屏界面仍只有三天内，不受影响 | 切页/下拉/后台同步**不改变导出结果**（读库，与微信响应无关）；若库行被同步改动，下一轮导出随之变；已导出条目按 `<snsId>.txt` 去重不重写（`docs/conclusions/moments.md:74`） | **关开关**：定时器停，但**已落盘文件不删**；**冷启**：`snsDb` 句柄丢失、需微信再刷一次朋友圈才重新捕获（`docs/_r20b_sns_live_review.md:91-97` + `SnsGuard.kt:1280`），期间导不出；**清缓存**：若 `SnsInfo` 行被清（`[需真机]`）则导出变空；**对方改回全部可见**：界面自然显示全部，但导出文件仍是旧筛选快照，**不自动更新** | 导出目录里的文件/索引与旧快照（不自动清理）；无微信库残留 |
| **R-B 读库·渲染旁路** | 常驻 `rawQuery` 钩子在首次查表（翻页 `d2.t5` / 相册 `getUserMediaSql`）时改写 SQL / 补 UNION；**首屏无效果**（首屏由网络响应重建，`docs/_r42_wechatmagician.md:41`、`docs/conclusions/moments.md:259-260`） | 切页再进：微信每进一次主页执行 6 条 `& -9`（`docs/conclusions/moments.md:24-25`）⇒ 钩子每次重跑；**下拉刷新用新响应覆盖内存列表**，内存补行被冲掉，但翻页查表路径仍在；后台同步（`NetSceneSnsSync`，`core/MomentsSyncTrigger.kt:76`）回来的响应同样不含三天外 id ⇒ 界面又只剩三天内 | **关开关**：钩子/判据停，补回行立即消失；**冷启**：钩子需重新 install + `snsDb` 重新捕获（`_r20b_sns_live_review.md:91-97`），首轮可能完全无效果；**清缓存**：本地行消失 ⇒ 无的放矢（`[需真机]`）；**对方改回全部可见**：服务端恢复下发，UNION/内存补行与真实响应**重复**（R31h 重复注入事故先例 `docs/_review_r31h_sns.md:115-119`） | 常驻 `rawQuery` 钩子本身（不卸载一直生效）、被改动过的 adapter 数据集（下次刷新覆盖）、日志限流状态；**无微信库残留**（不写库） |
| **R-C 复用写时拦截** | **仅当**微信对这些行执行 `updateWithOnConflict` / `execSQL` 才触发；进主页的 `& -9` 清 bit3（`SnsGuard.kt:61`）被 B 层改写为 no-op；若三天外行上**没有任何写库事件**（服务端不下发的后果），钩子**空转** ⇒ 整条路线证伪。效果上限 = 保住 `sourceType` ⇒ 翻页能翻到，**首屏仍解决不了**（`docs/_r42_wechatmagician.md:40-41`） | 切页再进：`& -9` 每进一次 6 条（`moments.md:24-25`）⇒ B 层每次再拦；下拉刷新覆盖内存列表，翻页查表仍带出库里的行；后台同步若带来新的清位写，before 再拦一次 | **关开关**：判据停，但**已写入微信库的 `sourceType` 保底值与 `[已删除]\n` content 前缀不会自动还原**（`docs/_r42_wechatmagician.md:42`「与服务器端状态不一致」、`SnsPrefixProbe.kt:67` 幂等前缀）；且 R42 已把 `undoInsert`/`rollbackStep`/`restore`/`show` 回滚能力整段删除（`docs/_r42_purge_report.md:51-56`）⇒ **当前源码没有任何回滚路径**；**冷启**：钩子重新 install、`snsDb` 重新捕获，但**库里的位/前缀是磁盘状态，冷启后仍在** ⇒ 反而可能比刷新后更容易翻到（`docs/conclusions/moments.md:263`「刷新后消失 + 冷启后可能还在」）；**清缓存**：若 `SnsInfo` 行被清（`[需真机]`）则库残留随之消失；**对方改回全部可见**：服务端恢复下发，被保位的旧行可能造成重复/顺序异常 `[推断]` | ⚠️ **`sourceType` 位、`[已删除]\n` content 前缀、库与服务端状态不一致**（`docs/_r42_wechatmagician.md:42`）——三条路线里**唯一有持久微信库残留**的一条，且无回滚能力 |

### 三档最容易漏的点（复位档）
1. **冷启 ≠ 下拉刷新**：刷新用新响应覆盖内存列表，冷启是进程重启 ⇒ `snsDb` 句柄与钩子都要重建（`_r20b_sns_live_review.md:91-97`、`SnsGuard.kt:1280`），但**库里的位/前缀活过冷启**（`moments.md:263`）。
2. **关开关 ≠ 恢复原状**：R-C 写进微信库的是磁盘状态，开关只关判据，不回滚；R42 删除后已无 `undoInsert`/`rollbackStep`（`_r42_purge_report.md:51-56`）。
3. **对方改回全部可见**会引入新的重复源：服务端恢复下发后，模块补回/保位的旧行与真实响应并存 ⇒ R-B 的 UNION 与 R-C 的补行都需要去重判据 `[推断]`（R31h 事故先例）。
4. **清缓存**是唯一可能把「本地行还在」这个前提整个抹掉的动作，但微信清缓存对 `SnsInfo` 的实际行为**无取证** ⇒ `[需真机]`。

[DONE] Q5+Q6 D4裁定+三档边界表

---

## Q7 证据分级 + 最小可执行第一步（答完）

### 7.1 证据分级表（三栏）

| ① 已取证事实（带锚点） | ② 静态推断 | ③ 必须真机才能定 |
|---|---|---|
| `feature/Sns*.kt` 只剩 7 个文件；`SnsAlbumMerge`/`SnsTimelineMerge`/`SnsTimelineSourceFix`/`SnsTag`/`SnsRestoreLogic` 全不在源码（`Glob`；`docs/_r42_purge_report.md:33`） | 三条路线的**代码量估算**（Q4 表）| **三天可见范围外动态，本地 `SnsInfo` 是否仍保留行**（Q3 判据 ②） |
| 存活 hook 全部落在 wcdb DB 方法 / UI adapter 上，**无网络响应注入入口**（Q1 表；`Grep c3.N\|h3.N\|zb5` 在 app/src/main 仅注释命中） | 「首屏内存渲染 / 翻页查表」的混合模型下，**三天外行若位未清，翻页理论上可能带出**（Q1 `[推断]`、Q5 §5.2） | 这些行的 `content`/`attrBuf` 是否完整（`clen>0` / `hex` 非空）、`sourceType` 是否已丢 bit1/bit3（Q3 判据 ②④） |
| `attachPrefix` 在 `SnsGuard.kt:716`（调用 `:552/:605`）；`keepAliveSource` 在 `SnsBitLogic.kt:122` | R-C **空转风险**：服务端不下发 ⇒ 三天外行上可能没有任何写库事件 ⇒ before 钩子无触发机会（Q4/Q5/Q6） | `createTime` 单位与 `T0` 边界值校准（Q3） |
| `op=sql` 只放 `select`/只读 pragma、拒 `with`/内部 `;`、钳 60 行（`SnsGuard.kt:1268-1282`；`EntryReceiver.kt:652-662`） | Q6 三档边界里**除有明文锚点外**的行为预测（重复源、清缓存后果、对方改回全可见的重复/顺序异常） | **三天外行上是否存在 `updateWithOnConflict` / `execSQL` 写库事件**（决定 R-C 生死） |
| `cellOf` 对 BLOB 只输出 `<blob NB>`（`SnsGuard.kt:1322-1329`）；`cast(BLOB as TEXT)` 被 `0x00` 在首个 NUL 截断（`docs/conclusions/moments_cdn.md:350-352`）⇒ 必须 SQL 内 `hex()` | `_r27_srcguard_refix.md:109`「三天可见之类由服务端收缩的内容也会留在本地时间线」是旧补位方案的**语义边界推断**，非实测 | 首屏 / 翻页 / 下拉刷新 / 后台同步 / 冷启 各场景的实际可见条数（`docs/conclusions/moments.md:259-263`） |
| 数据通路四类清单与两套 WHERE 谓词（Q2 表；`_r27_plan_a.md:21-25,30-31`、`moments.md:231-233,248-249,258-263`） | `docs/未完成项与计划.md:373`「6.2+6.3 代码已交付」与 R42 删除矛盾 ⇒ 判为**过时结论**（文件直接比对） | 清缓存对 `SnsInfo` 行的实际行为（Q6 复位档） |
| R42 后「仍有效 / 已失效」分界（`docs/conclusions/moments.md:27-36`） | 对方改回全部可见后模块补回行与真实响应**可能重复**（R31h 事故先例 `docs/_review_r31h_sns.md:115-119` 类推） | 点开单图 / 详情是否被服务端拒（`docs/未完成项与计划.md:348` 自留的实测项） |
| `docs/_r30*` 与 `docs/**/*r30*` 均 `No files found` ⇒ R30 相册页一手文档当前不可读（Q2 §2.1） | D4 在本场景「无控制落点」、只能退「观测+补救」的裁定（Q5 §5.3，基于 `CLAUDE.md:48-49` 的机制推理） | 任一路线 R-A/R-B/R-C 的真机验收（五阶段阶段 3，`CLAUDE.md:20-23`） |
| `_r42_wechatmagician.md:40-42`：本地行保持 `&2!=0` ⇒ 翻页能翻到、**首屏解决不了**、库状态与服务端不一致 | — | — |
| `snsDb` 一经捕获不再复位、常驻到进程结束（`_r20b_sns_live_review.md:91-97`）⇒ 冷启后需重新捕获（`SnsGuard.kt:1280`） | — | — |
| **不存在**任何真机/库层取证证明「三天外行本地仍在」：`未完成项与计划.md:374` 真机验 6.3 仍在待办、`派发审计台账.md:1167` 把「三天可见的截断信号」列为待取证任务（Q3 §3.1） | — | — |

### 7.2 若要动手，最小可执行第一步（≤3 条动作，均为只读取证 / 决策，不写码、不装包）

1. **真机跑 Q3 §3.2 的四条 `op=sql` 只读判据**（设三天可见 + 同时刻界面对照）：`pragma table_info(SnsInfo)` → ②三天外行 + `hex()` 完整性 → ③`older3d` 计数 → ④位状态。这是 R-A/R-B/R-C **共有的唯一前置**；若 ② 返回 0 行或 `clen=0` ⇒ 前提证伪，整题直接收口（回到「从没本地加载过的补不出来」`docs/未完成项与计划.md:347`）。
2. **同一真机抓 `SnsBlockProbe` 事件账本**（`op=snsg sub=blockstat/trace`）看三天外行上**是否真的出现** `updateWithOnConflict` / `execSQL` 写库事件：有 ⇒ R-C 才有触发机会；无 ⇒ R-C 直接出局，只剩 R-A/R-B。
3. **把 ①② 结果贴回 Q4 路线对照表交用户拍板**（D2：`CLAUDE.md:42-43`「动手前必须写一张表并贴给用户选，没有这张表不许开工」）：①不成立 ⇒ 只留 R-A 或收口；①成立且②有写事件 ⇒ R-A/R-B/R-C 三选一；①成立但②无写事件 ⇒ R-A/R-B 二选一。**本 lane 到此为止，不进入阶段 2 实现**。

> 本 lane 复核：全程只读，未修改任何源码 / 测试 / 配置 / 其他结论文件；未触碰 adb / 真机 / Gradle / 装包 / kvset；唯一写入文件为本 `docs/_r49_3day_forensics.md`。所有未坐实项已标 `[推断]` 或 `[需真机]`，每条结论附 `文件:行号` 或命令输出锚点。

[DONE] Q7+证据分级表
[DONE] ALL
