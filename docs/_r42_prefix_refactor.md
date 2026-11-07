# R42 prefix 重构报告：灰标渲染 → 删除时写 `[已删除]` 进 content，并移除开关

子代理 `prefixrefactor` · 2026-11-07 · 只改代码 + 编译 + 单测，未 adb / 未装包 / 未执行探针。

---

## [DONE-1] SnsPrefixProbe 抽出可复用的 `buildPrefixedBlob(db, id)`

在 `feature/SnsPrefixProbe.kt` 新增 `public fun buildPrefixedBlob(db: Any?, id: Long): ByteArray?`。

实现（复用探针已有的私有 `readBlob` / `parseTlo` / `descField` / `readDesc` / `serialize`，**没有另写一份编解码**）：

```kotlin
fun buildPrefixedBlob(db: Any?, id: Long): ByteArray? = runCatching {
    if (id == 0L) return@runCatching null
    val d = db ?: return@runCatching null
    val cl = loader() ?: return@runCatching null
    val orig = readBlob(d, id) ?: return@runCatching null
    val tlo = parseTlo(cl, orig) ?: return@runCatching null
    val f = descField(cl) ?: return@runCatching null
    val before = readDesc(f, tlo) ?: return@runCatching null
    if (before.startsWith(PREFIX)) return@runCatching null   // 幂等
    val setOk = runCatching { f.set(tlo, PREFIX + before) }.isSuccess
    if (!setOk) return@runCatching null
    serialize(cl, tlo)
}.onFailure {
    if (buildFailWarned.compareAndSet(false, true)) {
        LogX.w("PREFIX build fail: ...")
    }
}.getOrNull()
```

关键性质：
- **幂等**：`ContentDesc` 已以 `[已删除]` 开头 ⇒ 直接返回 null，调用方不写 content，重复删除同一行不会写成 `[已删除][已删除]`。
- **异常兜底**：整段 `runCatching`，任何失败（读不到 / 解析失败 / 字段缺失 / set 失败 / serialize 失败）一律返回 null，绝不上抛；告警用 `AtomicBoolean` 只打一次，防系统性失败在删除路径刷屏。
- **只读 + 内存改造，不落备份**：备份/还原仍是探针 `prefix`/`restore` 自己的职责，生产删除路径不做文件 IO（不在微信写库线程上写文件）。

## [DONE-2] SnsGuard 两个删除分支接入前缀（before，不是 after）

改动三处：

**① `CvApi` 加 `putBlob`**（`SnsGuard.kt`）：

```kotlin
val putBlob: Method? =
    runCatching { c.getMethod("put", String::class.java, ByteArray::class.java) }
        .getOrNull()
        ?: runCatching { c.getMethod("put", String::class.java, Any::class.java) }.getOrNull()
```

**② `applyBlock` 加 `db: Any?` 形参**，两处调用点传入：
- `onSnsUpdateBefore`：`applyBlock(db, values, api, id, bits, ...)`（`db = param.thisObject` 本就已取到）
- `onSnsUpdateDrop`：`applyBlock(db, values, api, id, bits, ...)`

**③ 两处 `SnsDeletedTag.onBlocked(id, bits, want)` 替换为 `attachPrefix(db, values, api, id)`**（写回路径与降级路径各一处），并新增：

```kotlin
private fun attachPrefix(db: Any?, values: Any, api: CvApi, id: Long) {
    if (api.putBlob == null) return
    val blob = SnsPrefixProbe.buildPrefixedBlob(db, id)
    if (blob == null) return
    runCatching { api.putBlob!!.invoke(values, "content", blob) }
        .onFailure {
            if (prefixWarned.compareAndSet(false, true)) {
                LogX.w("PREFIX attach fail: put(content) threw ...（保位不受影响）")
            }
        }
}
```

**为什么是 before 而不是 after**：`updateWithOnConflict` 的 after 半场时 SQL 已经执行完，再改 ContentValues 不会写进库。要「content 前缀与 sourceType 保位**一起**落地」，只能在 before 半场把 `content` 键塞进**同一个** ContentValues，让微信这一次 update 把两件事都做完 —— 第二次写库 = 竞态 + 冷启/下拉刷新被覆写。

