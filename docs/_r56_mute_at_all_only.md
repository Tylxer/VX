# R56 只防 @全体成员（@我 照常提醒）

> 需求：现有「防止被@」在有群消息 @我 **或** @所有人 时把通知静音。
> 用户要求：**只静音 @全体成员，@我 的消息照常响铃**。实现方式：新增开关 `chat.mute_at_all_only`，**默认 "true"**。

## 计划

改动面（5 个文件 + 本报告）：

| 文件 | 改动 |
|---|---|
| `data/Keys.kt` | 新增 `CHAT_MUTE_AT_ALL_ONLY` 常量 + PrefSpec（默认 "true"）；补 `CHAT_AT_ALIASES` 标题注释 |
| `feature/MuteAtLogic.kt` | `isAtMe` 加第三参 `allOnly: Boolean = false`；更新类注释第三节 |
| `feature/MuteAt.kt` | `shouldMute` 读 `Kv.getBool` 存局部变量传入；`probe` 加 `text` 参数 + `judge` 子命令 |
| `entry/EntryReceiver.kt` | `:469` 的 `at` 分支 `probe(arg)` 改为 `probe(arg, intent.getStringExtra("val"))` |
| `test/.../MuteAtLogicTest.kt` | 新增 allOnly 相关 6 类用例 |

判据语义（新 `isAtMe`）：
- `@所有人` 恒命中；`@all`（大小写不敏感）恒命中；
- `allOnly == true` ⇒ 判完前两条直接 `return false`（**不看 aliases**）；
- 其余（短名误伤 / CJK / 空文本）不变。

`probe` 新增子命令 `judge`：真机不依赖真有人 @全体，就能确定性验证判据。
用法：`--es op quiet --es sub at --es sub2 judge --es val "某条通知正文"`。
输出两种模式：`judge text=... allOnly=true -> T | allOnly=false -> F | aliases=[...]`。

## 变异验证方案

把 B 步的 `allOnly` 短路临时改成「仍走 aliases」（等价关掉该功能），预期**第 3 条用例红**
（`@V` + aliases=["V"] + allOnly=true 应从 false 变 true）；改完立刻改回。

---

## 已读文件确认

- `data/Keys.kt`、`feature/MuteAtLogic.kt`、`feature/MuteAt.kt`、`entry/EntryReceiver.kt`、
  `test/.../MuteAtLogicTest.kt`、`core/NotifyMute.kt`（只读）、`docs/conclusions/notify_mute.md`（只读）。
- 锚点核对无误：`MuteAt.kt:71` 现有 `isAtMe` 调用、`MuteAt.kt:86 probe(sub)`、
  `EntryReceiver.kt:469/479` 两处 `MuteAt.probe(arg)`、`EntryReceiver.kt:471-475` grp 分支已用 `getStringExtra("val")`。
- `Kv.getBool(key: String)` 已存在（`data/Kv.kt:35`），签名布尔。

（改动详情逐文件追加在下方。）

## 已改 Keys.kt [DONE]

新增常量（`CHAT_MUTE_AT_EXCLUDE` 之后）：

```kotlin
    /**
     * R56：只防 @全体成员（**默认 true**）。
     * ...
     * ⚠️ 只有把本开关关掉（置 false），[CHAT_AT_ALIASES] 才会重新参与判定。
     */
    const val CHAT_MUTE_AT_ALL_ONLY = "chat.mute_at_all_only"
```

新增 PrefSpec（`CHAT_MUTE_AT_ENABLED` 之后）：

```kotlin
        PrefSpec(
            Keys.CHAT_MUTE_AT_ALL_ONLY, PrefKind.SWITCH, "true", "聊天",
            "只防@全体成员（@我照常提醒）"
        ),
```

`CHAT_AT_ALIASES` 标题/注释补注（键名、默认值未动）：

```kotlin
        // ⚠️ 只在「只防@全体成员」关掉时生效（R56，见 Keys.CHAT_MUTE_AT_ALL_ONLY）
        PrefSpec(Keys.CHAT_AT_ALIASES, PrefKind.TEXT, "", "聊天", "我的昵称（判断@我）"),
```

