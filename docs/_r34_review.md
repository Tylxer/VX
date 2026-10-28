# R3.4 独立第二视角复核（只读复核子代理 general-purpose-3）

> 档位：Pro（复核反射/并发级改动）
> 复核对象：MediaGuardLogic.kt（新建）、VoiceFileGuard.kt、AutoHdImg.kt、MediaGuardLogicTest.kt（新建单测）
> 方式：`git diff` 工作树未提交改动 + 新建文件全量阅读
> 边界：只读，不改任何源码/单测

## 结论速览

> 完整结论表在文末 §「结论速览（汇总表）」。本处为要点：**无 P0、无 P1；5 条 P2**。
> 最该先修的两条：**§1.1（`hexToken` 无上界 ⇒ >32 位长 hex 顶掉 md5，保护静默失效且零日志）**、**§3.1（排除名单模式下 `talkerOf` 失败 ⇒ 全部 skipScope，与「排除」语义相反，真机已具备触发条件）**。

## 1. data/MediaGuardLogic.kt（新建）

[DONE data/MediaGuardLogic.kt（新建）]

### 1.1 ⚠️ P2 —— `hexToken` 可能返回「>32 位的非 md5 长 hex」，此时保护静默失效且无任何日志

**证据** `MediaGuardLogic.kt:42-58`：

```kotlin
fun hexToken(raw: String?, minLen: Int = MIN_TOKEN): String? {
    ...
    if (j - i > best.length) best = s.substring(i, j)     // 取**最长**段，不校验上界
    ...
    return if (best.length >= minLen) best.lowercase() else null
}
```

`MIN_TOKEN` 只是**下界**，没有上界。若 `imgPath` 里同时存在 md5 和一段更长的 hex（40 位 sha1、64 位 aes key、`serverid` 长整型十六进制串等，都是微信里真实存在的形态），`best` 会取到那一段而不是 md5。

后果链：`protectMedia` 收下这个 >32 位的 token → `protectedMedia[t]` 有值且 `hasMedia=true` → 但 `mediaHit` 的 `name.contains(token)` 对真实文件名 `<md5>hd` **永不命中** → 文件照删、日志里**没有任何一条**（既不是 SKIP 也不是 blocked，`dumpDir` 也不会跑）。

这正是本次改动想消灭的「静默失效」品类，只是换了个入口。

而且**单测完全没有覆盖这个形态**（见 §4-T4），所以它在测试里也是隐形的。

**建议改法**（三选一，推荐 ①）：
1. `hexToken` 增加上界参数 `maxLen`（默认 32），只接受 `best.length in minLen..maxLen`；超长段继续找次长段。
2. 或者：优先取**长度恰好 32** 的段，取不到才退回「最长 ≥32 段」并打 `LogX.w`。
3. 最低成本：在 `VoiceFileGuard.protectMedia` 里加一行 `if (t.length != 32) LogX.w("VFG token len=${t.length} raw=...")`，让失效可见。

### 1.2 ⚠️ P2 —— 视频（type=43）的 `imgPath` 形态**没有真机证据**，32 位下限对它未经验证

**证据**：
- `RevokeRescue.kt:119-122` 对 `TYPE_VIDEO` 一样调 `protectMedia`：

```kotlin
if (orig.type == HintTextLogic.TYPE_IMAGE || orig.type == HintTextLogic.TYPE_VIDEO) {
    runCatching { VoiceFileGuard.protectMedia(orig.imgPath) }
```

- 但项目自己的取证文档对视频是**推测**：`docs/_r29_g2_msg_object.md:251`
  > **视频路径 ② 说明**：…按微信惯例视频消息（`getType()==43`）的 `field_imgPath` 即 .mp4 本地路径。**属强推测，真机需验**。

- `MediaGuardLogic.kt:20-22` 的 32 位依据只写了图片：
  > 真机实测（R31g）：图片 `imgPath` = `THUMBNAIL_DIRPATH://th_<32 位 md5>`，最长 hex 段恒为 32 位。

也就是说：**「32 位」这条硬下限的图片证据是实锤的，视频证据是零。** 而视频文件恰恰落在 `MEDIA_DIRS` 里的 `/video2/` `/video/`（`MediaGuardLogic.kt:30`），判据链条看起来是完整的，容易让人误以为视频也修好了。

**为什么不是 P0**：视频 `imgPath` 若为空/非 hex，会走 `VoiceFileGuard.kt:132` 的 `SKIP no-token` warn —— 有痕迹，不是静默。所以定 P2（功能覆盖不全 + 文档口径夸大），不定 P0。

**建议改法**：
1. 类注释 `MediaGuardLogic.kt:20-22` 明确标注「32 位仅图片实锤，视频待真机」—— 别让后来者把「图片修好了」读成「图片+视频都修好了」。
2. 真机取证时把视频 `imgPath` 归入 `sig`/`no-token` 那批日志一起看（现在 `SKIP no-token raw=` 已经会打原始值，够用）。

### 1.3 ✅ 已核，无问题 —— 32 位下限对**图片**场景是成立的，不会误收数字串

逐字符走过真机形态 `THUMBNAIL_DIRPATH://th_0c947a1dd158f52fcca431bb84954d5b`：
`THUMBNAIL` 里能成段的只有 `B`(1) / `A`(1)；`DIRPATH` 里 `D`(1) / `A`(1)；`th`(0)；随后 md5 = 32。最长段 = 32 ✅。
`/image2/ab/cd/<md5>` → `a`(1)、`e2`(2)、`ab`(2)、`cd`(2)、md5(32) ✅。
`<md5>hd` → `h` 非 hex，md5 独立成段 ✅。

旧实现下限 16 会被 `1793015486` 这类纯数字串击穿的问题，本次确实堵住了（`MediaGuardLogic.kt:44` 先判 `s.length < minLen`，`MediaGuardLogic.kt:57` 再判 `best.length >= minLen`，两道都有）。**这条修复是实打实的，不是自我安慰。**

### 1.4 ✅ 已核，无问题 —— `contains` 不会在真实文件名之间串号

