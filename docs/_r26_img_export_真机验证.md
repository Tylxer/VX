# R26 · 朋友圈图片导出 —— 真机验证记录（2026-10-26 18:55–19:21）

> 设备 PJD110 / Android 16 / 微信 8.0.48(2588)。模块装包 `lastUpdateTime=19:17:35`。
> 判据一律对着**用户能看见的文件**写，不用内部计数器当判据。

## 结论：图片导出**通了**

真机实测 5 张图落盘到公共目录，且与微信缓存的源文件**md5 完全一致**。

## 一、代码改动（本轮）

| 文件 | 改动 |
|---|---|
| `data/Keys.kt` | 新增 `MOMENTS_COPY_IMAGES="moments.copy_images"` + `PrefSpec`（SWITCH，默认 false） |
| `feature/MomentsExport.kt` | 删 `const COPY_IMAGES`，改读配置；新增回填轮 `backfillImages`、`imgAlready` 计数、`IMG_LOG_MAX=200` 明细限行、`MAX_BACKFILL_PER_RUN=200`；`stat()` 加 `images=` / `imgHint=cache-only`；新增探针 `imgfill`、`estat` |
| `feature/MomentsLogic.kt` | 新增纯判据 `imgDone(exists, length)` |
| `feature/SnsGuard.kt` | **`listCurrentSns` 去掉 `(localFlag & 16) != 0` 过滤**（根因修复，见 §三） |
| `test/MomentsLogicTest.kt` | 新增 `imgDoneRequiresNonEmptyFile` |

单测 53 条全绿；变异（`>0L` → `>=0L`）被新用例抓住，EXIT=1。

## 二、图片导出判据（全部 PASS）

```
19:19:02 MOMENT sns index built files=64 dupSkipped=0 hashDirs=1 listFail=0
19:19:03 MOMENT img copied snsId=-3405880812362394850 #0 dst=img_-3405880812362394850_0.jpg
19:19:03 MOMENT img copied snsId=-3405884186011430303 #0 ...
19:19:03 MOMENT img copied snsId=-3405919387112631532 #0 ...
19:19:04 MOMENT img copied snsId=-3405920838192590232 #0 ...
19:19:04 MOMENT img copied snsId=-3405947194605235605 #0 ...
19:19:06 MOMENT img backfill rows=200 noTxt=190 noMedia=2 withIds=8 filled=0 copied=0 already=5 miss=3
19:19:06 MOMENT export done rows=500 written=26 skipped=0 listMiss=474 err=0
```

磁盘实景（`/sdcard/Documents/WXGroup/moments/`）：

| size | magic | 与同目录 txt 配对 | 路径 |
|---|---|---|---|
| 9016 | JPEG | ✅ | 星光龙阳洋-13958256621(AA889900000)/2026-10-26-1710/ |
| 9434 | JPEG | ✅ | 河证券无锡梁溪路营业部朱霖芸(zhulinyun)/2026-10-26-1830/ |
| 19951 | JPEG | ✅ | 星光龙阳洋-13958256621(AA889900000)/2026-10-26-1618/ |
| 20047 | JPEG | ✅ | 星光龙阳洋-13958256621(AA889900000)/2026-10-26-1823/ |
| 90250 | JPEG | ✅ | dra(drakeet)/2026-10-26-1713/ |

- **magic 5/5 = `ffd8ff`**（JPEG），不是空壳。
- **配对 5/5**：`img_<snsId>_0.jpg` 的 snsId 与同目录 `<snsId>.txt` 严格一致。
- **0 字节文件数 = 0**。

### 反向对照（最硬的一条）：字节级比对

```
导出图  af01cf64bbc5f6f7db70862451f274c2  .../dra(drakeet)/2026-10-26-1713/img_-3405919387112631532_0.jpg
缓存源  af01cf64bbc5f6f7db70862451f274c2  .../cache/22139c91134dbeddc19d20be980dee7f/sns/1/c/snst_15040824687272006429
```

⇒ **md5 完全一致**，证明导出的图就是微信 `sns/` 缓存里的那一份字节，
不是别的来源（不是 URL 下载、不是历史残留）。

