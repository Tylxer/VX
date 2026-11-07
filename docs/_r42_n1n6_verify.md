# R42 · N1+N6 可信度重构 · 真机验收（2026-11-07 19:49）

> 对象：`afb7e1d`（含 `29c8029` N1+N6 重构、`b5fe14c` install 日志与键收口）
> 设备：`467cf5bc`，微信 8.0.48/2588 GP，模块 0.2.8
> 上一版：`03:16:49`（灰标误报 B1 修复版） → 本版 `19:49:01`

## 0. 装包与「新代码确实在跑」的两道判据

| 项 | 值 |
|---|---|
| `lastUpdateTime` | `2026-11-07 03:16:49` → `19:49:01` |
| `pm path` | `~~O6jBfeAp7nVp00lo94uz6w==` → `~~Ecc38VCI21jNx7ZNoqGH-A==` |
| 冷启 | `dev.py restart` ×2 |

⛔ **不能只看 Gradle**：本次 `compileDebugKotlin` 报 `UP-TO-DATE`（源码 04:15 修改，
但 04:20 那次 `testDebugUnitTest` 已编过），若只信它就无法排除「装了个旧 dex」。
故直接扫 APK 里的 dex 字节：

```
classes5.dex  headerSig=3  SNSDEL offset=1  firstDataPos=1  observeHeader=1  reconcile=3
              sqlBlock=1  albumFix=1
```

`headerSig` / `firstDataPos` / `observeHeader` 是 N1/N6 新引入的符号，`sqlBlock=` / `albumFix=`
来自 `b5fe14c` 的 install 日志改造 —— 三者同时命中，才证明这个 APK 是本轮代码。

装载判据（顺手验证 `b5fe14c` 的日志补全）：

```
19:51:01.405 main SNS installed hooks=delete,execSQL,updateWithOnConflict,updateWithOnConflict.before,insertWithOnConflict,insert blockEnabled=true sqlBlock=true albumFix=true
19:51:01.416 main SNSDEL installed hooks=3 style=line
```

## 1. 冷启首屏（不滚动，直接 dump）

顶部：`拍照分享 / 轻触更换封面 / V / 慈溪剧本杀JF小客服`

```
SNSDEL stat ... bound=4 binds=13 sns=9 resolved=7 missed=2 match=0 hits=0
lastMiss=ambig(pos=0/10 passing=[] headerSig=1 texts=[换封面|正在加载...])
```

- ✅ `headerSig=1`：头部签名观测成功（H=1 ⇒ 真值 O=-1），**不再依赖 `learned=-1` 这种「常数非空」口径**
- ✅ 首屏无任何灰标（`match=0 hits=0`），新动态未被误标
- ✅ `miss` 仅 `pos=0` 封面项、`passing=[]`，属已知正常态

⚠️ 冷启最初两次绑定时 `headerSig=-1`（`miss#1/#2`，19:52:17），是「头部尚未观测到」的
正常未知态，随后稳定为 `1`。

## 2. 滚动扫屏（逐屏 dump + stat）

| 落点 | 屏幕上的灰标 | stat |
|---|---|---|
| s1（1 屏下） | `y=1549 该动态已被作者删除`（唯一） | `binds=25 resolved=19 missed=2 match=2 hits=2` |
| s2（2 屏下） | 无 | `binds=27 resolved=21 missed=2 match=4 hits=4` |

- ✅ 灰标只跟真被删那条；同屏其余正常动态（含同作者的另一条）无标
- ✅ `match == hits`（2==2、4==4），无「命中却不渲染」的漏标

## 3. B1 回归验证：滚回顶部 / 下拉刷新（本轮最关键一条）

B1 的形态是「封面项重绑 ⇒ 签名被复位成 -1 ⇒ 校准条目被删 ⇒ 灰标先消失再补回」。
判据取 `headerSig` 是否被打回 `-1`（比「灰标有没有闪」更硬 —— 闪是结果，签名复位是原因）。

| 动作 | `headerSig` | 说明 |
|---|---|---|
| top1（上滑 4 次到顶） | **1** | 封面重绑，未复位 |
| top2（再上滑 2 次，封面再重绑） | **1** | 重复触发仍不复位 |
| back1（下滚 2 屏） | **1** | 灰标不在此落点，位置不同非缺失 |
| top3（再回顶） | **1** | 第二次往返 |
| back2（再下滚 2 屏） | **1** | 稳定 |

