# R40 · 第 0 期真机验证手册（纯取证）

> 配套 `docs/_r38_plan.md` §5 第 0 期、`docs/_r40_p0_impl.md`（探针实现）。
> 本文只写**验证怎么跑、怎么判**，不重复架构推理。
> 设备 `467cf5bc`｜微信 8.0.48(2588) GP｜模块 `com.lx.wxgroup`

---

## §0 通用前置（每一大轮都做，别省）

```bash
export MSYS_NO_PATHCONV=1
ADB="C:/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe"
LOG="/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt"
```

**① 判包是否真装上**（⛔ 不看 install 输出，只看这个）：

```bash
"$ADB" shell pm path com.lx.wxgroup
"$ADB" shell dumpsys package com.lx.wxgroup | grep -E "lastUpdateTime|versionName"
```

**② 冷启两次**（第一次可能拿到旧进程的 ImageReader，模块不重载）：

```bash
"$PY" tools/dev.py restart; sleep 10
"$PY" tools/dev.py restart; sleep 10
```

**③ 确认模块活了 + 探针就位**：

```bash
"$ADB" shell "grep -E 'ENTRY|WIN installed|SNS-INJECT hook installed' $LOG" | tail -5
```

**④ 广播前确认前台是微信**：

```bash
"$ADB" shell dumpsys window | grep mCurrentFocus
```

**⑤ 每轮开始前清日志**（LogX 是 5000 行环形，不清必被冲掉）：

```bash
"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm"
```

> ⛔ **SQL 传参坑**（R39 踩过）：`dev.py probe sql --q "select ..."` 在 Windows 下**空格被 adb 参数层吃掉**，SQL 变碎片还静默失败。
> 正确写法：**整条命令作为一个参数**，SQL 用单引号包住 ——
> `"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op sql --es q 'select ...' -p com.tencent.mm"`

---

## §1 轮次 1：账本基线（**不需要用户操作手机**）

目的：确认 `SnsRoundLedger` 在工作、独立轮次判定不被心跳灌水。

```bash
# 清账本
"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op snsg --es sub winreset -p com.tencent.mm"
sleep 2
"$ADB" shell "grep 'WIN reset' $LOG"
```

请用户：**进朋友圈 → 下拉刷新一次 → 退出 → 再进一次**（制造 2~3 个独立轮次）。

```bash
"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op snsg --es sub win --es n 12 -p com.tencent.mm"
sleep 3
"$ADB" shell "grep '^WIN\| r=' $LOG" | tail -20
```

**判读**：

| 看到什么 | 含义 | 动作 |
|---|---|---|
| 有 `WIN new round=` 且 `n` 在 10~30 之间 | ✅ 独立轮次正常 | 继续 |
| 只有 1 轮、`dup` 很大（几十） | 心跳重复被正确跳过 | ✅ 也正常，说明去重闸生效 |
| **一轮都没有** | 探针没接上，或 `c3.N` 没触发 | ⛔ 先查 `SNS-INJECT hook installed`，再重进朋友圈 |
| `n` 恒为 1~2 | 抓到的是增量小响应，不是首屏 | 记下来，说明「顶层窗口」要按 n 最大的一轮取 |

---

## §2 轮次 2：P0-1 集合比对（验假设 A）

**看这一行**：

```
WIN gate round=<R> respN=<M> upsertN=<K> subset=<true|false> miss=[…]
```

| 结果 | 含义 | 下一步 |
|---|---|---|
| `subset=true` 连续 ≥3 轮 | ✅ **假设 A 成立** ⇒ 存档成员资格可用 `respIds` | 进第 1 期 |
| `subset=false` 且 `miss` 里有几个 id | 本节最重要的信息：**响应里有、但没被 `updateWithOnConflict` 写入的 id** | 抄下 `miss` 的 id，`op=sql` 点查它们在库里存不存在（见下） |
| `upsertN=0`（一行都没有） | `onSnsUpdateAfter` 的收集没接上 | ⛔ 探针 bug，回 `_r40_p0_impl.md` 查子步 3 |
| 从来没打过 `WIN gate` 行 | upsert 窗口没关闭（下一轮没来 / 3000ms 定时没跑） | 再触发一轮同步看 |

**`miss` 的归因（这一步决定假设 A 证伪后走哪条备选）**：

```bash
"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op sql --es q 'select snsId,sourceType,createTime from SnsInfo where snsId in (<miss 里的 id>)' -p com.tencent.mm"
sleep 3
"$ADB" shell "grep SQLX $LOG" | tail -5
```