### 回填轮正确短路

主循环刚拷完的 5 张，回填轮再遇到时 `already=5`、`copied=0` —— 说明
`imgDone(exists, length>0)` 的短路生效，**不会重复重写**。

`miss=3` 是那 3 个 mediaId 的图确实不在缓存里（缓存只有 1 张原图 + 一批压缩/缩略），属预期。

## 三、真正的卡点：`localFlag & 16` 不是可见位（本轮最重要的发现）

改代码后第一次真机验证（18:55–19:00）**图片一张也拷不出来**，因为：
导出只处理 7 条旧动态（最新 2026-10-16），而缓存里的图属于**今天的新动态**，两头对不上。

根因（全文见 `docs/_r26_bit16_rootcause.md`，现场数据见 `docs/_r26_img_blocked_facts.md`）：

- `SnsGuard.listCurrentSns` 原来用 `where (localFlag & 16) != 0` 筛「当前可见动态」；
- 8.0.48 实测最新 50 行 `flag` 分布 `{2:45, 4098:2, 5122:3}` —— **含 bit16 的行数为 0**；
- 而同一时刻朋友圈界面正常显示「8 分钟前」的新动态；
- 已导出的那 7 条恰恰都是 `localFlag=50`（32|16|2），所以**只有它们进得来**。

⇒ 旧结论「时间线白名单里每个值都置 bit4 ⇒ bit4 是可见位」把「删除会清这一位」
偷换成了「可见必须置这一位」，第一步从未在 8.0.48 上验证过。

**修法**：`listCurrentSns` 去掉 flag 过滤，只保留 `(createTime, snsId)` 键集分页。
取舍理由：这是防撤回的主数据源，**漏采 = 动态被删后永久找不回来**，比多带脏数据严重一个量级；
多导出的 txt 是加法、可逆（`renderText` 带 `来源=sourceType`，事后可筛）。
广告的 `sourceType` 映射未定死（实测出现 0/2/6/8/10/130），**不做黑名单**，避免再次误杀。

修复前后对比：

| | 修复前 | 修复后 |
|---|---|---|
| `rows` | 7 | 500（本轮上限） |
| `written` | 0 | 26 |
| `img copied` | 0 | **5** |

⛔ `VISIBLE_BIT=16` 常量**保留**，仍用于「拦下删除后补回可见位」（`restoreVisibility`），
那条路径语义是「微信自己清过这一位，我们照原样补回去」，与查询筛选无关。

## 四、⚠️ 必须记下的副作用与行为变化

1. **修复后导出会一次性补导大量历史动态**：修复前 `written` 恒 0，修复后单轮可写 26 条（上限 500/轮）。
   验证时把 `moments.export_list` 临时收窄到 5 位作者以控制规模，**已恢复成测试前的
   `["wxid_xij8e08lqr3e22"]`**。要不要放宽名单由用户定。
2. **`op=snsg sub=show` 不是只读探针**：它调 `restoreVisibility()`，把查到的行 `localFlag`
   从 `2` 写成 `18`（**写微信库**）。本轮误用，对 2 条动态产生了该副作用。
   改动方向是「补上它认为的可见位」，对显示无破坏，但没有探针能改回 `2`。
   ⚠️ 这条要进速查：`show` 名字像诊断，实际是写操作。
3. `op=moments sub=stat` 在接收端被 **MomentsAuto** 先截走，走不到导出模块的 `stat()`。
   新增的 `images=` / `imgHint=` 只能用 `sub=estat` 看（已加别名）。

## 五、遗留（未做，等用户定）

- `SOURCE_TYPE` 广告黑名单：需要更多样本把 `sourceType` 值映射定死，属 Phase 2。
- 回填无跨轮进度记忆：每轮从最新重扫，队头持续 miss 的行会占配额（见 `_r26_img_review.md` 中危项）。
- 回填与主循环对同一条动态会各自尝试一次，诊断四态计数略有重复（不影响文件）。
- `SnsCdnImage.EXPORT_DIR` 仍指旧私有目录（CDN 通路已判死，未动）。
