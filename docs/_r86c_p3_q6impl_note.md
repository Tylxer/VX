# R86c Q6 一轮只读取证 —— 最小诊断日志补丁实施记录

> 子代理 r86c-p3-q6impl-v2（fresh-eyes Pro）｜本轮按用户口头临时规则派发（规则内容依要求不落盘）
> 工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`
> 源码路径前缀 `wxgroup/app/src/main/java/com/lx/wxgroup/`，下文 `ForceForward.kt:2552` 即 `feature/ForceForward.kt:2552`。
> 档位：【源码实证】/【日志实证】/【推断·高|中|低】/【UNKNOWN】

## 骨架（随问写盘，防打断）

- [x] [DONE Q1] 读产物 + 定位源码 + 字段/配对方案定稿
- [x] [DONE Q2] 最小日志补丁落盘（只加观测、不改逻辑）
- [x] [DONE Q3] 三闸（compileDebugUnitTestKotlin / 定向单测 / 全量单测 / assembleDebug）结果
- [x] 三件套（改动清单 / 判据读法 / 统一声明）
- [x] 真 [DONE ALL]

---

## 铁纪律（本轮自约束）

- **写入边界**：唯一允许改的源码文件 = `ForceForward.kt`（实际在 `feature/` 包，派发单写成根包是笔误）；唯一允许新建的文档 = 本 note。禁改任何其它源码/单测/文档；禁碰 adb/装机/设备开关；禁 git。
- **只加日志不改逻辑**：诊断日志只打印既有局部变量的值，不改业务分支/返回值/字段写入/宿主对象/DB/消息发送；不改 `P3AdmitGate.admit` 判定，不改 `p3OriginFromMd5` 的 regex/拼径/返回。肉眼不会有变化。
- **口径不自造**：reason 码复用 `P3AdmitGate.admit` 既有集合（`no-exist`/`th-prefix`/`below-floor`/`bad-shape`/`magic-mismatch`/`thumb-sibling`；产物所写 `siblingReason` 是函数名，实际发出的码是 `thumb-sibling`），见 `ForceForward.kt:121-131`、`:158-168`。
- **已定案不推翻**：C2 命中≠清晰；g9c `hasBig` 非本地大图在场；C3/C4 已被主会话证据否证，本轮不再判。

---

## Q1 取证产物复核与源码定位

### 产物锚点（fresh-eyes 只读复核，不重新论证）

- `docs/_r86c_p3_keysrc.md:26-44`：P3 查找键 = 源图 md5 `e8b43a5eb6f7929c32b22214fd78eaac`，与缩略图 md5 `bb7ef5cbb35141ba265292df70d78104` 是两条并行路径；hit 文件 `image2/e8/b4/e8b43a5eb6f7929c32b22214fd78eaac.jpg` = 335644B（off1 `:160`）。
- `docs/_r86c_p3_keysrc.md:50-68`：分叉变量是「唯一拼径上的本地大图文件是否存在且过闸门」，闸门见 `ForceForward.kt:121-131`。
- `docs/_r86c_p3_keysrc.md:132`：Q6 建议的 miss 行字段即 `md5Head/pathTail/exists/size/gate/cand`。
- 主会话新证据：`_r86c_p3_transient_5x.txt:281/:382/:467/:543/:661` 五次 `FORWARD P3 copy skip: reason=th-prefix name=th_bb7ef5cbb…` ⇒ C4（copy 通过）被否，C3（同键 off1 命中）被否；只剩 C1 vs C2，二者共同观测量 = miss 时拼径文件的 `exists/size`。
- 设备侧 `adb ls` 不可行（产物同款结论）：`adb root` 失败（production build），shell uid=2000 读不到 `u0_a363` 私有 MicroMsg 目录 ⇒ 只能靠模块日志补观测量。

### miss 分支源码结构（`ForceForward.kt:2514-2557` 补丁前）

- `:2522-2529` 正则 `([0-9a-fA-F]{32})(?:\.(jpg|jpeg|png|webp))?` 从 img 的 String 字段抠候选，`.toList()` 得到 `candidates`。
- `:2531-2533` 从 localPath 取 `MicroMsg/<hash>/` 根。
- `:2535-2553` 遍历 candidates：`nameRe.find` 未命中 `continue`（不发文件尝试）；命中则拼 `image2/<2>/<2>/<md5>.<ext>`，过 `P3AdmitGate.admit(f, true)`；放行打 hit 并 `return`；拒绝仅 `lastReason = reason`，**不打键、不打路径、不打 exists/size**。
- 旧 miss 行 `:2552`：`FORWARD P3 origin-by-md5 miss reason=${lastReason ?: "no-candidate"}`。

### 配对方案（防张冠李戴，已定稿）

`lastReason` 原本只在「有实际文件尝试且被闸门拒绝」时赋值；`continue`（无 md5）分支不赋值。因此把 `lastAttemptedFile / lastMd5 / lastPath` 与 `lastReason` 在**同一行代码相邻处同步赋值**（新 `:2554-2557`），保证四者永远指向同一次尝试。miss 时 `attempt = lastAttemptedFile` 即最后一次被拒的那个 `File`，其 `exists()/length()` 与该 `reason` 天然配对；若所有候选都没形成文件尝试，`lastAttemptedFile=null` ⇒ 字段统一打 `-`、`gate=no-candidate`，不会误报某个无关文件。

档位：源码结构、配对逻辑【源码实证】；产物结论与 5x th-prefix【日志实证，来自既有产物】。

`[DONE Q1]`

---

## Q2 最小补丁实现

### 实际改动（仅 `ForceForward.kt`，行号为补丁后）

| 行号 | 改动 | 性质 |
|---|---|---|
| `:2535-2538` | 新增 3 个诊断局部变量 `lastAttemptedFile / lastMd5 / lastPath` + 1 行说明注释 | 只声明，无写入业务对象 |
| `:2554-2557` | 在原 `lastReason = reason` 之后追加 3 行同步赋值 | 只记录既有局部变量 `f/md5/path` |
| `:2559-2567` | 替换原 miss 日志为带 6 个观测字段的单行日志 | 只改日志文本 |

未改动：`P3AdmitGate.admit`（`:121-131`）、`nameRe`、拼径 `:2546`、`admit(f, true)` `:2549`、hit 返回 `:2550-2553`、任何字段写入/DB/消息发送/宿主对象。命中日志 `:2551` 保持不变（`R83OriginByMd5Test.logOnlyTailsNoFullHash` 继续锁住）。

### 字段语义（逐项，供主会话读日志）

| 字段 | 取值 | 语义 |
|---|---|---|
| `reason` | `gateCode` | 保留原字段、向后兼容；与 `gate` 同值 |
| `md5Head` | `lastMd5?.take(8)`，无则 `-` | 最后一次尝试提取到的**源图 md5 前 8**；`e8b43a5e` 即源图那条 |
| `pathTail` | `lastPath?.takeLast(40)`，无则 `-` | 最后一次拼径的尾 40 字符，足以核对 `image2/e8/b4/…` 分桶及文件名；不打完整 hash/MicroMsg 根 |
| `exists` | `attempt?.exists()`，无则 `-` | 最后一次尝试的 `File` 在模块进程视角下是否存在（这正是设备侧 `ls` 读不到的量） |
| `size` | 存在时 `attempt.length()`，否则 `-` | 同最后一次尝试文件的字节数；≈335644 对应源图大图 |
| `gate` | `lastReason ?: "no-candidate"` | `P3AdmitGate.admit(f, true)` 的既有码：`no-exist` / `th-prefix` / `below-floor` / `bad-shape` / `magic-mismatch` / **`thumb-sibling`** / `no-candidate`（无文件尝试）。注：产物写作 `siblingReason`，那是函数名，实际发出的码是 `thumb-sibling`（`:165-166`） |
| `cand` | `candidates.size` | 从 img String 字段筛出的候选串总数（`:2528` 过滤后），≥ 实际文件尝试数（无 md5 的串会 `continue`） |

`[DONE Q2]`

---

## Q3 三闸结果（全 EXIT=0 才绿；JDK21 = `G:\Program Files\Microsoft\jdk-21.0.7.6-hotspot`）

| 序 | 命令（工作目录 `wxgroup`） | 结果 | EXIT |
|---|---|---|---|
| 1 | `cmd //c "gradlew.bat compileDebugUnitTestKotlin"` | `BUILD SUCCESSFUL in 43s`；`:app:compileDebugKotlin` 与 `:app:compileDebugUnitTestKotlin` 均**真正执行**（非 UP-TO-DATE）。仅 2 条既有 warning（`:2452:35` 条件恒真、`:3176:31` 条件恒假），与本次补丁无关 | 0 |
| 2 | `bash tools/_junit.sh com.lx.wxgroup.feature.R83OriginByMd5Test com.lx.wxgroup.feature.R85P3AdmitTest` | `OK (31 tests)`，`Time: 0.45s` | 0 |
| 3 | `bash tools/_junit.sh`（默认全量，1215 条类全枚举） | `OK (1215 tests)`，`Time: 1.245s` | 0 |
| 4 | `./gradlew assembleDebug --offline` | `BUILD SUCCESSFUL in 15s`，`dexBuilderDebug`/`mergeProjectDexDebug`/`packageDebug`/`assembleDebug` 实跑 | 0 |

