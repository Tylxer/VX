# R35-4 新群自动免打扰 —— 会话列表锚点失效取证与修复

> lane: general-purpose-2（独立取证 + 实现）｜目标：微信 8.0.48 / 2588 GP（包 `com.tencent.mm`）
> 改动文件：**只有** `wxgroup/app/src/main/java/com/lx/wxgroup/feature/AutoDndGroup.kt`
> 取证脚本：`_r35_adnd_dex.py`（纯 dex 二进制解析；全程未碰 adb、未编译、未装包）

---

## ① 现象与现有锚点失效的取证

### 1.1 现场（主会话提供，2026-10-29 21:13 冷启）

```
QUIET ADND hooked ListView.setAdapter (baseline 快照)
QUIET ADND hooked BaseAdapter.notifyDataSetChanged (新群扫描)
QUIET ADND installed restored=5 seen=734 coldStartDone=true enabled=true
QUIET ADND scan: convAdapter=null（锚点类名变了？）新群扫描失效     ← 21:13:35.876
```

两个 hook 都**安装成功**（前两行），告警来自 `BaseAdapter.notifyDataSetChanged` 回调内的
`convAdapter == null` 分支（改动前 `AutoDndGroup.kt:254`，现: `:301` 附近）。

### 1.2 静态证据（`_cache/dex/` 15 个 dex，纯 Python 二进制解析）

> dex 来源说明：`_cache/base.apk`（379MB，2026-10-13 pull）解压的 15 个 dex；
> `docs/_r16_f4_defect_finding.md:118` 记「当前线上微信（8.0.48 / 2588 GP）」取的就是同一批。
> ⚠️ **这是假设不是实证**：dex 落盘于 10-13，设备当前包未必逐字节一致。
> 凡 Dex 结论都必须由真机日志复核（判据见 ③）——本轮为此专门加了可判定日志。

| # | 结论 | 命令 / 出处 |
|---|---|---|
| E1 | `ConversationListView` **类还在**，直接 `extends android.widget.ListView` | `python _r35_adnd_dex.py cls ConversationListView` |
| E2 | **没有子类**（上面那条同时列出全部匹配，只有本体 + `$1` 内部类） | 同上 |
| E3 | **未覆写** `setAdapter` / `getAdapter` | `python _r35_adnd_dex.py meth .../ConversationListView`(grồi) grep adapter；只恰 `setOnItemClick/LongClickListener` |
| E4 | 会话主页 Fragment `MainUI` 同时持有：`MainUI->o : ConversationListView`、`MainUI->v : com.tencent.mm.ui.conversation.p3` ⇒ 运行时确实就是这一对 | 脚本 `fields` 模式；字段 parse 见 `_r35_adnd_dex.py:fields_of` |
| E5 | adapter 继承链：`p3` → `com.tencent.mm.ui.i3` → **`android.widget.BaseAdapter`** | `python _r35_adnd_dex.py chain "com/tencent/mm/ui/i3;"` |
| E6 | `p3` 无子类；`i3` 另一子类是 `com.tencent.mm.ui.contact.e`（通讯录，别误伤） | `python _r35_adnd_dex.py sup "Lcom/tencent/mm/ui/i3;"` |
| E7 | 🔴 **`p3` 自己覆写了 `notifyDataSetChanged()`** | `python _r35_adnd_dex.py meth .../p3` → `V p3;->notifyDataSetChanged()V` |
| E8 | 🔴 但该覆写体里第一条就是 `[super] -> Landroid/widget/BaseAdapter;->notifyDataSetChanged()V` | `python _r35_adnd_dex.py calln notifyDataSetChanged` |
| E9 | 「谁触发会话刷新」的 `invoke-*` 调用链（调用点 ≠ 类存在） | 8 处调用 `p3->notifyDataSetChanged`：`g3.onNotifyChange(String,k74/u0)`、`z9.handleMessage(Message)`、`ja.U0(3×String)`、`MainUI.F()`、`e6.onChanged(Object)`、`aa.run()`、`c3.onDismiss()`、`p3.N(View,int,String)` |
| E10 | `ListView.setAdapter` 调用点里，会话包只有 `MainUI.x(Bundle)` | `python _r35_adnd_dex.py calln "->setAdapter"` |

### 1.3 根因（E7 + E8 + 代码读数）

会话刷新的真实调用链：

```
“会话列表刷新”
  └─ invoke p3.notifyDataSetChanged()                    ← E7：p3 有覆写
       └─ [super] BaseAdapter.notifyDataSetChanged()     ← E8：仍走到被 hook 的那个实现
            └─ afterHookedMethod(param)  ← param.thisObject == 活的 p3 实例
```

