# R30 资产盘点：朋友圈 item 打标框架**已经存在且在真机跑着**

> 主会话 2026-10-27 20:30 核实（读源码，非推测）。
> 目的：给 6.1 的实现 lane 当前提，**避免从零取证 adapter 链路**（那条链四轮真机才走通）。
> ⛔ 本文只记录「存在什么、在哪里」，**不做 Hook 设计**（属 Pro，交给实现 lane 判）。

---

## 1. 结论先行

`feature/SnsTag.kt`（836 行）是**朋友圈时间线 item 的打标框架**，由 `HookEntry.kt:244-246` 注册，
**当前是启用状态**（不是注释掉的死代码）：

```kotlin
// HookEntry.kt:238-246
// ---- 防撤回·朋友圈动态「[已删除]」标签（6.2 第三项）----
// 落点：新版时间线 ImproveSnsTimelineUI → WxRecyclerAdapter.onBindViewHolder（**类名未混淆**）
// + adapter.getData() 的 ImproveInfo(name=…, snsID='无符号十进制')。
// ⚠️ 老版那条 View.setTag → TimeLineObject.Id 链在新版**不存在**（四轮真机取证结论）。
runCatching { SnsTag.install(lpparam) }.onFailure { ... }
```

⇒ **6.1 不需要新的 adapter / onBind 取证**。`WxRecyclerAdapter.onBindViewHolder` 的挂法、
`holder → itemView` 的取法、`position → snsId` 的解析、复用残留清理、开关闸门、sweep 重判，
全部已有且已过真机验证。

---

## 2. 可直接复用的资产（精确位置）

| 能力 | 位置 | 说明 |
|---|---|---|
| 挂 `WxRecyclerAdapter.onBindViewHolder`（2 参与 3 参都挂） | `SnsTag.kt:152` `hookAdapterClass` | 基类钩 + `hookedMethods` 去重 |
| 抓具体 adapter 类补挂（子类覆写 onBind 时基类钩是死钩） | `SnsTag.kt:176` `hookSetAdapter` + `WX_RV_CLASS` | 挂 `WxRecyclerView#setAdapter` |
| **只认朋友圈 adapter**（否则全微信列表都在跑反射） | `SnsTag.kt:271` `SNS_ADAPTER_PREFIX = "com.tencent.mm.plugin.sns."` | 前缀判定，滚动性能的命门 |
| **holder → item 根 View** | `SnsTag.kt:321` `itemViewOf` | ⚠️ 已踩坑：必须排除 RecyclerView 本身、不能取第一个 View 字段；按「是 ViewGroup 且在 RV 里 / 面积最大」兜底，并按 holder 类**缓存字段** |
| **position → `ImproveInfo` → snsId** | `SnsTag.kt:395` `resolve` / `:439` `infoAt` / `:492` `infoOf` | 走 `adapter.getData()`（只认 `getData()`，`dataList` 注释里写明**不做**「任意返回 List 的无参方法」兜底） |
| 打标 / 摘标（**幂等**） | `SnsTag.kt:664` `ensure(host, show)` | 状态一致就什么都不做，绝不重复创建 |
| **复用残留清理** | `SnsTag.kt:293-305` | 解析失败时 `bound.remove(itemView)` + `ensure(itemView,false)`；注释原话：「item 是复用的，留着旧绑定会让标签错位到别的动态上」 |
| 定时重判（2s） | `SnsTag.kt:546` `sweeper` / `:568` `sweep` | 真相只认 `bound`（itemView→snsId），开关关着按空清单处理 = 全摘 |
| 绑定表 | `SnsTag.kt:122` `bound: MutableMap<View, Long>` | ⚠️ **这是「绑定过的 View 集合」，与开关无关**（项目记忆里栽过：拿它当开关判据） |
| 已救动态清单 | `SnsTag.kt:594` `deleted()` → `GroupDb.snsArchivedIds()` | TTL 5s 缓存；首屏同步取一次；**只查 `sns_id` 一列**，刻意不读三个 BLOB |

---

## 3. ⛔ 三个必须由实现 lane（Pro）判定的岔路

### 3.1 判据集合用哪个？**两个集合完全不相交，必须取并集**（✅ 已实测，不是推测）

⚠️ 2026-10-27 20:30 主会话拉真机库实测（`adb exec-out su -c cat wxgroup.db` → 本地 sqlite3）：

| 表 | 行数 | 语义 |
|---|---|---|
| `sns_archive`（`snsArchivedIds()`，`GroupDb.kt:501`） | **8** | 微信**物理 `DELETE` 整行**时被 `SnsGuard` 拦下并归档的动态 |
| `sns_visible_src` 中 `fixed_orig IS NOT NULL`（`listSrcFixedIds()`，`GroupDb.kt:671`） | **4** | 微信把 `sourceType` **清成 0** 时被 `SnsTimelineSourceFix` 补回 2 的动态 |

```
sns_archive ∩ fixed_orig = ∅      ← 交集 0
仅归档（只查 fixed 会漏标）= 8 条
```

