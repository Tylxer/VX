# R42 头像加载器取证 + 修复报告

> **范围**：纯静态只读 dex 取证（`_cache/dex/classes.dex` ~ `classes15.dex`，微信 8.0.48 / 2588 GP）
> + `AvatarLoader.kt` 探针接线 + 单测。
> **禁止**：本轮不装包、不 adb、不 commit、不跑 `build`。
> **证据分档**：① 字面（字节码/dexdump 原文）/ ② 强推测 / ③ 未定位到。

---

## 0 结论摘要（先给答案）

| # | 结论 | 等级 |
|---|------|------|
| 1 | **前提证伪**：`com.tencent.mm.modelavatar.a1`（SubCoreAvatar 服务类）在 8.0.48 的 dex 里**仍然存在**（classes7.dex），**没有**「类名随版本混淆漂移」这回事。 | ① |
| 2 | 现有 `AvatarLoader.kt` 的反射链 `a1.B8()` → `a1.z8()` → `x.i(String)` / `x.j(String)` 的类名与方法名**全部正确、全部 PUBLIC、无需修改**。 | ① |
| 3 | 本轮实际产出 = **纯日志诊断**（首次反射时 `LogX.i`/`LogX.w` 各打一行，含每步成败 + 失败步骤 + 异常类名）+ 纯判据 `AvatarProbeLogic` + JVM 单测。**没有改任何类名/方法名**（改了就是往坏里改）。 | — |
| 4 | **未验证项**：反射真伪只能等真机装包后 `adb logcat -s WXGroup` 看那一条日志（本轮不装包）。静态上找不到会失败的步骤，但 `d94/n0.c(Class)` 服务注册表在运行态是否已就绪属于 ②，见 §4。 | ② |

**一句话结论**：任务前提「`a1` 不存在」与 dex 事实不符；现有代码不需要改名修复。若要解释「头像只落到首字兜底」，更可能是**运行态**问题（服务未注册 / 磁盘头像未下载 / LruCache miss），而不是**类名漂移**。探针就是为定位运行态失败点准备的。

---

## 1 [DONE-1] 读懂现有实现与失败点

`core/AvatarLoader.kt` 的 `storage()` 反射链（首次调用懒解析，失败 `dead=true` 永久停用 + 只 warn 一次）：

```kotlin
private const val CLS_SUBCORE = "com.tencent.mm.modelavatar.a1"
val a1c = Class.forName(CLS_SUBCORE)                 // SubCoreAvatar 服务
val a1  = a1c.getMethod("B8").invoke(null)           // 静态 → 服务单例
val x   = a1c.getMethod("z8").invoke(a1)             // 静态 → AvatarStorage 实例
val xc  = x.javaClass
Storage(x, i = xc.getMethod("i", String), j = xc.getMethod("j", String))
```

任务给定失败点：`Class.forName("com.tencent.mm.modelavatar.a1")` 抛异常 → `dead` 置位 → 头像永久退回首字兜底。

**静态复核结论**：这个失败点在当前 dex 里**不成立**（见 §2、§3）。

---

## 2 [DONE-2] dex 扫描，列出候选与证据

复用 `_r35_adnd_dex.py` / `_r35_sns_dex.py` / `_r35_sns_dis.py`（同目录，R35 取证时已验证过正确性）。

### 2.1 按包名兜底：`com/tencent/mm/modelavatar/` 全部类

`python _r35_adnd_dex.py cls modelavatar` 命中 60+ 个类。关键两条：

```
classes7.dex  Lcom/tencent/mm/modelavatar/a1;  extends Ld94/w;   ← SubCoreAvatar 服务
classes7.dex  Lcom/tencent/mm/modelavatar/x;   extends Ljava/lang/Object;  ← AvatarStorage
```

另有 `classes6.dex  Lcom/tencent/mm/modelavatar/b;  extends Ld94/w;` —— 也是 `d94/w` 子类，但**零字段零方法**（`_r35_sns_dex.py self "modelavatar/b;"` 只打印类头），是空 marker/抽象服务，排除。

### 2.2 关键类方法 + 访问标志（自写 uleb 访问标志 dump，①）

`com.tencent.mm.modelavatar.a1`（classes7.dex）：