> 闸2/闸3 用仓库既有 `tools/_junit.sh`（JUnitCore 直跑编译产物，绕开本机不稳定 Gradle test worker）；闸1 先把改后源码编进 `debugUnitTest` 产物，顺序不可反（`开发回路速查.md:412`）。
> 注：**本次不改任何测试源码**，未引入假绿/假红风险；`_env.sh` 遮 python 的坑本轮未涉及（全部命令无 python 环节）。

`[DONE Q3]`

---

## 三件套 / C1·C2 判据读法 / 统一声明

### 1) 结论（改动清单）

- 只改 `feature/ForceForward.kt` 的 `p3OriginFromMd5`：新增 `:2535-2538` 诊断变量、`:2555-2557` 同步配对赋值、`:2559-2567` 富字段 miss 日志，共 1 个函数内 3 处。**没有碰其它任何源码/单测/文档**（本 note 是唯一新建文档）。
- 补丁效果：miss 行从 1 个字段扩到 `reason/md5Head/pathTail/exists/size/gate/cand` 7 个观测字段；hit 路径、闸门判定、循环、返回值、所有业务写入逐字未动。
- 三闸全绿（见 Q3），可交付主会话装机取证。

### 2) go/no-go 与判据读法（主会话拿到新日志后如何一锤定音）

