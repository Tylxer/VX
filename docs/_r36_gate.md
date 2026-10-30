# R36 · gate lane 产出（VersionGate fail-open 修复 + 三处过期文档订正）

> 执行者：r36-gate（实现 lane）
> 日期：2026-10-30
> 依据：`docs/_r35_pend_decisions.md` P1（方案 A）/ P3（§F 作废）/ P4（三处文档订正）
> 纪律：**未跑 gradle、未碰 adb、未装包**。只改了 1 个源码文件 + 2 个文档文件。

---

# 任务 A · `VersionGate` fail-open（方案 A）

## A.0 缺口回顾（为什么必须改）

`core/VersionGate.kt` 白名单原有两条：

| # | versionName | versionCode | signatureSha256 |
|---|---|---|---|
| ① | `8.0.48` | `2588L` | `0fe4ff85…a3c7c`（完整） |
| ② | `8.0.72` | **`0L`**（默认值） | **`null`**（默认值） |

而 `check()` 的两条校验带短路前置：

- `if (matched.versionCode != 0L && …)` ⇒ `vc == 0` 时**整段跳过**
- `if (matched.signatureSha256 != null && …)` ⇒ `sha == null` 时**整段跳过**

⇒ 第②条 = **两个校验都短路 = 无条件 `Ok`**，即 fail-open。
微信一旦升到 8.0.72，闸门放行，但锚点全是 `Anchors848.kt` 里按 **8.0.48 硬编码**的常量，
跨版本自动定位（`DexKitLocator`）**未接入** ⇒ 「门禁说 OK、钩子全挂不上」，
最坏反射取到错对象**崩微信** —— 与 `VersionGate.kt:8` 自述「宁可功能失效，也不能让微信闪退」相反。

## A.1 改动 1：删除 `8.0.72` 条目

**改动前**（`VersionGate.kt:54-55`）：

```kotlin
        // 原定目标版本，实机尚未安装，先留着
        Target(Channel.GOOGLE_PLAY, "8.0.72"),
    )
```

**改动后**（`VersionGate.kt:55-68`，原条目删除，原位留说明注释）：

```kotlin
        // ⛔ 2026-10-30 删除：原 8.0.72 条目只给了 channel + versionName，
        // versionCode 取默认 0L、signatureSha256 取默认 null。而 check() 里两条校验分别是
        // `if (matched.versionCode != 0L && …)` 与 `if (matched.signatureSha256 != null && …)`，
        // 两个前置条件都为假 ⇒ 整段短路 ⇒ 该条目等价于「无条件 Ok」。
        // 这是 fail-open：微信升到 8.0.72 时闸门放行，但锚点仍是 Anchors848 里按 8.0.48
        // 硬编码的常量、DexKitLocator 的跨版本自动定位尚未接入 ⇒ 钩子全挂不上，
        // 最坏反射取到错对象导致微信闪退 —— 正与本文件开头「宁可功能失效也不能闪退」相反。
        // 现在版本不匹配会得到 Reject("version 8.0.72 not whitelisted, supported: 8.0.48")。
        //
        // 以后要支持 8.0.72：必须**连同锚点一起**加（先取证该版本的类名/方法/字段，
        // 再按版本分派，Anchors848 只覆盖 8.0.48），否则加回来就是重开这个 fail-open。
        // ⛔ 不要用「versionCode=0 表示不校验」这种隐式写法表达「临时放行」——
        // 这就是本次这个坑的成因。取证期需要临时放行：本地改、不入库，
        // 或显式加一个 allowIncomplete 字段并在日志里打 "gate ok (INCOMPLETE TARGET)"。
    )
```

注释三条必备信息齐备：**为什么删**（vc=0 + sha=null 在 `check()` 里是「不校验」语义 ⇒ 无条件放行）／
**以后要加必须连同锚点一起加**（`Anchors848` 是 8.0.48 硬编码、`DexKitLocator` 未接入）／
**⛔ 禁用「vc=0 表示不校验」隐式写法**（本坑成因）。

