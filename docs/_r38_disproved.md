# R38 · 朋友圈防删除：已证伪清单 + 可用资料索引

> 状态：**事实归集**（主会话产出）。判据重设计与分期计划见 `docs/_r38_plan.md`（Pro lane）。
> 时间：2026-11-02 23:00 ｜ 设备 `467cf5bc` ｜ 微信 8.0.48/2588 GP ｜ 模块 `com.lx.wxgroup`

## §0 一句话现状

好友删动态时**我方客户端只收到一条 UPDATE**（`sourceType & -3`，`2→0`），**一条 DELETE 都没有**；
行与内容全部保留，界面消失。而现有实现的两条路各搭了一半：

- **DELETE 拦截 → 归档 → 注入**：收不到事件（本场景零 DELETE），且当前版本**零成功归档**；
- **`sourceType` 补位**：能写到库，但**改不动同步响应** ⇒ 界面照旧不可见。

⇒ 「防删除」目前**实际未生效**，「灰标」**结构性无数据源**。

---

## §1 已证伪（永久排除，不要再走）

| # | 错误项 / 曾经的假设 | 否证证据 | 后果 |
|---|---|---|---|
| 1 | 「好友删动态会发 DELETE，挂在 DELETE 上就能防」 | 三轮受控实验 `blocked` 恒 1→1；`SNS blocked delete` 0 行；关开关对照 `SNS delete seen (off)` 也 **0 行** | 归档永远收不到事件 |
| 2 | 「删除只把 `sourceType` 从 10 改成 8」 | 实测 `2→0`（清 bit1），非 10→8 | 判据方向本身错 |
| 3 | 「用行字段（`sourceType`/`serverExtFlag`/`localFlag`/`subType`）判删除」 | 同作者 10 条**四字段完全同形**；全表 `8`=29,685 / `0`=16,330，信噪比 ≈ 1:5000 | 这一**类**里没有信号，不是还没找到 |
| 4 | 「补回 bit1 就能让被删动态继续可见」 | 时间线列表按**同步响应**构建；22:34 实测 `src=10` 但 `has_verity=False` | 改库 ≠ 改界面 |
| 5 | 「`x2.M/N/O/P/Q/U` 是删除动作（H3b）」 | 6 方法全挂、三轮零命中（含 `onGYNetEnd errType=0` 的成功同步） | H3b 作废 |
| 6 | 「时间线响应里带删除列表/墓碑（H3a）」 | `docs/conclusions/moments.md:227`：无 `deletedIds`/tombstone 形态 | 无此结构 |
| 7 | 「列表级差集：不在响应里 = 被删」 | 事故 `SNS-INJECT attempt=24 ok=24` **全是正常动态**；分页 200 条里 190 条不在当前页 | 误报率灾难级 |
| 8 | 「`snstag dump` 可判动态是否存在」 | 只采样 `bound` WeakHashMap 里的**旧 View**，与当前屏幕无关（多次误判） | 存在性一律按 id 查库 |
| 9 | 「用『自己删自己动态』验灰标/防删」 | 微信**先把行删掉**（拦不到），再补一条级联 `SNS blocked delete`（`snap=false`） | 没有可打标对象 |
| 10 | 「发完立刻删」验端到端 | 我方还没把行拉下来，服务端已标记删除 ⇒ 像「从未存在过」 | 必须「她发 → 我方刷出并确认看见 → 她再删」 |
| 11 | 「把 `T_SRC` 补位台账当删除判据」（R31h） | 事故：正常动态全被插灰标 | 红线，永不启用 |
| 12 | 「`msync sync` 能带回删除状态」 | 三轮强制同步后 `src` 仍为 2 | 只能等微信自己那轮同步 |
| 13 | 「手势下拉必然触发同步」 | `MSYNC watch` 60s 窗口 `observed=0`；只有后台定时同步 / 退出重进首屏才真同步 | 判读前必须确认 `onGYNetEnd` 真发生 |

---

## §2 已定案事实（可直接当地基）

| # | 事实 | 依据 |
|---|---|---|
| F1 | 好友删动态 = `UPDATE SnsInfo SET sourceType = sourceType & -3`（`2→0`） | R37-20 八项判据 + `SnsTimelineSrcLogic.kt:85 isSourceClearSql` |
| F2 | 行 / `content` / `localFlag` / `serverExtFlag` 全保留，**零 DELETE** | 删前删后逐字段一致 |
| F3 | 清位发生在**同步**时（`updateFilterTimeLine`：全表清 bit1 → 只回填本批返回行） | `SnsGuard.kt` update 通道注释（R27 取证） |
| F4 | 模块补位是**贴脸反应**（只在「微信写 0 那一刻」触发），错过即永久丢 | 关开关期间删的那条至今仍 `0`；开着开关删的已回 `10` |
| F5 | `sourceType` 的**任何**变化都不可归因（微信清位 + 模块全表补位两个主体） | `srcstat fixed=500 pages=30` + `re-fix fixed=2118` |
| F6 | 归档唯一入口在 DELETE 通道；**当前版本零成功归档**（archive 8 条是 5~8 月老数据） | `SnsGuard.kt:433→642→ GroupDb.kt:417`；`sub=ls` 实测 |
| F7 | `SNS-INJECT` 源自 `listArchiveIds(500)`，每轮注入 8 条旧动态 | `SnsTimelineMerge.kt:172/:182` |
| F8 | 时间线响应无删除列表结构 | `conclusions/moments.md:227` |
| F9 | `SNSSYNCDEL` 旁听 `x2` 六方法：本场景恒 0 命中 | 三轮实测 |

