# 并行编排 routine（派发模板 / 分诊细则 / 真机前闸门）

> **规则在 `CLAUDE.md` §四，本文只放操作细节。** 冲突时以 `CLAUDE.md` 为准。
> 本文不重复：分包下限、档位判定、台账顺序、轮末自审、子代理生命周期 —— 全部见 `CLAUDE.md`。

## 一、分诊：这个任务派给谁

**只看产出物，不看难度和是否只读：**

| 产出物 | 档位 | 典型任务 |
|---|---|---|
| 判据设计 / 根因推理 | **Pro** | 推 Hook 链路、看不懂根因、设计对账判据 |
| diff 复核（**审 Hook / 反射 / 异步时序 / 并发改动**） | **Pro** | 档位 = 被审改动的档位；纯逻辑/单测/文档的复核见下行 Flash |
| 复核纯逻辑 / 单测 / 文档改动 | Flash | 审 `*Logic.kt`、测试用例、注释 |
| 按现成判据写代码 | Flash | 取证结论已定锚点，照着接线 |
| 写单测 / 写脚本 / 写文档 / 修编译错 | Flash | 批量、机械、无判断 |

⚠️ **最容易错的两种极端**（都实测踩过，别只防一边）：
- 「复核是只读、不改代码 ⇒ 低价值 ⇒ Flash」—— **错**（审 Hook / 反射 / 异步时序 / 并发改动要 Pro）。
- 「复核产出的是判断 ⇒ 一律 Pro」—— **也错**（审 `*Logic.kt` / 单测 / 文档，按被审改动档位 ⇒ Flash）。

**唯一判据：复核档位 = 被审改动的档位**（`CLAUDE.md` §四②），与「复核者读不读源码」「改动几个文件」无关。

### 分诊歧义时的裁决顺序

1. 有现成判据（取证结论 / 设计文档 / 复核清单）→ **Flash**（照着做）
2. 需要判断「哪种可能」→ **Pro**
3. 两者都有 → **按最贵的那一段定档**（Pro 能做好 Flash 的活，反过来不行）

## 二、串行性：只看是否碰设备

- **串行**：`adb install` / 跑场景 / 拉日志。
- 子代理**一律不跑 gradle**，主 agent 统一编译。
- 串行性来自**设备争用**，不来自任务长短、不来自模型档位。可并行清单见 `CLAUDE.md` §三。

> 需要现场跑场景/盯实时 logcat 才入设备锁；基于**已落盘证据**（日志片段 / diff / 已知坑）推理可并行。

## 三、派发模板

**Flash（体力活，同消息发多个 = 并行）**
```
Agent(subagent_type="general-purpose", model="deepseek-v4-flash-202605",
  prompt="<目标>。独占文件：<路径>，不得改其它文件。不跑 gradle、不碰 adb。
          先写盘再回报；回报只给 git diff 摘要 + 自测结果。")
```

**Pro（推理 / 复核）**
```
Agent(subagent_type="general-purpose", model="deepseek-v4-pro-202606",
  prompt="现象/改动：<贴 git diff 正文，不要给路径>
          已知坑：Kotlin apply 静默失效 / adapter 未挂 / 字段反相 / ConcurrentHashMap 禁 null
          要求：推导根因 + 最小修复 diff + 回滚路径 + 边界漏项清单。
          先写盘再回报。")
```

**prompt 必带的三句**
1. 独占文件清单（防撞车）
2. 「不跑 gradle、不碰 adb」
3. **「先建产物文件再动手；每子步完成即写盘，留 `[DONE]` 标记」** + token 纪律（日志用 `dev.py q`；不整读 >300 行，Grep 后 `Read(offset,limit)`；复核只给 diff）

> **长任务照常派。** 子代理会在父 turn abort 时被杀，但只要分步落盘，留下的就是可用半成品，
> 从 `[DONE]` 标记续跑即可。**「长任务归主 agent」是错的做法**，会架空分包下限（`CLAUDE.md` §四①）。

## 四、真机前闸门（少崩微信 / 少烧 Pro）

### F. 纯逻辑先过单测
`feature/*Logic.kt` 零 Android 依赖的判据，上机前先跑 `python tools/gradle_run.py testDebugUnitTest`
（⚠️ 别写 `test`：会跑全 variant 所有测试，更慢且易挂 —— 脚本参数即 gradle task，与 `CLAUDE.md` §三 保持一致）。
过了才进 device 轮。**真机只验最后一公里**。
⚠️ 单测文件不得 import 微信/Context，否则 test worker 起不来。

### G. 归因先做基线对照（is-it-ours）
开 Pro 推根因**之前**，把相关 `kv` 关掉复现，确认 bug 真是本模块的锅。
关掉后现象仍在 ⇒ 微信原生/其他模块，直接排除，省一次 Pro 深推。
```bash
adb ... --es op kvset --es key chat.group_row_enabled --es val false   # 列表类
adb ... --es op kvget --es key <k>                                     # 读回
```

### H. 新 Hook / 反射必走「只读探针 → 真发」两步
反射调微信内部类（`hl0/r1` 发送、`q64/f.c` 免打扰）**绝不直连代码**：
① `sub=probe`（只读枚举，零副作用）→ ② `sub=test`（真发一条给文件传输助手）→ 再回填正式代码。
直连 = 崩微信 / 静默无果。
⚠️ 8.0.48 方法名已混淆，定位只能靠日志 tag / CGI 路径 / 类结构反推。

## 五、上机验证

### C. 粗筛机械化（主 agent 只做 grep，不做自由判断）
命中任一即升 Pro 判读，**宁可误升不可漏升**：
```
ANR / FATAL / CRASH / 非预期 Exception
<功能> installed 缺失（模块没加载）
adapter.count>0 而 childCount=0
字色同底「假空白」（先 dev.py ui 探，再截图定死）
```

### D. 编译失败回收
gradle fail → 把**报错原文**丢给 Flash 子代理修（机械活），修完回主再编。
不要回 Pro 重开，也不要主自己改。
⚠️ 跑 gradle 必须用 `tools/gradle_run.py`（剔 PATH 含空格脏条目），否则 test worker 起不来。

## 六、一句话流程

1. **分诊**（§一）→ 2. **记台账 + 派发**（`CLAUDE.md` §四③）→ 3. **闸门 F/G/H** → 4. **上机**（主 agent 串行）→ 5. **粗筛 C，可疑升 Pro** → 6. **自审 + 重排**（`CLAUDE.md` §四④⑤）

## 七、为什么禁止并行改同一个源文件

1. 一台设备、一个微信进程、一份落盘日志；两个 agent 同时 install 互覆盖 APK，同时拉日志互污染。
2. 单进程内联 hook，一个功能崩了带崩全部，并行改动无法归因。
3. LSPosed 重载不确定（`force-stop` 第一次常是旧代码），并行时更难判断跑的是谁的代码。

（**不同文件**并行改是允许且必须的 —— 见 `CLAUDE.md` §四①。）
