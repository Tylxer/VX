# R37 朋友圈灰标 SnsDeletedTag —— 装包前交叉复核（第二视角）

- 目标微信：8.0.48 / 2588 GP 版
- 模块包：`com.lx.wxgroup`
- 设备：`467cf5bc`（本轮**纯静态复核，未接触 adb/设备**）
- 复核范围：
  - `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTag.kt`（+452/-53）
  - `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsDeletedTagLogic.kt`（+94）
- 基线（修复前真机）：`stat on=true deleted=8 bound=1 hooks=12 / binds=65 sns=20 resolved=20 missed=0 hits=16 painted=0 missedTime=43 tv=0 paths=1`
- 立场：**不采信作者说明，一律独立验证**。每条结论落到行号，落不到的标 `[推测]`。

---

## 1. 【最高优先】会不会崩微信

### 1.1 全部 `afterHookedMethod` 的 try/catch 覆盖 —— **OK**

本文件只有 **2 个** `XC_MethodHook`（grep 实测，不是 9 个对象；9 条是被挂的**方法**共用同一个对象）：

| # | 位置 | 包裹 | rethrow | 结论 |
|---|---|---|---|---|
| 1 | `SnsDeletedTag.kt:362` setAdapter 钩子 | `runCatching{...}.onFailure{}`（364-368） | 无 | OK |
| 2 | `SnsDeletedTag.kt:408` `bindHook`（9 条 bind 方法共用，含 `RecyclerView.Adapter.bindViewHolder`） | `try{onBind}catch(t:Throwable)`（413-418） | **无**（416 注释明写「绝不 rethrow」） | OK |

`binds++` 在 try **之前**（412），所以「一次都没进来」与「进来就崩」不会混成同一个数。⇒ **没有漏网的钩子回调，不会崩微信。**

### 1.2 本轮新增的四个函数各自有没有包住

| 函数 | 自身有没有 try/catch | 实际兜底在哪一层 | 结论 |
|---|---|---|---|
| `visibleTexts:801` | **有**，817 行 `runCatching { walk(root, 0) }` 包住整棵递归 | — | **OK**（自身即边界） |
| `reconcileAt:768` | 无 | 调用链 `dataOf:682/716` ← `onBind:447`（在 `bindHook` 的 try 内）/ `retryPaint:524`（`retryResolve:518` 的 `runCatching` 内） | **不崩**，但见 1.3 |
| `walkForTimeHolder:1255` | 无 | 调用点 `apply:1050` 的全部**外部**调用方都已兜：SnsTag.sweep 的 sweeper（`SnsTag.kt:553-558` `runCatching{ sweep() }`）、SnsTag 的 bindHook（`SnsTag.kt:247` `runCatching{ onBind }`）、`fakeMark:767` / `markVisible:744`（各自 `runCatching{ apply(v,vId) }`） | **不崩，OK** |
| `hasTimeControl:1253` | 无 | 同上（它只是 `walkForTimeHolder` 的包装） | **OK** |

⇒ **`hasTimeControl` 这条新增闸门挂在 `apply` 里，而 `apply` 的所有外部入口都在 `runCatching` 内 ⇒ 即使抛异常也只是丢一条标签，不会冒泡到 RecyclerView 布局流程。**

### 1.3 唯一未包住的新增可达点（不崩，但会吞诊断）

- **`SnsDeletedTag.kt:518`**：`mainHandler.post { runCatching { retryPaint(...) } }` —— `runCatching` **没有 `.onFailure`**。
  ⇒ `retryPaint` 里 `dataOf → reconcileAt → visibleTexts` 任一步抛异常 ⇒ **100% 静默**：无日志、无计数、无告警。
  严重度 **P2**（诊断），但它会让「滚动时某些动态不打标」变成零线索的静默失效。
- **`SnsDeletedTag.kt:447` 抛异常时**：`missed++`（450）与 `lastMiss=`（453）都排在 `dataOf` 之后 ⇒ 这次解析失败**不会计入 `missed`**，只有 417 那句 `warnOnce`。而 `warnOnce` 的 5 条额度是与 `id clash:1035`、`level-4 fallback:1228`、`archived:1556` 共用的 ⇒ 大概率被抢光 ⇒ 看不到。
  严重度 **P2**。
- **`SnsDeletedTag.kt:1367`** `found.isAccessible = true`（`walkForTimeHolder:1256` 必经过）：抛 `SecurityException` 的可能是 [推测]，且外层已兜 ⇒ 不崩。

### 1.4 本条结论

**严重度 = OK（不会崩微信）**；附 2 条 P2 诊断缺陷（518 全静默吞、447 抛异常时丢 `missed` 计数）。

**不需要为「崩微信」挡装包。**

---

## 2. 【最高优先】对账是不是真的挡住了「打错标签」

### 2.1 是否存在「不经 `reconcileAt` 就直接用 `data[pos]`」的路径 —— **不存在，作者这条成立**

grep 实测 `adapterItem(` 全部命中：

| 调用点 | 上下文 | 是否必经对账 |
|---|---|---|
| `SnsDeletedTag.kt:737` | `probePath` 的 `@` 分支，**上一行 736 就是 `reconcileAt(...) ?: continue`** | ✅ 必经 |
| `SnsDeletedTag.kt:878` | `stepOf`：`adapterItem(adapter, pos + atOffset(step))` | ✅ 只能拿到 `dataPathCache` 里存过的路径，而那种路径只有 736/739/742 三处生成，全都必经 `reconcileAt` |

且 `stepOf:877` 的第一句 `if (step.startsWith("@"))` 会先截走，`probePath:745` 传给它的 `h` 只可能是 `z/E/D`（`@` 已在 731 被 `if (h == "@")` 拦下）⇒ **不存在绕过对账的 `data[pos]` 取数**。
`dataListOf` 的另两处（769、907）也都走 `reconcileAt` / `adapterItem` 内部。⇒ **作者这条口径经独立验证成立。OK**

### 2.2 `reconcileAt` 返回 null 时是否真的不写 `bound` / 不写 `dataPathCache` —— **是，但有一条不是「不写」而是「删了又重写」**

逐条走查 `dataOf:665-695` 的三条出口：

1. **cache 命中 + `srcStillValid` 通过** → `return v`（674）→ `onBind:448` 拿到 id → 写 bound。合法。
2. **cache 命中 + 失配** → `dataPathCache.remove(key)`（675）→ 落到 `takeProbe/probePath`。**真的 remove 了** ✅
3. **`probePath` 也返回 null**（682-689）→ 走 `logDataFail` 后 `return null`（688），**不写 `dataPathCache`** ✅ → `onBind:449` `id == null` → 只 `missed++` + `retryResolve`，**不写 `bound`**（`bound` 写入点只有 `onBind:468`、`retryPaint:529`、`apply:1056` 三处，689 这条返回 null 时三处都到不了）✅

⇒ **对不过账 ⇒ 不绑、不缓存。OK**

⚠️ 一处小瑕疵：**`walkPath` 返回 null 时（670-676），缓存条目不 remove**（`remove` 写在 `if (v != null)` 里面）。后果不重（这次反正返回 null、不绑），但会留一条永久失配的脏条目。严重度 **P2**。

### 2.3 「作者名为空串 ⇒ 判 false」有没有被绕过 —— **没被绕过**

- `matchName:156-163` 的 `val n = candName ?: return false` + `if (n.length < 2) return false` 全路径必过 ⇒ 空串/1 字恒 false ✅
- 调用侧 `reconcileAt:774-775` 是先 `snsIdOf` 再 `matchName` 的**与**关系，没有任何 `if (texts.isEmpty()) 跳过校验` 的短路 ✅
- `srcStillValid:716` 是 `reconcileAt(...) ?: return false`，不是 `?: return true` ✅
- 反向确认：`reconcileAt:789` 返回的 `nameOf(cand).orEmpty()` 看似可能产出 `""`，但能让 `pickWindow` 判过的候选必然 `matchName == true`，而 `matchName` 要求 `n.length >= 2` ⇒ **走到 789 时 name 必然非空**，`srcStillValid:717` 的 `pick.second == src.name` 不会退化成 `"" == ""` 恒真。✅

