# R26 · 图片导出受阻的现场事实（真机 2026-10-26 18:55–19:06）

> 设备 PJD110 / Android 16 / 微信 8.0.48(2588) / 模块 `com.lx.wxgroup` 装包 `lastUpdateTime=18:56:50`。
> 本文件只记**实测数据**，不下结论；根因分析另派。

## 1. 图片导出代码已就绪（本轮完成）

| 项 | 状态 |
|---|---|
| 门控 `const COPY_IMAGES` → 配置键 `moments.copy_images` | 完成，默认 `false` |
| 回填轮 `backfillImages`（txt 已存在但图缺时补） | 完成，上限 200 条/轮 |
| 「图已存在且非空」短路 `MomentsLogic.imgDone` | 完成，0 字节残留会重拷 |
| `img mid` 明细日志限行 `IMG_LOG_MAX=200` | 完成 |
| 探针 `sub=imgfill` / `sub=estat` | 完成（`estat` 是别名，见 §5） |
| 单测 | 53 条全绿；变异 `>0L`→`>=0L` 被 `imgDoneRequiresNonEmptyFile` 抓住 |
| 交叉复核 | PASS（`docs/_r26_img_review.md`） |

## 2. 真机：开关与配置都生效

```
18:57:23 MOMENT export stat enabled=true images=true imgHint=cache-only running=false ... imgCopied=0 imgAlready=0
18:57:25 MOMENT path root=/storage/emulated/0/Documents/WXGroup/moments exists=true dir=true writable=true
18:55:21 PROBE kv moments.copy_images = true
```
导出目录里 `img_*` 文件数 = **0**（基线）。

## 3. 真机：滚朋友圈后缓存确实长出来了

滚屏前 / 后（`cache/<hash32>/sns/**`）：

| | 总文件 | `snsb_`(原图) | `snsu_`(压缩) | `snst_`(缩略) |
|---|---|---|---|---|
| 滚屏前 | 41 | 1 | 13 | 27 |
| 滚屏后（9 次 swipe） | 88 | 1 | 23 | 40 |

⇒ 滚动确实把图写进了缓存，**图片源是活的**。

## 4. ⛔ 卡点：导出永远只看到 7 条**旧**动态

```
19:00:14 MOMENT sns index built files=64 dupSkipped=0 hashDirs=1 listFail=0
19:00:14 MOMENT img backfill rows=7 noTxt=0 noMedia=3 withIds=4 filled=0 copied=0 already=0 miss=4 readFail=0 rootMiss=0
19:00:14 MOMENT export done rows=7 written=0 skipped=7 listMiss=0 err=0
```

只有 7 行、且 7 行全都已导出过 ⇒ 没有任何新动态进入「写 txt + 拷图」路径 ⇒ 回填也只能对这 7 条徒劳试一次（`miss=4`）。

## 5. 根因候选：`localFlag & 16` 这个过滤条件

`SnsGuard.listCurrentSns` 的 SQL（L535）：

```
select snsId,userName,createTime,type,sourceType,localFlag,content
from SnsInfo where (localFlag & 16) != 0 and (createTime < ? or (createTime = ? and snsId < ?))
order by createTime desc, snsId desc limit N
```

`SnsGuard.kt` L286-290 的依据：

> 时间线查询 `localFlag in (16,2064,18,2066,24,2072,…)` 里**每个值都置了 bit4(16)**。
> 微信删动态时先清掉这一位（实测落成 2），拦完 DELETE 必须置回去，否则界面不显示。
> `VISIBLE_BIT = 16`

### 实测数据（`op=snsg sub=dump n=50`，即最新 50 行）

```
flag 分布: {2: 45, 4098: 2, 5122: 3}
含 bit16 的行数 = 0
时间跨度: 最新 t=1793012217 最旧 t=1793000203（约 3.3 小时）
```

⇒ **最新的 50 条动态，一条都没有 bit16。**

### 已导出的那 7 条长什么样（`op=snsg sub=one`）

```
snsId=-3499646661242318257 user=wxid_myzsbcu t=1781832858 type=1  localFlag=50
snsId=-3424074357631413578 user=wxid_myzsbcu t=1790841780 type=3  localFlag=50
snsId=-3412979636140764498 user=wxid_lx9vwt2 t=1792164377 type=28 localFlag=50
```

⇒ `50 = 32 | 16 | 2`，**都有 bit16**，且都是 6 月～10-16 的旧动态。

### 界面侧反证

同机打开朋友圈（`ImproveSnsTimelineUI`），UI dump 里出现 `8分钟前` 的动态（作者「王成宇@迈富时」），
即 **20 分钟内的新动态用户是看得见的**，而它们在库里是 `flag=2`。

⛔ 注意：`op=snsg sub=show` **不是只读探针** —— 它调 `restoreVisibility()`，
把查到的行 `localFlag` 从 `2` 改成 `18`（写入 DB）。
本轮对 2 条动态（`snsId=-3405867444757851470`、`-3405874486139631`）已发生该副作用：

```
19:04:26 SNS visibility restored snsId=-3405867444757851470 2 -> 18 rows=1
19:04:28 SNS visibility restored snsId=-3405876080446139631 2 -> 18 rows=1
```

本次只影响这 2 行，改动方向是「补上认为的可见位」，对显示无破坏；但没有探针可以改回 `2`。

## 6. 影响面（比图片导出大）

- 文本导出同样只扫到那 7 条旧动态 ⇒ **新动态从来没被导出过**（最新一条是 2026-10-16）。
- 图片导出依赖「新动态进主循环」或「老动态的图还在缓存」，两头都不成立 ⇒ 功能无法验收。

## 7. 待答问题（交给根因分析）

1. 8.0.48 里 `localFlag` 的 bit4(16) 到底是什么语义？（旧结论是「可见」，实测不支持）
2. 若 bit16 不是可见位，`50`（32|16|2）与 `2`、`4098`(4096|2)、`5122`(4096|1024|2) 各是什么组合？
3. 导出源应该用什么**判据**筛「用户当前看得见的动态」？候选：去掉过滤 / 用 bit1(2) / 用 `type` 排除 / 用 `createTime` 窗口。
4. 去掉过滤会不会把广告、非时间线内容导进来（`sourceType` 有 0/2/6/8/10/130 等值）？
5. 现有 7 条已导出动态在改判据后会不会重复导出或行为回退？
