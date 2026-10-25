# R14 交叉复核：SnsCdnProbe.kt（上机前）

对象：`wxgroup/app/src/main/java/com/lx/wxgroup/probe/SnsCdnProbe.kt`（119+/16-）
方式：静态复核 diff + 全文 + `tools/_r9_cdn.py` 消费端（未改任何文件、未碰 adb）

## 结论：**PASS**（可上机）

6 项重点逐条过了，没有一项会导致真机出问题。下面按重要性给结论 + 依据，
末尾有 3 条**非阻塞**的读日志注意事项。

---

## 1. 脚本日志匹配 —— PASS

新增字段全部追加在行尾，行首 `CDN stat hooked=` 原样未动
（`SnsCdnProbe.kt:547-551`）。

`tools/_r9_cdn.py` 里所有取 `CDN stat` 的地方（`last_cdn(lines,"stat hooked=")`）：
`_r9_cdn.py:461`（run_i0）、`:487`（run_i0_after_scroll）、`:787`（主控流程）。
`last_cdn()` 是**子串**匹配（`:247` `"CDN " + key in lines[i]`），从后往前取第一条，
行尾加长不影响。

逐条验正则（新行 = `... hitK=.. hitCB=.. last=<iso> hitN=.. hitPicE=.. hitPicNE=.. hitDir=.. hitNoField=..`）：

| 正则 | 脚本位置 | 会不会误命中新字段 | 判定 |
|---|---|---|---|
| `proc=(\S+)` | `:465` | 新字段无 `proc=`，行内也只有一处 | ✅ |
| `hooked=(\d)` | `:466` | 行首唯一 | ✅ |
| `hitK=(\d+)` | `:490`, `:788` | 新字段是 `hitN/hitPicE/hitPicNE/hitDir/hitNoField`，**无一含子串 `hitK=`** | ✅ |
| `hitCB=(\d+)` | `:491`, `:789` | 同上，无一含 `hitCB=` | ✅ |

⚠️ 特别确认了团队点名的风险点：`hitK=(\d+)` **不会**被 `hitN=` 命中，
`hitNoField=` 里也没有 `hitK=` 子串（N-o-F ≠ K）。`\d+` 贪婪也无害——
每个字段后都是空格边界。

另外确认全仓没有其他消费者断言 stat 行格式（只有 `docs/取证结论.md`
和 `_cache/*.md` 里的历史样本，非代码）。

## 2. 热路径（`onC2CDownloadCompleted`，每张图） —— PASS

关键：`hasNoFieldCause()`（`:613`）**只在失败分支**被调用
（`readField` 的 `.getOrElse { }`，`:610`）。正常路径（字段在、值非空）
走的是 `runCatching { OK to getObjectField(...) }`，**不遍历 cause 链、不构造异常**。

新增开销 = 一个 `Pair` 对象分配（`OK to value`）+ 一次解构，每张图一次，
可忽略。对比老代码（`runCatching { ... as? String }.getOrNull()`）本来也
每次分配 `Result`，所以**没有回归**。

失败路径（字段漂移、每次回调都抛 NoSuchFieldError）确实会每次填栈，
但**老代码也是每次填栈**（同样是 `runCatching` 包裹），同样不是回归，
量级 ~几十 µs/次，不构成 ANR。

回调里仍然**不写 LogX**（只动 AtomicInteger / volatile 字段），热路径纪律没破。

## 3. `ConcurrentHashMap` null 问题 —— PASS

`reqErrCount` 唯一用法是 `:627`：
`computeIfAbsent(reason) { AtomicInteger() }.incrementAndGet()`

- key `reason` 全部非空：`no-arg0` / `l1-nofield` / `l1-null` / `l1-empty` 是字面量；
  `l1-not-array cls=${raw.javaClass.name}`（`javaClass.name` 非空）；
  `"ex:" + (it.javaClass.simpleName ?: "?")` 有 elvis 兜底。
- mapping lambda 返回 `AtomicInteger()`，非空。
- 全文件没有任何 `reqErrCount.get(...)` / `put(k, null)`。

⇒ 无 NPE 面。另外 `observedDirs`（也是 CHM keySet）的两处 `add`
（`:335` `File(pic).parent ?: pic`、`:408` `File(p).parent ?: p`）都在
`pic`/`p` 非空之后，不会 add(null)。