⇒ **OK，这条是实的。**

### 2.4 `pickWindow` 的「唯一命中」是不是真的不取第一个 —— **是真的**

`SnsDeletedTagLogic.kt:190-201`：`return WindowPick(if (n == 1) found else -1, n)`。
命中数 ∈ {0,2,3,4,5} ⇒ 一律 `-1`；只有 `n==1` 才吐下标。**没有「取第一个命中」** ⇒ R31h 同款形态被排除。

用 `python -c` 独立复算（`OFFS=[0,1,-1,2,-2]`）：

```
size=0 pos=0        -> (-1, 0)    越界/空列表全部 continue
size=1 pos=0        -> (0, 1)
size=10 pos=0 全 ok -> (-1, 3)    pos=0 只有 {0,1,2} 在界内
size=10 pos=9 全 ok -> (-1, 3)
size=10 pos=-1 全 ok-> (-1, 2)    （上游 770 已挡，进不来）
size=10 pos=50 全 ok-> (-1, 0)
pos=5 ok={7,6}      -> (-1, 2)    ← 关键：明明 6 先被访问到，仍然吐 -1 而不是 6
```

⇒ **严重度 = OK。这是本轮最该盯的一行，它是对的。**

### 2.5 ⛔ **P0：`atOffset` 在 `srcStillValid` 里收到的是「整条 path」而不是「单个 step」—— 永久失效循环**

`SnsDeletedTag.kt:884`：
```kotlin
private fun atOffset(step: String): Int = step.substring(1).toIntOrNull() ?: 0
```
参数名叫 `step`，两个调用点喂的东西却不一样：

| 调用点 | 实参 | 值 | 结果 |
|---|---|---|---|
| `stepOf:878` | `parts[0]`（已按 `;` 切开的**单个 step**） | `"@-1"` | `-1` ✅ 正确 |
| **`srcStillValid:717`** | **`src.path`，即整条路径串** | `"@-1;f:abc"` | `substring(1) = "-1;f:abc"` → `toIntOrNull()` = **null → 0** ❌ |

于是对 `@<off≠0>;<子 step>` 这种**两步路径**，`srcStillValid:717` 判成：
```kotlin
pick.first == atOffset(src.path)   // -1 == 0 → false
```
⇒ 失配 ⇒ `dataOf:675` `remove` ⇒ 重新 `probePath` ⇒ 736-742 又生成**一模一样**的 `"@-1;f:abc"` 并写回缓存 ⇒ **下一次 bind 再失配、再 remove、再全量探测**。

而 `takeProbe:912` 的预算是**每个 key 只有 `PROBE_MAX=12` 次且永不复位**，所以：

1. 每次 bind 都白跑一次全量探测（含 `visibleTexts` 子树遍历 + 4 个候选的反射/`toString`/两条正则）⇒ 主线程滚动期持续烧 CPU（见 §4）；
2. 第 13 次起 `takeProbe` 恒 false ⇒ `dataOf` 走 678-680 直接 `return null`，**这个 holder×adapter 此后永远解析不出 id** ⇒ 该界面**所有动态都不打标**，且只有一条 `no snsId on holder` 日志（被节流）。
   ⇒ **静默失效 + 全废**。

**触发条件（三条同时成立）**
1. `data` 列表元素是**需要 `unwrap` 一层**才拿得到 snsId 的包装对象（否则 739 直接命中，只生成单步 `@0/@-1/@2`，`atOffset` 单步时是对的）；
2. 对账出的偏移 **≠ 0**（有封面 header / 广告卡时偏移恒为 ±1，这正是 R37 根因里那个偏移）；
3. `E/z/D` 三条兜底在该 holder 上不成立（否则 741 之后会改写缓存成 `E`，循环自然终止）。

> 三条的关系：条件 3 不成立时系统能自愈（缓存落成 `E` ⇒ `srcStillValid:715` 对非 `@` 路径直接 `return true`，从此零成本）。**只要 3 成立，1+2 就是必死循环。**

**修法（1 行）**：`srcStillValid:717` 改成对首个 step 取偏移，例如
`pick.first == atOffset(src.path.substringBefore(";"))`。
或在 `DataSrc` 里直接存一个 `off: Int`，别再靠解析字符串。

**真机判据（一眼定性）**：`grep "SNS_DELTAG path"`，若**同一条** `-> @-1;f:xxx` / `-> @1;m:k` 被打印**超过一次**，就是它在每帧重探；随后 `no snsId on holder` 的 `fail=N/seen=N` 一路涨到 12，然后 `resolved` 不再增长、`painted=0`。若只看到单步的 `-> @0` / `-> @-1`，本条处于休眠。

**严重度 = P0（静默失效）。** 是本次复核里唯一够格单独叫停装包的缺陷。

### 2.6 `matchName` 的 3 字阈值推演（含短昵称）

`SnsDeletedTagLogic.kt:160-162`：`t.length >= 2 && (t.contains(n) || (t.length >= 3 && n.contains(t)))`
`python -c` 复算（Kotlin 的 `length` = UTF-16 码元，与 Python `len` 对 BMP 一致）：

| 场景 | `nameOf` 值 | 界面可见文本 | 结果 | 含义 |
|---|---|---|---|---|
| A 名字原样显示 | 陈健峰 | 陈健峰 | **true** | 正常 |
| B 界面更长 | 陈健峰 | 贸易商陈健峰 | true | 正向命中 |
| C 文档说的加工 | 贸易商陈健峰 | 陈健峰 | true（反向，3 字成立） | 正常 |
| D **备注名** | 贸易商陈健峰 | **陈总** | **false** | 无子串关系 ⇒ 该格 `@` 恒不过 |
| D' 备注名 | 张三 | 三哥 | **false** | 同上 |
| E **单字昵称** | 龙 | 龙 / 龙哥 | **false**（`n.length<2` 先否） | 该用户动态 `@` 恒不过 |
| F 2 字英文昵称 | ab | table | **true** | 反向不成立、正向 `contains` 太松 ⇒ 任何含 `ab` 的文本都算命中 |
| I 反向阈值 | 陈健峰 | 陈健（2 字） | **false** | 2 字截断不认，3 字截断才认 |

结论：
- **3 字阈值不会让短昵称「恒匹配」** —— 恒匹配来自 F 那一路（正向 `contains` 对 2 字短名太松），与 3 字阈值无关。
- **会让一部分昵称恒不匹配**：D（备注名：界面显示的是备注，与 `ImproveInfo.name` 没有子串关系）、E（单字昵称）、I（名字被 ellipsize 截到 2 字）。
- 但**这三类都不是致命的**：§2.5 之外还有 `E` 这条**位置无关**的兜底路径，`@` 恒不过只是退回 `E`，标签照样能画（详见 2.7）。⇒ 它们表现为「`@` 路径形同虚设」，**不是**功能全废。

严重度 **P2**（作者自己在 `_r37_tag_paint.md:232` 已经把「残留风险」写明了，这里只是把它量化成具体场景 + 给出真机辨识串）。

### 2.7 一条把 2.6 的严重度压下来的关键事实

`probePath:730` 的候选顺序是 `arrayOf("@", "z", "E", "D")`，**`@` 在最前**：
- `@` 过 ⇒ 缓存 `@<off>`（热路径要跑 `visibleTexts`）；
- `@` 不过 ⇒ 落到 `E`，而 `E` 是 **holder 自己的字段**，holder↔itemView 天生一一对应，**不可能变成隔壁那一格的数据**（`srcStillValid:715` 对非 `@` 路径直接 `return true` 就是建立在这个性质上）。