## 已改 MuteAtLogic.kt [DONE]

类注释第三节补「只防@全体」态；`isAtMe` 增加第三参 `allOnly: Boolean = false`：

```kotlin
    fun isAtMe(text: String, aliases: Collection<String>, allOnly: Boolean = false): Boolean {
        if (text.isEmpty()) return false
        if (containsAtAlias(text, "所有人")) return true
        if (containsAtAlias(text, "all", ignoreCase = true)) return true
        if (allOnly) return false
        return aliases.any { containsAtAlias(text, it) }
    }
```

关键点：`@所有人` / `@all` 恒命中；`allOnly` 短路在前两条之后、不看 aliases；`containsAtAlias` / CJK / 空文本逻辑一字未动。

## 已改 MuteAt.kt [DONE]

`shouldMute` 读一次 `Kv.getBool` 存局部变量再传（热路径不重复查）：

```kotlin
            val text = notificationText(notification)
            val allOnly = Kv.getBool(Keys.CHAT_MUTE_AT_ALL_ONLY)
            if (!MuteAtLogic.isAtMe(
                    text,
                    MuteAtLogic.aliases(Kv.getString(Keys.CHAT_AT_ALIASES)),
                    allOnly,
                )
            ) {
                return false
            }
```

`probe(sub, text: String? = null)` 新增 `judge` 子命令（其余子命令输出格式一字未动）：

```kotlin
            "judge" -> {
                val t = text?.takeIf { it.isNotBlank() }
                if (t == null) {
                    "judge: val 为空（--es val \"某条通知正文\"）"
                } else {
                    val aliases = MuteAtLogic.aliases(Kv.getString(Keys.CHAT_AT_ALIASES))
                    val on = MuteAtLogic.isAtMe(t, aliases, allOnly = true)
                    val off = MuteAtLogic.isAtMe(t, aliases, allOnly = false)
                    "judge text=$t allOnly=true -> $on | allOnly=false -> $off | aliases=$aliases"
                }
            }
```

## 已改 EntryReceiver.kt [DONE]

`:469` 的 `at` 分支照 `:471` grp 分支写法，把 `--es val` 传进 `probe`：

```kotlin
                            "at" -> LogX.i(
                                "QUIET " + com.lx.wxgroup.feature.MuteAt.probe(
                                    arg, intent.getStringExtra("val")
                                )
                            )
```

（`:479` 的 else 分支 `MuteAt.probe(arg)` 靠 `probe` 的默认参数 `text=null` 保持编译不变，judge 在未知 sub 路径下等价于「val 为空」提示。）

## 已改 MuteAtLogicTest.kt [DONE]

新增「只防@全体成员（R56 allOnly）」小节 4 个用例：

```kotlin
    @Test
    fun allOnlyBroadcastMentionsStillMatch() {
        assertTrue(MuteAtLogic.isAtMe("@所有人 请查看", emptyList(), allOnly = true))
        assertTrue(MuteAtLogic.isAtMe("@all 请查看", emptyList(), allOnly = true))
        assertTrue(MuteAtLogic.isAtMe("@ALL 请查看", emptyList(), allOnly = true))
        assertTrue(MuteAtLogic.isAtMe("@所有人请查看", emptyList(), allOnly = true))
    }

    @Test
    fun allOnlyIgnoresAliases() {
        // 核心断言：allOnly=true 时 @我的昵称不再静音
        assertFalse(MuteAtLogic.isAtMe("verity: @V 3", listOf("V"), allOnly = true))
        assertFalse(MuteAtLogic.isAtMe("张三: @老王 在吗", listOf("V", "老王"), allOnly = true))
    }

    @Test
    fun allOnlyFalseKeepsOldBehavior() {
        assertTrue(MuteAtLogic.isAtMe("verity: @V 3", listOf("V"), allOnly = false))
        assertTrue(MuteAtLogic.isAtMe("verity: @V 3", listOf("V")))  // 不传第三参 = 默认 false
    }

    @Test
    fun allOnlyEmptyTextNeverMatches() {
        assertFalse(MuteAtLogic.isAtMe("", listOf("V"), allOnly = true))
    }
```

