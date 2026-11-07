# R42 前缀探针实现报告（prefixwriter）

> 任务：加最小验证探针，真机把 `[已删除]` 写进一条动态 `content`（field 5 `ContentDesc`），并支持还原。
> 范围：只加探针，不动灰标逻辑、不动开关。只写代码 + 编译，不 adb / 不装包 / 不执行探针。

---

## [DONE-1] SnsPrefixProbe.kt 实现（四个子命令 + 备份路径 + 幂等）

新文件 `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsPrefixProbe.kt`，object，入口
`run(sub, id): List<String>`。四个子命令：

| sub | 行为 | 输出（前缀 `PREFIX `） |
|---|---|---|
| `probe` | 只读：`select content` → `parseFrom` → 取 `ContentDesc` 前 30 字，**不写库** | `probe id=… len=… desc=[…] hasPrefix=true/false` |
| `prefix` | 先备份 → 加前缀 → `update` 单列 | `write id=… before=… after=… rows=N backup=…` |
| `restore` | 从备份文件读回原始 blob → `update` 单列 | `restore id=… rows=N from=…` |
| `stat` | 列备份目录里的 snsId 与字节数 | `backups=[snsId:bytes,…]` |

关键点落位：
- **前缀常量** `private const val PREFIX = "[已删除]\n"`（半角方括号 + 单个 LF，共 12 字节，⛔ 非全角、非 `\r\n`）。
- **备份落实**：写到 `HostContext.app()?.filesDir` 下 `sns_prefix_backup/<snsId>.bin`；
  `prefix` 里 `writeBackup` 若文件已存在**不覆盖**（保留最早的原始版本），输出 `backup=kept-existing(NB)`。
- **幂等**：`ContentDesc` 已以 `[已删除]` 开头 ⇒ `prefix` 直接 `skipped=already`，不重复加、不写库。
- **只改 content 一列**：`update("SnsInfo", ContentValues{content=…}, "snsId=?", [id])`，不整行覆盖、不反射 `SnsInfoStorage.update`。
- **反序列化用宿主 ClassLoader**：`Class.forName(TLO_CLASS, false, hostLoader)`，字段/方法全走反射。
- **对 `parseFrom` 的返回值取 `ContentDesc`**（不是对 newInstance 的空 `tlo` 取），返回值 null 兜底报错。
- 字段读不到（`NoSuchFieldException`）/ parse 失败 / update 失败，全部 `LogX.w` 如实报错，不静默。

---

## [DONE-2] SnsGuard 暴露 snsDbHandle / 宿主 ClassLoader 的改法

`SnsGuard.kt` 仅动了三处（全部是「加」，未改任何现有逻辑/开关）：

1. **`snsDbHandle()` 已存在**（`SnsGuard.kt:860` 原文 `internal fun snsDbHandle(): Any? = snsDb`），
   **本轮无需新增** —— 任务清单里「只加一个 `snsDbHandle()`」是过时描述，实际它早就在。
2. 新增宿主 ClassLoader 字段（放在 `snsDb` 字段旁边）：
   ```kotlin
   @Volatile
   private var hostLoader: ClassLoader? = null
   ```
3. `install()` 第一行记下：`hostLoader = lpparam.classLoader`
   （放在 `findClass` 之前，即使 wcdb findClass 失败早退，探针也还能拿到 loader）。
4. 新增只读暴露：
   ```kotlin
   internal fun hostLoaderHandle(): ClassLoader? = hostLoader
   ```

---

## [DONE-3] EntryReceiver 接入（改动后的片段）

在 `"sql" -> { … }` 分支**后面**新增，走 `io {}`（读/写朋友圈库必须后台线程，防 ANR）：

```kotlin
// 写前缀最小验证探针（只读 probe / 写 prefix / 还原 restore / 列备份 stat）
// --es op snsprefix --es sub probe|prefix|restore|stat --el id <snsId>
"snsprefix" -> {
    val sub = intent.getStringExtra("sub").orEmpty()
    val id = intent.getLongExtra("id", 0L)
    io {
        for (l in com.lx.wxgroup.feature.SnsPrefixProbe.run(sub, id)) {
            LogX.i(l)
        }
    }
}
```

（`--el` 存 Long，`getLongExtra` 直接读，负数 snsId 也正确；与同文件 `firstLong` 的
「`--ei` 是 Integer 会静默返回 0」的坑不同，本探针用 `--el`。）

---

## [DONE-4] 编译结果（EXIT=0 + APK 路径与时间戳）

命令（PATH 收窄，PowerShell 执行）：
```powershell
Push-Location 'C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\wxgroup'
$env:PATH='G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin;C:\Windows\System32;C:\Windows'
$env:JAVA_HOME='G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'
& '.\gradlew.bat' assembleDebug
```

真实输出（关键尾部）：
```
BUILD SUCCESSFUL in 1m 2s
35 actionable tasks: 4 executed, 31 up-to-date
EXIT=0
app-debug.apk  3459338  21:19:21
```

- 结果：`EXIT=0`。
- APK：`wxgroup/app/build/outputs/apk/debug/app-debug.apk`，3459338 字节，时间戳 `21:19:21`（本次）。
- 注：首轮编译报了 1 个 Kotlin 空安全错误（`SnsPrefixProbe.kt:113` `f.length()` 对 `File?` 调用），
  已改为 `f?.absolutePath ?: "n/a"` 后重编通过。第二轮 Kotlin daemon 断连自动回退到
  「Compile without Kotlin daemon」完成编译，不影响产物；其余告警均来自既有文件（非本次改动）。

---

## [DONE-5] 我在实现中发现的、与取证结论不符 / 与任务样例不符的地方

1. **任务样例里的 `as? android.database.sqlite.SQLiteDatabase` 强转必为 null（最重要）**：
   朋友圈库句柄是 **wcdb** 的 `com.tencent.wcdb.database.SQLiteDatabase`（super=`SQLiteClosable`，
   与 AOSP 无继承关系，见 `docs/_r21c_voice_sqlform.md` §3.1），**不是** `android.database.sqlite.SQLiteDatabase`。
   照样例 `SnsGuard.snsDbHandle() as? android.database.sqlite.SQLiteDatabase` 会恒为 null ⇒ 探针永远 `PREFIX no db`。
   ⇒ 我改成反射：`db.javaClass.methods` 找 `update(String, ContentValues, String, String[])` 与
   `rawQuery(String, String[])/(String, Object[])` 后 invoke。`ContentValues` 用 **android.content.ContentValues**，
   这是真机同库同表已验证过的写法（`docs/_r18_snscomment_backup/SnsCommentGuard.kt` 的 `writeAttrBuf` 与
   现役 `RevokeRescue.updateMethod` 同款）。这与任务「取证结论」不矛盾，只是任务给的样例代码与 wcdb 现实不符。

2. **`snsDbHandle()` 早已存在**（`SnsGuard.kt:860`），任务「只加一个 `snsDbHandle()`」是过时描述；
   本轮实际只补了 `hostLoader` 存储 + `hostLoaderHandle()`。

3. **真机验证注意点（非取证不符，是使用须知）**：`SnsInfo.getTimeLine()` 有 `cacheTimeLine`
   （按 content md5 缓存，取证报告 Q7 #6）。本探针**读**走 `rawQuery` 直接读库（不经过该缓存），
   所以 `probe` 读回的一定是落库真实字节；但微信 UI 若已缓存旧 content，界面可能不立即刷新，
   「写库成功但界面不变」时先别误判为探针失败。

---

[ALL-DONE]