⇒ **对账失败的最坏结果是「退化成位置无关的 `E` 路径」，而不是「拿一个错位的 id」。** 这是本轮设计里最结实的一环 —— 它保证了 §2.6 那几类恒不匹配的代价只是「多用一条路径」，不是打错标签。

### 2.8 本条结论汇总

| 子项 | 严重度 |
|---|---|
| 2.1 无绕过对账的 `data[pos]` 路径 | OK |
| 2.2 对不过账 ⇏ 不绑不缓存 | OK（+ 一个 P2 的脏条目不 remove，670-676） |
| 2.3 空串判 false 未被绕过 | OK |
| 2.4 `pickWindow` 唯一命中 | OK |
| **2.5 `atOffset` 收到整条 path ⇒ 永久失效循环** | **P0** |
| 2.6 短昵称/备注名恒不匹配 | P2（被 2.7 兜住） |

---

## 3. 缓存 `dataPathCache` 的失效与泄漏

### 3.1 `srcStillValid` 重算失败时是否真的 remove 并重探 —— **是**

`dataOf:668-695` 逐行：

```
668  val src = dataPathCache[key]
670  val v = walkPath(...)                     // 先按旧路径取一次
671  if (v != null) {
674      if (srcStillValid(...)) return v      // ✅ 只有验证通过才返回
675      dataPathCache.remove(key)             // ✅ 失配真的删
676  }
678  if (!takeProbe(key)) { ...; return null } // 有预算才重探
682  val found = probePath(...)
690-691  dataPathCache[key] = s                // 用新证据覆盖
```

- **不存在「失配却仍返回旧值」的路径**：`return v` 只有 674 一处，且被 `srcStillValid` 守卫。✅
- **不存在「失配后不重探」的路径**：675 之后必然落到 678。✅
- ⚠️ 唯一不 remove 的分支是 `walkPath` 返回 null（670 为假 ⇒ 跳过整个 671-676 块）：那次返回 null、不绑条，后果无害，但脏条目留着。**P2**（与 2.2 同一处）。

### 3.2 `DataSrc` 是否真的只存 String —— **是，无泄漏面**

`SnsDeletedTag.kt:624`：`private class DataSrc(val path: String, val name: String)`。
两个字段都是 String，没有 View / holder / 数据对象，也没有内层类隐含的外层引用（KDoc:618 明确禁止引用外层成员）。
`dataPathCache` 的 key 是 `cacheKey:700` 拼出的**类名字符串**（holder 类 + adapter 类）。

⇒ **value 与 key 都不钉住任何 View ⇒ 不存在「RecyclerView 条目永不回收」的泄漏。OK**

同类检查顺手做了：`perKeyLogged:152` 只存 `String -> Int`，四个 key 分别是 `nodynamic:${类}`（1052）、`notv:${类}`（1139）、`tv:${类}`（1297）、`tvnull:${类}`（1301），全是类名串，且**不含 View 实例** ⇒ 无泄漏。上限 = 出现过的 View 类数 × 2 条配额，朋友圈场景是常数级。**OK**

### 3.3 `Collections.synchronizedMap` 的组合操作（check-then-act）

确实存在三处组合操作，且都**不在**同步块里：

| 位置 | 组合 | 判定 |
|---|---|---|
| 668 get → 674 用 → 675 remove | check-then-act | 见下 |
| 682 → 691 put | get(decrement)/put 分离（`takeProbe:912` 也是） | 见下 |
| `logKeyOnce:165-167` get → put | check-then-act | 见下 |

**实际并发度判定**：所有写路径（668/675/691）都只由 `onBind`（bind 钩子，`afterHookedMethod` = 主线程）和 `retryPaint`（`mainHandler.post`，518 = 主线程）进入；`stat()`（1604）与 `logDataFail` 只读 `.size` / 计数。⇒ **写者唯一且都是主线程，不存在丢更新的并发场景。**

唯一的跨线程读者是 `SnsTag.stat():804` → `missedTimeCount()`（172）—— 它读的是 `@Volatile` 字段，不是本 Map。

⇒ **当前不会丢更新。严重度 = OK（附条件：这个结论依赖「只有主线程会写」，将来若把 `dataOf` 挪到后台线程就会真丢更新）。** 若想一次性堵死，把 666-695 整段包进 `synchronized(dataPathCache) {}` 即可。

### 3.4 缓存会不会永久失效循环

分两种路径形状：

**A. 单步 `@0` / `@-1` / `@2`（739 生成）**
`atOffset("@-1")` = `-1` 正确 ⇒ 命中缓存时验证成立 ⇒ 稳定；只有「作者文本还没渲染出来」那几拍会失配 ⇒ remove ⇒ 重探 ⇒ 这时 `E` 那三条里至少一条成立 ⇒ 缓存落成 `E` ⇒ `srcStillValid:715` 对非 `@` 直接 `return true` ⇒ **从此零成本、不再重探**。
⇒ **自愈，不循环。OK**

**B. 两步 `@<off≠0>;f:xxx` / `;m:k`（742 生成）**
⇒ **会永久循环，且 12 次预算耗尽后彻底死掉**。详见 §2.5。**P0**

**C. 缓存永远建不起来（`@` 与 `E/z/D` 全部不成立）**
每次 bind 都走 678 → 682 全量探测，12 次后 `takeProbe` 恒 false ⇒ 该 key 永久 null。
触发条件：**文本始终对不上（备注名/单字昵称）** 且 **`E` 在该 holder 上不可用**。
R35 的 dex 证据（`SnsDeletedTag.kt:653-655`：`c1/d1` 把真 item 写进 `m0.E`）说明 `c1/d1/l0` 这三条钩子执行后 `E` 就是真数据 ⇒ 至少三次机会里有 `E` ⇒ **C 在当前版本上不成立**。
⇒ 严重度 **P1**（当前不出事，但它是 2.5 之外的第二张倒下的多米诺；若 `E` 那族字段被 R8 改名，C 与 B 会同时爆）。

### 3.5 本条结论

| 子项 | 严重度 |
|---|---|
| 3.1 失配必 remove + 重探，无「失配仍返回旧值」 | OK |
| 3.2 `DataSrc` 只存 String，无 View 强引用 | OK |
| 3.3 组合操作（当前单线程写） | OK（附条件） |
| 3.4-A 单步路径自愈 | OK |
| 3.4-B 两步路径永久循环 ⇒ 全废 | **P0**（= §2.5） |
| 3.4-C 缓存建不起来（依赖 `E` 仍在） | P1 |

---

## 4. 主线程性能（滚动掉帧）

### 4.1 线程归属 —— 全部主线程

`visibleTexts` 只被 `reconcileAt:771` 调；`reconcileAt` 只有两个入口：

| 入口 | 调用链 | 线程 |
|---|---|---|
| `probePath:736` | `dataOf:682` ← `onBind:447`（bind 钩子的 `afterHookedMethod`） / `retryPaint:524` | **主线程** |
| `srcStillValid:716` | `dataOf:674` ← 同上；`retryPaint` 由 `mainHandler.post`（518）发出 | **主线程** |

⇒ 确认跑在主线程，**没有后台化**。

### 4.2 定量估计（[推测]，量级结论可用，绝对值依赖机型）

单节点规模参考：历史 dump `_r35_sns_tag.md:873` 里 `host=FrameLayout paths=37`（那还是 `TREE_DEPTH=9` 截断后的输出条数）；真动态 item 的子树节点数按 **80~200** 估。

**一次 `reconcileAt` 的账**