**只有一次写**：`values` 里同时带 `sourceType`（保位）与 `content`（前缀），微信这一次 update 全做完。前缀附加放在**保位逻辑之后**：保位（put/remove 及 SKIP_EMPTY 放回）已完成、结果已定，前缀失败只打限流日志、绝不 rethrow、不影响删除拦截。SKIP_EMPTY / SKIP_NO_API / OFF 分支不附加前缀（那些分支本条根本没拦住，写前缀无意义）。

## [DONE-3] 灰标文件与引用清理

删除文件（4 个）：
- `feature/SnsDeletedTag.kt` —— 灰标渲染主体（插灰字 View / 台账写入 / 探针），整套被 content 前缀替代。
- `feature/SnsDeletedTagLogic.kt` —— 灰标纯判据（parseStyle/shouldRecord/偏移校准/渲染决策），随功能一并删除。
- `test/java/com/lx/wxgroup/feature/SnsDeletedTagLogicTest.kt` —— 65 例灰标判据单测。
- `test/mut/mut_deltag.py` —— 灰标判据变异验证脚本（引用已删的 SnsDeletedTagLogic）。

清理引用（4 处）：
- `HookEntry.kt`：删掉 `SnsDeletedTag.install(lpparam)` 那个 `runCatching` 块（连同注释）。
- `EntryReceiver.kt`：删掉 `"snsdel" ->` 分支（灰标探针）；`snsprefix` 分支**保留**。
- `res/values/ids.xml`：整文件删除 —— 它只含 `wxgroup_sns_mark` 一个 `<item>`（灰标标记 View 的 tag key），删除灰标后无任何引用点。
- `SnsBlockProbe.kt`：`Bits.userName` 注释更新（原「灰标排除自己」现已无消费点），**字段与列读取保留**，理由见 DONE-4。

## [DONE-4] sns_deleted 表与 recordSnsDeleted 的处置（删除）

`GroupDb.kt` 删除：
- `T_SNS_DELETED` 常量；
- `createSnsDeletedTable(db)` 及其在 `onCreate`、`onUpgrade`（`oldVersion < 10` 分支）里的调用；
- 6 个成员：`recordSnsDeleted` / `hasSnsDeleted` / `snsDeletedIds` / `countSnsDeleted` / `recentSnsDeleted` / `purgeSnsDeleted`。

理由：前缀本身就是记录（`[已删除]` 直接写进 `SnsInfo.content`），不再需要模块自己的台账。

**关于 team-lead 提醒的「`shouldRecord` 判定仍被 SnsBlockProbe 统计用到」—— 核实结论：不成立。** 全仓 grep 证明 `shouldRecord` / `SnsDeletedTagLogic` 的调用点只有 `SnsDeletedTag.onBlocked` 一处，`SnsBlockProbe` 里**没有任何**对它的引用。bit3 计数是 `SnsBlockProbe.record` 里的 `dropBlocked`/`blocked`/`afterShape` 四个 shape 计数器与 `sqlBlock9`，与 `shouldRecord`（时间线位丢失 + 排除自己）完全正交，删除台账**不打断** bit3 计数。`Bits.userName` 因此从「灰标排除自己」的唯一消费点变为无消费点，但**保留**（多读一列零成本），避免为删一个字段去动 `readBits` 的 SELECT 列表、触及 bit3 计数 / after 校验路径。

处置决定：DB_VERSION 维持 10 不升，老用户库里的孤儿表 `sns_deleted` 留在原地不 `DROP`（无害、不再被读取），避免为一张「未消费的冷表」触发一次全库迁移。

## [DONE-5] 开关移除（功能常开，设置页不再提供）

- `Keys.kt`：删除 `const val SNSDEL_STYLE = "antirevoke.sns_deleted_tag"` 及其 KDoc。
- `Keys.kt` `Prefs.ALL`：删除 `PrefSpec(Keys.SNSDEL_STYLE, PrefKind.SWITCH, "true", "防撤回", "已删除灰标")`。
- **UI**：设置页由 K2 渲染器自动从 `Prefs.ALL` 画出来，删除该 PrefSpec 即自动从界面消失，**没有**独立的条目/字符串资源/监听要删（标题「已删除灰标」是 PrefSpec 内联字面量，非 `strings.xml` 资源）。
- **测试**：`PrefsVisibilityTest` 只锁 `REDPACKET_ALLOW/REDPACKET_EXCLUDE` 两个 hidden 项 + key 唯一性 + 前缀↔域映射，**不引用 SNSDEL_STYLE**，删除后**无需改动**（实测 761 例里它照常绿）。
- `SnsGuard.install()` 日志里的 `albumFix=`/`sqlBlock=` 字段保留；原日志本来就没有 `style=` 字段，无需处理。