---

## §3 可用资料索引

### 3.1 代码锚点（`wxgroup/app/src/main/java/com/lx/wxgroup/`）
| 关注点 | 位置 |
|---|---|
| DELETE 拦截 / 计数 / 快照 | `SnsGuard.kt:424 readRow` → `:427 blocked++` → `:433 saveAsync`（execSQL 同形 `:464/:467/:470`） |
| 归档早退 | `SnsGuard.kt:642-646`（`row==null` → `archive skipped`）、`:650 archived++` |
| 归档落库 | `data/GroupDb.kt:417 archiveSns`、`:560 listArchiveIds`、`:537 snsTagIds` |
| 删除语句识别 | `feature/SnsSql.kt:51-59 matchDeleteCall`（`snsId=` 才拦，批量清理放行） |
| 清位识别 | `SnsTimelineSrcLogic.kt:85 isSourceClearSql`（只有 `& -3` 被认领，`& -9` 走 `noteUnmatchedSourceWrite`） |
| 补位（受开关总控） | `SnsTimelineSourceFix.kt:531 reFixAfterClear`、`:367 patchAlbumBit`、`:478 albumfix` |
| 注入 | `SnsTimelineMerge.kt:172/:182`，开关 `:281` |
| 开关键 | `data/Keys.kt:141 antirevoke.sns`、`:380 PrefSpec`（标题「防撤回 · 防止朋友圈动态撤回」） |

### 3.2 探针与工具
- 归档 / 拦截：`op=snsg sub=stat|ls|one|show|srcmap|sqlspy|setsrc|del`
- 灰标：`op=snstag sub=stat|dump|fake`（**`dump` 不能判存在性**）
- 同步：`op=msync sub=stat|sync|watch`（`watch` 固定 60s 窗口）
- 只读 SQL：`op=sql --es q '…'`（**必须整串包一层引号**，否则被 `adb shell` 二次解析截断）
- 删除动作旁听：`SNSSYNCDEL`（`x2` 六方法，默认开，`KV debug.sns_syncdel_probe`）
- 屏幕取证：`uiautomator dump` + `wxgroup/_vis.py`（先 `cp` 成 `_vis.xml` 再在 `wxgroup/` 内跑）
- 时间线页：`com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI`
- 模块总闸：`kvset antirevoke.sns false` ⇒ 四处 `sourceType` 写入全静默（**做「模块关闭」对照只需这一条**）

### 3.3 文档
- `docs/_r37_deletesrc_verify.md`：四条子命题裁决（P1✅ / P2❌ / P3✅ / P4✅ 但依据订正）
- `docs/_r37_graytag_datasource.md`：上一版判据设计（窗口闸门 C1~C6 / G1~G5 仍有效）
- `docs/_r27_clear_api_forensics.md`：清位真实通道是 `update` 而非 `execSQL`
- `docs/conclusions/moments.md`：响应结构、scene 256

---

## §4 尚未否证、值得评估的方向（交给计划）

1. **「存档」与「判删」解耦**：每轮同步看到 `src & 2 != 0` 就快照（不依赖删除事件），判删只看「下一轮窗口内是否还在」。这样判据不依赖 `T_SRC`，绕开 R31h。
2. **窗口闸门**：顶部/底部覆盖判定（`id.ct` 落在响应最老~最新闭区间 + MARGIN），不过闸门一律「放弃」而非「打标」。
3. **好友相册接口差集**（H4）：单作者集合、无顶部插入，窗口可信度更高，但**需用户交互 ⇒ 半自动**。
4. **注入源替换**：从 archive 换成「快照表 + 当前窗口过滤」，同时解决「旧动态乱插」这个已观察到的副作用。

---

## §5 踩坑纪律（判读前先过一遍）

1. 存在性**一律按 id 查库**（`u=` 换负 id 用 Python 算）；⛔ 不用 `snstag dump`。
2. 广播前确认 `mCurrentFocus` 是 `com.tencent.mm`（后台不投递，会读到旧环形日志）。
3. `LogX` 只有 5000 行环形；大动作（全表补位）前先 `logclear`。
4. 装包后必须冷启两次；判包用 `pm path` + `lastUpdateTime`。
5. 单测/构建一律 `--rerun-tasks`（否则 FROM-CACHE 假绿）。
6. 开/关对照要成对做：关开关后必须先验证「模块确实静默」，再下结论。