| 组成 | 明细 | 估计 |
|---|---|---|
| `visibleTexts:801` | 80~200 次节点访问；其中 25~40 个 TextView 各做 `getText()+toString()+trim()`（3 次分配） | 0.05~0.25 ms |
| 候选 `toString()` | 5 个候选 × **2 次**（`snsIdOf:774` 一次 + `nameOf:775` 一次，**没有复用同一个串**），ImproveInfo 类 toString 常含正文约 300~800 字符 | 0.10~0.30 ms |
| 正则 | 10 次 `find`（`ID_RE`/`NAME_RE`）扫 300~800 字符 | 0.05~0.15 ms |
| `matchName` | 5 × 20 = 100 次短串 `contains` | ~0.02 ms |
| **合计** | | **0.2~0.7 ms**，中位 ~0.4 ms |

**摊到每帧**

- 一次 RecyclerView bind 会进多条钩子（R37 基线 `sns=20` 对应约 7~10 个 view，即 **2~3 条钩子/view**）⇒ 单 view ≈ **0.8~1.2 ms**
- 滚动时每帧有新条目进屏 1~3 个 view ⇒ **0.8~3.6 ms/帧**（16.6 ms 预算）

⇒ **是不是会掉帧：单独看不至于，但在中低端机上 3.6 ms 已是同量级的额外开销，叠加微信自己的 bind 后很可能在快速滑动时出现可感知的抖动。**

### 4.3 热路径的重量取决于「缓存里存的是哪条路径」

这是本轮一个容易看漏的性质 —— `srcStillValid:715` 对非 `@` 路径直接 `return true`：

| 缓存内容 | 每次 bind 的开销 | 谁决定 |
|---|---|---|
| `E` / `z` / `D`（`srcStillValid` 秒回 true） | ≈ 10 µs（2 次反射 + `list.get`） | `probePath:730` 的候选顺序 `arrayOf("@","z","E","D")` —— **`@` 在最前** |
| `@0` / `@-1` / `@2` | + 0.2~0.7 ms（每次都重算窗口） | 同上 |

⇒ 只要探测那一拍 `@` 通过对账（最可能发生在 `retryResolve` 延后的那一拍，那时文本已就位，`_r37_tag_paint.md:221` 也这么预期），**之后每一次 bind 都要付 0.4 ms**。

**好消息**：2 秒 sweep **不**走这条热路径 —— `sweep:1469` → `paint` → `timeTextOf:1216` → ① 级 `findViewById(tvId)` O(1)。⇒ 不存在「每 2 秒对全屏 N 个 view 各跑一次子树遍历」这个最坏情况。**这一点必须记下来，否则容易把 Q4 误判成 P0。**

### 4.4 有没有缓存 / 限流 —— **没有，一处都没有**

`visibleTexts` 每次 bind 全新遍历、全新分配 ArrayList(20) + 最多 20 个 String；`reconcileAt` 每次把 5 个候选的 `toString()` 各算 2 遍；`srcStillValid` 每次命中都重算。

**建议（按性价比排序）**

1. **把候选顺序从 `arrayOf("@","z","E","D")`（730）改成 `arrayOf("E","z","@","D")`。**
   理由：`E` 是 holder 自己的字段、位置无关、本来就不需要对账，`srcStillValid` 对它直接 `return true` ⇒ 热路径成本归零；`@` 只在 `E/z` 都不成立时才上场，而它照样被 `reconcileAt` 守着 ⇒ **对账强度一点没降，热路径直接消失**。这是本条里唯一「改 1 行同时拿到性能和正确性」的方案。
2. `snsIdOf(cand)` 与 `nameOf(cand)` 复用同一个 `toString()` 结果（774-775）⇒ 候选开销立刻减半。
3. 给 `visibleTexts:803` 加节点访问上限（如 `visited < 150` 才继续）—— 现在的上限只有「收集到 20 条文本」和「深度 8」，而 `WALK_KIDS=16` × 8 层的**最坏访问数是 16^8 量级**；真机上正常，一旦遇到九宫格 + 深嵌套的卡片就有尾部风险。
4. 若仍需 `@`：把 `srcStillValid` 的复查降频（offset==0 时每 8 次 bind 复查一次）。

### 4.5 本条结论

**严重度 = P1。**
理由：不崩、不打错标签，但「每次 bind 多 0.4 ms × 2~3 条钩子」在快速滚动时是可感知的开销；且它附在 §2.5 那条 P0 上时会被放大（那时每次 bind 还要叠一次全量 `probePath`）。
**不构成装包阻挡，但建议连同 4.4-1 一起处理。**

---

## 5. `hasTimeControl` 新闸门会不会把功能关死

### 5.1 闸门的作用域 —— **只在「本类没有 Binding」时生效，正常路径碰不到**

`apply:1030-1063` 的结构：
```
1030  if (snsId != 0L) {
1031      val prev = bound[itemView]
1032      if (prev != null && prev.id != 0L) { ...paint...; return }   ← 正常路径从这里出去
1050      if (!hasTimeControl(itemView)) { ...; return }             ← 闸门只在这之后
1056      val b = Binding(snsId).also { bound[itemView] = it }
```
- 本类 `onBind:468` / `retryPaint:529` 建过 Binding 的 view ⇒ 走 1032 分支 ⇒ **从不经过 1050**。✅
- `snsId == 0L` 那条兜底（1064-1077）也不经过。✅
- `bound` 的写入点仍只有 `onBind:468`、`retryPaint:529`、`apply:1056` 三处（与 `_r37_tag_paint.md:277` 声称一致，grep 复核通过）。

⇒ **不会在正常路径上误触发。OK**

### 5.2 两个方向的判定 —— **方向都对，没有把功能关死**

| 宿主 | `walkForTimeHolder:1256` → `publishTimeMethod:1346` | 结果 | 对不对 |
|---|---|---|---|
| 真动态根 `u3/g2/i3/…` | 父类链第 9 层就是声明 `getPublishTimeText()` 的 `Llh3/l1`，`SUPER_MAX=16` 覆盖（1354）；`tv host=u3 depth=0` 已实证成功 | **true** ⇒ 放行 | ✅ 正常动态不被挡 |
| 封面 header（裸 `FrameLayout`） | 全子树无该类 | **false** ⇒ 拦下 | ✅ 拦下的本来也画不上 |

⇒ 判 true 的是「本来就画得上」的，判 false 的是「本来就画不上」的。**这个闸门选的判据与它要达成的目的是同一个**，因此不会造成 `painted` 恒 0。

### 5.3 ⭐ `snstag fake` 会不会被这个闸门挡住 —— **不会**（本次最该查的一条，结论是放行）

完整调用链（逐跳核实，不是采信文档）：
```
EntryReceiver.kt:610  snstag fake --es id <u=...>
  → SnsTag.kt:762 fakeMark
      → 713 setForce(setOf(id)) → 715 SnsDeletedTag.forceTag(ids)   （双写，两边 sweep 同口径）
      → 767 for ((v, vId) in snapshot()) runCatching { apply(v, vId) }
          → SnsTag.kt:730 SnsDeletedTag.apply(host, vId, del + forceSet)
              → SnsDeletedTag.kt:1024 apply
```
进了 `apply` 之后分两支，**两支都能到 `painted++`**：

**分支 A（本类已有 Binding，1032）**：`paint(itemView, prev, tagged)` → `hit = isTagged(prev.id, tagged)`，`tagged` = `del + forceSet` 含 fake 的 id ⇒ 命中 ⇒ `timeTextOf` → ① `tvId` 缓存 O(1) ⇒ `want != cur` ⇒ `setText` ⇒ `painted++`。**闸门不参与。**