⇒ **两种"删除"是两条互斥路径**：一条走 `DELETE FROM SnsInfo`，一条走 `UPDATE sourceType=0`。
只查任一边都会漏。**§6.1 的判据必须是两者的并集**（12 条）。

> 📌 我原来在 `未完成项与计划.md` §6.1 写的是「现成 `listSrcFixedIds()`」，**那条是错的**（依据只有一个
> 集合、没做交集验证）。**以本文为准**，等实现 lane 落代码时同步订正那边。

**附带发现（值得单独记）**：方案 A 的 Response-Merge 目前**只注入 `fixed_orig` 那 4 条**，
因为它从 `SnsInfo` 表读 `attrBuf` —— 而物理删除那 8 条如果真被删了，`SnsInfo` 里就没行、
`readAttrBuf` 会返回 null（走 `emptyBuf` 分支）。**归档表自带 `attr_buf` 列**，
理论上可以补上这 8 条的注入。这是 R30 之外的一个增量机会，先记账不扩范围。

**待真机确认（不影响先按并集实现）**：那 8 条归档动态现在在微信库里 `sourceType` 是多少 ——
若是 2 就本来就显示（标记会立刻可见，正好当验收样本）；若不在表里就界面没有，标记也不可见。
按并集实现两种情况都不会错。

### 3.1b 归档 8 条明细（真机实测，供验收对账）

| sns_id | 作者 | create_time | source_type |
|---|---|---|---|
| -3405840021235297608 | `wxid_myzsbcur81xr21` | 1793015483 | 4 |
| -3412979636140764498 | `wxid_lx9vwt2ucse022` | 1792164377 | 2 |
| -3424074357631413578 | `wxid_myzsbcur81xr21` | 1790841780 | 6 |
| -3437240302323363201 | `wxid_myzsbcur81xr21` | 1789272278 | 6 |
| -3442401033659805004 | `wxid_myzsbcur81xr21` | 1788657068 | 6 |
| -3452252834713759157 | `wxid_myzsbcur81xr21` | 1787482645 | 6 |
| -3462689929279958455 | `wxid_myzsbcur81xr21` | 1786238445 | 6 |
| -3499646661242318257 | `wxid_myzsbcur81xr21` | 1781832858 | 6 |

fixed_orig 4 条：`-3405726322886831589`、`-3405753000984047083`、`-3405736005952990706`、
`-3405768558946217460`（均 `source_type=2`、`fixed_orig=0`）—— 就是 R27 方案 A 真机验过的那 4 条。

### 3.2 现有 `ensure()` 是「往 itemView 追加 TextView」，用户要的是「改时间那行文字」

`SnsTag.LABEL = "[已删除]"`（`SnsTag.kt:68`），是**新增一个 TextView 挂在 itemView 上**。
用户 2026-10-27 拍板的是：**灰色小字，拼在发布时间后面**（`setText(原时间 + " [删除已拦截]")`）。

⇒ 需要决定：
- (a) 改 `SnsTag` 的呈现方式 —— 但 `SnsTag` 承载的是**另一个已交付功能**（动态 `[已删除]` 标签），
  改它会动到既有行为，属于「为一个需求改坏另一个」；
- (b) **新建 `SnsDeletedTag`，复用 `SnsTag` 的 hook 框架**（加一个外部回调，避免重复挂 `onBindViewHolder`）；
- (c) 直接在 `SnsTag.apply` 里加一段「顺带改时间文案」。

⚠️ (b)(c) 都涉及「同一方法挂两个 hook / 加回调」⇒ **Hook 链路改动，Pro 判**。

### 3.3 时间 `TextView` 还没定位到

`SnsTag` 只做「往 itemView 末尾加 View」，**从没碰过时间 TextView**，所以它没留下任何线索。
⇒ `docs/_r30_lane1_time_label.md`（并行取证中）要补的正是这一环：
资源 id / 字段名 / 被 `setText` 的指令地址 + **该 ViewHolder 是否被相册页复用**。

---

## 4. 主会话本轮已完成的纯逻辑层（不依赖取证）

| 文件 | 内容 | 验证 |
|---|---|---|
| `feature/SnsDeletedTagLogic.kt`（新建） | `MARK` 常量、`taggedIds()`（过滤 snsId=0 + 去重）、`isTagged()`、**`appendTag()` 幂等追加** | — |
| `test/.../SnsDeletedTagLogicTest.kt`（新建，12 例） | 含「连调两次 == 调一次」「尾空格不产生双空格」「空文案无前导空格」「snsId=0 永不标」 | **12/12 通过**（`tools/_junit.sh`，0.056s） |
| `.tmp/mutate_tag.sh`（新建） | 5 个变异点 | **5/5 全红**（基线 OK:12） |

**为什么要幂等**：`appendTag` 会被重复调用 —— ① hook 重入（我们 `setText` 又触发 `setText` 钩子）；
② ViewHolder 复用后残留。判据错了的界面表现是 `[删除已拦截] [删除已拦截]` 或普通动态被标成已删，
且**只在滚动后出现**，真机极难复现。