覆盖需求列的 6 条：`@所有人`、`@all`/`@ALL`、`@V`+allOnly=true=false、`@V`+allOnly=false=true、空文本、`@所有人请查看`（CJK）。

## 编译验证 [DONE]

```
cd wxgroup && ./gradlew assembleDebug --offline -q
→ EXIT=0（-q 静默，无错误输出）
```

## 单测验证 [DONE]

```
cd wxgroup && ./gradlew testDebugUnitTest --offline --tests "*MuteAtLogicTest*" --rerun-tasks
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 49s
22 actionable tasks: 22 executed
EXIT=0
```

XML 统计（`app/build/test-results/testDebugUnitTest/TEST-com.lx.wxgroup.feature.MuteAtLogicTest.xml`）：

```
tests="20" skipped="0" failures="0" errors="0"
```

（20 = 原有 16 条 + 新增 4 条，全部通过。）

## 变异验证 [DONE]

**变异**：把 `isAtMe` 里的 `if (allOnly) return false` 整行删掉（等价于「关掉该功能」，allOnly=true 时仍走 aliases）。

结果（真实输出）：

```
> Task :app:testDebugUnitTest FAILED
MuteAtLogicTest > allOnlyIgnoresAliases FAILED
    java.lang.AssertionError at MuteAtLogicTest.kt:114
20 tests completed, 1 failed
BUILD FAILED in 19s
EXIT=1
```

**结论**：只有 `allOnlyIgnoresAliases`（需求核心断言第 3 条：`@V`+aliases=["V"]+allOnly=true 应 false）红；
其余 19 条（含 `allOnlyBroadcastMentionsStillMatch`、`allOnlyFalseKeepsOldBehavior`、`allOnlyEmptyTextNeverMatches`）全绿。
变异已立刻改回，改回后重跑全绿（见下）。

## 回改后复测 [DONE]

```
cd wxgroup && ./gradlew testDebugUnitTest --offline --tests "*MuteAtLogicTest*" --rerun-tasks
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 49s
22 actionable tasks: 22 executed
EXIT=0
```
XML：`tests="20" skipped="0" failures="0" errors="0"`。

## 补 @全体成员 令牌 [DONE]

**背景**：`isAtMe` 此前只认 `@所有人` / `@all`，缺 `@全体成员`。`@全体成员` 与 `@所有人` 同级：**恒命中，不受 allOnly 影响**。

`MuteAtLogic.isAtMe`（新行插在 `@所有人` 之后、`if (allOnly)` 之前）：

```diff
         if (containsAtAlias(text, "所有人")) return true
+        if (containsAtAlias(text, "全体成员")) return true
         if (containsAtAlias(text, "all", ignoreCase = true)) return true
         if (allOnly) return false
         return aliases.any { containsAtAlias(text, it) }
```

kdoc 如实标注（类注释第三节新增条目，并把「只认前两条」改为「前三条」）：

```kotlin
 * - `@全体成员` 恒命中（与 `@所有人` 同级，**不受 allOnly 影响**）。属**防御性多认**：
 *   8.0.48 真机 extras 尚未取证（dex 里 `@所有人` / `@全体成员` 均 0 命中，文案在 arsc），
 *   待 `op=quiet sub=at sub2=extras` 确认后收敛；
```

（该段措辞为落地时的实际文本；语义与要求一致：@全体 文案可能是 `@所有人` 也可能是 `@全体成员`，dex 0 命中、在 arsc，真机 extras 待取证。）

`containsAtAlias` 本体、CJK 规则、短名误伤规则**一字未动**。

`MuteAtLogicTest.kt` 新增两条：