固定 32 位 md5，`<md5>` / `<md5>hd` / `th_<md5>` / `<md5>_tmp.jpg` 四态都靠 `contains` 覆盖（`MediaGuardLogicTest.kt:81-84` 已锁）。
反方向「误命中别的图」需要另一个文件名里恰好含这 32 个字符的连续串，概率可忽略；且 `inMediaDir` 已把范围收到三个媒体目录下。**判据成立。**

唯一值得一提的：`contains` 作用在**文件名**上（`MediaGuardLogic.kt:86-90`）而不是整条路径 —— 这是**正确**的选择（桶目录是 2 位 hex，若用整路径 `contains`，token 出现在目录部分也会误命中）。但单测区分不出这一点，见 §4-T2。

### 1.5 🟡 P3 —— 过期边界口径在两处重复实现，容易改歪一处

**证据**：
- `MediaGuardLogic.kt:69` `for (v in pool.values) if (v >= now) return true`
- `MediaGuardLogic.kt:89` `if (until < now) continue`

两处都是「`until >= now` 即存活」，语义一致 ✅，但是两个字面量。哪天有人把其中一处改成 `>`，就会出现「`anyAlive` 说活着但 `mediaHit` 不命中」的错位（表现为 `hasMedia` 永不复位 → 每次 `File.delete` 都白取一次 path，纯性能问题，但很难查）。

**建议**：抽一个 `private fun alive(until: Long, now: Long) = until >= now`，两处共用；并补一条 `until == now` 的边界用例（见 §4-T3）。

### 1.6 🟡 P3 —— `hasMedia` 的复位依赖「恰好发生一次 `File.delete`」

**证据** `VoiceFileGuard.kt:278-287`：只有进入 media 分支且 `mediaHit` 返回 null 时才会评估 `anyAlive` 并复位。若保护池全过期后微信长时间不删任何文件，`hasMedia` 会一直为 true，于是**每一次** `File.delete` 都多取一次 `self.path`。

实际代价：`File.getPath()` 是纯字段读取，纳秒级；池是个位数。**影响可忽略**，仅记录。

**已核无竞态**：`protectMedia` 先写 map 再置 `hasMedia = true`（`VoiceFileGuard.kt:136-140`），顺序正确；`hasMedia` 是 `@Volatile`。不存在「置 true 后又被人复位成 false 而漏保护」的窗口。

### 1.7 ✅ 已核，无问题 —— `VoiceFileGuard.mediaHit` 移出 `hasMedia` 检查是等价的

旧实现 `mediaHit` 首行 `if (!hasMedia) return null`，现调用点 `VoiceFileGuard.kt:278` 已有 `if (!hasMedia) return`。`mediaHit` 是 private，全文件只有这一处调用（已 grep 确认）。**无行为变化。**

顺带：新实现把 `hasMedia` 复位从「只在路径确实落在媒体目录时」放宽到「任何一次 null 结果都评估」，复位更积极，**是改善不是退化**。

## 2. feature/VoiceFileGuard.kt

[DONE feature/VoiceFileGuard.kt]

### 2.1 ✅ 已核，无问题 —— 「SKIP no-token」日志补得对，且补在了正确的位置

**证据** `VoiceFileGuard.kt:128-134`：

```kotlin
val t = MediaGuardLogic.hexToken(token)
if (t == null) {
    LogX.w("VFG protectMedia SKIP no-token raw=${token?.take(40)}")
    return
}
```

这条把上一版「第一道判据静默 return、`grep VFG` = 0 行」的无头案彻底堵住了 —— 取不到 token = 这张图**完全没被保护**，是最该留痕的地方。级别用 `w` 也合适（不是模块自己的错误，是「这条救不下来」）。

`raw=` 带原始值 40 字符，足够下次真机一眼看出视频/图片的 imgPath 到底长什么样（与 §1.2 的取证需求正好咬合）。**这条是本批改动里价值最高的一处。**

### 2.2 🟡 P3 —— `SKIP no-token` 用 `LogX.w`，图片撤回密集时可能刷屏

同一张图被反复撤回（或微信重试触发多次 `record`）会重复打。`RevokeRescue.kt:120` 每次拦截都调一次 `protectMedia`，没有节流（而命中侧的 `logAt` 有 60s 节流，`VoiceFileGuard.kt:79`）。

**建议**：复用 `logAt`（key 用 `"skip:$raw"`）做同样的节流，或降到 `LogX.i`。非阻断。

### 2.3 🟡 P3 —— `dumpDir` 在 `File.delete` 的 hook 回调里做磁盘 IO（列目录）

**证据** `VoiceFileGuard.kt:296-298` + `314-319`：

```kotlin
LogX.i("VFG blocked MEDIA delete img=... path=$path")
dumpDir(self)
...
private fun dumpDir(file: File) {
    val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
```

`listFiles()` 会枚举整个目录（image2 的桶目录可能有上千个文件），而这是在 `File.delete()` 的 `beforeHookedMethod` 里**同步**跑的 —— 若微信主线程在删文件，就是主线程同步 IO。

**为什么只是 P3**：触发条件极窄（必须先命中保护池 + 过 60s/token 节流），实际频率 = 撤回图片的次数，且 `kids.take(8)` 已限制输出。真实卡顿风险很低。

**建议**：真要保险就丢到后台线程，或加一个 `Kv` 调试开关。另外 `path=$path` 打出完整路径（含 wxid 目录），日志外泄面比上一版大 —— 若日志会进用户可见的导出，考虑 `path.takeLast(24)` 与语音分支保持一致（`VoiceFileGuard.kt:270` 用的是 `takeLast(24)`，这里用了全量，**两处口径不一致**）。

### 2.4 🟡 P3 —— 语音分支在开关关闭时也会消费保护项

**证据** `VoiceFileGuard.kt:206-213` + `259-261`：

```kotlin
fun shouldBlock(path: String?, now: Long): Boolean {
    val until = protected[img] ?: return false
    if (until < now) { protected.remove(img); return false }   // ← 过期即移除
    return true
}
// deleteGuard:
if (!shouldBlock(path, now)) return
if (!on()) return        // ← 开关判断在后面
```