**⇒ hook `BaseAdapter.notifyDataSetChanged` 对会话刷新依然命中，而且回调里 `param.thisObject` 就是活的会话 adapter。**

但改动前的回调**丢弃了 thisObject**，转去用冷启时 `ListView.setAdapter` 抓到的那个
`WeakReference<ListAdapter>`（`:125` / `:210`）：

```kotlin
val ad = convAdapter                 // 冷启那一次抓的引用
if (ad == null) { LogX.w("convAdapter=null…"); return }   // ← 21:13:35.876 打的就是这条
```

正确性完全押在「冷启那一刻恰好抓到会话 adapter」上：

- 抓不到 ⇒ 变量永远 null；
- 抓到了但后来 ListView 被换（会话 Fragment 重建）、旧对象只剩弱引用 ⇒ 一轮 GC 就变 null；
- 而且 `lastScanMs` 在判 null **之前**就更新 + `convAdapterWarned` 只打一次
  ⇒ **第一次失败之后彻底静默**，日志再也看不出任何异常。这正是本次事故的形状。

**结论：不是「锚点类名变了」**（E1/E2/E4 证明类还在、没子类、MainUI 还在用）。
真因是「回调里不用 thisObject，却依赖一次性抓取的弱引用」这个**结构性缺陷**。

### 1.4 仍未闭环（诚实标注）

| 待取证 | 说明 |
|---|---|
| `MainUI.x(Bundle)` 里 `setAdapter` 的 receiver 是不是 `MainUI.o` | invoke 是 virtual，receiver 静态类型读不出来 ⇒ **无静态证据**。由本次新增日志判定（③ A2） |
| `BaseAdapter.notifyDataSetChanged` 这次是否真为 p3 命中过 | 该 hook 全局生效（上千 adapter 都走它），无法从旧日志反推。由新增 `scan ok adapter=` 判定（③ A1） |
| dex 版本是否 == 设备当前包 | 见 1.2 假设；A2 顺带复核 |

---

## ①b 主会话补充的真机证据（2026-10-29 21:22）+ 对它的分辨

**现象**：连续两次 `force-stop` + `am start` 冷启后，日志里 `QUIET ADND` **一条新的都没有**
（仍停在 21:13:35 那批旧行），而同一时刻 K7 正常打印：

```
21:22:37.874 main K7 installed inner=com.tencent.mm.ui.conversation.p3 rows=2986
```

### b.1 这条 K7 行本身是**重大正面证据**（先记功）

`ConvFilter.kt:766`：`LogX.i("K7 installed inner=${inner.javaClass.name} rows=${fa.size}")`
——它在 `install(list)` 里，**每次** ListView.setAdapter 都会打，不是每进程一次。
⇒ 它在**运行时**（不是 dex 猜测）证明了三件事：

1. `ConversationListView` 仍然是会话列表 ListView（否则 ConvFilter 的 hook 命中不了）；
2. **会话 adapter 运行时类名就是 `com.tencent.mm.ui.conversation.p3`** —— 与本文 E4/E5/E7 完全对齐；
3. `ListView.setAdapter` 在冷启时会正常命中 ConversationListView ⇒ 锚点 D 的回调是通的。

### b.2 install 调用链（回答「是不是 install 没被调用」）

`HookEntry.kt:335`（`bootstrap()` 里）：

```kotlin
runCatching { AutoDndGroup.install(lpparam) }
    .onFailure { LogX.e("AutoDndGroup install failed", it) }
```

- **无条件调用**，前后也无开关/版本前置把关（唯一的前置是 `VersionGate`，但它在 bootstrap 开头，
  既然 ConvFilter（`:186`，更早）装上了，gate 一定是过的）。
- 所以：install 要么被调用、要么整条 bootstrap 在 `:186`~`:335` 之间被打断（每步都包了 `runCatching`，理论上打断不了）。

### b.3 ⚠️ grep 口径有个致命缺口 —— 很可能就是这个

| 失败点 | 打的日志 | 能被 `grep 'QUIET ADND'` 看到吗 |
|---|---|---|
| `install()` **内部**抛异常 | `QUIET ADND install failed: <Ex>`（AutoDndGroup.kt:188） | ✅ 能 |
| `install()` **之前**抛异常（典型：`object AutoDndGroup` 的 `<clinit>` 失败，例如静态字段初始化炸了） | `AutoDndGroup install failed`（HookEntry.kt:336，`LogX.e`） | ❌ **看不到** —— 这行不含 "QUIET ADND" |
| 两个 hook 安装失败 | `QUIET ADND hook setAdapter failed:` / `hook notifyDataSetChanged failed:` | ✅ 能 |