- **行存在** ⇒ 响应里的 id 库里有，只是这条没走 `update`（微信用 insert 或压根没写）⇒ `respIds` 仍可代表「服务端返回了什么」，`subset=false` 只是**写库口径差异**，不是 A 证伪。
- **行不存在** ⇒ 响应返回了库里没有的 id（新动态首次落地）⇒ 同上，需在下一轮再看它是否 `subset=true`。

> ⚠️ `subset` 判定要注意：微信可能**只 upsert 变化的行**，未变化的行不写。所以单轮 `subset=false` **不构成 A 证伪**，
> 必须**同一条 id 在它首次出现的那一轮**做集合比对才有效。这是本手册最重要的判读纪律。

**已知采样盲区（判读前必读）**：`upsertIds` 靠 `SnsGuard.snsIdOf(a)`（`:298-307`）从 `whereArgs` 取 id，
它要求 **`a[i]` 是长度恰为 1 的数组**。因此：

| 场景 | 能否采到 | 现象 |
|---|---|---|
| `updateWithOnConflict("SnsInfo", cv, "snsId=?", arrayOf(id), 0)` | ✅ 能 | 每轮 4 条的常态路径（R27 取证） |
| 批量 `whereArgs=[id1,id2,…]` 或 `WHERE snsId IN (?,?)` | ❌ 不能 | `upsertN` 偏小、`miss` 偏多 |
| `values` 里才带 `snsId`、`whereArgs` 为空 | ❌ 不能 | 同上 |
| **`ContentValues` 里没有 `sourceType`**（例如只改点赞数） | ❌ 不能 | 口径选择，见下 |
| ⛔ **新动态首次落库（走 INSERT）** | ❌ **不能** | **`miss` 里出现的正是本轮新发的动态** |

> ⛔⛔ **最重要的判读纪律（2026-11-03 22:36 真机实证）**：
> **`subset=false` 不能作为「假设 A 被证伪」的证据。**
> 实测：verity 发新动态那一轮 `subset=false`、`miss=-3399959319660981746`，
> 但该 id **在 `SnsInfo` 里查得到（`src=2`）** ⇒ 它确实落库了，只是走 **INSERT 而非 UPDATE**。
> `onUpsert` 只挂 `updateWithOnConflict`，采不到新增行 ⇒ **本轮有新内容时 `subset` 必然 false**。
>
> **临时判读规则**（在补 insert 采样之前）：
> 1. 先看这一轮 `ctTop` 是否比上一轮**变大**——变大说明本轮带入新动态 ⇒ `subset=false` 属预期，**不是证据**。
> 2. `subset=false` 且 `ctTop` 未变 ⇒ 才值得深究（先按上面的采样盲区表逐条排除）。
> 3. 永久修法：`SnsGuard` 补 hook `SQLiteDatabase.insertWithOnConflict`，判据改 `respIds ⊆ (upsertIds ∪ insertIds)`，日志加 `insertN=`。

**口径声明（实现已定型，判读时按这个）**：`onUpsert` 采样点位于 `st == null` 判断**之后**、`st != 0` 分支**之前**（`SnsGuard.kt:325-327`）。
⇒ `upsertIds` = 「**带 `sourceType` 的** `SnsInfo` update」。只改其它列的 update 不计入。
这个口径是**偏保守**的（`upsertIds` 偏小 ⇒ `subset` 更容易 `false` ⇒ 更容易判 A 不成立），方向安全：宁可错判「A 不成立」也不误判「A 成立」。

⇒ **判读顺序**：先看 `upsertN` 是否 ≥ `respN` 的一半。若 `upsertN` 明显偏小，**先怀疑采样盲区**，
用 `op=sql` 点查 `miss` 里的 id 在库中是否存在（存在 = 确实被写了但没采到 ⇒ 盲区，不是 A 证伪）。
只有「`miss` 里的 id **在库里也查不到**」才说明响应里那些 id 根本没落库，那才是 A 的真反例。

---

## §3 轮次 3：判删链路（验 C1 / 双轮缺席 / 正常动态零误判）

**这是唯一需要用户配合的一轮，且是唯一合法样本**：
**「她发 → 我方刷新并确认肉眼看见 → 她再删」**（⛔ 不用自删、不用发完立刻删）。

### 3.1 删前：确认目标进了 respIds

1. 让 verity 发一条**纯文字**动态
2. 用户下拉刷新，肉眼确认看见
3. 立刻拿 id：

```bash
"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op sql --es q 'select snsId,userName,createTime,sourceType from SnsInfo order by createTime desc limit 5' -p com.tencent.mm"
sleep 3
"$ADB" shell "grep SQLX $LOG" | tail -6
```

4. 确认它在账本里：