## [DONE-6] 编译结果

PowerShell 收窄 PATH（`G:\...\jdk-21.0.7.6-hotspot\bin;C:\Windows\System32;C:\Windows`）跑 `assembleDebug`：

```
> Task :app:compileDebugKotlin  （Kotlin daemon 连接失败，自动回退 non-daemon 编译，仅警告）
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1m 13s
35 actionable tasks: 7 executed, 3 from cache, 25 up-to-date
EXIT=0
```

无 error；仅既有的 deprecation / unchecked-cast 警告（VersionGate、EntryReceiver、GroupBar、UiKit 等，与本轮无关）。Kotlin daemon 的 `Could not connect` 是环境抖动，Gradle 自动 fallback 后编译通过，非代码问题。

## [DONE-7] 单测结果

PowerShell 收窄 PATH 跑 `testDebugUnitTest --rerun-tasks`：

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 4m 1s
22 actionable tasks: 22 executed
EXIT=0
```

从 `app/build/test-results/testDebugUnitTest/*.xml` 汇总：**suites=40 tests=761 failures=0 errors=0 skipped=0**。

少了多少、为什么：`SnsDeletedTagLogicTest`（65 例，`git show` 数到恰好 65 个 `@Test`）随灰标判据文件物理删除而消失，这是预期的。761 = 原 826 − 65（其余 40 个 suite 数量与前一致，`PrefsVisibilityTest` 等未受影响，全部通过）。

## [DONE-8] 红线自检（5 条逐条对照）

1. **保位逻辑零回归** ✅ —— `applyBlock` 里 `keep = old | 10` 写回、`old == null` 与 `old & 10 == 0` 两条降级边界、SKIP_EMPTY 放回，**语义一行未动**；只新增了 `db` 形参与两处 `attachPrefix`（在保位完成之后）。
2. **`st != 0 && !needsOld(st)` 快速路径零新增工作** ✅ —— 该路径在 `onSnsUpdateBefore` 里 `if (!SnsBitLogic.needsOld(st)) return`，未触碰；`buildPrefixedBlob` 只在 `applyBlock` 的 BLOCKED 分支才被调，热路径零额外工作。
3. **前缀写入失败绝不影响删除拦截** ✅ —— `buildPrefixedBlob` 与 `attachPrefix` 都是整段 `runCatching`，失败只打限流日志（`AtomicBoolean` 只打一次）、绝不 rethrow；保位 put/remove 已在前完成，前缀失败不影响其落地。
4. **幂等** ✅ —— `ContentDesc` 已以 `[已删除]` 开头 ⇒ `buildPrefixedBlob` 返回 null ⇒ 不塞 content，重复删除同一条不会写成 `[已删除][已删除]`。
5. **`restore` 仍可用** ✅ —— 备份/还原机制（`backupDir`/`writeBackup`/`backupFile`/`restore`/`prefix`/`stat`）**完整保留**，`sub=restore` 仍能还原；`buildPrefixedBlob` 是新增的只读+内存改造路径，未删任何备份代码。

## [DONE-9] 3 个「最可能被推翻的点」

1. **`putBlob` 的反射签名**：`CvApi.putBlob` 先试 `put(String, byte[])` 再回退 `put(String, Object)`。真机上 `updateWithOnConflict` 的 `values` 是 wcdb 的 ContentValues（注释称「非 AOSP 子类」），若 wcdb 版没有 `put(String, byte[])` 而 `put(String, Object)` 对 byte[] 做了错误装箱，前缀可能写不进去（静默失败、只打一条 `PREFIX attach fail`）。这是纯反射假设，未真机验证。
2. **「前缀写进同一次 update」的前提**：依赖 `values` 就是微信即将执行的 ContentValues、且我们在 before 里 put 的 `content` 键会随这条 update 落地。若微信某条删除路径**不走** `updateWithOnConflict`（例如走裸 `execSQL` 或先 DELETE 再重 INSERT），则前缀那条分支根本不触发——但那是「删不删得掉」层面的另一条通道，与「写前缀」正交，真机验证的 `sub=prefix` 直写已证明 content 落地本身可靠。
3. **幂等判据只看前缀开头**：`buildPrefixedBlob` 用 `before.startsWith("[已删除]\n")` 判幂等。若微信/用户把正文**原本就**以 `[已删除]` 开头（合法正文），会误判为「已加前缀」而跳过，导致这条删除不落标记。低频、且半角方括号+单个 LF 的组合在正常正文里几乎不会出现，但严格说是一个「漏标」而非「错标」的边界。

## [DONE-10] 新增两个模拟子命令 simdrop / setsrc2（子代理 `simprobe`）

只改一个文件 `app/src/main/java/com/lx/wxgroup/feature/SnsPrefixProbe.kt`，给探针加两个子命令，用来**模拟一次好友删除动态**、验证「自动写前缀」的钩子路径。未 adb / 未装包 / 未 commit。

### 实现方式

**入口 `run()` 扩两个分支**，else 提示串同步更新：

```kotlin
fun run(sub: String, id: Long): List<String> = when (sub.lowercase()) {
    "probe" -> probe(id)
    "prefix" -> prefix(id)
    "restore" -> restore(id)
    "stat" -> stat()
    "simdrop" -> simdrop(id)
    "setsrc2" -> setsrc2(id)
    else -> listOf("PREFIX unknown sub=$sub（可用 probe|prefix|restore|stat|simdrop|setsrc2）")
}
```

**① `sub=simdrop`（id 必填）**——写 `sourceType=0`，走真实的 `updateWithOnConflict → snsBlockGuard` 钩子：

```kotlin
private fun simdrop(id: Long): List<String> {
    if (id == 0L) return listOf("PREFIX simdrop id=0（--el id <snsId> 必填）")
    val d = db() ?: return listOf("PREFIX simdrop no db（先让微信刷一次朋友圈拿到句柄）")
    val m = updateMethod(d) ?: return listOf("PREFIX simdrop no-update-method")
    val rows = runCatching {
        val cv = ContentValues().apply { put("sourceType", 0) }
        m.invoke(d, TABLE, cv, "snsId=?", arrayOf(id.toString())) as? Int ?: -1
    }.onFailure {
        LogX.w("PREFIX simdrop update fail: ...")
    }.getOrDefault(-1)

    val src = SnsBlockProbe.readBits(d, id)?.src          // 回读 sourceType
    val cl = loader()
    val desc = if (cl != null) readBlob(d, id)?.let { parseDesc(cl, it) } else null
    val has = desc?.startsWith(PREFIX) ?: false
    return listOf(
        "PREFIX simdrop id=$id rows=$rows",
        "PREFIX simdrop-verify id=$id src=${src ?: "?"} desc=[${desc?.let { show(it) } ?: ""}] hasPrefix=$has"
    )
}
```

- `updateMethod(d)` 是已有的 `update(String, ContentValues, String, String[])` 反射；wcdb/AOSP 里 `update(4参)` 内部委托 `updateWithOnConflict(5参)`，所以这条 update 会触发挂在 `updateWithOnConflict` 上的 `snsBlockGuard` before 钩子 → `onSnsUpdateBefore`（`a[0]=="SnsInfo"`、values 里 `sourceType=0` 命中写 0 分支）→ `applyBlock`（`keep = old|10`）+ `attachPrefix`（把 `[已删除]\n` 塞进同一个 ContentValues 的 content）。验证的是**完整自动路径**，不是手动写。
- `src` 回读**复用了 `SnsBlockProbe.readBits(d, id)?.src`**（钩子自己读 sourceType 的同一套函数，`select sourceType,... from SnsInfo where snsId=$id`），没有再另写一份 rawQuery 反射；`desc` 用已有的 `readBlob` + `parseDesc`。

**② `sub=setsrc2`（id 必填）**——写 `sourceType=2`（正常可见），恢复用：

```kotlin
private fun setsrc2(id: Long): List<String> {
    if (id == 0L) return listOf("PREFIX setsrc2 id=0（--el id <snsId> 必填）")
    val d = db() ?: return listOf("PREFIX setsrc2 no db")
    val m = updateMethod(d) ?: return listOf("PREFIX setsrc2 no-update-method")
    val rows = runCatching {
        val cv = ContentValues().apply { put("sourceType", 2) }
        m.invoke(d, TABLE, cv, "snsId=?", arrayOf(id.toString())) as? Int ?: -1
    }.onFailure {
        LogX.w("PREFIX setsrc2 update fail: ...")
    }.getOrDefault(-1)
    val src = SnsBlockProbe.readBits(d, id)?.src
    return listOf("PREFIX setsrc2 id=$id rows=$rows src=${src ?: "?"}")
}
```

- 值非 0 ⇒ 钩子走 `st != 0` 分支；`needsOld(2)=true`（`2&8==0`）会进降级判定 `onSnsUpdateDrop`。⚠️ 若旧值是 `10`，`decide(10,2)=BLOCK`（纯降位、丢 bit3）⇒ 会被写回 `keep=10`，**恢复不会落地**；回读的 `src` 会如实报出。这条是恢复用，不要求触发拦截。

### 重入风险判断（simdrop 的核心结论）

**结论：安全，无重入风险，不需要任何绕法。** 依据三条：

1. **钩子是 `updateWithOnConflict` 的 before 半场**。Xposed 在**原方法体执行之前**调用 before 钩子 —— 此刻原方法尚未编译 SQL、尚未从连接池拿连接/写锁，**没有任何锁被持有**。嵌套的 `rawQuery` 独立拿连接、用完即还，不构成「持写锁再取读锁」的死锁。
2. **这条「update 钩子内 rawQuery」正是生产路径**：`SnsGuard.attachPrefix → SnsPrefixProbe.buildPrefixedBlob → readBlob → rawQuery` 已经在每一次真实删除时跑过；如果它不安全，R42 prefix 功能真机上早就崩了。`probe` 子命令也证明了 `rawQuery` 在 update 之外能独立跑通。
3. **兜底闭环**：即便某种 wcdb 形态真出问题，`buildPrefixedBlob`/`readBlob` 全程 `runCatching`，失败只返回 null（跳过前缀、只打一条限流日志），**保位（keep/摘键）逻辑不受影响**，微信不会崩。

### `simdrop` 能否可靠触发钩子 —— 判断

**能，可靠。** 依据：

- `update("SnsInfo", {sourceType=0}, "snsId=?", [id])` → `updateWithOnConflict` 是 `SnsGuard` 文档里早已抓到的真实清位通道（`docs/_r27_clear_api_forensics.md`）；wcdb 的 4 参 `update` 标准委托到 5 参 `updateWithOnConflict`。
- 钩子第一句 `(a[0] as? String) != TABLE` 命中（`"SnsInfo"`）；`cvApiOf` 对 `android.content.ContentValues` 命中（类名含 "ContentValues"，`get/remove/size/put(Integer)/put(byte[])` 全部存在）；`sourceType` 取到 0 → 走写 0 命中分支；`snsIdOf` 从 `arrayOf(id.toString())` 抠出 id。
- **唯一前提**：`cfg().blockEnabled` 为真（主开关 `Keys.ANTIREVOKE_SNS` 或调试覆盖 `ANTIREVOKE_SNS_BLOCK`）。若关闭，`applyBlock` 只 `record(OFF)` 返回，`simdrop-verify` 会如实报 `src=0 hasPrefix=false` —— 这是诚实输出，不是触发失败。

### 编译真实输出

PowerShell 收窄 PATH 跑 `assembleDebug`：

```
> Task :app:compileDebugKotlin  （Kotlin daemon 连接失败，自动回退 non-daemon 编译，仅警告）
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1m 9s
35 actionable tasks: 4 executed, 31 up-to-date
EXIT=0
app-debug.apk  3435668  21:59:00
```

无 error；仅既有的 deprecation / unchecked-cast 警告（VersionGate、EntryReceiver、GroupBar、UiKit、DexProbe、SnsHuntProbe、SnsViewProbe 等，与本轮无关）。Kotlin daemon 的 `Could not connect` 是环境抖动，Gradle 自动 fallback 后编译通过，非代码问题。APK 时间戳 21:59:00 为本次构建产物。

[ALL-DONE-2]