即防撤回总开关关着时，`shouldBlock` 仍会把过期项从 `protected` 里删掉。语义上无害（过期项本来就没用了），但「开关关着还动状态」这个顺序不干净，且 `on()` 在前更符合「关了就什么都不做」的注释承诺（`VoiceFileGuard.kt:159-161`）。

注意 media 分支的顺序也一样（`VoiceFileGuard.kt:281-288`：`mediaHit` 在 `on()` 之前），但 `mediaHit` **不消费**池项，所以只影响性能不影响状态。**两处一起挪最省事。**

### 2.5 ✅ 已核，无问题 —— 保护池清理与容量控制

`VoiceFileGuard.kt:137-139`：

```kotlin
if (protectedMedia.size > 256) {
    protectedMedia.entries.removeIf { it.value < now }
}
```

`ConcurrentHashMap` 的 `entrySet()` 迭代器支持 `remove()`，`Collection.removeIf` 默认实现走的就是迭代器 → **可用，不会抛 `UnsupportedOperationException`**。弱一致迭代，安全。
且清理后紧接着写入的这一条必然未过期 → 池不会清空，`hasMedia = true` 成立。**无问题。**

### 2.6 ✅ 已核，无问题 —— 语音/媒体两条分支互斥，不会互相抢命中

`VoiceFileGuard.kt:256`：语音分支 `name.startsWith("msg_") && name.endsWith(".amr")` 命中后一定 `return`（四个出口全部 return）。媒体目录下的文件名不含 `msg_`/`.amr` 组合。两条路径不会重复计数、也不会出现「语音文件被媒体判据误拦」。**无问题。**

## 3. feature/AutoHdImg.kt

[DONE feature/AutoHdImg.kt]

### 3.1 ⚠️ P2 —— 「排除名单」模式下，`talkerOf` 一失败就把**所有**会话挡掉，与「排除」语义恰好相反

**证据** `AutoHdImg.kt:299-315`：

```kotlin
if (allowRaw.isNotEmpty() || excludeRaw.isNotEmpty()) {
    val (groups, labels) = buckets(allowRaw, excludeRaw)
    val talker = talkerOf(msgId, cl)          // ← 失败时返回 null（异常被 runCatching 吞，:387-393）
    lastTalker = talker.orEmpty()
    if (!ListResolver.inScope(talker = talker, ...)) { skipScope.incrementAndGet(); return }
}
```

而 `ListResolver.kt:120-121`：

```kotlin
val who = talker?.trim().orEmpty()
if (who.isEmpty()) return false               // ← talker 拿不到 = 不在作用域
```

**语义反转在这里**：用户只配了**排除**名单（语义 = 「除这几个之外全部自动下原图」）时，只要 `talkerOf()` 反射失败拿不到 talker，`inScope` 恒 false ⇒ **全部 skipScope，功能完全不生效**。用户配的是「排除 A」，实际得到的是「谁都不下」。

R31g 真机已经证实 `talkerOf` 在本版会抛 NPE（`AutoHdImg.kt:293-295` 的注释记录了 `lastErr=talker:NullPointerException`）。本次改动只解决了「名单为空时白白调一次」这条路径，**名单非空时同样会 NPE，且后果是静默全挡**。

**建议改法**（最小）：名单非空时也区分两种模式 ——
```kotlin
val talker = talkerOf(msgId, cl)
if (talker.isNullOrBlank() && allowRaw.isEmpty()) {
    // 纯排除模式：拿不到 talker 就放行（排除语义 = 默认生效）
    // 记一行 LogX.w 表明降级
} else if (!ListResolver.inScope(...)) { skipScope++; return }
```
更彻底的做法是让 `inScope` 支持「talker 未知」的三态，但那会动 `NotifyMute` 的既有口径，先别碰。

### 3.2 ⚠️ P2 —— 参数解析优先于实例字段，参数顺序一旦与假设相反就会静默用错 `localId`/`msgId`

**证据** `AutoHdImg.kt:198-201`：

```kotlin
val fldLocal = instLong(param.thisObject, "h").let { if (it > 0) it else 0L }
val fldMsg   = instLong(param.thisObject, "q").let { if (it > 0) it else 0L }
val localId = if (argLocal > 0) argLocal else fldLocal     // ← 参数优先
val msgId   = if (argMsg   > 0) argMsg   else fldMsg
```

注释（`AutoHdImg.kt:194`）自己写了字段语义是 **dex dump 确认过**的（`h/i/j`=localId、`q`=msgId），而参数位置的语义**正是真机刚推翻的那个假设**（撤回后点开时微信建的是 `(0, msgId)`，第一参不是 localId）。

既然如此，优先级应该反过来：**字段为准，参数兜底**。

现在的风险场景：若某个重载是 `(msgId, localId, ...)`（顺序与假设相反），两个参数都 > 0 ⇒ 字段兜底**根本不会被启用** ⇒ `fire(localId=真msgId, msgId=真localId)` ⇒ `svc.c()` 拿错的 id 去下，多半返回 false（`fail++`），也可能下到别的资源上。**没有日志指出「参数与字段不一致」** —— `sig` 日志（`:206-213`）虽然带了 `h/q` 和 `args`，但有 `sigSeen.size < 12` 上限，且需要人肉比对。

**建议改法**（按优先级）：
1. `val localId = if (fldLocal > 0) fldLocal else argLocal`（字段优先），`msgId` 同理。
2. 无论怎么选，都加一条**一致性校验**：`argX` 与 `fldX` 都 > 0 且不相等时，无条件打一条 `LogX.w`（不进 `sigSeen` 的 12 条上限），把「参数语义可能与假设不符」这件事直接摆到日志里。
3. `svc.c` 的 `localId` 若被用错，最坏是下载到错的图 —— 建议在 `fire` 里对 `img == null`（`storage.o2(localId)` 查不到 `ImgInfo`）的情况加一条 warn，因为那正是 localId 错的强信号（现在只有 `imgT=null` 静默往后走，`:287-289`）。