## 4. `readString()` / `readObject()` 行为不变 —— PASS（逐调用点验）

以 `readField()` 为底后，返回值语义：

| 场景 | 老 `readString` | 新 `readString` | 同？ |
|---|---|---|---|
| `obj == null` | `null` | `readField→(OTHER,null).second as? String` = `null` | ✅ |
| 字段存在、是 String | 该 String | 同 | ✅ |
| 字段存在、非 String（如 Integer） | `as? String` → `null` | `raw as? String` → `null` | ✅ |
| 字段不存在（NoSuchFieldError） | `getOrNull()` → `null` | `(NO_FIELD,null)` → `null` | ✅ |
| 其它异常（ClassCast / IllegalAccess…） | `null` | `(OTHER,null)` → `null` | ✅ |

`readObject` 同理（成功返原值、失败返 null）。
⇒ 五个调用点（`rewriteImagePath:399`、`collectImageDir:407`、
`logReq:413/417/418`）行为**逐条不变**。

关键：`runCatching` 捕获的是 `Throwable`，所以 `NoSuchFieldError`（是 `Error`
不是 `Exception`）**能被接住**，在 `getOrElse` 里正确归类为 `NO_FIELD`。
这点写对了——如果用 `catch (Exception)` 就会漏掉。

⚠️ 顺带：`readObject`（`:597`）改完后**已无调用点**，成为死代码
（全 `com/lx/wxgroup/` 只剩它自己的定义）。`build.gradle.kts` **没有**
`allWarningsAsErrors`，所以只是 warning、不阻断编译。非阻塞。

## 5. 节流逻辑 —— PASS（边界逐个验）

`:627` `c = computeIfAbsent(reason){AtomicInteger()}.incrementAndGet()` ⇒
**首次即 c=1**（`incrementAndGet` 先加后返，不可能为 0）。

| c（第几次） | `c <= 3` | `bitCount(c)==1` | 打？ |
|---|---|---|---|
| 1 | ✅ | — | **打** ✅ 首次失败看得到 |
| 2 | ✅ | — | **打** |
| 3 | ✅ | — | **打** |
| 4 | ✗ | ✅(100b) | **打** |
| 5 | ✗ | 2 | 不打 |
| 6 | ✗ | 2 | 不打 |
| 7 | ✗ | 3 | 不打 |
| 8 | ✗ | ✅ | **打** |

⇒ 0 次不会打（`incrementAndGet` 最小 1）；第 1 次必打；长跑时
n 次累计约 `3 + log2(n)` 条，不刷屏且能看出「还在持续发生」。逻辑正确。

## 6. `doReset()` 漏清 —— PASS

`:557-566` 清了：`hitK` `hitCB` `hitN` `hitPicE` `hitPicNE` `hitNoField`
`reqErrCount.clear()` `lastCbAt` `lastPicParent` `observedDirs` `installNote`。
新增的 4 个计数 + `reqErrCount` **全覆盖**，前后差值有意义。

---

## ⚠️ 非阻塞：真机读日志时注意（不影响装包）

1. **`hitN` 含所有 `n` 重载**（`hookAllMethods(cls,"n")` 挂同名全部重载，
   见 `:128-129` 注释）。真机若 `ok0.x0` 有别的 `n(...)` 重载，`hitN` 会
   被它们顶高，且会额外产生 `no-arg0` / `l1-nofield` 的 `reqerr`。
   判「`ok0.x0#n(Ltj/g;)I` 到底调没调」必须**结合 `reqerr reason=` 一起看**，
   不能只看 `hitN>0`。

2. **`l1-null` 是混合原因**：字段在但值真的是 null、以及「反射抛了
   `NoSuchFieldError` 之外的异常」都归到它（`:360` 注释已声明）。
   真机看到 `l1-null` 别直接断定「值是 null」。

3. **对账恒等式**：`hitCB ≈ hitPicE + hitPicNE + hitNoField`
   （不等的唯一原因是 `args[1]` 为 null 时提前 return，`:321`）。
   上机后可以先拿这个式子快速验探针自洽。

---

[DONE]