**分支 B（本类没有 Binding，1050）**：目标 view 是真动态 ⇒ `hasTimeControl` = **true** ⇒ 放行 ⇒ 1056 建 `Binding(snsId)` ⇒ 1062 `paint` ⇒ `timeTextOf(host, b)`：
- `b.tvId == NO_ID`、`b.hint == ""` ⇒ 跳过 ①、跳过 ③；
- ② `walkForGetter:1290` → `m.invoke(v) as? TextView` ⇒ **返回的是 TextView 对象，与文本是否为空无关**（`_r37_tag_paint.md:60` 同口径）⇒ 命中 depth=0；
- `cur = ""` ⇒ `base = strip("") = ""` ⇒ `want = appendTag("") = "[删除已拦截]"`（`SnsDeletedTagLogic.kt:317` 单独成串，无前导空格）⇒ `want != cur` ⇒ `tv.text = want` ⇒ `painted++`。

⇒ **空文本不挡路，闸门也不挡路。fake 路径通。OK**

**但有 3 个前置条件，缺一条就白装**（真机上按这个清单自查）：

| # | 条件 | 缺了会怎样 | 辨识串 |
|---|---|---|---|
| a | 目标动态必须**在 `SnsTag.bound` 里**（`fakeMark:767` 只遍历 SnsTag 自己的 snapshot） | fake 压根不会被调到 ⇒ 毫无日志 | 先 `snstag dump`，`bound(n)` 里要有那条 `u=` |
| b | 本类已有 Binding 时，`prev.id` 必须与 SnsTag 那条 id **一致** | 走 1032 ⇒ `hit=false` ⇒ `painted` 不涨 ⇒ **fake 白做** | 专属日志 `SNS_DELTAG: id clash own=… ext=…`（1035） |
| c | SnsTag 传进来的 host 必须是真动态根，不能是封面 header | 1050 拦下，该格 `lastMiss=nodynamic(...)` | `SNS_DELTAG: skip non-timeline host=…`（1053，warn 级） |

⇒ 装包后**先 `snstag dump` 确认 a、再看有没有 `id clash` 确认 b**，然后 fake。

### 5.4 `ensureSweep()` 的补调用 —— **无重复调度、无泄漏**

- `ensureSweep:1438` 有 `sweepScheduled.compareAndSet(false, true)` ⇒ 不会重复 `postDelayed`。即使 `sweeper.run:1430` 先把标志置 false 后外部又调了一次 ⇒ 最多 1 个 pending，不会堆积。✅
- 泄漏：`sweeper.run:1433` 自调度，但 `ensureSweep:1439` 在 `bound` 空时直接 return ⇒ **离开朋友圈后 sweep 自动停**；`sweep:1465` 还会把 `!isAttachedToWindow` 的 view 摘掉；`bound` 是 `WeakHashMap` ⇒ 三重自清理。✅
- **特意查的一条**：1056 的 `Binding(snsId)` 会不会每 2 秒新建一个（那样 `missCounted` 去重会被击穿，`missedTime` 又回到 R37 的无限涨）？
  ⇒ **不会**。第一次 adopt 后 `bound[view]` 就有值了，之后 SnsTag.sweep 每 2 秒进 `apply` 都走 1032 分支，不再新建。而 `invalidateIfReused:238` 只在 pos 真变了时才删 ⇒ 那时是「view 换了内容」，重建 Binding 是**正确**的。✅

### 5.5 一个 [推测] 的次生项

adopt 出来的 Binding `hint` 恒为 `""`（1056 不调 `timeHintOf`）⇒ ③ 级「按内容认时间行」对它永久不可用 ⇒ 一旦 ② 级 `getPublishTimeText()` 被 R8 改名，这类 Binding 会**直接掉到 ④ 级 `isTimeLike`**（④ 整串匹配，已挡掉 `00:15` 时长徽章，但挡不住正文里的 `2026-10-29` / `昨天`）。
这是**既有行为**（修复前 `Binding(snsId)` 也是空 hint），不是本轮引入；但本轮给它新加了 `ensureSweep`（1060）⇒ 触发频率上升。
严重度 **P2 [推测]**（依赖 ② 级失效，当前有实证可用）。

### 5.6 本条结论

| 子项 | 严重度 |
|---|---|
| 5.1 正常路径不误触发 | OK |
| 5.2 对 header 判 false、对动态判 true | OK |
| **5.3 `snstag fake` 不被挡（含 3 个前置条件）** | **OK**（这是本轮最该查的一条，答案是放行） |
| 5.4 `ensureSweep` 无重复/无泄漏、去重不被击穿 | OK |
| 5.5 空 hint ⇒ ② 失效时直落 ④ | P2 [推测] |

---

## 6. 诊断计数与限流的可用性

### 6.1 `missedTime` 拆分后字段名/位置是否保留 —— **是**

`stat():1600-1605` 原字段顺序（`binds/sns/resolved/missed/hits/painted/missedTime/tv/paths/lastMiss`）**一字未动**，三个新字段全部追加在末尾：
```
missedTime=$missedTime tv=$tvCached paths=${dataPathCache.size} lastMiss=$lastMiss
  mtHit=$missedTimeHit mtRestore=$missedTimeRestore lastRecon=$lastRecon
```
对外接口 `missedTimeCount():172` 仍返回总和（=`mtHit + mtRestore`，由 1137-1138 保证）⇒ `SnsTag.stat():804` 那边读到的还是同一个数、同一个语义。✅

split 的正确性：`noteMissTime:1134-1143` 是互斥二选一（`if (hit) missedTimeHit++ else missedTimeRestore++`），且 `missedTime++` 在 1137 先行 ⇒ **missedTime ≡ mtHit + mtRestore** 恒成立。⇒ **OK，真机文档引用的名字全部可用。**

### 6.2 「按 host 分桶」是否真的解决「第二次 Activity 看不见」 —— **只解决一半，作者这条声称不成立**

`logKeyOnce:164-169` + `perKeyLogged:152`：

| 声称 | 实测 | 判定 |
|---|---|---|
| 「每桶独立额度 ⇒ 不同结构的 View 各有 2 条」 | **成立**。key 是 `notv:${host类}`/`tv:${host类}`… ⇒ `u3` 与 `FrameLayout` 与 `g2` 互不抢额度 | ✅ 这是真进步（原来第一个 View 就把 5 条吃光，别的结构一行都看不到） |
| 「第二次进 Activity 的结果仍然可见」（`_r37_tag_paint.md:300`、「R37-1 记录」§P2 #4） | **不成立** | ❌ |

**为什么不成立**：分桶的 key 是 **host 类名**（1052 / 1139 / 1297 / 1301 全是 `xxx:${view.javaClass.name}`），而**类名在 Activity 的两个实例之间是完全相同的**。第二次进朋友圈，屏幕上还是 `u3/g2/i3` 那几个类 ⇒ 命中同一批 key ⇒ 那批 key 的 2 条额度在**第一次进入时就已经用完**（`perKeyLogged` 是进程级、无任何清理/重置，311 行作者自己也承认「无清理策略」）。
⇒ 第二次进入时 `tv:` 成功日志、`notv:` 失败日志**一条都不会再出现**，与 R37 被误导时的表现一模一样。

**这是本轮一个「改了但没解决它自己声明要解决的那个问题」的点。**

修法（任选，都不引入强引用）：
1. `perKeyLogged` 加 TTL：记录上次清空时间，每 60 s 清一次；
2. key 里拼上 Activity 身份串（`"${key}#${System.identityHashCode(activity)}"`，只存 int 的字符串形式，不存对象）⇒ 换 Activity 就换桶；
3. 最省事：`PER_KEY_LOG_MAX` 从 2 提到 4，并把 `tv:` 那条成功日志单独开一桶（它是唯一一条「证明功能活着」的日志，最该一直可见）。