### 3.3 ⚠️ P2 —— `done` 误挡：一次失败 = 本进程内这张图永远不再自动下载

**证据** `AutoHdImg.kt:225`：

```kotlin
synchronized(done) { if (!done.add(msgId)) { skipDup.incrementAndGet(); return } }
```

`done` 的 `add` 在 fire **之前**，且 `fire` 的失败路径（`:327-330` `svc.c returned $r`、或 `:231-236` 的异常）**不会把 msgId 从 `done` 里移除**。所以：网络抖动导致这一次失败 → 该 msgId 在 `done` 里 → 微信后续自己重试/用户再点开时再建 t1 → 一律 `skipDup` → 本进程内永远不再自动下原图，直到 `done` 淘汰 1024 条（`AutoHdImg.kt:138-142`）或微信进程重启。

**为什么不是 P1**：我们**没有**拦截微信自己的下载（`ctorHook` 从不设 `param.result`），只是在构造后旁路补一次 `fire`。所以 `done` 误挡只影响「自动补下载」这个增强，**用户手动点「查看原图」完全不受影响**。降级为 P2。

**建议改法**（最小）：失败时把 msgId 从 `done` 里摘掉，或改记「失败集合」并允许一次重试：
```kotlin
runCatching { fire(localId, msgId) }.onFailure { synchronized(done) { done.remove(msgId) }; ... }
```
更稳的是给 `done` 存状态（`TRYING / OK / FAILED`）而不是纯集合，但那是重构，先看真机 `fail` 计数再决定。

**顺带确认无问题**：`synchronized` 是 Kotlin 的 inline 函数，`:225` lambda 里的 `return` 是**非局部返回**，正确地从 `afterHookedMethod` 返回 —— 不是「只跳出 lambda 然后继续往下 fire」。✅

### 3.4 ✅ 已核，无问题 —— 「实例字段兜底」不存在「构造未完成就反射读字段」的风险

`ctorHook` 用的是 `afterHookedMethod`（`AutoHdImg.kt:174`）。Xposed 的 `afterHookedMethod` 在**被 hook 方法返回前**触发，而 `hookAllConstructors` 挂的是 `<init>`，此时 `<init>` 的方法体（含所有 `iput` 字段赋值）**已执行完毕**。

`MediaGuardLogic`/`AutoHdImg` 里也没有在 `beforeHookedMethod` 里读字段的路径。所以「读到未初始化值」这条路**不成立**。

唯一会拿到「非预期值」的情况是**字段名漂移**：`instLong` 的 `runCatching {}.getOrNull() ?: -999L`（`:248-249`）→ 被 `.let { if (it > 0) it else 0L }` 归一成 0 → 落到 `msgId == 0L` 分支。见 §3.5。

### 3.5 🟡 P3 —— 字段名漂移时 `instLong` 静默返回 -999，可诊断性有上限

**证据** `AutoHdImg.kt:248-249`：

```kotlin
private fun instLong(o: Any?, name: String): Long =
    o?.let { runCatching { XposedHelpers.getLongField(it, name) }.getOrNull() } ?: -999L
```

反射失败（字段不存在 / 类型是 int 不是 long）全被吞成 `-999`，再被归一成 0。落到 `:214-224` 会打一条 `noMsgId` warn + `noMsgId.incrementAndGet()`，`stat()` 里能看到累计值 ✅ —— **可诊断性是有的，这一条比原来的静默 return 好得多。**

但 `missSeen` 的 key 是 `sig = "参数个数:type"`（`:205`）且**每种只打一条**、`missSeen.size < 8`。字段名漂移导致的失败与具体 sig 无关，真机上大概率只留 1 条 warn + 一个计数。**够用，但不算充分。**

**建议**：给 `instLong`/`instInt` 加一个「该字段名连续 N 次取不到」的一次性 warn（`ConcurrentHashMap.newKeySet<String>()` 记字段名），让「`q` 漂移了」这句话直接出现在日志里，而不是靠人从 `noMsgId=37` 反推。

### 3.6 🟡 P3 —— `skipOther` 名字与语义已经不符

**证据** `AutoHdImg.kt:92` + `:204`：

```kotlin
private val skipOther = AtomicInteger(0) // ⚠️ 已不拦截，仅统计「档位不是 TYPE_THUMB」的次数
...
if (type != TYPE_THUMB) skipOther.incrementAndGet()      // 后面照常往下走，不 return
```

注释自己承认了「已不拦截」，但变量名和 `stat()` 里的字段名仍是 `skipOther=`。看 stat 的人会以为「有 N 次被跳过了」，实际是「N 次档位不是 1（全部照常 fire 了）」。

**建议**：改名 `typeNot1`（或 `notThumb`），stat 里同步改。纯观感，但不改的话下次真机读日志必然误判一次。

### 3.7 🟡 P3 —— `sigSeen` / `missSeen` 的 check-then-act 非原子

`AutoHdImg.kt:206`、`216`：`if (sigSeen.size < 12 && sigSeen.add(sig))`。并发下可能略超 12/8 条上限。后果只是多打几条日志，**无功能影响**。若想严谨就用 `ConcurrentHashMap.newKeySet()` + 计数 `AtomicInteger` 的 CAS，或干脆只保留 `add` 的返回值判断上限（用 `Collections.newSetFromMap(LinkedHashMap)` 在 `synchronized` 里做）。优先级低。

### 3.8 🟡 P3 —— `imgPathOf` 只遍历 `declaredFields`，拿不到父类声明的 String 字段

**证据** `AutoHdImg.kt:262`：

```kotlin
img.javaClass.declaredFields.asSequence().filter { it.type == String::class.java }
```

`ImgInfo` 若继承自某个基类（微信的 ORM 对象常见），路径字段可能在父类上 ⇒ 恒返回 null ⇒ 日志里 `img=null`。

**为什么只是 P3**：`imgPathOf` 的用途**只有日志**（`:288` 取值 → `:325` 打进 `fired` 那行），不参与任何判据。判重靠的是 `imgT`（`getIntField` 走 `XposedHelpers`，会沿 super 链找）。所以最多是「日志里 img=null」让人误判，**不影响下载行为**。