⇒ **只 grep `QUIET ADND` 会把最坏的一类失败判成「没打日志」**。必须补 grep `AutoDndGroup install failed`。

### b.4 还有一个更可能、也更省事的解释：进程根本没换（force-stop 没生效）

`QUIET ADND installed / hooked …` 是**每进程只打一次**的行；而 `K7 installed inner=…` 是
**每次 setAdapter 都打**的行。若 21:22 看到的是后者而没有前者，最自然的解释是：

**这个进程就是 21:13 那个进程**（微信/系统把它拉回来了，`force-stop` 没真杀干净）。

在这个读法下，21:13 那三条 install/hooked 行是**更早那次加载**留下的，
连 21:13:35 那条 `convAdapter=null` 也未必来自"这次冷启"，而 `convAdapterWarned` 的一次性
开关已经翻过 ⇒ 之后永久静默。与观测完全自洽。

**分辨方法（不需要改代码就能做）**：看 21:22 附近有没有这些**每进程一次**的行：

| grep | 出处 | 含义 |
|---|---|---|
| `package loaded, process=` | HookEntry.kt:78 | 有 ⇒ 进程真重启了 |
| `host version=` / `gate ok:` | HookEntry.kt:114/116 | 同上（顺序在它的后面一步） |
| `K7: hooked ListView.setAdapter` | ConvFilter.kt:333 | **最关键的对照**：它是每进程一次，且与 ADND 的 setAdapter hook 挂同一个方法 |

判定树：
- 21:22 附近有 `K7: hooked ListView.setAdapter` ⇒ **进程真重启了** ⇒ AutoDndGroup.install 必然也被调用过
  ⇒ 立刻去 grep `AutoDndGroup install failed`（见 b.3 缺口）。
- 21:22 附近**没有**它、只有 `K7 installed inner=…` ⇒ **进程没重启** ⇒"`wp force-stop` 没生效"，
  21:13 那批是旧进程的残留，本轮"冷启"没产生任何有效数据，需要按 ③ 的判据重跑。

### b.5 代码侧已经补上的两个硬判据

1. `QUIET ADND installed pid=${android.os.Process.myPid()} ...`（AutoDndGroup.kt:185 附近）
   —— 冷启后 pid 必变，**一眼看出进程有没有真的换过**。
2. `QUIET ADND anchor setAdapter hit list=<identityHashCode> adapterCls=<类名或 null>`（:212 附近）
   —— 打在 `list.adapter` 取值**之前**，把「回调没来」和「回调来了但 adapter 为 null」分开。
   原来的写法（取不到就静默 return）正是把这两个失败合成同一副面孔的元凶。

---

## ② 候选新锚点（按证据强度排序）

### 锚点 A（推荐，已实现）：`BaseAdapter.notifyDataSetChanged` 的 `param.thisObject`

- **怎么用**：回调里直接 `param.thisObject as? ListAdapter`，识别后原地枚举。
- **证据**：E5+E7+E8。会话刷新必然走到该回调，且 thisObject 就是活的会话 adapter。
- **为什么比原来强**：不再经过 View / Activity / 弱引用 ⇒ 不存在「抓漏」和「GC 后变 null」，
  会话 Fragment 重建也不会丢（新 adapter 自己会 notify）。
- **失效风险**（都有兜底）：
  1. 微信改掉 `p3.notifyDataSetChanged` 里的 super 调用 ⇒ 不再为 p3 命中 →
     兜底：识别失败会 **warn（带真类名 + count）**，下一轮把新类名补进名单即可，不会静默。
  2. `p3` 不再是 BaseAdapter 子类 ⇒ 同上。
  3. **类名变了不影响**：主路径不靠类名（`p3` 只当加速 hint）。

### 锚点 B（备选，未采用）：直接 hook 具体类 `com.tencent.mm.ui.conversation.p3`

- 效果等同 A（`thisObject` 一样）。**不用**因为它俩都撞同一个坑：`p3` 是混淆名，微改版就换。
- 保留价值：若 A 的 warn 显示「p3 不再调 super」，B 是最快补丁
  （`findAndHookMethod("com.tencent.mm.ui.conversation.p3", cl, "notifyDataSetChanged", cb)`）。

### 锚点 C（备选，未采用）：hook 业务事件 `g3.onNotifyChange(String, k74.u0)` / `MainUI.F()`