严重度 **P2**（纯诊断，不影响功能与正确性）；但装包前值得知道：**真机上看不到 `tv host=u3` 不等于没成功**。

### 6.3 有没有「计数在 catch 里被吞」 —— **有 2 处**（详见 §1.3，这里只给定位）

| 位置 | 形态 | 被吞掉的东西 |
|---|---|---|
| `SnsDeletedTag.kt:518` | `runCatching { retryPaint(...) }` **无 `.onFailure`** | 整条延后一拍路径的所有异常 ⇒ 100% 静默（无日志、无计数） |
| `SnsDeletedTag.kt:447` 抛 | `bindHook:415` 只 `warnOnce` | `missed++`(450) 与 `lastMiss=`(453) 都排在 `dataOf` 之后 ⇒ 这次失败**不计入 `missed`**，只有那句 warn；而 `warnOnce` 的 5 条额度与 `id clash:1035` / `level-4:1228` / `archived:1556` 共用 ⇒ 大概率抢不到 |

⇒ 真机上可能出现「`missed` 不涨、但标签确实没画上、且无失败日志」。严重度 **P2**。

### 6.4 去重开关的正确性 —— **OK**

`Binding.missCounted:222` + `noteMissTime:1135-1136`：
- 同一格反复 sweep ⇒ 只计一次 ⇒ `missedTime=43` 那种「1 个 View 刷出来的总数」不再可能 ✅
- view 被复用给另一条动态 ⇒ `invalidateIfReused:245` 删旧 Binding ⇒ 新 Binding 的 `missCounted=false` ⇒ 新内容照会计一次 ✅ 真实故障不会被抹掉
- `retryPaint:527` 复用 prev 当 `prev.id==i && prev.pos==pos`，否则新建 ⇒ 新建时去重重置是**正确**的

### 6.5 本条结论

| 子项 | 严重度 |
|---|---|
| 6.1 `missedTime` 名字/位置/和语义保留 | OK |
| **6.2 分桶只解决「多结构」，不解决「第二次 Activity」** | **P2** |
| 6.3 518 全静默吞 + 447 抛时丢 `missed` | P2 |
| 6.4 `missCounted` 去重语义 | OK |

---

## 7. `SnsDeletedTagLogic` 新增纯函数的边界

> 以下除注明 [推测] 外，均用 `python -c` 按 Kotlin 语义（`length` = UTF-16 码元、`contains` 大小写敏感）独立复算过。

### 7.1 `nameOf`（`SnsDeletedTagLogic.kt:132-136`）

`NAME_RE = [^A-Za-z]name\s*=\s*'?([^,'\n]{1,32})`，用 `find()` 取**第一个**匹配。

| 输入 `toString()` | `nameOf` | 判定 |
|---|---|---|
| `null` / 空串 | `null` | ✅ |
| `ImproveInfo(name=陈健峰, snsID='…', time:25分钟前)` | `陈健峰` | ✅ 正常 |
| `name=张三`（串首，前面没有字符） | **`null`** | ⚠️ 串首的 `name=` 匹配不上（前缀 `[^A-Za-z]` 要吃一个字符）。真实格式是 `ImproveInfo(name=…` ⇒ 不触发。P2 |
| `Info(nickname=张三, name=李四, …)` | `李四` | ✅ 前缀把 `nickname` 挡住了（`k` 是字母）—— 作者这条是真的 |
| **`Info(real_name=王五, name=李四)`** | **`王五`** | ⚠️ 前缀只挡**字母**结尾的 key，`_` 属于 `[^A-Za-z]` ⇒ `real_name` / `user_name` / `.name` / `1name` 全都能抢在 `name=` 前面。**P2 [推测]**（当前格式下 `name` 在第一位，不触发） |
| **`Info(name=李四)`**（name 是最后一个字段） | **`李四)`** | ⚠️ `[^,'\n]` 不排除 `)` ⇒ 吃掉右括号 ⇒ `matchName('李四)', ['李四'])` = **false** ⇒ 该类对象恒不匹配。当前格式 `name=…, snsID=…` 有逗号 ⇒ 不触发。**P2** |
| `Info(name='李四', x=1)`（带引号） | `李四` | ✅ 尾引号被 `[^,'\n]` 挡住 |
| 名字 > 32 字 | 截断到 32 | 微信昵称上限远低于此 ⇒ 无影响 |

### 7.2 `matchName`（`:156-163`）的边界

| 输入 | 结果 | 判定 |
|---|---|---|
| `candName=null` / `""` / `" "`（trim 后空） | **false** | ✅ 空名不采信（§2.3） |
| `visibleTexts=[]` 或全空串 | **false** | ✅ |
| 超长候选名（>32 被截断）+ 超长文本 | 见 7.3 | ⚠️ |
| emoji 名 `"😀"`（`length=2` 代理对） | 与 `"😀"` 匹配 = **true** | ✅ 代理对让它过了 `>=2` 闸门，方向正确 |
| 中文昵称 2~6 字原样出现 | **true** | ✅ |
| **2 字英文昵称 `ab` vs 文本 `table`** | **true** | ⚠️ 正向 `contains` 对 2 字短名太松 ⇒ 「等于没校验」。但 2 字拉丁昵称罕见，且多半会退化成 `ambig` 而不是误绑。**P2** |
| **单字昵称 `龙`（`n.length<2`）** | **false**，连文本 `龙哥` 也是 false | ⚠️ 该用户动态 `@` 恒不过 ⇒ 退回 `E` 路径（§2.7）⇒ 不致命。**P2** |
| **备注名：界面 `陈总` vs `name=贸易商陈健峰`** | **false** | ⚠️ 两者无子串关系 ⇒ 该用户 `@` 恒不过。**P2**（被 `E` 兜住） |

### 7.3 ⭐ 重点：`matchName` 会不会把「正文里恰好包含作者名」的动态误判为命中 —— **会**

`visibleTexts:801-819` 收的是 itemView 子树里**所有**「可见 + 非空」的 TextView 文本，**不区分角色**：作者昵称、正文、转发来源、位置、链接标题、点赞/评论文案全在一个 list 里。
`matchName:161` 的正向判定是 `t.contains(n)` ⇒ **只要正文里出现了候选的作者名，该候选就算命中**。

`python -c` 复算：
```
matchName('李四', ['今天和李四一起吃饭'])  -> True
matchName('张三', ['转发 张三 的动态'])    -> True
```

**后果分两级**（关键在 `pickWindow` 的唯一命中闸门能不能兜住）：

| 级 | 条件 | 结果 | 性质 |
|---|---|---|---|
| (i) | 正确候选**也**命中（正文提到的人名恰好也是本条作者 ⇒ 罕见；更常见的是邻居名出现在正文里，而正确候选靠昵称命中） | hits ≥ 2 ⇒ `pickWindow` 返回 `-1` ⇒ `reconcileAt` null ⇒ 该格 `@` 不过 ⇒ 退回 `E` | **安全，但静默漏标**（`lastRecon=ambig2`） |
| (ii) | 正确候选**没**命中（备注名 / 单字昵称 / 昵称 View 是 GONE / 被 ellipsize 截到 1~2 字），而正文里恰好出现某个**邻居**的 `ImproveInfo.name` | 那个邻居成为**唯一命中** ⇒ `reconcileAt` 返回邻居的偏移 ⇒ 这个 View 绑上**邻居的 snsId** ⇒ 邻居若已被删，这条动态被打上 `[删除已拦截]` | **打错标签（R31h 同款）** |

(ii) 的真实场景（不虚构，条件都是微信常态）：
> 你给李四设了备注「李总」⇒ 界面显示 `李总`，而 `ImproveInfo.name` 仍是 `李四` ⇒ **正确候选恒不命中**；
> 李四这条的正文写了「今天和张三吃饭」⇒ 邻居 `张三` 的候选在 ±2 窗口内且**正文命中**；
> ⇒ 唯一命中 = 张三 ⇒ **这格绑上张三的 id** ⇒ 张三有被删动态时，李四这条被打标。

