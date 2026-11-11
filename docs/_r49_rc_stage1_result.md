# R49 · R-C「复用写时拦截」可行性验证 · 阶段一结论（2026-11-08 23:1x）

> 用户指令：**「只需验证 R-C 复用写时拦截该技术路线是否可以实现，不考虑 R-A 和 R-B，
> 设计尽可能全面的探针去验证」**。
> ⇒ 本轮只做 R-C 可行性判定；R-A/R-B 排除；被撤回图片的落地实现暂缓（取证已留档）。
> ⇒ 本文件是**阶段一（0 装包、纯只读）**的事实与结论；阶段二尚未执行。

---

## 一、探针设计交付物（「尽可能全面」已落盘）

| 文件 | 内容 | 行数 |
|---|---|---|
| `docs/_r49_3day_forensics.md` | R-C 定义、Q3 四条只读 SQL 判据、Q4 路线对照表、Q6 三档边界、Q7 证据分级 | 278 |
| `docs/_r49_rc_probe_design.md` | 探针矩阵 7 组 / 判据树 6 前提 / 三态判定表 9 条 / 三档边界 / 盲区表 | 521 |
| `docs/_r49_rc_probe_inventory.md` | 现有探针与广播入口全清单、R42 六钩子、能力缺口、改动面 | 361 |
| `docs/_r49_rc_probe_verdict.md` | 裁决 D/E 分歧、V2 假阴性、最终探针总表 P-01~P-25、V8 真机步骤 | 498 |
| `docs/_r49_rc_lifeline_review.md` | ⭐ 复核 R-C 生死假设并**翻转**、`& -9` WHERE 原文、最小验证路径 | — |

裁决链（三次独立视角，逐层收紧）：

1. **分歧**：lane D 判「现成 op 足够、0 装包」；lane E 判「`blockstat` 无行级字段、必须新增
   `SnsWriteProbe` + 装包」。
2. **裁决 F**：选 (c) 分两阶段。抓到 lane D 的**结构性假阴性** ——
   `SnsSqlRewrite.kt:46` 把 `& -9` 改写为 `& -1`（no-op），`execSQL` 照常执行、
   命中集合与 `changes()` 不变 ⇒ 「进主页前后 `op=sql` 快照无变化」**必然**被误读成
   「没有写事件」。lane E 方向对，但「无 createTime」是事实错误（实际有 `Event.ct`），
   且漏了 T=false 可 0 装包收口。
3. **复核 G（本轮最关键）**：⚠️ **R-C 的生死假设本身写错了**，见下 §三。

---

## 二、阶段一真机实测（0 装包，全只读）

设备 `467cf5bc`，微信已拉到前台；`op=snsg sub=stat`：
`SNS stat blockEnabled=true sqlBlock=true albumFix=true snsDb=true`（句柄已捕获）。

⛔ **工具坑（本轮踩到，务必记）**：`python tools/dev.py probe sql --q "…含括号 SQL…"`
在 Windows 下被二次解析打坏，报 `/system/bin/sh: syntax error: unexpected '('`
且伪装成「广播未投递」。**可靠通道**是把整条 `am broadcast` 作为单个参数传给
`adb.exe shell`、SQL 值用设备侧单引号包住：
`adb shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op sql --es q 'pragma table_info(SnsInfo)' -p com.tencent.mm"`。
每条测前 `op=logclear`、测后 `cat wxgroup_dbg.txt | grep SQLX` 回读复核。

| 判据 | 实测 | 结论 |
|---|---|---|
| `pragma table_info(SnsInfo)` | **18 列**，含 `createTime INTEGER`、`content/attrBuf/postBuf BLOB`、`sourceType`、`userName`、`stringSeq` | 列名与旧文档一致 |
| `createTime` 单位 | **10 位 Unix 秒**；`min=1337694993`(≈2012)、`max=1794144038`(≈2026-11-08) | 单位已校准，`T0` 可直接算 |
| `select count(*) from SnsInfo` | **262,822 行** | 本地是长期历史库，不是三天滚动窗口 |
| `T0 = 1794144038 − 3×86400 = 1793884838` | **older3d = 262,154 行**（99.75%） | 三天外行大量存在 |
| 同一批行的 BLOB 完整性 | `length(content)>0` = **262,154**；`length(attrBuf)>0` = **262,154** | ⭐ 全部完整，无空 BLOB |
| 最新 3 条 `hex(substr(content,1,16))` | `0A14…`（protobuf 头），`sourceType` = 10 / 10 / 2 | 内容非截断、可解析 |
| 三天外行的位状态 | `bit1(&2)!=0` = **205,406**；`bit3(&8)!=0` = **60,251** | 相册位仍有 6 万+ 行，正是 `& -9` 的潜在命中面 |
| 按 `userName` 分组 | top1 好友 10,094 条；top20 均 1,600+ 条 | 行分散在大量好友，非单例 |

⇒ **Q3 前置（触发条件 T）= true，且是压倒性的**：
「三天外动态本地行还在 + `content`/`attrBuf` 完整」成立 ⇒
「无米之炊」这条一票否决被排除，R-A/R-B/R-C 的共性前提都活。