```kotlin
    @Test
    fun quantitiesAllMembersTokenMatchesEvenInAllOnly() {
        // `@全体成员` 与 `@所有人` 同级：恒命中，不受 allOnly 影响
        assertTrue(MuteAtLogic.isAtMe("@全体成员 请查看", emptyList(), allOnly = true))
    }

    @Test
    fun quantitiesAllMembersTokenMatchesWhenFollowedByCjk() {
        // CJK 紧贴也必须命中（证明不算名字字符的规则同样覆盖新关键字）
        assertTrue(MuteAtLogic.isAtMe("@全体成员请注意", emptyList(), allOnly = true))
    }
```

### 编译（真实输出）

```
cd wxgroup && ./gradlew assembleDebug --offline -q
→ EXIT=0（-q 静默，无错误输出）
```

最终一次性复核（把编译与单测放在同一次调用里，避免并发构建互相踩缓存）：

```
cd wxgroup && ./gradlew assembleDebug testDebugUnitTest --offline \
  --tests "*MuteAtLogicTest*" --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process
> Task :app:compileDebugKotlin
> Task :app:bundleDebugClassesToCompileJar
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 49s
EXIT=0
```

产物：`app-debug.apk` 2.99 MB（23:16），`classes.jar` 483 个 class（4.0 MB）。

### 单测（真实输出，含回改后复测）

```
cd wxgroup && ./gradlew testDebugUnitTest --offline --tests "*MuteAtLogicTest*" --rerun-tasks
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 53s
EXIT=0
```

XML 统计：

```
tests="22" skipped="0" failures="0" errors="0"
```

（22 = 上一轮 20 条 + 本轮新增 2 条。）

### 变异验证（真实输出）

**变异**：删掉新加的 `if (containsAtAlias(text, "全体成员")) return true` 一行。

```
> Task :app:testDebugUnitTest FAILED
MuteAtLogicTest > quantitiesAllMembersTokenMatchesEvenInAllOnly FAILED
    java.lang.AssertionError at MuteAtLogicTest.kt:134
MuteAtLogicTest > quantitiesAllMembersTokenMatchesWhenFollowedByCjk FAILED
    java.lang.AssertionError at MuteAtLogicTest.kt:140
22 tests completed, 2 failed
BUILD FAILED in 23s
EXIT=1
```

**结论**：只有新加的两条变红，其余 20 条全绿 ⇒ 新行确为唯一生效判据。改回后复测全绿（见上，22/22 通过）。

### 本轮环境踩坑（供后续 lane 参考）

中途 `compileDebugUnitTestKotlin` 连报 `Unresolved reference 'AvatarLoadLogic' / 'AvatarProbeLogic'`。
**不是源码缺失**：这两个 object 就在 `core/AvatarLoader.kt:169` / `:194`，**顶层声明、git 未改动、文件完好**。
真因是构建中间产物被并发构建打断：

- `intermediates/compile_app_classes_jar/.../classes.jar` 只剩 **1771 字节 / 5 个 class**（只有 `R` 系列），
  正常应为 4.0 MB / 483 个 class ⇒ 测试编译的 classpath 里根本没有 app 类；
- `build/tmp/kotlin-classes/debug` 为 **0 字节**；
- 但 Gradle 状态仍判 `compileDebugKotlin UP-TO-DATE`，于是报「未解析引用」这种误导性错误。

另 `--rerun-tasks` 时还偶发 `e: Daemon compilation failed: null`
（`NoSuchFileException: .../kotlin/compileDebugKotlin/cacheable/dirty-sources.txt`，
`Could not delete .../cacheable/caches-jvm`）—— Kotlin 增量缓存被并发构建抢占。

**绕法（已验证可稳定通过）**：

```bash
export PATH="/usr/bin:/bin:/c/Windows/System32:/c/Windows"
cd wxgroup
rm -rf app/build/kotlin/compileDebugKotlin app/build/intermediates/compile_app_classes_jar
./gradlew assembleDebug testDebugUnitTest --offline --tests "*MuteAtLogicTest*" \
  --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process
```

要点：①两个 lane 同时跑 Gradle 会互相清空/锁住同目录，**尽量串行**；
②`-Pkotlin.compiler.execution.strategy=in-process` 绕开 Kotlin daemon（daemon 起不来时就是 `failed: null`）；
③`--rerun-tasks` 后 `classes.jar` 恢复 483 class、单测 22/22 通过（见上）。