**严重度判定 = P1**（不是 P0，理由要写清楚）：
- 「唯一命中」这道闸门确实挡住了 (i)——只要正确候选能命中就必然 `hits≥2` 而放弃，这是本轮最结实的那一行（§2.4）；
- (ii) 需要**两个条件同时成立**（正确候选因备注名等 miss **且** 正文提及邻居全名），不是常态；
- 且 §2.7 那条「`@` 不过就退回位置无关的 `E`」只保「不绑错」，不保 (ii)——因为 (ii) 里 `@` 是**过了**的。

**廉价修法（1 行，建议顺手带上）**：给正向判定加文本长度上界，把「长正文」排除在对账语料外 —— 作者昵称 TextView 不会很长，正文会。
```kotlin
t.length >= 2 && t.length <= n.length + 4 && (t.contains(n) || (t.length >= 3 && n.contains(t)))
```
（代价：昵称前面带前缀且前缀 >4 字时漏标 —— 例如 `name=陈健峰` 而界面显示 `来自深圳的陈健峰`，此时正向失配；但反向 `n.contains(t)` 也救不了它。更稳的取法是上界用 `max(n.length + 8, 16)`，按真机 `lastRecon` 的 `texts=N` 再调。）

**真机判据**：`lastRecon` 若大量出现 `ambig2`/`ambig3` ⇒ (i) 在起作用（安全）；若 `SNS_DELTAG dump` 里出现 **两条不同 View 的 `u=` 相同**（同一 snsId 绑了两个格子）⇒ (ii) 已经在发生 ⇒ 立刻停手。

### 7.4 `pickWindow`（`:190-201`）的边界

全部复算过（表见 §2.4）：`size=0`、`size=1`、`pos=0`、`pos=size-1`、负 pos、越界 pos 全部正确，**命中数 ≠ 1 一律返回 -1，从不取第一个**。
⇒ **严重度 = OK。**

另注：`reconcileAt:770` 挡了 `pos < 0` 与空列表，但**没挡 `pos >= list.size`** ⇒ 那种情况下会先跑完 `visibleTexts` 再 5 次越界 `continue`，白烧一次子树遍历才返回 null。⇒ **P2 微优化**（加一句 `if (pos >= list.size) return null`）。

### 7.5 `WindowPick`（`:172`）

纯数据类，无副作用、无 Android 依赖，可直接 JVM 单测 ✅。**OK**

### 7.6 本条结论

| 子项 | 严重度 |
|---|---|
| 7.1 `nameOf` 的 `_name` 抢先 / 尾括号 / 串首 | P2（当前格式均不触发） |
| 7.2 `matchName` 空/空白/emoji/中文 | OK；短名与备注名 ⇒ P2 |
| **7.3 正文含作者名 ⇒ 误命中 ⇒ (ii) 打错标签** | **P1** |
| 7.4 `pickWindow` 全部边界 | OK |
| 7.5 `WindowPick` 可单测 | OK |

---

## 8. 装包后首要观察点排序

> 排序口径：**崩微信风险 → 静默失效 → 功能不对 → 打错标签**。
> 每条给「期望看到什么 / 看到什么就要停手」。

### 第 1 层 —— 崩微信风险（本轮静态判定为**不崩**，真机只需确认）

| 序 | 动作 | 期望 | 反例 ⇒ 处置 |
|---|---|---|---|
| 1.1 | 进朋友圈 + 快速上下滚动 30 s，同时 `logcat` grep `FATAL EXCEPTION\|SNS_DELTAG bind err\|SNS_TAG err\|SNS_DELTAG sweep err` | **全部 0 条** | 出现 `bind err` ⇒ 记下类名（415 那句只打 `simpleName`）；出现 `FATAL` ⇒ **立刻卸载** |
| 1.2 | 顺便看微信自己的 crash 弹窗 | 无 | 有 ⇒ 立刻卸载 + `adb pull` tombstone |

> 静态依据：§1.1（2 个钩子全包、不 rethrow）+ §1.2（4 个新函数的调用链全被外层 `runCatching`/`try` 兜住）。

### 第 2 层 —— 静默失效（**本轮真正的风险所在，先看这里**）

| 序 | 动作 | 期望 | 反例 ⇒ 处置 |
|---|---|---|---|
| 2.1 | 滚两屏后 `grep "SNS_DELTAG path"` | **每条 `-> xxx` 只出现 1 次**；形状是 `@0`/`@-1`/`E` 单步 | ⛔ **同一条 `-> @-1;f:xxx` 或 `-> @1;m:k` 被打印 2 次以上** ⇒ §2.5 的 P0 已在每帧重探 ⇒ **立刻停手，不要继续滚** |
| 2.2 | 接着看 `grep "no snsId on holder"` 的 `fail=N/seen=N` | N 保持很小（≤4）且不再涨 | ⛔ N 一路涨到 12，之后 `resolved` 彻底不涨、`painted=0` ⇒ 该界面全废（§2.5 的终局）⇒ **回滚** |
| 2.3 | `SNS_DELTAG stat` 看 `lastRecon` | `noname(...texts=0)` 偶发（等下一拍即可），随后 `resolved` 跟上 | ⛔ 持续 `noname(pos=p/n texts=N>0)` 且 `resolved` 不涨 ⇒ 作者名没进子树（备注名/单字昵称，§7.2）⇒ 记下 N，下一轮决定要不要放长度上界 |
| 2.4 | 看 `lastRecon` 的 `ambig2`/`ambig3` 占比 | 低 | 持续 `ambig2` ⇒ §7.3(i)（邻居互相对上了）⇒ 安全但漏标；占比过高时**不要**改成「取第一个命中」 |
| 2.5 | `missedTime` 与 `mtHit` 一起看 | `missedTime` 从 43 掉到个位数，且 **`mtHit=0`** | ⛔ `mtHit>0` ⇒ 命中却找不到时间控件 ⇒ 去看 `getPublishTimeText() returned null on …`（1301），那是 ② 级失效 |

### 第 3 层 —— 功能不对：**`snstag fake` 的具体步骤与期望日志**

> 全部动作要在 **30 s force TTL** 内做完（`SnsTag.kt:239` 的 `refresh` 会 `setForce(emptySet())` 清掉）。

1. 进朋友圈，上下滚两下，让几屏动态都经过 bind。
2. `adb shell am broadcast ... --es op snstag --es sub dump`
   - 从输出 `bound(n)` 里挑**一条** `u=<id>`。
   - ⛔ 必须同时看它的 `host=`：只能是 `u3/g2/i3/q2` 那一族；**不要挑 `host=FrameLayout` 的**（那是封面 header，会被新闸门拦下）。
   - 记下它的 `txt=`（作者名 + 内容的前 12 字），UI 断言时要用来定位。
3. `... --es op snstag --es sub fake --es id <上一步的 u=>`
4. 立刻 `... --es sub stat`（看 `SNS_TAG stat`）以及能拿到 `SNS_DELTAG stat` 的那个入口。
5. **期望（按可信度排序）**