```
direct   acc=0x0009  a1;->B8()Lcom/tencent/mm/modelavatar/a1;    ← PUBLIC STATIC，返回自身（单例 getter）
direct   acc=0x0009  a1;->z8()Lcom/tencent/mm/modelavatar/x;     ← PUBLIC STATIC，返回 AvatarStorage
direct   acc=0x0009  a1;->F8()...  / H8()...  / x8()...          ← 其它 getter
virtual  acc=0x0001  a1;->onAccountInitialized(...)V / onAccountReleased(...)V
```

`com.tencent.mm.modelavatar.x`（classes7.dex）：

```
direct   acc=0x0009  x;->e(Ljava/lang/String;Ljava/lang/String;Z)Landroid/graphics/Bitmap;  ← getAvatarFromFile
virtual  acc=0x0001  x;->i(Ljava/lang/String;)Landroid/graphics/Bitmap;   ← 内存 LruCache 查表
virtual  acc=0x0001  x;->j(Ljava/lang/String;)Landroid/graphics/Bitmap;   ← HD 磁盘解码
virtual  acc=0x0001  x;->f(Ljava/lang/String;ZZ)Ljava/lang/String;        ← 拼绝对路径(判存在)
```

`0x0009 = PUBLIC|STATIC`，`0x0001 = PUBLIC`。`getMethod()` 只能找到 PUBLIC 方法 —— 这两个类的 `B8/z8/i/j` 全部 PUBLIC，反射**不会**因访问级别失败。

### 2.3 方法体反汇编（钉死语义，①）

`a1.B8()` 字节码 = 服务注册表单例 getter：

```
const-class    v0, Lcom/tencent/mm/modelavatar/a1;
invoke-static  Ld94/n0;->c(Ljava/lang/Class;)Ld94/m;   ← 微信服务注册表 getService(Class)
check-cast     v0, Lcom/tencent/mm/modelavatar/a1;
return-object  v0
```

`a1.z8()` 字节码 = 懒初始化 `x` 字段并返回：

```
invoke-static  a1;->B8() ...
iget-object    v0, a1;->e : Lcom/tencent/mm/modelavatar/x;   ← 读字段 e
new-instance   v1, Lcom/tencent/mm/modelavatar/x;            ← 首次调用新建
invoke-direct  x;-><init>()V
iput-object    v1, v0, a1;->e : ...                          ← 写回字段 e
iget-object    v0, a1;->e : ...
return-object  v0
```

### 2.4 字符串锚点互证（①）

`python _r35_sns_dis.py str AvatarStorage`：

```
classes7.dex  x;->b(...)V / e(...)  / h(...)  / n(...)  / p(...)  / s(...)
classes7.dex  AvatarStorage$MMTrimMemoryEventListener;->callback(...)Z
```

`x` 多个方法体内引用 `"com/tencent/mm/modelavatar/AvatarStorage"` 埋点串 ⇒ `x` = AvatarStorage，钉死。

`python _r35_sns_dis.py str SubCoreAvatar`：

```
classes7.dex  Lcom/tencent/mm/modelavatar/z0;->onTimerExpired()Z
```

`z0` 是 `a1` 的辅助类（log tag `MicroMsg.SubCoreAvatar`）⇒ `a1` = SubCoreAvatar 服务。

### 2.5 三条证据互证（任务要求「两条互证」，这里三条）

1. **方法签名**：`a1.B8() -> a1`（静态无参返回自身 = 单例 getter）+ `a1.z8() -> x` + `x.i(String)/x.j(String) -> Bitmap`。
2. **包名**：都在 `com/tencent/mm/modelavatar/`。
3. **字符串锚点**：`x` 方法内 `AvatarStorage` 串、`z0` 内 `SubCoreAvatar` 串。

三条互相印证，无歧义。

---

## 3 [DONE-3] 定案类名 / 方法名

**结论：类名与方法名均无需修改，维持现状。**

| 项 | 现值（现有代码） | 定案 | 证据 |
|----|------------------|------|------|
| SubCoreAvatar 服务类 | `com.tencent.mm.modelavatar.a1` | **不变** | §2.1/§2.4 存在且 extends `d94/w` |
| 服务单例 getter | `B8()` | **不变** | §2.2/§2.3 PUBLIC STATIC 返回自身 |
| 取 AvatarStorage | `z8()` | **不变** | §2.2/§2.3 PUBLIC STATIC 返回 `x` |
| 存储类 | `com.tencent.mm.modelavatar.x`（由 `z8()` 返回） | **不变** | §2.4 AvatarStorage 串 |
| 内存查表 | `i(String)` | **不变** | §2.2 PUBLIC → Bitmap |
| 磁盘解码 | `j(String)` | **不变** | §2.2 PUBLIC → Bitmap |

