# R86c G1 开关真机 A/B 取证裁决 —— 原生链切换成功但清晰未翻转 ⇒ ①否·归档

> 执行：主会话（真机 + 用户肉眼终审）；日期 2026-11-26 00:10~00:19
> 依据：`docs/_r86c_native_feasibility.md` Q6「条件 go，仅一轮真机 A/B」的升级/降级判据
> 纪律：零改码、未装机、未提交；唯一自变量 = `chat.forward_native` 开关；判据对肉眼不对计数器
> 落盘证据：`_r86c_g1_baseline_off.txt` / `_r86c_g1_open.txt` / `_r86c_g1_reverse_off.txt`

## 一、现场起点（步 0，实测全绿）

| 项 | 实测 | 预期 |
|---|---|---|
| G1 `chat.forward_native` | `false`（默认） | 关 ✅ |
| r86c 探针 | `fs=false r0=false` | off ✅ |
| stockcdn | `enabled=false` | off ✅ |
| b6step | `enabled=false` | off ✅ |
| hevc | `enabled=false pending=0` | off ✅ |
| 包/进程 | armfix 3363983B、微信前台、模块已加载 | ✅ |

## 二、三轮取证（同一张图、同目标口径）

### 轮 1 · 关态基线（G1=false）
- 肉眼：**A 原生转发=清晰；B 模块「转发（模块）」×2=模糊**（用户报告）。
- 日志：`00:09:34.613` / `00:09:41.068` 两条 `FORWARD plan strategy=RESEND_FILE reason=原生转发不可用，降级重发文件 kind=IMAGE targets=1`；**无** `FORWARD native probe` 行（`nativeState()` 开关关即 UNKNOWN，连探测都不做）。
- 判：G1 关 ⇒ 降级链，与肉眼模糊一致。**基线成立**。

### 轮 2 · 开态（G1=true，唯一自变量翻转）
- 肉眼：**A 原生转发=清晰；B 模块「转发（模块）」×2=仍模糊**（用户报告）。
- 日志（`_r86c_g1_open.txt`）：
  - `00:14:12.420 FORWARD native probe=true`
  - `00:14:12.421 FORWARD plan strategy=NATIVE reason= purpose... targets=1 kind=IMAGE`
  - `00:14:12.565 FORWARD native image queued target=filehelper sub=13 marked=true msgId=19757536`
  - `00:14:23.472 FORWARD plan strategy=NATIVE targets=2` + 两条 `native image queued ... marked=true`（多目标逐条）
  - `00:14:30.063 FORWARD plan strategy=NATIVE targets=1` + `native image queued ... marked=true`
  - `FORWARD native fail stage` / `fail stage` / `native image stage=` 计数 = **0**
- 判：G1 开 ⇒ **链真实切换到原生**（probe=true + strategy=NATIVE + image queued marked=true，无 fail），**但肉眼仍模糊**。

### 轮 3 · 反对照（G1=false 翻回 + 重启）
- 肉眼：**B 模块转发=模糊**（用户报告）。
- 日志：`00:19:01.615 FORWARD plan strategy=RESEND_FILE reason=原生转发不可用`；`FORWARD native` 计数 = **0**。
- 判：G1 关回 ⇒ 链回到降级、肉眼回到模糊。**正反闭环成立，开关可回退**。

## 三、裁决（对照 Q6 已定判据，机械比对）

Q6 升级/降级判据（原文见 `_r86c_native_feasibility.md` Q6 / 三件套②）：

- **升①可行** 需四者**同时**：① `FORWARD native probe=true` ✅；② `FORWARD native image queued sub=13` ✅；③ 出口 filemd5 翻源图身份（本轮未取证，探针保持 off）⛔未证；④ **用户肉眼 A→B→A 翻转** ❌（开 G1 后 B 仍模糊，未翻转）。
- **降③不可行**：开 G1 后仍模糊 ✅ —— **命中**。

⇒ **裁决 = ③不可行，方向①归档。**

链切换的三项机制判据全中（probe=true / NATIVE / queued marked=true / 零 fail），证明 G1 开关**确实驱动了原生链**且转发标记完好——这排除了「链没切成功」「native fail SEND」「空壳/无标记」这几种失败模式。但清晰并未随之翻转，**与 Q5 隐藏假设部分崩塌的预测完全一致**：清晰决定量是本地 hevc 缓存字节状态（原图在场 45522B/cff4f5a5 vs 压缩 9554B/0ef205c1），走原生链是**必要非充分条件**，`m.a>0→服务端秒传→清晰` 的因果（源码注释 `FileSender.kt:200-203`）在本场景不成立。

## 四、诚实边界 / 未取证项

- **出口 filemd5 未取证**：四探针全程 off（守复位边界），未开 stockcdn 看 `SCD-x0cb` 出口 md5 是否翻源图身份。但此项**不改变裁决**——肉眼终审是判据，且 Q6 要求四者同时，肉眼翻转这一项已明确为否，单独补 md5 旁证无法翻案。
- **A 原生清晰 = 用户报告·非日志可核**：肉眼属用户终审，日志不记录清晰度。
- **未改任何代码**：本轮零改码，G1 开关已翻回 false 并重启，现场全探针 off 复位。
- **本轮不推翻的硬结论**：C2 命中≠清晰；B5 机制信号·推断·高；清晰决定量=本地 hevc 字节状态；R86c B5→P3→Q6→G1 全链归档、无「改一处代码即可治本」的靶点。

## 五、后续（不在本轮执行）

方向①（消除降级、走原生）经真机证伪，与已归档的 B5/P3/Q6 合并构成 R86c 完整结论：**模块转发图片模糊的根因不在「选了降级链」这一层，而在本地源图 hevc 字节缺席**；要治本需解决「发图时本地原图字节在场性」，那属独立问题、非本次 G1 开关可覆盖。是否继续、走哪条，待用户决策；本轮不自行开工。