**建议**：换成 `XposedHelpers` 的字段遍历（沿 super 链），或改用 `generateSequence(img.javaClass) { it.superclass }` 拼 `declaredFields`。顺带把注释里的「取第一个满足条件的」标注为「顺序依赖 `declaredFields` 返回顺序，不保证稳定」。

### 3.9 🟡 P3 —— `selfFiring` 可能挡掉微信自己在同一线程建的合法 t1

**证据** `AutoHdImg.kt:228-239`：

```kotlin
pool.execute {
    selfFiring.set(true)
    try { runCatching { fire(localId, msgId) } ... } finally { selfFiring.set(false) }
}
```

若 `svc.c()` 内部在**同一个 pool 线程**上同步构造 t1，且那个 t1 恰好是微信自己为**别的图**建的（不是我们 fire 的那张），也会被 `selfFiring.get() == true`（`:178`）挡掉 ⇒ 那张图永远不会被自动下载（因为它自己的 t1 构造没被记进 `done`）。

概率很低（`svc.c` 一次调用内同步建别的图的 t1 不常见），且 `done` 的淘汰（1024）后不会永久化（因为下次微信再建自己的 t1 时 `selfFiring` 已 false）。**记录，不必改。**

### 3.10 ✅ 已核，无问题 —— 去掉 `downloadType` 闸门这个决定本身是对的

真机证据 `seen=14 fired=0 skipOther=14` 是「闸门从未放行」的硬证据（`AutoHdImg.kt:27` 引述），而且 dex 取证（`:31-36`）也确认了参数序列和落字段位置 —— **取值逻辑没错，错的是常量假设**。既然常量是猜的，就不该当闸门用。改成「`msgId != 0` + `done` 首次收录」后，递归由 `done` 兜住（`AutoHdImg.kt:125-133` 的注释写得很清楚，且**明确否认了 `selfFiring` 是唯一防线**）—— 这个自我认知是准确的，**没有把 ThreadLocal 当银弹**。

`done` 的 key 语义注释（`:129-130`「必须是微信传进来那个 msgId，不能换成 localId 或派生值」）也是对的：换成派生值就与 fire 出来的那次对不上，递归无界。**这条注释建议保留，别在后续重构里删掉。**

## 4. app/src/test/.../data/MediaGuardLogicTest.kt（新建单测）

[DONE app/src/test/.../data/MediaGuardLogicTest.kt（新建单测）]

先说结论：**这组单测的质量明显高于「为交差而写」的水平** —— 它锁住了两条真机事故的关键判据，且我逐条做过变异推演，多数变异会红。下面是**仍然漏得掉的**变异（真·假验证）和**没有覆盖的形态**。

### 4.1 ⚠️ P2 —— 完全没有覆盖「最长 hex 段 > 32 位」这个形态（与 §1.1 是同一个洞的两面）

测试里所有正例的 hex 段**恰好都是 32 位**。于是：

| 变异 | 测试结果 |
|---|---|
| `hexToken` 取最长段（现状，>32 也收） | 全绿 |
| `hexToken` 只收长度 == 32 的段 | 全绿 |

两种**行为完全不同**的实现都能通过全部用例。而 §1.1 说明：现实里 imgPath 一旦掺入 sha1(40) / aes key(64) 这类更长的 hex，两种实现一个会静默失效、一个不会。

**建议补**（两条都加）：
```kotlin
@Test fun hexTokenPrefersExactMd5WhenLongerHexPresent() {
    // 同一串里既有 32 位 md5 又有 64 位 hex —— 必须取 md5
    val long64 = "a".repeat(64)
    assertEquals(md5A, MediaGuardLogic.hexToken("THUMBNAIL_DIRPATH://th_${md5A}_$long64"))
}
@Test fun hexTokenDocumentsOverLongBehavior() { /* 固化你选的口径，别留灰 */ }
```

### 4.2 🟡 P3 —— `MIN_TOKEN` 只被锁在 `[19, 32]` 区间，且下界锁得不够高

**推演**：
- 上界由 `hexTokenAcceptsThumbnailPrefix`（`:26`，32 位必须被接受）锁住 ⇒ `MIN_TOKEN ≤ 32`。
- 下界由 `hexTokenRejectsNumericOnlyString`（`:39`，`"202510280001234567"` 18 位数字必须被拒）锁住 ⇒ `MIN_TOKEN ≥ 19`。

⇒ 把 `MIN_TOKEN` 改成 20 / 24 / 28，**测试全绿**。

更要紧的是：**18 位这个样本比真实威胁短**。本项目自己记录过 `msgSvrId` 是 19 位（`docs/_r21c_voice_root.md:152` 的 `6139184963485905836`）。若有人把下限降到 19，19 位的 `msgSvrId` 就会被当成 token，`contains` 误拦 —— 而测试放它过。

**建议补**：
```kotlin
assertNull(MediaGuardLogic.hexToken("6139184963485905836"))   // 19 位 msgSvrId，本项目真实样本
```
这一条把下界从 19 抬到 20，正好卡住最长的一类纯数字串。

（注：`[20,32]` 区间内的变异在真机上与 32 **行为等价**（真机 md5 恒 32 位），所以剩下的宽松度是**可接受**的，不必苛求锁死到 32。）

### 4.3 🟡 P3 —— `name.contains` 与 `path.contains` 是等价变异漏洞

**推演**：把 `MediaGuardLogic.kt:90` 的 `name.contains(token)` 改成 `p.contains(token)`（拿整条路径匹配），全部用例仍然绿 —— 因为所有正例的 token 都在文件名里，反例都被 `inMediaDir` 挡在前面，没有任何一条路径是「目录部分含 token、文件名不含」。

现状实现（用 `name`）**是正确的**，只是测试证明不了它正确。