**排除的候选**：`com.tencent.mm.modelavatar.b`（classes6.dex）同为 `d94/w` 子类，但零字段零方法，不是 SubCoreAvatar，排除。

---

## 4 [DONE-4] 改代码 + 探针（纯日志版）

> 因类名/方法名不需要改，本轮代码改动仅为**纯日志诊断 + 纯判据 + 单测**。

### 4.1 `core/AvatarLoader.kt` 改动（唯一改动文件）

- 原 `storage()` 里的 try/catch 反射拆成 `resolve()`：逐步执行并记录每步成败，成功打一行 `LogX.i`、失败打一行 `LogX.w`（扩展现有 dead 分支那条 warn），然后由 `storage()` 置 `dead`。
- 纯判据 `object AvatarProbeLogic`（零 Android 依赖）：
  - `enum class Step { FOR_NAME, GET_B8, INVOKE_B8, GET_Z8, INVOKE_Z8, GET_I, GET_J }`
  - `firstFailure(errors)`: 返回第一个失败步骤（error 非 null 者）；全成功返回 null。
  - `verdict(errors)`: `"OK"` 或 `"FAIL at <step>"`。
  - `diagnose(targetCls, errors)`: 拼一行 `cls=… FOR_NAME=ok … GET_J=? -> OK/FAIL at X`。

### 4.2 探针形式（纯日志，无广播入口）

**不新增任何接线点**，装机后 `adb logcat -s WXGroup` 直接可见：

```
# 成功（首次反射时打一条）
AvatarLoader 反射链路 OK cls=com.tencent.mm.modelavatar.a1 FOR_NAME=ok GET_B8=ok INVOKE_B8=ok GET_Z8=ok INVOKE_Z8=ok GET_I=ok GET_J=ok -> OK

# 失败（扩展现有 warn，一条，含失败步骤 + 异常类名）
AvatarLoader 初始化失败（头像退回首字兜底） cls=com.tencent.mm.modelavatar.a1 FOR_NAME=ok GET_B8=ok INVOKE_B8=ClassNotFoundException ... -> FAIL at INVOKE_B8
```

### 4.3 `entry/EntryReceiver.kt` 变更记录（已还原）

第一版曾加 `op=avatar sub=who` 广播分支，随后按 team-lead 要求撤销。**注意**：该文件同时有 deltag2 在改（`snsdel` 分支），故未用 `git checkout --`（会连 deltag2 未提交的 `snsdel` 分支一起抹掉），而是**只删了自己那 7 行 `avatar` 分支**，保留 deltag2 的 `snsdel`。最终 `git diff` 里 EntryReceiver.kt 只剩 `snsdel` 一处改动，无 `avatar` 残留。

### 4.4 单测

新增 `core/AvatarProbeLogicTest.kt`：`firstFailure`（全成功 / 首步 / 中间 / 末步 / 多步失败取最早 / 空 map）+ `diagnose`（全 ok 一行、失败步骤带异常类名 + 其后 `?`、首步失败其余 `?`）。

### 4.5 降级路径（保持原样）

反射失败 → `dead=true` + `LogX.w` 只一次 → 所有行退回首字兜底；`x.i`/`x.j` 返回 null → 静默兜底；不 `recycle()` 微信返回的共享 Bitmap。**一字未动**。

---

## 5 [DONE-5] 构建 + 单测

| 项 | 命令 | 结果 |
|----|------|------|
| 构建 | `./gradlew assembleDebug`（JDK 21，未跑 `build`） | **BUILD SUCCESSFUL**（35 任务，无 error，仅既有 deprecation/unchecked-cast 警告） |
| 单测（本轮） | PowerShell：`$env:PATH='…jdk-21…\bin;…'` 后 `.\gradlew.bat testDebugUnitTest` | 全量 **770 条 / 1 失败 / 0 错误** |

**唯一失败不是我的**：`data.PrefsVisibilityTest.keyPrefixMatchesDomainGrouping` —— 报 `snsdel.style 的前缀 snsdel 不在已知域里`。这是 **deltag2** 新增 `snsdel` 域但还没同步改 `PrefsVisibilityTest` 的既存缺口，与本轮头像改动无关。