- 这是「会话数据变了」的**语义层**事件（E9），比「列表重绘」更贴「入群」。
- **不用**：拿到的是事件对象不是 adapter，还得回头找 list；且同样是混淆名。
- 保留价值：以后做「入群即时性」优化时的第一候选。

### 锚点 D（现有，`ListView.setAdapter` + 类名相等判定）—— **保留但降级**

- 仍然有用：唯一能在**没有任何刷新事件**时就拿到 adapter 的路。
- 保留它做启动快照；但不再是扫描的数据源。加了自己的可判定日志（A2）。

---

## ③ 判据（真机日志怎么一眼看出"新群扫描"恢复）

| # | 判据 | grep | 期望 | 失败含义 |
|---|---|---|---|---|
| **A1** | 锚点 A 命中（会话 adapter 现身） | `QUIET ADND scan ok` | 至少 1 行 `adapter=<simp> convs=<n> new=<k>` | 会话刷新从没走到 BaseAdapter 回调 ⇒ 转锚点 B |
| **A2** | 锚点 D 是否还活着（对照） | `QUIET ADND anchor setAdapter` | 有行 = 活；无 = 死 | 死了不影响功能（A 已兜底），但要更新本案结论 |
| **A3** | 识别失败能看见 | `QUIET ADND scan: 未识别 adapter` | 出问题时带**真类名 + count** | 把那个类名补进 `CONV_ADAPTER_CLASS_HINTS`（或确认无关） |
| **A4** | 端到端（要真进群） | `QUIET ADND` | `[scan] new groups=1` → `muted <chatroom>` → `realmute ok=true sent=true` | 全链路打通 |
| **A0** | 前置①：进程真换了 | `QUIET ADND installed pid=` | pid 与上一轮**不同** | pid 相同 ⇒ 模块没重载（`force-stop` 没生效），后面都不用看 |
| **A0b** | 前置②：install 没被静默吃掉 | `AutoDndGroup install failed`（⛔ 注意这行**不含** "QUIET ADND"） | 期望 0 行 | 有行 ⇒ ①b.3 那类失败（clinit / 进入 install 前就炸） |
| **A5** | 对照：进程是否真的重启 | `K7: hooked ListView.setAdapter`（ConvFilter.kt:333，每进程一次） | 冷启后必然出现 | 没出现 ⇒ 进程没换，见 ①b.4 |

⚠️ A1 的「至少 1 行」前提是**冷启后进过会话页**（要有一次刷新才有 notify）。
最快触发：进微信 → 停在会话列表 → 上下滑一下 / 让某人发条消息。

---

## ④ 代码改动与行号

均在 `wxgroup/app/src/main/java/com/lx/wxgroup/feature/AutoDndGroup.kt`（改动后行号）：

| 行号 | 改动 |
|---|---|
| 83-91 | 新增 `CONV_ADAPTER_CLASS_HINTS`（dex 实证的会话 adapter 类名，**只做加速 hint**）、`MIN_CONV_COUNT=20`、`CONV_PROBE_SAMPLES=12` |
| 143 / 147 / 150 | 新增状态：`anchorMatched`（setAdapter 是否命中过）、`learnedConvAdapterClass`（运行时学到的会话 adapter 类名）、`rejectedAdapterClasses`（未知 adapter 告警去重 + 结果缓存） |
| 185 | `QUIET ADND installed pid=<pid> …` —— pid 是「进程有没有真换」的硬判据（判据 A0） |
| 204-243 `hookConversationList` | **先打日志再取值**（:218 `anchor setAdapter hit`），把「回调没来」和「回调来了但 `list.adapter` 为 null」分开（:222 专门 warn）；取值成功后 :230 打 `anchor setAdapter ok cls=<类名> count=<n>` 并 `anchorMatched=true`；**快照逻辑一字未动**（仍是启动快照主路） |
| 305-368 `hookAdapterChanged` | 🔴 核心：不用 `convAdapter` 弱引用，改用 `param.thisObject as? ListAdapter`（锚点 A）；识别后才继续；`setAdapter` 没命中过时补做一次启动快照（走原 `snapshotBaseline`）；识别顺序挪到节流**之前**（无关 adapter 不再抢占节流窗口） |
| 352-359 | 新增必需日志 `QUIET ADND scan ok adapter=<simp> convs=<n> new=<k>`（判据 A1）；`convs=0` 时打 warn（判据 A3e） |
| 382-414 `isConversationAdapter` | 三步识别：① 学到的类名 ② hint 名单 ③ 内容判定（够长 + 采样读出 `@chatroom`）；排除本项目自己的 adapter（`com.lx.wxgroup*`）；结果进 `learnedConvAdapterClass` / `rejectedAdapterClasses` 缓存，同一个类只探一次（主线程开销恒定）；失败 warn 带真类名 + count（判据 A3） |
| 591-599 | `probe sub=anchor` / `sub=seen` 输出新锚点状态（`learned=` / `anchorMatched=`） |

