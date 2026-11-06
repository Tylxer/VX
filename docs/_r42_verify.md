# R42 拆除后装机验证（2026-11-06 19:06~19:13）

> 目的：`bed099c`（删除乙方案全部实现，朋友圈防撤回只留甲）装机后，确认
> ① 甲依赖的 6 个钩子仍能挂上；② 用户界面开关能**独立**驱动甲；③ 拆除没有回归主功能。
> 对象：设备 `467cf5bc`（PJD110），模块 `com.lx.wxgroup` 0.2.8，
> 包时间 `2026-11-06 19:06:54`，产物 `app-debug.apk` 3,011,226 字节。

## 1. 六钩子注册（一次装机、三次冷启，每次一致）

```
19:07:14.478 SNS installed hooks=delete,execSQL,updateWithOnConflict,updateWithOnConflict.before,insertWithOnConflict,insert blockEnabled=true
19:09:19.998 SNS installed hooks=delete,execSQL,updateWithOnConflict,updateWithOnConflict.before,insertWithOnConflict,insert blockEnabled=true
19:09:27.813 SNS installed hooks=...(同上) blockEnabled=true
19:11:17.811 SNS installed hooks=...(同上) blockEnabled=true
```

6 项与拆除前的钩子清单**逐字一致**：`delete` / `execSQL` / `updateWithOnConflict`(after) /
`updateWithOnConflict.before` / `insertWithOnConflict` / `insert`。
⇒ 拆除只删了钩子**内部**的补位/归档逻辑，没有碰到钩子注册本身。

## 2. 主开关独立驱动甲（正向 + 反向对照）

拆除前，`blockEnabled` 读的是调试键 `antirevoke.sns_block`（默认 false，需手动 `snsg block on`）。
拆除后改为 `Kv.getBool(ANTIREVOKE_SNS) || Kv.getBool(ANTIREVOKE_SNS_BLOCK)`。

| 步 | `antirevoke.sns`（界面开关） | `antirevoke.sns_block`（调试覆盖） | 冷启后 `blockEnabled` |
|---|---|---|---|
| 装机初态 | `true` | `true`（上一轮实验残留） | `true` ← **无法区分来源，故补下面两组** |
| 正向 | `true` | `false`（`snsg block off` 写入并回读） | **`true`** ✅ |
| 反向对照 | `false`（kvset 写入并回读） | `false` | **`false`** ✅ |
| 恢复 | `true`（回读确认） | `false` | **`true`** ✅ |

- 正向 = 关掉调试覆盖后主开关仍能开启甲 ⇒ **用户在界面上开「防止朋友圈动态撤回」即可生效**，不必再走调试键。
- 反向 = 两个都关后 `blockEnabled=false` ⇒ 排除「字段硬编码 true / 配置根本没被读」的假象。
- **反向态下 6 个钩子照样注册**（`hooks=` 列表不变），只是不拦截。⇒ 关开关是「不拦」不是「不挂」，
  统计与旁证能力不受开关影响，不会出现「关了开关就看不到有没有人删」的盲区。

## 3. 旧子命令与旧表已彻底清除

```
19:12:08 SNSG unknown sub=srcfix
19:12:08 SNSG unknown sub=win
19:12:08 SNSG unknown sub=winreset
19:12:08 SNSG unknown sub=snstag
19:12:08 SNSG unknown sub=sqlspy
19:12:11 SQLX err SQLiteException: no such table: sns_info_source_fix
```

`sqlspy`/`win`/`srcfix`/`snstag` 全部落到 `SNSG unknown`，补位台账表已不存在（那条 SQLiteException
是**我主动查这张表**造成的，不是模块运行时抛错）。保留的 `stat|block|blockstat|trace` 与 `op=sql` 均可用。

## 4. 主功能（好友分组）未回归

```
19:12:11 K7 perf n=3018 snaps=77ms total=82ms path=light scrolling=false
19:12:33 K7 perf n=3018 snaps=36ms total=39ms path=full
19:12:34 K7 perf n=3018 snaps=31ms total=33ms path=full
RRV apply on=false total=416 ok=416 fail=0 skip=0
```

- `n=3018` 连续三轮稳定 ⇒ 分组视图有 3018 条数据参与布局，分组库没有因删表而受损。
- `DB_VERSION` 未变（仍 9），被删的两段迁移块本来就只为建 `sns_archive` / `sns_visible_src`，
  不会触碰 `T_GROUP` / `T_MEMBER` / `T_FRIEND`。

## 5. 计数基线（干净，可用于下一轮实测对照）

```
BLOCK stat hit=0 blocked=0 nonZero=0 skipOff=0 last=-
BLOCK skip empty=0 noApi=0 noId=0 parseFail=0 afterOk=0 afterFail=0
BLOCK upd seen=0 zero=0 nosrc=0 | ins seen=0 zero=0 nz=0 nosrc=0
BLOCK w2 delSns=1 delRow=1 sqlSns=6 sqlSrc=0 sqlClear=0 sqlOwn=0 sqlUnmatched=0
```

`delSns=1 / sqlSns=6` 是冷启必现的那条假 id 删除（`snsId=9223372036854775807`）与 6 条含
`SnsInfo` 的 execSQL，与拆除前的旧包**逐字相同** ⇒ 拆除没有改变冷启阶段的写库行为。
`sqlSrc=0 / sqlClear=0` 再次印证：不存在全表清位 SQL（R27 定案），路线甲唯一的漏口是
**按好友批量清位的 raw SQL**（上一次实测见的 `& -9`），当前版本未出现 `&-3` 形态。

## 6. 顺带发现的既有缺陷（非本次引入，不阻塞）

```
19:10:29 AvatarLoader 初始化失败（头像退回首字兜底）: ClassNotFoundException
19:11:19 AvatarLoader 初始化失败（头像退回首字兜底）: ClassNotFoundException
19:11:26 AvatarLoader 初始化失败（头像退回首字兜底）: ClassNotFoundException
```

- 位置：`core/AvatarLoader.kt:25` `CLS_SUBCORE = "com.tencent.mm.modelavatar.a1"`，`Class.forName` 失败。
- **与本次拆除无关**的证据：`git log -- AvatarLoader.kt` 最后一次改动是 `f231af2`（R24–R29），
  本次 `bed099c` 的 31 个文件里没有任何 avatar 文件。
- 影响：分组行头像退回首字兜底，功能可用但无真头像。`dead` 标志一旦置位本进程内不再重试。
- 结论：这是一条**独立的既存问题**，根因大概率是微信 8.0.48 把该类改名/移动了，
  需要单独取证（在微信 dex 里找 `modelavatar` 包下的新宿主类）。列入待办，不在本轮修。

## 7. 结论

| 检查项 | 结果 |
|---|---|
| 6 个钩子注册 | ✅ 与拆除前逐字一致，三次冷启稳定 |
| 界面开关独立驱动甲 | ✅ 正反双向对照成立 |
| 关开关不掉钩子 | ✅ 只停拦截，统计与旁证仍在 |
| 旧子命令清理 | ✅ 5 个全部 unknown |
| 补位台账表删除 | ✅ no such table |
| 分组主功能 | ✅ n=3018 稳定，无异常 |
| 冷启写库行为 | ✅ 与旧包一致（delSns=1 / sqlSns=6） |

**拆除可以判定交付。** 设备现处于「甲已生效、开关已恢复 true、调试覆盖 false、计数干净」的可用状态。