| 判据 | 期望 | 反例 ⇒ 含义 |
|---|---|---|
| **`painted>0`** | **必看到**（§5.3 两支都能到） | ⛔ `painted=0` 且 `mtHit>0` ⇒ ② 级 getter 失效；`painted=0` 且 `mtHit=0` ⇒ fake 的 id 没落在这条 View 上，看下面两条 |
| `SNS_DELTAG tv host=u3 cls=… id=… depth=0` | 看到 = ② 级通 | **看不到 ≠ 失败**（§6.2：第二次进朋友圈这条日志会被 2 条/桶的额度吃掉）⇒ 不要据此判失败 |
| `id clash own=… ext=…` | **不出现** | ⛔ 出现 ⇒ §5.3-b：本类与该 View 的 id 不一致 ⇒ 记下两个 id，`snstag dump` 与 `SNS_DELTAG dump` 对着看谁的偏移错 |
| `skip non-timeline host=…` | 不出现（挑对 host 时） | 出现 ⇒ 挑到非动态 View 了，换一条 `host=u3` 的 |
| `mtHit` | `0` | `>0` ⇒ 见 2.5 |

6. **UI 断言（最硬，必做）**：`uiautomator dump`，在被 fake 那条动态的时间节点上搜 `[删除已拦截]`；定位必须按 **bounds / 父子节点 / 相邻作者文本**（用第 2 步记的 `txt=`），**不能全局搜一次**。
7. `... --es sub unmark`，再 dump 一次 ⇒ 该节点的 MARK 应消失。

### 第 4 层 —— 打错标签（前三层都过了再做）

| 序 | 动作 | 期望 | 反例 ⇒ 处置 |
|---|---|---|---|
| 4.1 | `SNS_DELTAG dump` 逐条看 `u=` | 每条 View 的 `u=` 互不相同 | ⛔ **两条不同 View 的 `u=` 相同** ⇒ §7.3(ii)（正文提到邻居名字导致唯一命中错位）已经在发生 ⇒ **立刻停手** |
| 4.2 | 用力滚动 30 s 后 `uiautomator dump`，列出所有含 `[删除已拦截]` 的节点 | 每个都落在**台账里那条已删动态**上 | ⛔ 出现在别的动态上 ⇒ 打错标签；⛔ 出现在视频时长徽章/正文/卡片日期上 ⇒ ④ 级兜底在起作用，看 `tv by level-4 fallback` |
| 4.3 | 滚出去再滚回来 | 标记不闪烁、不残留到普通动态 | 闪烁 ⇒ 两条 2 s sweep 又在互相贴/剥（看 `forceSet` 双写是否一致） |
| 4.4 | `snstag mark` → 全屏打标 → `snstag unmark` | 全屏出现又全部消失 | 有残留 ⇒ `strip`(1642) 或 `restore`(1146) 没跑到那些格子 |

### 一句话顺序

**滚两屏 → 先 grep `SNS_DELTAG path` 看有没有重复打印（P0 判据）→ 再看 `painted` 与 `mtHit` → 然后按第 3 层 7 步跑 `snstag fake` + UI 断言 → 最后做第 4 层的打错标签核对。**

---

## 结论

### 判定：**GO-WITH-CONDITIONS**

> 严格按本文件自己的 P0 口径：**若装包前不允许动那 1 行，本轮应为 NO-GO，卡在 §2.5。**

### 卡住的那一条：§2.5（`atOffset` 收到整条 path ⇒ 永久失效循环）

- `SnsDeletedTag.kt:884` 的 `atOffset(step)` 在 `srcStillValid:717` 被喂了**整条路径串**（`"@-1;f:abc"`）而不是单个 step，`substring(1)` 后 `toIntOrNull()` 失败 ⇒ 恒返回 **0**；
- 于是两步路径 `@<off≠0>;…` 每次命中缓存都判失配 ⇒ **remove → 全量 `probePath` → 又存同一条**；
- `takeProbe:912` 每个 key 只有 12 次预算且永不复位 ⇒ 第 13 次起 `dataOf` 恒返回 null；
- **终局 = 该界面所有动态都不打标（静默）+ 每次 bind 都白跑一次子树遍历（掉帧）**。

修法 1 行（二选一，都不改变判定强度）：
```kotlin
// A：只取首个 step
pick.first == atOffset(src.path.substringBefore(";"))
// B：顺手把候选顺序换成 E 在前，热路径直接归零，同时吃掉 §4
for (h in arrayOf("E", "z", "@", "D"))
```
修法 B 更划算：`@` 仍是唯一依赖 position 的路径且照样被 `reconcileAt` 守着，对账强度一点没降。

### 条件清单（按强度）

| # | 条件 | 不满足的后果 |
|---|---|---|
| **C1（硬）** | 装包前先落 §2.5 的那 1 行（或候选顺序改 `E,z,@,D`） | 一旦真机三个条件成立 ⇒ 全废 + 掉帧，且只有一条被节流的 `no snsId on holder` |
| **C2（硬）** | 装包后**第一件事**做 §8-2.1：`grep "SNS_DELTAG path"`，看到同一条 `-> @-1;f:xxx` 打印 ≥2 次 ⇒ 立即停手 | 继续滚只会把 12 次预算烧光 |
| C3（软） | 带上 §7.3 的正向长度上界（1 行） | 存在 §7.3(ii) 的打错标签路径（备注名 + 正文提邻居名） |
| C4（软） | 若观察到滚动掉帧，按 §4.4-1/2 处理 | 快速滑动可感知抖动 |

### 本次复核确认「修对了」的部分（不采信作者说明，独立验证通过）

| 项 | 位置 | 结论 |
|---|---|---|
| P0 #1 核心：不存在绕过 `reconcileAt` 的 `data[pos]` | `737` / `878`，grep 实测只剩这两处 | ✅ **真修好了** |
| 对不过账 ⇒ 不写 `bound`、不写 `dataPathCache` | `668-695` 三条出口 | ✅ |
| 命中数 ≠ 1 时**不取第一个**（R31h 同款） | `SnsDeletedTagLogic.kt:200` | ✅ 最该盯的这行是对的 |
| 作者名为空串判 false，未被任何「优化」绕过 | `156-163` / `716` / `774-775` | ✅ |
| 缓存失配真 remove + 重探，无「失配仍返回旧值」 | `674-675` → `678` | ✅ |
| `DataSrc` 只存 2 个 String，无 View 强引用 | `624` | ✅ 无泄漏 |
| 9 条钩子的回调全被包住、不 rethrow | `362` / `408` | ✅ **不会崩微信** |
| `snstag fake` **不会**被新闸门挡住 | `1032` 分支 + `1050→1056→paint` ② 级 | ✅ 本轮最该查的一条，答案是放行 |
| `ensureSweep` 无重复调度/无泄漏，且不击穿 `missCounted` 去重 | `1438` CAS / `1056` 只 adopt 一次 | ✅ |

### 遗留条目汇总

| 严重度 | 条目 |
|---|---|
| **P0** | §2.5 `atOffset` 收整条 path ⇒ 永久失效循环（= §3.4-B） |
| **P1** | §3.4-C 缓存建不起来（依赖 `E` 仍在）；§4 主线程每次 bind +0.4 ms；§7.3(ii) 正文含作者名 ⇒ 打错标签 |
| **P2** | §1.3 518 全静默吞 / 447 抛时丢 `missed`；§2.2 `walkPath` null 时不 remove；§2.6 短昵称·备注名恒不匹配；§5.5 空 hint 直落 ④ 级；§6.2 分桶不解决第二次 Activity；§6.3 计数被吞；§7.1 `_name` 抢先 / 尾括号；§7.4 `pos >= size` 未早退 |

### 作者说明里**不成立**的两条（不采信的收益）

1. `_r37_tag_paint.md:300` 与 §P2 #4 声称「按 key 分桶 ⇒ 第二次进 Activity 的结果仍然可见」—— **不成立**（key 是 host 类名，跨 Activity 恒定且永不重置，见 §6.2）。
2. §4/§2.5 共同点：作者把 `@` 放在候选第一位（730），代价是热路径必须每次跑 `visibleTexts`；**把 `E` 放第一位可以零成本拿到同样的对账强度**（`E` 是位置无关的，本来就不需要对账）。

---

[DONE]