## A.2 改动 2：修注释漂移（KDoc「静默退出」）

**改动前**（`VersionGate.kt:11`）：

```
 * 版本不匹配时静默退出，一行桩都不打。
```

**改动后**（`VersionGate.kt:11-12`）：

```
 * 版本不匹配时不打任何桩，并打一条 `LogX.w("DISABLED: <reason>")` 后退出
 * （见 HookEntry.kt 的 `Verdict.Reject` 分支）—— 便于排查，不是无声无息。
```

订正依据：`HookEntry.kt:117-119` 实际在 `Verdict.Reject` 分支打 `LogX.w("DISABLED: ${verdict.reason}")`
后 `return`。原 KDoc 的「静默…一行桩都不打」会让人排查时以为**完全没有日志**，与实际不符。
（「一行桩都不打」这部分仍成立 —— 是真的不打桩，只是**有日志**。）

## A.3 判据 P1-J1（静态 · ✅ 通过）

命令（在 `app/src/main/java/com/lx/wxgroup/` 下）：

```
grep -n "Target(" core/VersionGate.kt
```

结果：

```
26:    data class Target(
49:        Target(
```

- `:26` = `data class Target(` 的**声明处**，非白名单条目；
- `:49` = 白名单**唯一条目** `8.0.48`，完整内容：

```kotlin
        Target(
            Channel.GOOGLE_PLAY,
            "8.0.48",
            versionCode = 2588L,                       // ← 非 0 ✅
            signatureSha256 = "0fe4ff85c215918396dadc7cd8ce6963339af33d37751a56e54c7206b63a3c7c",  // ← 非 null ✅
        ),
```

⇒ **白名单每条都有非 0 `versionCode` + 非 null `signatureSha256`**，P1-J1 通过。
（顺带：注释里刻意**不写** `Target(` 字面量，避免以后 `grep "Target("` 被注释污染。）

⚠️ 未验证项：本 lane **未编译**。改动是「删掉一个 `listOf` 元素 + 在 `)` 前留一段纯注释」，
Kotlin 语法上合法（`listOf(x, /* comment */)`），但**编译通过未经构建确认**，留给下次装包时顺带验。

## A.4 待真机判据清单（本 lane 不做，留档）

| # | 判据 | 通过标准 | 状态 |
|---|---|---|---|
| P1-J2 | 真机 8.0.48 冷启一次 | 日志同时满足：有 `host version=8.0.48 code=2588`、有 `gate ok: gp`、**无** `DISABLED` ⇒ 证明没误伤当前版本 | ⏳ 待真机 |
| P1-J3 | 造一次不匹配（本地把 `whitelist` 的 `versionName` 临时改 `8.0.49` 编一版，**验收后改回、不入库**） | 日志出现 `DISABLED: version 8.0.49 not whitelisted`，且**无** `hook registered` / 无 `boot completed` ⇒ 证明闸门真关得上 | ⏳ 待真机 |
| P1-J4 | 未来真升到 GP 8.0.72 时（**被动判据**） | 出现 `DISABLED: version 8.0.72 not whitelisted`，微信**不崩**、功能全失效 ⇒ A 的目的达成。⚠️ **不要为了验它去升微信** | ⏳ 等自然升级 |

## A.5 边界遵守情况

- ✅ 只改 `core/VersionGate.kt` 一个文件；
- ✅ 未动 `HookEntry.kt` / `core/Anchors848.kt` / `core/DexKitLocator.kt`；
- ✅ 未重构 `check()`（`vc != 0L` / `sha != null` 的短路语义保持原样 —— 方案 A 不依赖改它）。

---

# 任务 B · 三处过期文档订正 + §F 作废横幅

订正纪律：`未完成项与计划.md` 文件头写明**本文已降级为「历史追溯」** ⇒ 一律
**保留原句 + 加「✅ 已订正（2026-10-30）」标注 + 现状**，不抹掉原文、不改成执行文档。