**统一前置**：先回读确认现场探针状态与本轮一致、日志里出现新格式行（含 `md5Head=`）：否则视为新代码未加载，不解读。

- **C1（源图真缺席 → 归档收尾，无代码缺陷靶点）** ⛔ **【已废止字面 md5 条件 · 2026-11-25 q6-verdict 裁决】**：把【推断·高】的 `e8b43a5e` 硬编码为归档门槛 + 误读 `md5Head`（实为"最后一次被拒候选"非真键声明）属判据设计缺陷。改用 **C1′**：`exists=false` + `gate=no-exist`（且非 `no-candidate`）⇒「最后一次尝试的 image2 文件本地缺席」【日志实证】+ miss 行存在即无候选命中【源码实证】+ C2/C3/C4 全否 ⇒ 归档；`md5Head/pathTail/cand` 降级为观测量。详见 `docs/_r86c_q6_verdict.md` Q2 §2.4。以下原条款保留作历史、不再执行：
  - 判据（原·已废止）：miss 行 `exists=false` + `gate=no-exist` + `md5Head=e8b43a5e` + `pathTail` 尾段指向 `image2/e8/b4/e8b43a5eb6f7929c32b22214fd78eaac.jpg`。
  - 读法：模块进程视角下该拼径文件确实不在盘 ⇒ 与 `adb ls` 路（本轮不可用）互为替代的**直接观测量** ⇒ 坐实「本地源 jpg 缺席」，`no-exist` 是真实事实而非闸门误拒 ⇒ 不修键、不改闸门，归档或另立「确保转发前原图落盘」独立需求。
- **C2（闸门误拒在盘大图 → narrow go）**
  - 判据：miss 行 `exists=true` + `size≈335644` + `md5Head=e8b43a5e`（拼径正确）但 `gate` 为非 `no-exist` 码（`bad-shape` / `magic-mismatch` / `thumb-sibling` / `below-floor`）。
  - 读法：文件在盘且是源图大小，却被闸门拒绝 ⇒ 拼径形态或准入判据有 bug ⇒ narrow go 做最小修复，之后须走 B6 全量 + 用户肉眼 A→B→A 终审。
- **已否证，本轮不再判**：
  - **C3（键错）**：`_r86c_p3_keysrc.md:40-42` 证 miss 时记录仍持有 `e8b43a…`，off1 同键命中；新日志若出现 `md5Head` 不是 `e8b43a5e`（如缩略图 `bb7ef5cb`）才重新开启 C3，否则视作已否证。
  - **C4（下游 copy/发送）**：`_r86c_p3_transient_5x.txt:281/:382/:467/:543/:661` 五次 `copy skip: reason=th-prefix` 证明 copy 未通过、根本没走到下游发送；C4 已否证。

**读日志注意**：`reason` 与 `gate` 同值，是刻意保留的兼容字段，不是两个独立证据；`cand` 是候选串总数，可能大于实际文件尝试数（无 md5 的候选 `continue`，不打行）；若 `md5Head/exists/size` 全为 `-` 且 `gate=no-candidate`，说明没有任何候选形成文件尝试（`nameRe` 全未命中或 localPath 无 MicroMsg 根），这是独立情形，不可归为 C1/C2，需回看 `cand` 与 img 字段。

### 3) 统一声明

- 本补丁**只加观测、不改变任何发送行为**：不发消息、不写文件/DB/宿主字段、不改闸门与拼径，肉眼不会有变化。
- **日志证据 ≠ 清晰**：即便 C1/C2 判读成立，出口是否清晰仍须以真实发送链的字节/内容为准。
- **清晰须 B6 全量 + 用户肉眼 A→B→A 终审**：本轮不做、不授权、不要求用户发图；C2 若 real bug 也须按此流程验收后才能定案。

`[DONE ALL]`