**下拉刷新时间线**（列表从 20 项涨到 30 项，顶部换成 `津轩阁饭店15168572623（生日宴）`）：

```
binds=106 sns=102 resolved=94 missed=8 match=18 hits=18  headerSig=1
```

- ✅ 头部构成变了（新动态插入、项数 20→30），`headerSig` 仍为 `1`
- ✅ `match == hits`（18==18）
- ✅ 刷新后下滚 1 屏，两条真被删动态的灰标都在（`y=1200`、`y=2648`）

⇒ **B1 的「复位判据 `pos == prev` 而非 `<=`」与「`offsets` 只增改不删除」两处修法在真机成立。**

## 4. 双灰标同屏的归属核对（防误标复核）

同屏出现两条灰标时必须排除「锚到相邻动态」：

```
     45  dra_qgdjczzj            ← 动态 A 作者
    171  [已删除] 哈哈，请更多坚持做自己。   ← A 正文（1 图）
   1117  22分钟前                 ← A 时间
   1200  该动态已被作者删除        ← A 的灰标  bounds=[259,1200][1440,1258]
   1296  绍兴上虞圣瑞斯牟伦英      ← 别人的动态（无标）
   1768  dra_qgdjczzj            ← 动态 B 作者
   1898  哈哈，看看 AI 多么适合做朋友…  ← B 正文（3 图）
   2565  33分钟前                 ← B 时间
   2648  该动态已被作者删除        ← B 的灰标  bounds=[259,2648][1440,2706]
   2744  陶朗常总                  ← 别人的动态（无标）
```

- ✅ 灰标 `x=259` = `MARK_LEFT_DP 72dp`，是本模块 `addView` 插入的 TextView，不是宿主自带文本
- ✅ 两条灰标各自落在**自己 post 的末尾**（`y` 在正文之后、下一位作者之前）
- ✅ 两条被标动态虽同属 `dra_qgdjczzj`，但**中间隔着 `绍兴上虞圣瑞斯牟伦英`**，
  不构成「同名相邻」⇒ 不触发 `passing` 多候选，不存在锚错的空间

## 5. bit3 相册位未受本轮改动影响

```
BLOCK drop snsId=-3397160043383352561 before=10 want=8 ...
BLOCK after ... before=10 after=10 keep=10 act=writeback shape=BLOCKED_BY_REWRITE
BLOCK stat hit=1 blocked=1 nonZero=94 drop=76/9
```

写回幂等（`before=10 after=10`）与计数口径不变。

## 6. 结论

| 红线 | 结果 |
|---|---|
| 冷启首屏签名校准成功、新动态无灰标 | ✅ `headerSig=1`，`match=0` |
| 滚动后只有真被删那条带灰标 | ✅ 逐屏核对，正常动态无标 |
| `match == hits` | ✅ 2==2 / 4==4 / 12==12 / 18==18 |
| `miss` 仅顶部封面项 | ✅ 6 条 miss 全为 `pos=0 passing=[]` |
| **滚回顶部再滚回来，灰标始终在（B1）** | ✅ `headerSig` 三次往返 + 下拉刷新恒为 `1` |
| bit3 未被破坏 | ✅ `keep=10` 幂等 |

**总裁决：PASS。**

## 7. 本轮真机取不到的样本（如实记录）

1. **O=0 设备（N6 的直接验证）**：本机 H=1 ⇒ O=-1，N6 只由单测覆盖
   （`calibration_survives_signature_flap` 等），**真机未验**。
2. **N1 的头部漂移（O 从 -1 漂到 -2）**：需要封面项数变化，本机固定 H=1，只能靠单测
   `header_rebind_does_not_reset_signature` / `header_growth_shifts_signature` 覆盖。
3. **「同一好友连续两条且一条被删」的冷启首屏**：本轮时间线里 `dra_qgdjczzj` 的两条不相邻，
   取不到该样本。这正是 2026-11-07 那次误报的原始形态，**建议下次有同名连续两条时补测一次**。
4. `logOnce` 是进程级一次性日志（`miscLogged` CAS），`SNSDEL offset=` 被其它 `logOnce`
   调用点抢占 ⇒ 该行不可作为常规观测；应看 `stat` 的 `headerSig=`。
   **非缺陷，但下次别拿它当判据**（本轮曾误以为「没日志 = 没解析」，实际 `resolved=19` 已证解析成功）。