## 环境备注（重要，供其他 lane / team-lead 参考）

本机默认 `PATH` 环境变量是**损坏的**：含内嵌双引号 + 「Program Files」空格断裂的条目，例如
`"G;F:\Program Files\WorkBuddy\resources\vendor\PortableGit\Program Files\Microsoft\jdk-21.0.7.6-hotspot\bin"`。

这会让 Gradle 的**测试 worker JVM** 启动时 `-Djava.library.path=<PATH>` 里的内嵌引号破坏命令行解析，
报 `找不到或无法加载主类 Files\WorkBuddy\resources\vendor\PortableGit\Program`、`Process 'Gradle Test Executor' finished with non-zero exit value 1`。

**这不是代码问题**（`assembleDebug` 与 `compileDebugUnitTestKotlin` 均能编译通过），是环境 PATH 损坏。
**绕法**：跑单测前先停旧 daemon、再用干净 PATH 起新 daemon：

```bash
export PATH="/usr/bin:/bin:/c/Windows/System32:/c/Windows"
./gradlew --stop
./gradlew testDebugUnitTest --offline --tests "*MuteAtLogicTest*"
```

（`JAVA_HOME` 已指向 `G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot`，gradlew 用它定位 java，不依赖 PATH。）

## 改动面核对（红线自查）

- 进入边界：只读了列出的 5 个文件 + `docs/conclusions/notify_mute.md` + `core/NotifyMute.kt`（只读）。
- 返回边界：只写了 `Keys.kt`、`MuteAtLogic.kt`、`MuteAt.kt`、`EntryReceiver.kt`、`MuteAtLogicTest.kt` + 本报告。
- 复位边界：未 `adb` / 未装包 / 未冷启微信 / 未改 `_cache/` / 未改 `docs/派发审计台账.md` / 未做任何 git 写操作。
- 补 `@全体成员` 这一轮：只写了 `MuteAtLogic.kt`、`MuteAtLogicTest.kt` 与本报告，其余同上。

[DONE]

---

## 补充（team-lead 追加约束 · 第二轮）[DONE]

team-lead 追加三条，逐条落实情况：

### 1. 真机现场状态（上下文，无代码改动）
`aliases=[V]`、`allow` 17 群、`exclude=[]` —— 正是「别人 @V 也被静音」的抱怨来源，与本轮 `chat.mute_at_all_only` 默认开完全对齐。

### 2. `@全体成员` 纳入 @全体 令牌（防御性多认）
`MuteAtLogic.isAtMe` 增加 `@全体成员` 判据，与 `@所有人` 同级、恒命中、不受 allOnly 影响：

```kotlin
    fun isAtMe(text: String, aliases: Collection<String>, allOnly: Boolean = false): Boolean {
        if (text.isEmpty()) return false
        if (containsAtAlias(text, "所有人")) return true
        if (containsAtAlias(text, "全体成员")) return true
        if (containsAtAlias(text, "all", ignoreCase = true)) return true
        if (allOnly) return false
        return aliases.any { containsAtAlias(text, it) }
    }
```

类注释第三节如实标注（含 team-lead 要求的原话要素）：

```
 * - `@全体成员` 恒命中（与 `@所有人` 同级，**不受 allOnly 影响**）。属**防御性多认**：
 *   8.0.48 真机 extras 尚未取证（dex 里 `@所有人` / `@全体成员` 均 0 命中，文案在 arsc），
 *   待 `op=quiet sub=at sub2=extras` 确认后收敛；
```

⚠️ 未动 `containsAtAlias` 的短名误伤规则、未动 CJK 规则。

单测补两条（在 `MuteAtLogicTest`，由 general-purpose-2 落盘、我复核）：
- `quantitiesAllMembersTokenMatchesEvenInAllOnly`：`@全体成员 请查看` + allOnly=true ⇒ true
- `quantitiesAllMembersTokenMatchesWhenFollowedByCjk`：`@全体成员请注意`（CJK 紧贴）+ allOnly=true ⇒ true