## B1-1 §1.3 `ChatroomSource` 触发时机（现 `未完成项与计划.md:116-117`）

**原文（保留）**：

> | **现状** | `runIfNeeded()` 只挂在 `ContactSync.doSync` 尾部 ⇒ 用户不打开通讯录就永远不跑（真机实测 `cached=0 runs=0 lastSync=never`） |

**新增订正行**：

> | **✅ 已订正（2026-10-30）** | 上面的「只挂在 `ContactSync.doSync` 尾部」**已过期**。代码**已按上面选项②实施**：
> `PickerTabs.kt:49-52` 在 `all()` 入口补了 `ChatroomSource.runIfNeeded()`，`PickerPage.kt:261`
> 注册弱引用完成回调，扫完 `written>0` 时主线程回调「群聊」Tab 补出。节流靠 `shouldRun`
> （`ChatroomSource.kt:109-114`：`cached==0` 或 `now-last > FULL_INTERVAL_MS` 才跑；`runIfNeeded`
> 内部 `force=false` 再判一次），所以 2.4s 全表扫只在**新装/清缓存**时出现一次，不阻塞 UI（daemon 线程）。
> **当前只差真机验**（判据见 `docs/_r35_pend_decisions.md` P4-F1J1~J4）。
> 出处：`docs/_r29_f1_chatroom_trigger.md` |

并把「选项」行的②标注为**已选②**、「后果」行标注已化解。

## B1-2 §1.4 无名群排序（现 `未完成项与计划.md:123` 下方新增块）

**原文（保留）**：

> `ORDER BY remark, nickname` 把空名群排最前 ⇒ 一进群聊 Tab「全是数字群号」。
> 已证伪为**非缺陷**（这些群在微信源表里就没名字，显示群号是正确兜底）。
> 可选改成 `CASE WHEN nickname='' THEN 1 ELSE 0 END` 把有名字的排前面。
> 出处：`docs/_r24_picker_tabs.md` §6②、§7.4

**新增订正块**：

> **✅ 已订正（2026-10-30）**：上面「把空名群排最前」的现状描述**已过期**。
> **已实施**，且走的是 **Kotlin 侧**而非上面建议的 SQL `CASE WHEN`：
> `data/ChatroomSortLogic.kt` 的 `hasName(remark, nickname) = remark.isNotBlank() || nickname.isNotBlank()`
> + `sortNamedFirst`（`compareBy { 0/1 }` 稳定排序，组内保持数据库原有顺序）；应用点 `GroupDb.kt:1340`。
> ⛔ **刻意不改** `GroupDb.queryFriends` 里那条共享 `ORDER BY`（好友列表 / `findByDisplayName` 共用，
> 改了会波及「好友」Tab）⇒ 上面建议的 SQL 层 `CASE WHEN` 方案**未被采纳**（R29-4）。
> **当前只差真机观感验**（判据见 `docs/_r35_pend_decisions.md` P4-F2J1~J4）。

## B1-3 §四 表格首行「评论防删除」（现 `未完成项与计划.md:230-231`）

**原文（保留）**：

> | 评论防删除 + 评论`[已删除]`标签 | 用户 2026-10-20 拍板不做；代码 `SnsCommentGuard` / `SnsCommentTag` / `SnsCommentLogic` **保留冻结**，是否物理删除待你定 | 需求清单 6.2.1 |

**新增订正行**（紧随原行，用 `↳` 标记）：