**建议补**：
```kotlin
@Test fun mediaHitIgnoresTokenAppearingOnlyInDirectory() {
    // token 只落在目录段、文件名里没有 ⇒ 不该命中（用整路径 contains 会误命中）
    assertNull(MediaGuardLogic.mediaHit("/sdcard/MicroMsg/x/image2/$md5A/other.jpg",
        mapOf(md5A to now + 1000), now))
}
```

### 4.4 🟡 P3 —— 过期边界 `until == now` 没有用例，`>=` / `>` 变异不红

**推演**：
- `mediaHit`（`:89` `if (until < now) continue`）改成 `until <= now`：用例只用 `now-1`（过期）和 `now+1000`（存活）⇒ 全绿。
- `anyAlive`（`:69` `if (v >= now)`）改成 `v > now`：同理全绿。

两处口径目前一致（`until >= now` = 存活），但没人锁住。与 §1.5 呼应。

**建议补**：`mediaHit("$dir$md5A", mapOf(md5A to now), now)` 断言**命中**（固化「到期那一刻仍受保护」），`anyAlive(mapOf(md5A to now), now)` 断言 `true`。

### 4.5 ✅ 已核，无问题 —— 以下变异**会被现有测试抓住**（这组用例是真有效的）

| 变异 | 被哪条抓住 |
|---|---|
| 去掉 `.lowercase()`（`:57`） | `hexTokenNormalizesCase`（`:55`）红 |
| `isHex` 不认 `A-F` | 同上红（md5A 大写后含 `C/A/D/F/B`） |
| 退回「整串必须全 hex」的旧实现 | `hexTokenAcceptsThumbnailPrefix`（`:26`）红 |
| `inMediaDir` 用 `startsWith` 替 `indexOf` | `inMediaDirMatchesOnlyMediaDirs`（`:62-64`）全红 |
| 去掉 `MEDIA_DIRS` 里的 `"/video/"` | `:64` 红 |
| 去掉 `"/image2/"` 两侧斜杠（改 `"image2"`） | `:68` `myimage2` 反例红 |
| `contains` → `==` | `:82-84`（`hd` / `th_` / `_tmp.jpg`）红 |
| `contains` → `startsWith` | `:83` `th_<md5>` 红 |
| `mediaHit` 去掉 `inMediaDir` 前置 | `:93` voice2 反例红 |
| `mediaHit` 忽略过期 | `:95` 红 |

**这组用例对「两条真机事故」的锁定是实打实的**，不是摆设。

### 4.6 🟡 P3 —— 一处死代码：`hexToken` 里的 `trim()`

`MediaGuardLogic.kt:43` `val s = raw?.trim().orEmpty()`。由于后面取的是**最长连续 hex 段**，前后空白本来就不可能进入 `best`；去掉 `trim()` 后 `hexToken("  $md5A  ")` 仍返回 md5A ⇒ `hexTokenAcceptsImagePathAndBare:33` 依然绿 ⇒ **测试也证明不了 trim 有用**。属于无害冗余，可留可删，不算缺陷。

### 4.7 ✅ 已核，无问题 —— 工程层面可编译、可运行

- 包名 `com.lx.wxgroup.data` 与目录 `app/src/test/java/com/lx/wxgroup/data/` 一致 ✅
- JUnit4（`org.junit.Test` / `org.junit.Assert.*`），`app/build.gradle:74` 已有 `testImplementation("junit:junit:4.13.2")` ✅
- 被测类是 `object` 且零 Android 依赖 ✅ 纯 JVM 可跑（符合「不许跑 gradle」边界下我对可运行性的静态判断）
- `emptyMap()` 等泛型推断无歧义 ✅

## 5. 交叉问题（跨文件）

[DONE 交叉问题（跨文件）]

### 5.1 ⚠️ P2 —— 「同一个 `message.imgPath` 列，语音 26 位 / 图片 32 位 / 视频未知」：口径分裂本身就是一个信号

**证据**：
- 语音：`VoiceBackupLogic.kt:68` `if (imgPath == null || imgPath.length != 26) return false`
- 图片：`MediaGuardLogic.kt:27` `const val MIN_TOKEN = 32`
- 视频：无判据，`RevokeRescue.kt:119` 直接复用图片的 32 位

同一个 DB 列、同一个字段，三种消息类型三种长度 —— 这在本项目里已经是**被真机证实的事实**（26 位 8/8 样本实锤，32 位图片实锤）。

⇒ 这直接说明「用一套长度判据覆盖所有媒体类型」是**站不住的假设**，而 `RevokeRescue.kt:119` 恰恰就做了这件事（把视频和图片塞进同一个 `protectMedia`）。

**建议**：`protectMedia` 增加消息类型入参（或按类型分两个入口），让「视频要不要 32 位」变成一个**可独立调整**的判据，而不是被图片的实锤顺带背书。至少在 `RevokeRescue.kt:119` 加一句注释标明「视频沿用图片口径，未真机验证」。

### 5.2 反射吞异常盘点（逐处裁决）

| # | 位置 | 行为 | 裁决 |
|---|---|---|---|
| 1 | `AutoHdImg.kt:248-249` `instLong`/`instInt` | 失败 → `-999` → 归一为 0 | **可接受**（落到 `:214` 有 warn + 计数），但建议补字段名级 warn（§3.5） |
| 2 | `AutoHdImg.kt:231-236` `fire` 外层 | 失败 → `fail++` + `lastErr` + `LogX.w` | **可接受**，可诊断性好 |
| 3 | `AutoHdImg.kt:387-393` `talkerOf` | 失败 → `null` | **危险**：`null` 会被 `inScope` 当「不在作用域」⇒ 静默全挡（§3.1）。已带栈帧诊断，但**行为没改** |
| 4 | `AutoHdImg.kt:284` `storage.o2(localId)` | 失败 → `img=null` → `imgT=null` → **不判重直接下载** | **半危险**：localId 解析错的强信号被吞了（§3.2 建议 3） |
| 5 | `AutoHdImg.kt:265` `imgPathOf` | 失败 → `null` | 可接受（纯日志） |
| 6 | `VoiceFileGuard.kt:254/279` `self.name`/`self.path` | 失败 → `return`（放行删除） | 可接受（放行是安全侧） |
| 7 | `VoiceFileGuard.kt:226/236` hook 安装 | 失败 → `LogX.w` | 可接受 |
| 8 | `RevokeRescue.kt:120` `protectMedia` | 失败 → `LogX.w` | 可接受 |