### 3. `status` 末尾追加 `allOnly=`（不插中间、不改既有取值）
`MuteAt.probe` 的 `status` 只在**末尾**追加：

```kotlin
"status" -> "registered=$registered enabled=${Kv.getBool(Keys.CHAT_MUTE_AT_ENABLED)} matched=${matched.get()} bridge=${QuietNotificationBridge.status()} allOnly=${Kv.getBool(Keys.CHAT_MUTE_AT_ALL_ONLY)}"
```

`registered=` / `enabled=` / `matched=`、`scope` 的 `allow=`/`exclude=`、`aliases` 的 `aliases=` 均未动。
（`tools/_r56_judge.py` 已依赖 `status` 里的 `allOnly=`，本追加正好补上该依赖。）

### 验证证据（第二轮）

- **主源码编译**：`./gradlew compileDebugKotlin --offline` → `BUILD SUCCESSFUL in 35s`，`EXIT=0`
  （仅有既存 deprecation 告警，无新增错误）。
- **单测**：`MuteAtLogicTest` 全量 22 条通过，XML 统计
  `tests="22" skipped="0" failures="0" errors="0"`（含新增 2 条 `@全体成员` 用例，general-purpose-2 23:05 跑出）。

### ⚠️ 阻塞项（非本任务代码，已上报）—— ⛔ 本节的「生产类被删除」结论作废，见下一节修正

`compileDebugUnitTestKotlin` 目前被**无关的孤儿测试文件**打断，无法再跑一次全量单测：

```
e: AvatarLoadLogicTest.kt: Unresolved reference 'AvatarLoadLogic'
e: AvatarProbeLogicTest.kt: Unresolved reference 'AvatarProbeLogic'
```

根因：`core/AvatarLoadLogic.kt`、`core/AvatarProbeLogic.kt` 这两个**未跟踪**生产类已被删除，
但已跟踪的测试文件 `AvatarLoadLogicTest.kt`（Oct 27）/ `AvatarProbeLogicTest.kt`（Nov 6）仍在，
引用这两个已删类 ⇒ `compileDebugUnitTestKotlin` 全量编译失败。
`build/.../dexBuilderDebug/out/` 里仍残留 `AvatarLoadLogic.dex` / `AvatarProbeLogic.dex`，证明它们是「先存在后被删」。
**不在我的返回边界内**（我只能写 Keys/MuteAtLogic/MuteAt/EntryReceiver/MuteAtLogicTest 5 个文件），
已转交 general-purpose-2 / team-lead 处理。

### 上述阻塞项已排除（结论修正，general-purpose-2）

「两个生产类已被删除」的判断**不成立**：`AvatarLoadLogic` / `AvatarProbeLogic` 是
`core/AvatarLoader.kt:169` 与 `:194` 的**顶层 object**，git 未改动、`AvatarLoader.kt` 完好，
`compileDebugKotlin` 一直能过。

真因是**构建中间产物被并发构建打断**：测试编译用的
`intermediates/compile_app_classes_jar/.../classes.jar` 退化成 1771 字节 / 5 个 class（只有 `R` 系列），
grep 自然一个 `avatar` 都没有；而 dex 里残留的 `AvatarLoadLogic.dex` 只是**旧产物**，不能作为「先存在后被删」的证据。

处置：`rm -rf app/build/kotlin/compileDebugKotlin app/build/intermediates/compile_app_classes_jar` +
`--rerun-tasks -Pkotlin.compiler.execution.strategy=in-process` 重编后，`classes.jar` 恢复 **483 个 class**，
`compileDebugUnitTestKotlin` 通过，`MuteAtLogicTest` **22/22 全绿**（`tests="22" skipped="0" failures="0" errors="0"`）。
即：**无需删除/修改 `AvatarLoadLogicTest.kt`、`AvatarProbeLogicTest.kt`**，问题在构建缓存，不在代码。

[DONE]