⛔ **完全没碰**：`install()` 的 restored 恢复、`GroupDb` 台账、`baseline` 持久化、
`diffAndApply`（含保险丝 `MAX_FRESH_PER_ROUND=3`）、`persistSeen`、`applyOne`、`applyRealMute`。
`restored=5 / seen=734 / coldStartDone=true` 那条路的行为与改动前一致。

---

## ⑤ 需要主会话在真机执行的命令

> 本 lane 全程未碰 adb。以下均为「拷过去就能跑」，建议一次跑完全部判据（一次装包）。

### 0. 编译 + 装包（主会话做，按既有流程）

### 0b. 先确认「进程真的换了」（⛔ 这一步必须先做，否则后面全白跑）
```bash
python tools/dev.py q 'package loaded, process' 5     # 期望：有一行时间戳是本次冷启的
python tools/dev.py q 'K7: hooked ListView.setAdapter' 20  # 若 21:22 那轮缺这一行 ⇒ 进程没重启
python tools/dev.py q 'AutoDndGroup install failed' 10 # 期望 0 行（这行不含 QUIET ADND！）
python tools/dev.py q 'QUIET ADND installed pid=' 5   # 期望：pid 与上一轮不同
```

### 1. 清日志（不必重启微信）
```bash
python tools/dev.py probe logclear --clear
```

### 2. 制造一次会话列表刷新
回到会话列表页停留几秒（上下滑一下，或让群里有人发条消息）。

### 3. 看判据
```bash
python tools/dev.py q 'anchor setAdapter'    # A2：有/没有 "QUIET ADND anchor setAdapter cls=..."
python tools/dev.py q 'scan ok adapter'      # A1：期望 "QUIET ADND scan ok adapter=... convs=... new=..."
python tools/dev.py q '未识别 adapter'        # A3：期望 0 行；有行就把它给的 cls= 抄给我
python tools/dev.py q 'QUIET ADND' 40        # 兜底全量
```

预期形态：
```
QUIET ADND anchor setAdapter cls=android.widget.HeaderViewListAdapter count=2913   ← D 活着（可选）
QUIET ADND scan ok adapter=p3 convs=731 new=0                                      ← A 打通
```

### 4. 端到端（可选，要真进群）
被拉进新群后（群里有人说话 → 列表出现该会话）：
```bash
python tools/dev.py q 'QUIET ADND' 40
```
期望四连：
```
QUIET ADND [scan] new groups=1 -> [xxx@chatroom]
QUIET ADND muted xxx@chatroom (silent channel)
QUIET ADND realmute ok=true sent=true stateInt=0 ...
QUIET ADND realmute verified xxx@chatroom muted=true
```
补充口径：直接看状态也可以
```bash
python tools/dev.py probe quiet grp seen      # convAdapter=... anchorMatched=... learned=...
python tools/dev.py probe quiet grp status    # baselineReady=... handled=... joined=...
```

---

## ⑥ 风险与回退

| 风险 | 等级 | 说明 / 缓解 |
|---|---|---|
| 内容判定误采纳非会话 adapter（如通讯录 `contact.e`） | 低 | 后果只是多枚举一次；`diffAndApply` 的「从没见过才算新群」+ 保险丝 `MAX_FRESH_PER_ROUND=3` 双兜底，**不会误设老群免打扰** |
| 主线程多出的开销 | 低 | 每个未知类只走一次内容判定（结果进 `rejectedAdapterClasses` / `learnedConvAdapterClass`），之后恒定成本 = 1 次字符串比较或 1 次 `getCount`；枚举仍在后台线程 + 30s 节流（不变） |
| 未知 adapter 刷屏 | 低 | 每个类名只 warn **一次** |
| `param.thisObject` 不是 ListAdapter | 无 | `as?` + 整段 `runCatching`，不抛异常 |
| baseline / 落盘语义被改坏 | 无 | 完全没动相关代码；兜底快照走的是原来那个 `snapshotBaseline` 函数本身 |
| 启动快照被提前或重复触发 | 低 | `anchorMatched` 保证「D 和 A 谁先到谁触发，且只触发一次」 |

**回退**：改动全在 `AutoDndGroup.kt` 一个文件里，`git checkout -- 该文件` 即回到现状
（现状 worst case = 新群不自动免打扰，不 Crash、不影响微信其它功能）。

---
[DONE]