**结论：没有「吞掉后完全无痕迹」的反射调用。** 唯一有实质后果的是 #3 和 #4，已分别在 §3.1 / §3.2 立案。

### 5.3 并发盘点

| 对象 | 保护 | 裁决 |
|---|---|---|
| `AutoHdImg.done`（`LinkedHashSet` 子类） | `synchronized(done)` 包住 `add`（`:225`）；`stat()` 不读它 | ✅ 无问题（淘汰逻辑 `it.next(); it.remove()` 在锁内） |
| `AutoHdImg.selfFiring`（ThreadLocal） | 单线程 executor，try/finally 复位 | ✅ 无泄漏（线程数=1）。**跨线程无效已由注释承认**，靠 `done` 兜底 |
| `VoiceFileGuard.protectedMedia` | `ConcurrentHashMap` | ✅ 弱一致遍历安全；`entries.removeIf` 可用（§2.5） |
| `VoiceFileGuard.hasMedia` | `@Volatile`，写顺序正确（先写 map 后置 true） | ✅ 无丢保护窗口（§1.6） |
| `AutoHdImg.sigSeen`/`missSeen` | `ConcurrentHashMap.newKeySet` | ✅ 无安全问题，仅 check-then-act 超限（§3.7） |
| `AutoHdImg.pool` | 单线程 | ⚠️ 若某次 `fire` 卡住（网络同步等待？），后续所有图排队。现有代码在 pool 线程上直接反射调 `svc.c`，**没有超时**。记录，非本次引入 |

### 5.4 ✅ 已核，无问题 —— 本批改动没有引入任何「反向失效」（P0）

逐条排除：
- 媒体保护**受总开关约束**（`VoiceFileGuard.kt:288` `if (!on()) return`），关掉防撤回就不会拦删除 ⇒ 不会「存储只增不减」。
- `param.result = true` 只在命中 32 位 md5 token + 在三个媒体目录下时才发生 ⇒ 误拦面极窄。
- `AutoHdImg` 的 `ctorHook` **从不设 `param.result`**，纯旁路 ⇒ 不可能阻断微信自己的下载链路。
- TTL 存在且到期复位（`PROTECT_MEDIA_MS = 600_000`、`hasMedia` 复位）⇒ 不会永久堵住微信 GC。

**P0 判定：无。**

---

## 结论速览

[DONE 结论速览]

| 级别 | 条目 | 位置 |
|---|---|---|
| **P2** | `hexToken` 无上界，>32 位长 hex 会顶掉 md5 ⇒ 保护静默失效、零日志 | §1.1 |
| **P2** | 视频（type=43）`imgPath` 形态零真机证据，32 位下限对它未验证 | §1.2 / §5.1 |
| **P2** | 排除名单模式下 `talkerOf` 失败 ⇒ 全部 skipScope，与「排除」语义相反 | §3.1 |
| **P2** | 参数解析优先于（已 dex 取证确认的）实例字段，顺序取反会静默用错 id | §3.2 |
| **P2** | `done` 失败不摘除 ⇒ 一次失败 = 本进程内该图永不再自动下载 | §3.3 |
| P3 | 过期边界口径两处重复实现 | §1.5 |
| P3 | `hasMedia` 复位依赖恰好发生一次 delete | §1.6 |
| P3 | `SKIP no-token` 无节流、用 warn | §2.2 |
| P3 | `dumpDir` 在 delete hook 里同步列目录 + 路径打全量（与语音分支 `takeLast(24)` 口径不一） | §2.3 |
| P3 | 开关关闭时仍在消费保护项（`on()` 应在最前） | §2.4 |
| P3 | `skipOther` 名不副实（已不拦截） | §3.6 |
| P3 | `instLong` 字段漂移缺字段名级日志 | §3.5 |
| P3 | `sigSeen`/`missSeen` check-then-act 非原子 | §3.7 |
| P3 | `imgPathOf` 只扫 `declaredFields`（纯日志用途） | §3.8 |
| P3 | 单测：无 >32 位段用例、下界只锁到 19（应为 20）、`name`/`path` contains 等价变异、无 `until == now` 边界 | §4.1–4.4 |
| P3 | `hexToken` 的 `trim()` 是死代码 | §4.6 |

**判定**：本批改动**方向正确、核心修复实打实**（16→32 位下限堵住了数字串误拦；`THUMBNAIL_DIRPATH://th_` 前缀不再被首道判据拒收；`SKIP no-token` 消灭了上一版的零日志无头案；去掉 `downloadType` 闸门有真机硬证据支撑）。**无 P0，无 P1。** 5 条 P2 里最该优先处理的是 **§1.1（hexToken 上界）** 和 **§3.1（排除名单语义反转）** —— 前者是「静默失效」这一本项目头号坑的残余入口，后者在真机上已经具备触发条件（R31g 已见 `talkerOf` NPE）。

---

## 6. 修复落盘（R34 第二轮，经 team-lead 授权改为可写）

[DONE 修复落盘]