> | ↳ **✅ 已订正（2026-10-30）** | 上一行的「**保留冻结**，是否物理删除待你定」**已过期** ——
> **已于 2026-10-24 物理删除**（N7），问题已不存在，无需再拍板。删除范围：
> `SnsCommentGuard` / `SnsCommentTag` / `SnsCommentLogic` / `SnsCommentLabelLogic` + 两个测试
> （约 207KB / 121 个单测）。三处留档：`HookEntry.kt`（R35 材料记 `:343-346`，当前实际在 `:355-358`
> 附近，行号有漂移）、`EntryReceiver.kt:616-617`、`SnsGuard.kt:1572-1573`。
> 残留的 `SnsComment` 只剩**数据库表名**（`SnsGuard.kt:1599` `select * from SnsComment`），不是功能代码。
> ⛔ 将来若重启该功能，先看留档里两条硬阻碍（attrBuf 载体 / UI 评论 proto 不带 `commentSvrID`） |

## B2 `docs/_r30_62_album_gate.md` §F 作废横幅

加在 §F **标题下、正文前**（原 §F 正文一字未改）。横幅全文：

```
> ⛔⛔ **本节的「结论与建议」已作废（2026-10-30 标注）· 不要拿它当依据**
>
> 本节末尾那句「**6.2 的正确解法不是改库，是干预 `NetSceneSnsUserPage` 的响应合并
> （`SnsAlbumMerge` 方向）**」—— **这个建议后来没有被采纳**。
>
> - **现象判据本身仍然成立**：「点进去的一瞬间有，马上消失」确实是**网络回包覆盖**造成的
>   （`h3.N` 收响应 → `updateWithOnConflict` 按服务端值整行 upsert → 补好的 bit8 被打回），
>   这部分与现行代码的认识一致，可继续引用。
> - **但解法走了相反的路 —— 改库**：现行实现是 `SnsTimelineSourceFix.fixAlbum`
>   + **`patchAlbumBit`**，并把补位点**压进了写库事务窗口**（由 `SnsGuard.kt` 在
>   `SnsInfo` 的 update after 里调用），读库时看不到中间态；配套还有
>   `installAlbumTrigger`（挂 `h3.N` after）与 `installAlbumSyncTrigger`（挂 `x2.onGYNetEnd`）。
>   三处都在 `HookEntry.kt` **实际启用**（不是注释）。
> - **为什么否掉响应注入**：相册是**读库渲染**（`qg3/i` 的 `d:List` 元素是 `SnsInfo` 存储实体），
>   往 `h3.N` 的 `jc5.f` 塞对象**对 UI 无直接增量**，只能借道 `l5.o` 写库，属绕路；
>   且与上面这套已启用的改库路线**功能重叠**，两套都往 `SnsInfo` 写必然打架。
>   `SnsAlbumMerge` 至今保持**停用**。
> - ⇒ **现在若拿本节当依据，会得出与现行代码相反的结论。** 详细论证见
>   `docs/_r35_pend_decisions.md` P3（推荐：防 / 就此收口 / 不新做响应注入）。
>
> （以下原文保留，仅作追溯。）
```

横幅讲清了三件必答的事：**该建议已被否**／**现行路线是什么**（改库：`fixAlbum` + `patchAlbumBit`，
补位点压进写库事务窗口）/ **为什么**（读库渲染 ⇒ 注入对 UI 无增量、只能借道 `l5.o` 绕路写库，
且与已启用路线重叠会打架）。

---

# 本 lane 的取证纪律声明

- **只改源码 1 个文件**：`app/src/main/java/com/lx/wxgroup/core/VersionGate.kt`（2 处改动，
  均在文件内，未动其它任何 `.kt`）。
- **只改文档 2 个文件**：`docs/未完成项与计划.md`（3 处点状订正）、`docs/_r30_62_album_gate.md`（1 处横幅）。
  未重写全文、未改文档结构。
- **未跑 gradle、未碰 adb、未装包** ⇒ 改动**未经编译验证**；真机判据（P1-J2/J3/J4）**未执行**，已在 A.4 留档。
- 所有「现行代码是什么」的表述均转引自 `docs/_r35_pend_decisions.md`（P3 / P4）+ 本次对
  `VersionGate.kt` / `HookEntry.kt:115-121` / `HookEntry.kt:355-358` 的直接阅读。

[DONE]