---

## 三、⭐ 生死假设翻转（独立复核 lane G 的结论）

既有三份报告都把 R-C 写成：

> 服务端不下发三天外 SnsObject ⇒ 微信本地**不会**对这些行写 `update`/`execSQL`
> ⇒ `snsBlockGuard` 的 before **空转** ⇒ R-C 证伪。

**这条因果链断裂**。真机已证的 `& -9` 清位 SQL（`SnsSqlRewrite.kt:10-13`、
`_r42_bit_paths.md:192-198`、`docs/conclusions/moments.md:24-25`）：

```sql
UPDATE SnsInfo SET sourceType = sourceType & -9
WHERE userName = '<好友>' AND sourceType IN (8,264,10,266,12,268,14,270,…,222,478)
  AND snsId > A [AND snsId < B]
```

- WHERE **没有 `createTime`、没有任何时间窗**；只有 `userName`、64 个全含 bit3 的
  `sourceType` 白名单（不含 0/2）、以及 `snsId` 分页区间。
- 每进一次好友主页发 **6 条**，语义本身就是「**服务端这次没返回、但本地还有 bit3 ⇒ 清掉 bit3**」
  —— 它恰恰是「服务端不下发」在客户端的落地动作，而不是「不下发 ⇒ 不写」。
- ⇒ `execSQL` before **不空转**，R-C 的拦截面是真实存在的；
  且三天外行里 **60,251 条带 bit3**，落在白名单的潜在命中集合内。

⛔ **但不能据此判「R-C 可实现」**，两道未跨过的坎：

1. **行级覆盖未证**：`snsId > A [and < B]` 的区间是按什么分页、是否真的覆盖这
   60,251 条三天外行，静态证据不足。现成 `BLOCK w2 sql-clear9` 日志只给区间弱线索
   （去重封顶 60、单行截 1500 字符、LogX 5000 行环形、异步时序），判不了行级命中。
2. **「拦住写」≠「界面显示」**：首屏由网络响应重建
   （`docs/_r42_wechatmagician.md:40-41`），保住 `sourceType` 最多让**翻页查表**带出，
   首屏仍可能空；且库位与服务端不一致是持久状态。

⇒ **R-C 当前判定：`不空转，但未证明可行`**（中间档），不是「证伪」也不是「可实现」。

---

## 四、还差什么才能给二元结论

| 缺口 | 最小动作 | 是否需装包 | 是否需用户配合 |
|---|---|---|---|
| A1 行级命中：6 条 `& -9` 展开后的 `snsId` 区间 ∩ 三天外行集合是否非空 | 阶段二 `SnsWriteProbe`：写事件按 `snsId`/`rangeKey` 展开 + join `createTime` | **1 次** | 否（可自动进主页） |
| A2 拦住写之后界面是否真显示三天外动态 | 翻页 / 首屏 `dev.py ui` 条数对照 | 同上 | 否 |
| A3 三档边界：返回（切页/下拉/后台同步/冷启）、复位（关两个开关 / 对方改回全可见 / 清缓存） | 逐档 `op=logclear` + 重跑 | 同上 | **是** |
| A4 阳性/阴性对照 | 请一位好友删一条动态；另一位保持全部可见 | 同上 | **是** |

- 阶段二改动面（裁决 V7）：新增 `SnsWriteProbe` + 扩写 `SnsGuard.kt` / `SnsBlockProbe.kt` /
  `EntryReceiver.kt`，**全部只读观测、不写微信库**；按 `CLAUDE.md` §四① 应派 ≥2 个实现 lane，
  改动攒齐 **一次 `adb install`**，装包前须把 `git diff` 交第二视角复核。
- 阶段二观测重点按 lane G 修订：从「有没有写事件」改为
  「**逐条 WHERE 展开 → `hitS` 行级命中 → 拦住后翻页是否显示**」。
- 裁决 V2（no-op 改写导致快照必然假阴性）**保留**；V5 分两阶段框架**保留**，
  但阶段二判据按上一条修订。

---

## 五、本轮边界（如实标注）

- ✅ 阶段一全部为**只读**：零源码改动、零配置改动、零微信库写、零装包、零 `kvset`。
- ✅ 微信被拉到前台（`am start -n com.tencent.mm/.ui.LauncherUI`）；未改任何开关。
- ⛔ **未跑**任何需要好友配合的场景（设三天可见 / 删动态 / 改回全可见 / 清缓存）。
- ⛔ **未给** R-C 最终二元结论 —— 按判据纪律，行级命中与界面显示都还没观测，
  不把「钩子不空转」粉饰成「可实现」。
- ⛔ 被撤回图片方向：三份取证报告（`_r49_revokeimg_forensics.md` /
  `_r49_revokeimg_reference.md`）+ 网上/微X对照已落盘，**实现暂缓**，不在本轮范围。
- ⛔ 好友一律未以裸 wxid 汇报；阶段二需要的 `<昵称A>/<昵称B>/<昵称C>` 由用户指定。