| 条目 | 文件:行号 | 做法 |
|---|---|---|
| P2① | `MediaGuardLogic.kt:55` 新增 `MD5_LEN=32` + `isMd5()`；`hexToken` (`:80-97`) 改两级取值 | 优先取**长度恰好 32** 的段，没有才退回「最长 ≥32 段」 |
| P2① | `VoiceFileGuard.kt:135-145` | `protectMedia` 在 `!isMd5(t)` 时打 warn，带 `len` / `tok` / `raw`；**仍照样入池**（不退化为丢弃） |
| P2② | `AutoHdImg.kt:336-358` | 纯排除模式（`allowRaw.isEmpty()`）+ talker 空 ⇒ 放行 + warn；allow 模式**保持拦截** |
| P2② | `feature/ListResolver.kt:112-127` | 仅补 KDoc：警告调用方「空 talker 不能直接信返回值」，并指明判断**不能下沉**到本函数（会动 NotifyMute 口径） |
| P2③ | `AutoHdImg.kt:207-227` | `localId/msgId` 改**字段优先**（`fldLocal/fldMsg`），参数兜底；两者都>0 且不等 ⇒ 按 sig 去重打 warn（上限 12） |
| P2③ | `AutoHdImg.kt:114-121` | 新增 `mismatchSeen` |
| P2④ | `AutoHdImg.kt:261-264`（异常路径）、`AutoHdImg.kt:376-380`（`svc.c` 返回非 true） | 两条失败路径都 `synchronized(done) { done.remove(msgId) }`，日志尾部加 `(retryable)` |
| P2④ 延伸 | `AutoHdImg.kt:356-362` | allow 模式下 **talker 取不到**（=没判出来，不是判出来不在范围）也摘出 `done`；真被判定不在名单里的照旧占位 |
| P2⑤ | `VoiceFileGuard.kt:129-134` + `:135-145` | 不改判据。非 32 位 token 走新 warn、取不到 token 走既有 `SKIP no-token`（raw 从 40 提到 48）；`RevokeRescue.kt:97-102` 的 `RRV record` 行**已同时打了 `origType=` 与 `imgPath=`**，两条日志能对上 ⇒ **不需要改 RevokeRescue 就能把 type=43 的真实形态读回来** |

### 新增单测（`MediaGuardLogicTest.kt`）

| 用例名 | 锁住的变异 |
|---|---|
| `hexTokenPrefersMd5OverLongerHexSegment`（`:65-76`） | 「取最长段」vs「优先取 32 位段」—— sha1(40)/key(64) 在 md5 前、后、尾巴上四种摆放 |
| `hexTokenFallsBackToOverLongSegmentWhenNoMd5`（`:78-87`） | 固化「没有 32 位段才退回最长段」，并用 `isMd5()` 断言兜底结果**不是** md5（调用方据此告警） |
| `hexTokenRejectsNineteenDigitMsgSvrId`（`:96-101`） | 用项目记录的 19 位 msgSvrId `6139184963485905836` + 20 位样本，把下界从 19 顶到 **21** |
| `mediaHitIgnoresTokenAppearingOnlyInDirectoryPart`（`:153-161`） | `name.contains` vs `p.contains`（token 只在目录段 ⇒ 必须不命中；文件名里有 ⇒ 命中） |
| `mediaHitTreatsExpiryAtNowAsAlive`（`:163-170`） | `until == now` 边界，`mediaHit` 的 `<` 与 `>` 变异 |
| `anyAliveTracksExpiry` 末尾新增一行（`:189-192`） | `anyAlive` 的 `>=` vs `>` 变异，并与 `mediaHit` 对齐口径 |

### ⚠️ 给 team-lead 的两点提醒
1. **P2⑤ 的 type 标签**：因边界限制我没动 `RevokeRescue.kt`，所以 warn 里**不带 `type=43`**。靠 `RRV record ... origType=43 imgPath=<raw>` 与 VFG warn 的 raw 值对上来取证。若你希望 warn 里直接带类型，需要改 `RevokeRescue.kt:119-122` 把 `orig.type` 传进 `protectMedia`（1 行，未做，等你定）。
2. **P2④ 延伸**（`AutoHdImg.kt:356-362`）超出你字面要求，是我主动加的：allow 模式下 talker 取不到属于「没判出来」而非「判出来不在范围」，按 P2④ 同一原则不该永久占位。若你认为多余可直接删那两行。

---

**被明确判定为「已核，无问题」的项**：§1.3（32 位对图片成立）、§1.4（`contains` 不串号）、§1.7（mediaHit 迁移等价）、§2.1（SKIP 日志补得对）、§2.5（池清理）、§2.6（两分支互斥）、§3.4（无未初始化字段风险）、§3.10（去掉闸门正确 + `selfFiring` 定位准确）、§4.5（多数变异会被抓住）、§5.4（无反向失效）。

---

## 6. 变异验证（补做，2026-10-29）

`d351b11` 提交时标注「⛔ 未做：5 组变异验证」，现已补做完成，**5 组全部被抓住**。

### 工具坑（已解决，别再踩）

| 现象 | 真因 | 解法 |
|---|---|---|
| 报告 `tests="10"` 但源码有 15 个 `@Test` | **增量编译不重编测试类**（跑的是 22:33 的旧 `.class`）⇒ 802 vs 807 是假判据 | 先显式 `compileDebugUnitTestKotlin`，再跑 |
| `export PATH=...` 后报「no `*Test.class`」 | 清 PATH 后 Windows `find.exe` 顶掉 Git `find` ⇒ `_junit.sh` 找不到类 | **编译与测试必须分两次调用**，不能在同一条命令里换 PATH |
| Python `subprocess(shell=True)` 编不动 | 走 cmd.exe，`export` / `./gradlew` 都不成立 | 改 bash 脚本 `_mut_r34.sh`：子 shell 里换干净 PATH 重编 → 退回原环境跑 `_junit.sh` |
| PowerShell 在 Python subprocess 里 `FileNotFoundError` | — | 不用它 |

### 结果

| # | 变异 | 失败数 / 总数 | 判定 |
|---|---|---|---|
| 1 | `hexToken` 退回「只取最长段」 | 1 / 807 | ✅ 抓住 |
| 2 | `MIN_TOKEN` 32 → 20 | 2 / 807 | ✅ 抓住（预期 1 个方法红，实际 2 个 ⇒ 强于预期） |
| 3 | `name.contains` → `p.contains` | 1 / 807 | ✅ 抓住 |
| 4 | `anyAlive` `>=` → `>` | 1 / 807 | ✅ 抓住 |
| 5 | `mediaHit` `until <` → `<=` | 1 / 807 | ✅ 抓住 |

对照组：未变异全量 `OK (807 tests)`。

⇒ `d351b11` 的「⛔ 未做」标注**已消除**。临时脚本 `_mut_r34.sh` / `_mut_r34.py` 已删除。