**本轮新增 `AvatarProbeLogicTest` 9 条全绿**：
`allStepsOkHasNoFailure` / `firstStepFailureIsReported` / `middleStepFailureIsReported` /
`lastStepFailureIsReported` / `earliestFailureWins` / `emptyMapIsTreatedAsNoFailure` /
`diagnoseAllOkIsOneLineWithEveryStep` / `diagnoseReportsFailingStepAndExceptionClass` /
`diagnoseMarksUnreachedStepsAsUnknown`。既有 `AvatarLoadLogicTest` 11 条仍全绿。

> 说明：中途一次 `--rerun-tasks` 因 gradle daemon 崩溃 / 与 deltag2 并行写入冲突（`New files were found`）+ 残留 `binary/results.bin` 失败，已清理残留并定向重跑 `AvatarProbeLogicTest` 确认 9/9 通过。

---

## 6 未验证项（本轮不装包）

1. **运行态反射真伪**：静态上找不到会失败的步骤，但 `d94/n0.c(Class)` 服务注册表是否已在模块 hook 上下文中就绪（R29 已标 ②），只能装机后 `adb logcat -s WXGroup` 看 `AvatarLoader 反射链路 OK/初始化失败 …` 那一条。
2. **头像本身是否可命中**：即使反射全绿，`x.i`（LruCache）/`x.j`（磁盘文件）也可能因「从未下载 / 被 trim」返回 null，头像仍落首字 —— 这是**数据缺失**而非**反射失效**，日志只能证明「反射通不通」，不能证明「头像有没有」。

---

## 7 [DONE-6] 真机定案：ClassLoader 不是类名

### 7.1 决定性真机日志（刚采到）

```
WXGroup : AvatarLoader 初始化失败（头像退回首字兜底） cls=com.tencent.mm.modelavatar.a1
  FOR_NAME=ClassNotFoundException GET_B8=? INVOKE_B8=? GET_Z8=? INVOKE_Z8=? GET_I=? GET_J=?
  -> FAIL at FOR_NAME
```

- `FOR_NAME` 即 `Class.forName(CLS_SUBCORE)`，**第一步就抛 CNFE**，其后各步全部 `?`（未执行）。
- 类名 `com.tencent.mm.modelavatar.a1` 已用 dex 反汇编确认存在于微信 `classes7.dex`，且 `B8`/`z8`/`i`/`j` 全部 PUBLIC —— 类名、方法名、访问级别全对，**唯一错的是「用哪个 ClassLoader 去找」**。

### 7.2 根因

`core/AvatarLoader.kt` 原第 77 行 `Class.forName(CLS_SUBCORE)` 用的是**调用者（模块）自己的 ClassLoader**，而不是宿主的 `lpparam.classLoader`，因此即便目标类在微信 dex 里，仍抛 `ClassNotFoundException`。

### 7.3 修法（两处，最小改动）

**A. `core/AvatarLoader.kt`**

1. 加宿主 ClassLoader 持有位与注入口：
   ```kotlin
   @Volatile private var hostCl: ClassLoader? = null
   fun attach(cl: ClassLoader) { hostCl = cl }
   ```
2. 反射首步改为优先用宿主 CL（`false` = 不立即初始化，反射取方法不需要静态初始化，少一次副作用）：
   ```kotlin
   val a1c = step(AvatarProbeLogic.Step.FOR_NAME) {
       val cl = hostCl
       if (cl != null) Class.forName(CLS_SUBCORE, false, cl) else Class.forName(CLS_SUBCORE)
   }
   ```
3. 失败日志补 `cl=` 字段，区分「没 attach」与「attach 了仍找不到」：`cl=${hostCl?.javaClass?.simpleName ?: "NULL(未attach)"}`。

**B. `HookEntry.kt`**

在 `GroupDb.open(context)` 之后、任何 `AvatarLoader` 可能被用之前：
```kotlin
// 宿主 ClassLoader，缺了它 AvatarLoader 的 Class.forName 必抛 CNFE（真机证）
AvatarLoader.attach(lpparam.classLoader)
```

### 7.4 约束与结果

- ⛔ 只改了 `core/AvatarLoader.kt` 与 `HookEntry.kt`，`SnsDeletedTag*` / `SnsGuard` / `Keys` / `GroupDb` / `EntryReceiver` 均未动。
- 保留 `probe()` / `AvatarProbeLogic` 与诊断日志结构，只加 `cl=` 字段；`AvatarProbeLogic` 纯判据（9 例）一字未改。
- 构建 `testDebugUnitTest --rerun-tasks`：**BUILD SUCCESSFUL**；全量 **771 条 / 0 失败 / 0 错误**。