```bash
"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op snsg --es sub win --es n 5 -p com.tencent.mm"
sleep 3
"$ADB" shell "grep 'WIN r=' $LOG" | tail -6
```

看 `ids=` 里有没有这个 snsId。

| 结果 | 含义 |
|---|---|
| 有 | ✅ `respIds` 覆盖到了它，可继续 |
| 没有 | ⛔ 说明这一轮响应没带上它（可能还是缓存）⇒ 让用户再刷新一次再看 |

### 3.2 删后：等清位（**不要催**）

R39 实测：删除后本地 `sourceType` 会**保持 `2` 约 3~7 分钟**，之后才被清成 `0`。

- 让 verity 删
- **每隔 1 分钟**查一次库，记下 `sourceType` 变化时刻：

```bash
"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op sql --es q 'select snsId,sourceType from SnsInfo where snsId = <目标 id>' -p com.tencent.mm"
sleep 3
"$ADB" shell "grep SQLX $LOG" | tail -2
```

**这一段本身就是 U1（清位触发点）的取证**：每次查库时**记下用户这一分钟做了什么**（没动 / 刷新 / 重进 / 切后台）。
U1 定了，模块才知道在哪一刻截获最稳。

### 3.3 判定

| 观察 | 判据 | 结论 |
|---|---|---|
| `src 2→0` 之后，`WIN` 账本里该 id 从某轮起连续 ≥2 轮不在 `ids` | 判删主判据成立 | ✅ 第 2 期可开工 |
| 该 id 首次缺席那一轮，`ctTop` 与上一轮**不同** | C1 不满足 ⇒ 该轮「放弃」 | ✅ 正确的放弃行为（不是 bug） |
| **正常动态**（未被删的那些）在任何一轮被判缺席 | ⛔ 判据不成立 | **停在第 0 期**，退回 §6 重设计 |
| `src` 一直不变、也不消失 | 清位没来（R39 已见最长 7min） | 继续等，或让用户主动刷新一次触发同步 |

---

## §4 gate 汇总（照这张表收尾）

| # | gate | 数据来源 | 达标线 |
|---|---|---|---|
| ① | C1 可满足率 | `WIN c1 ctTopUnchanged=x/y` | **≥1 次**（5 轮内） |
| ② | 被删 id 双轮缺席 | 3.3 的账本对比 | 连续 ≥2 轮 |
| ③ | 正常动态零误判 | 同上，反向看 | **必须 = 0** |
| ④ | 假设 A | `WIN gate … subset=` | ≥3 轮 `true`（且按 §2 归因纪律判读） |
| ⑤ | 翻页覆盖率（非 gate） | `WIN summary` 覆盖率行 | 只记录，用于写 §3.2.1 有效范围 |

**分流**：①②③ 任一不过 ⇒ 停第 0 期；④ 不过 ⇒ 走假设 A 备选（存档改挂 upsert 批次）继续第 1 期。

---

## §5 正反对照（收尾必做，成对）

> ⛔ **2026-11-03 首次真机教训**：R39 做完 `kvset antirevoke.sns false` 后**没恢复**，
> 导致第 0 期第一轮 `rounds=0`（`enabled()` 短路，连探针都没跑）。
> ⇒ 本节两条命令**必须成对执行**，且收尾再回读一次确认是 `true`。

```bash
# 关：模块补位完全静默（R38 §2.6，四处 sourceType 写入总闸）
"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op kvset --es key antirevoke.sns --es val false -p com.tencent.mm"
# 回读确认
"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op kvget --es key antirevoke.sns -p com.tencent.mm"
# 开
"$ADB" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op kvset --es key antirevoke.sns --es val true -p com.tencent.mm"
```

⛔ **关掉后必须先验证模块确实静默**（日志里不再出现 `SNS srcguard re-fix after clear`），再下「判据不吃 sourceType」的结论。
⛔ `kvget` 只证明写进，**可见行为变化才证生效**。

> 注意：第 0 期判据（`respIds` 差集）**结构上不读 `sourceType`**，所以正反对照的预期是**两组结论一致**。
> 若不一致 ⇒ 说明有隐藏的 `sourceType` 依赖，必须查清才能进第 1 期。

---

## §6 收尾

```bash
# 恢复开关
# 把本节所有观察写进 docs/_r40_p0_gate.md（一行一判据，含时间戳）
# 台账登记 R40-2（验证结果）
```

**遗留提醒**：`setsrc` 实验会污染补位台账（`upsertVisibleSrc` + `markSrcFixed`），做完必须
`--es op snsg --es sub srcfix --es sub2 clean`。本手册**不使用** `setsrc`（全部走真实链路）。
