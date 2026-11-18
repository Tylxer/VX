# R60 — 分组行长按菜单文案对齐（离线，2026-11-17）

> 用户请求：「长按菜单文案对齐」。本轮只做离线代码 + 单测，未碰设备（正式开发/真机等用户通知）。

## 1. 对齐依据（来自既有取证，非本轮新猜）

- 我方长按 `GroupRowMenu` 五项：`置顶该分组`(已置顶→`取消置顶`) / `重命名` / `管理成员` / `标记已读` / `删除分组`。
- 微X 分组行长按菜单（`docs/_r55c_longpress_menu.md` §2/§3、`_r55_microx_click_longclick.md` Q3）：
  `解散聊天分组` / `归拢设置` / `标为未读` / `取消置顶` / `不显示该聊天` / `删除该聊天` / `加入分组` / `标记已读`。
- 取证文档给的边界（Q6 / Q5 改动 4）：
  - 只对齐**文案**，且**按开关二选一**：`gconv=1` 时 `删除分组 → 解散聊天分组`，`gconv=0` 保留旧文案；
  - 不条件化就破红线①「GCONV_MODE=false 零差异」（`GroupRowMenu` 在 OFF 的旧自绘分组行照样会弹）；
  - `归拢设置` **暂不对齐**——点下去干什么未知（T2 未做），不能在真实分组乱点。

## 2. 为什么只改这一项

- `解散聊天分组` 与 `删除分组` **语义等价**：都走 `db.deleteGroup(g.id)` 只删我方分组表、
  伪会话由 `GroupConvStore` 下一轮 sync 清掉，成员不丢（确认框正文「只会取消归组，成员不会丢失」本就准确，不动）。
- `归拢设置` / 前插 / 菜单形态（微X 是往宿主 PopupWindow 插项，我方是自绘 AlertDialog）都不在本轮对齐范围。

## 3. 实现

- 新增纯逻辑 `feature/GroupRowMenuLabels.kt`（不依赖 Android）：
  - `delete(gconvOn) = if (gconvOn) DISSOLVE else DELETE`
  - `deleted(gconvOn)` = 对应 toast 文案
- `GroupRowMenu.kt`：
  - 加 `gconvOn()` 读一次 `Kv.getBool(Keys.GCONV_MODE)`（弹菜单是低频动作，非 getView 热路径）；
  - 菜单末项 + 确认框标题用 `GroupRowMenuLabels.delete(on)`，toast 用 `.deleted(on)`。
- ⛔ OFF 分支逐字保留「删除分组」，由单测锁定。

## 4. 验证（离线）

| 项 | 结果 |
|---|---|
| `GroupRowMenuLabelsTest` | 3/3 通过（`offKeepsLegacyLabel` / `onAlignsWithMicroX` / `twoBranchesAreMutuallyExclusive`） |
| 全量单测 `testDebugUnitTest` | `tests=928 failures=0 errors=0 skipped=0`（基线 925 + 3） |
| **变异验证** | 把 `delete()` 变异成恒返回 `DISSOLVE` ⇒ `offKeepsLegacyLabel` + `twoBranchesAreMutuallyExclusive` **2 条变红**（`928 tests completed, 2 failed`）⇒ 证明非空转；变异常量已清除并复跑全绿 |
| `assembleDebug` | EXIT=0 |

## 5. 真机验证（2026-11-17 22:51，设备 467cf5bc / 微信 8.0.48）

上机前把完整 diff 交给独立复核代理（父会话模型）静态复核 ⇒ **PASS**（红线①、三处文案同向、语义等价、Kv 兜底、编译引用、单测覆盖全通过，未越界碰归拢设置）。复核提的 KDoc 陈旧注释非阻断，保持「复核 diff == 装机 diff」未改。

装包：APK 22:38 编译（新字符串已进 dex）→ install Success → 首次加载检测 False → 双 force-stop 冷启后 `loaded=True`（记忆里的「装包常不重载」坑复现）。

**验证手段**：真手长按菜单无法用 `adb input` 触发（R55 定案会当点击）。改用只读探针 `--es op menulabel` 直接打印菜单 UI 实际调用的那份 `GroupRowMenuLabels` 映射（`Kv.getBool` 每次读库无缓存，`kvset` 后无需冷启）。

**双向对照结果**（`kvset` 均 `kvget` 回读确认写入）：

| 开关 | 探针输出 | 判定 |
|---|---|---|
| gconv=1 | `MENULABEL gconv=true item=解散聊天分组 toast=已解散聊天分组` | ✅ 对齐微X |
| gconv=0 | `MENULABEL gconv=false item=删除分组 toast=已删除分组` | ✅ 红线① OFF 逐字保留 |
| 恢复 gconv=1 | 回到 `解散聊天分组 / 已解散聊天分组` | ✅ 现场已复位 |

- 三处文案（菜单末项 / 确认框标题 / toast）同源码同一份结果，双态均同向，无串味。
- 加探针后全量单测复跑：`tests=928 failures=0 errors=0 skipped=0`（无回归）。
- ⚠️ 未做真手长按看 UI 弹出：本次纯文案改动，UI 渲染正确性由「菜单三处共用该映射（静态复核确认）+ 真机双态探针」覆盖。若日后要验长按手势本身，需在真机上真手长按 `123` 分组行目测。
