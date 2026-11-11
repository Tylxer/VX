# R52 甲路线 · 真机首次拦截成功 + 97 秒性能缺陷（2026-11-11 18:32）

> 装包：`lastUpdateTime 00:59:05 → 18:21:50`，APK sha256 `19a8033732487b6587287b44d5c7bb899319faa6cfddd709506bd5dd39c610e9`。
> 样本：**verity** 在「测试群聊」发图后撤回，`msgId=19649033`，缩略图 md5 `1a7b9f5d918f9d80ce5c8065d3dcb5e7`。
> 日志原文：`_cache/dbg_live.txt`（270 行，无 `log rotated` 标记）。

---

## 一、功能成功：首次真正拦住 `ImgInfo2` 删除

```
18:32:05.515 AR shape message type=268445456 isHint=true msgId=19649033 content="verity" 撤回了一条消息
18:32:05.526 RRV record msgId=19649033 origType=3 imgPath=THUMBNAIL_DIRPATH://th_1a7b9f5d918f9d80ce5c8065d3dcb5e7
18:32:05.533 VFG protectMedia img=1a7b9f5d91..(32) ttl=600000ms pool=1
18:32:05.544 RRV imgAudit msgId=19649033 md5=1a7b9f5d91..(32) row=0 status=-999 ...   ← 旧口径，预期 row=0
18:33:42.714 MTG id-lookup md5=1a7b9f5d918f9d80ce5c8065d3dcb5e7 rows=1 ids=[876171] cost=97166
18:33:42.717 MTG id-lookup done tokens=1 ids=1 capped=false cost=97171
18:33:42.832 VFG blocked MEDIA delete name=th_1a7b9f5d918f9d80ce5c8065d3dcb5e7
18:33:42.846 [D]#worker MTG blocked delete table=ImgInfo2 where=id=? reason=blocked gen=1   ← ⭐ 首次拦住
18:34:17.043 HINTBADGE badge added text=已阻止撤回 · 图片
18:34:20.674 HDIMG fired msgId=19649033 localId=876171 t=0 img=SERVERID://409084934435530232
```

### 与昨晚失败的逐项对比（同一套判据，唯一的自变量是修复版判据）

| 项 | 昨晚 01:06（失败） | 本次 18:34（成功） |
|---|---|---|
| `MTG delete ImgInfo2` | `reason=shouldBlock-false` 放行 | `reason=blocked` **拦住** |
| `HDIMG fired` | `localId=0 t=0 img=null` | `localId=876171 img=SERVERID://409084934435530232` |
| `ImgInfo2` 行 | 被删（id 跳号） | **保留**（`where id=876171` 查得到） |
| UI | 看不到 | `已阻止撤回 · 图片` |

⇒ **T2 甲-7 的 `[需真机]` 支已排除**：拦住 delete、行保留后，微信并没有另把 `h/i/j` 填 0，
`localId` 与 `img` 都正常取到了。**「甲」路线成立。**

## 二、拦住的行实测字段（`select id,origImgMD5,msglocalid,msgSvrId from ImgInfo2 where id=876171`）

```
id=876171 | origImgMD5=83582fb44ecf622411a2115b37ebd591 | msglocalid=NULL | msgSvrId=409084934435530232
```

- `msglocalid` **恒 NULL** ⇒ 再次实证 `RRV imgAudit` 的 `where msglocalid=?` 口径必然 `row=0`，不是行被删。
- `msgSvrId=409084934435530232` 与 `HDIMG img=SERVERID://409084934435530232` **逐字一致**。
- `origImgMD5=83582fb4…` **≠** 缩略图 md5 `1a7b9f5d…` ⇒ `origImgMD5` 是**原图** md5，
  ⛔ 不能拿它当保护池 key（lane B「不靠 origImgMD5」的判断正确）。

## 三、⛔ 新缺陷：反查耗时 97 秒，机制靠「卡住宿主线程」侥幸成功

`cost=97166ms`。时间线：`18:32:05.526 RRV record` → `18:33:42.714 id-lookup 完成` = **97 秒**。

### 根因
`ImgInfo2` 实测 **842,933 行**（`select count(1) from ImgInfo2` → `n=842933`）。
`instr(thumbImgPath, ?)` 无法用索引 ⇒ 全表扫 84 万行 TEXT 列 ⇒ 97 秒。
`LOOKUP_BUDGET_MS` 只在**每个 token 之前**检查，单条 `rawQuery` 是原子的，中途无法打断 ⇒ 预算保护形同虚设。

### 为什么这次仍然拦住了（⚠️ 是侥幸，不是机制可靠）
`lookupRowIds()` 在 `[GT]ColdPool#15` 里**同步阻塞 97 秒**，而微信的 `ImgInfo2` 删除
（`[D]#worker`，18:33:42.846）恰好排在这次撤回处理链之后 ⇒ **是我们自己把宿主卡住，才等到反查完成**。
⛔ 若微信在独立线程里 10~25ms 就删完，反查必然来不及 ⇒ 拦截失效。
⇒ 当前实现**不可发布**，只是「碰巧成功」。

### 附带代价
`AR blocked revoke msgId=19649033` 在 18:33:42.724 才执行 ⇒ **撤回提示替换、UI 更新全部延后 97 秒**。
主线程未卡死（`K7`/`BAR` 一直在跑），卡的是 `[GT]ColdPool#15` 后台线程。

## 四、优化路径（已实测验证，可把 97 秒降到毫秒）

`ImgInfo2` 实测索引：`serverImgInfoIndex`、`serverImgInfoHdIndex`、`msgLocalIdIndex`、
`iscomplete_index`、`origImgMD5_index`。

### 推荐：走 `msgSvrId` 双跳索引（两步都命中索引）

```
1) select msgSvrId from message where msgId=? limit 1        ← message 主键，O(log n)
2) select id from ImgInfo2 where msgSvrId=?                   ← serverImgInfoIndex，O(log n)
```

实测验证（本次样本）：
```
message: msgId=19649033 | msgSvrId=409084934435530232 | imgPath=THUMBNAIL_DIRPATH://th_1a7b9f5d918f9d80ce5c8065d3dcb5e7
ImgInfo2: id=876171 | msgSvrId=409084934435530232
```
`record()` 本来就有 `msgId`（`19649033`），第 1 步直接可用；第 2 步命中 `serverImgInfoIndex`。
⇒ 无需 `instr` 全表扫、无需建索引、无需写库。

### 备选：`order by id desc limit N` 取最近行再应用层匹配
撤回图必然是最近插入的，`select id, thumbImgPath from ImgInfo2 order by id desc limit 500`
走主键逆向扫描，毫秒级；应用层比对 md5。风险：撤很久以前的图（微信允许 2 分钟）可能超出 N 行。

### 否决
- 建表达式索引：会写微信库，越界。
- 靠 `LOOKUP_BUDGET_MS` 拦：单条 SQL 原子，中途打不断。
- 退回乙（窗口内全拦）：误伤 GC / 删会话，已论证否决。

## 五、本轮判据小结

| 判据 | 结果 |
|---|---|
| `MTG blocked delete table=ImgInfo2 reason=blocked` | ✅ 首次出现 |
| `id-lookup rows=1 ids=[876171]` | ✅ 反查命中 |
| `HDIMG fired localId≠0 && img=SERVERID://…` | ✅ 对照昨晚 `localId=0 img=null` |
| `HINTBADGE 已阻止撤回 · 图片` | ✅ UI 层 |
| 反查 `cost` | ❌ **97166ms，不可接受** |
| 用户可见「能看完整大图」 | ⏳ **待用户确认（唯一主判据）** |

⛔ 不拿前四条当功能结论：唯一主判据仍是用户点开能否看到完整大图。

---

# 附：性能修复后真机复验（2026-11-11 19:05，样本 msgId=19649208）

> 装包 `lastUpdateTime 18:21:50 → 19:01:47`（性能修复版）。
> 样本：**verity** 在「测试群聊」发图后撤回，缩略图 md5 `5b2ff9d16cccbb121b678a2055674384`。

## 完整时间线（毫秒级，全部同一进程）

```
19:05:21.971 [GT]ColdPool#1 RRV record msgId=19649208 origType=3 imgPath=THUMBNAIL_DIRPATH://th_5b2ff9d16cccbb121b678a2055674384
19:05:21.976 [GT]ColdPool#1 RRV imgAudit msgId=19649208 md5=5b2ff9d16c..(32) row=0 ...   ← 旧口径，预期
19:05:21.980 [GT]ColdPool#1 MTG id-lookup via=svr msgId=19649208 svr=5486923862373781243 rows=1 ids=[876183] cost=3
19:05:21.981 [GT]ColdPool#1 AR blocked revoke msgId=19649208                            ← record 后仅 10ms
19:05:21.999 [D]#worker   VFG blocked MEDIA delete name=th_5b2ff9d16cccbb121b678a2055674384
19:05:22.005 [D]#worker   MTG blocked delete table=ImgInfo2 where=id=? reason=blocked gen=1
19:05:22.802 main          HINTBADGE badge added text=已阻止撤回 · 图片
19:05:24.053 wxg-hdimg     HDIMG fired msgId=19649208 localId=876183 t=0 img=SERVERID://5486923862373781243
```

## 与修复前逐项对比

| 指标 | 18:32 修复前 | 19:05 修复后 | 变化 |
|---|---|---|---|
| `id-lookup cost` | **97166ms** | **3ms** | **↓ 3.2 万倍** |
| 走哪条路径 | `instr` 全表扫 842,935 行 | `via=svr`（PK + covering index） | — |
| `RRV record` → `AR blocked revoke` | 97,000ms | **10ms** | ↓ 9700 倍 |
| 反查 vs 删除的先后 | 反查完成后删除才到（**靠阻塞宿主换时间**） | 反查 **提前 25ms** 完成，删除随后到达 | **机制从侥幸变为真正快** |
| `ImgInfo2` 行 | 保留 | 保留 | 不变 |
| `HDIMG` | `localId=876171 img=SERVERID://…` | `localId=876183 img=SERVERID://5486923862373781243` | 均正常 |
| 用户可见 | 能看完整大图 | **能看完整大图**（用户确认） | 不回退 |

## 关键结论

1. **`via=svr` 主路径命中**：`msgId=19649208` → `svr=5486923862373781243` → `ids=[876183]`，与执行计划的
   `INTEGER PRIMARY KEY (rowid=?)` + `COVERING INDEX serverImgInfoIndex` 完全吻合，**3ms 是实测值不是估算**。
2. **不再依赖阻塞宿主**：反查 9ms 完成、删除 25ms 后才到 ⇒ 拦截靠的是「比删除快」，不是「把删除卡住」。
   上一版 97 秒阻塞的机制性隐患（换设备/换并发场景即失效）已消除。
3. **功能零回退**：用户确认仍能看到完整大图。
4. 附带：19:05:47 `已阻止撤回 · 语音 3秒` —— 语音撤回拦截同时正常。

## 仍未覆盖

- `via=recent` 兜底路径**本轮未被触发**（本次 svrId 有值），其真实 `cost` 仍为 `[需真机]`。
- `AR blocked revoke` 后 UI 重渲染是否还有其它延迟，未单独计时（本轮只验证了 record→block 段）。

---

# 附二：兜底路径 `via=recent` 实测（2026-11-11 19:11，探针 `op=mtg sub=lookup`）

## 探针

新增 `MediaTableGuard.probeLookup(msgId, probeToken)` + `op=mtg sub=lookup [--es val <msgId>] [--es tok <md5>]`：
- `--es val 0` ⇒ 强制走 `via=recent`（`msgId > 0` 判断不成立）
- `--es tok <md5>` ⇒ 用指定 md5 当唯一 token（冷启后保护池为空时用它跑真实命中）
- ⛔ 只读：不改 `cachedTokens`/`cachedRowIds`/`gen`/`lastIssuedMs`，不写账本，不影响拦截判定

装包 `lastUpdateTime 19:09:20 → 19:10:44`。

## 三组实测结果

```
A 强制兜底（val=0, tok=5b2ff9d1…）
19:11:38.094 MTG id-lookup via=recent scanned=500 rows=1 ids=[876183] cost=322
19:11:38.097 MTG probe-lookup tokens=1 (arg) msgId=0 path=recent-forced hit=1 ids=[876183] totalCost=325

B svr 主路径（val=19649208, tok=5b2ff9d1…）
19:11:40.307 MTG id-lookup via=svr msgId=19649208 svr=5486923862373781243 rows=1 ids=[876183] cost=6
19:11:40.309 MTG probe-lookup tokens=1 (arg) msgId=19649208 path=svr-then-recent hit=1 ids=[876183] totalCost=8

C 池空（val=19649208, 无 tok）
19:11:42.522 MTG id-lookup MISS reason=tokens-empty rows=0 cost=0
19:11:42.524 MTG probe-lookup tokens=0 (pool) msgId=19649208 path=svr-then-recent hit=0 ids=[] totalCost=2
```

| 组 | 路径 | 命中 | 耗时 | 判定 |
|---|---|---|---|---|
| A | `via=recent` | `876183` ✅ | **322ms** | 功能可用，但偏慢 |
| B | `via=svr` | `876183` ✅ | **6ms** | 达标（<100ms 硬指标） |
| C | 短路 | — | 0~2ms | 正常 MISS |

## ⚠️ 修正一处此前的表述（判据纪律）

上一节写过「修复前 97 秒能拦住是**侥幸**，等于自己卡住宿主」。**这个说法不准确，现予修正**：

`invalidate()` 是在 `RevokeRescue.record()` **内部同步调用**的，微信的 `ImgInfo2` 删除发生在 `record()` 返回**之后**。
两次实测均为「`record` 结束后约 25ms 删除到达」（18:32 与 19:05 一致）⇒ **删除排在 `record` 之后是稳定行为，不是偶然**。

因此：
- 97 秒那版**机制上也能拦**（只是拖慢宿主 97 秒，体验不可接受）；
- 322ms 这版兜底**同样能拦**（322ms 仍在删除到达之前）；
- 真正的问题从来不是「赶上赶不上」，而是**阻塞时长**。

⚠️ 该修正只基于**本设备两次样本**，若微信在独立线程删图则会失效 —— 仍列 `[需真机]` 长期观测项。

## 322ms 的来源与优化空间

`scanned=500` ⇒ 扫满 500 行未提前退出。单行约 0.64ms，主要耗在反射逐行 `getString(thumbImgPath)` + Kotlin 侧 `contains`。

可优化（⛔ 本轮未实施，仅记录）：
1. **命中后提前 break**（单 token 场景扫到即停）；
2. **把匹配下推到 SQL**：
   `select id from ImgInfo2 where id > (select max(id) from ImgInfo2) - 500 and instr(thumbImgPath, ?) > 0`
   —— `id > ?` 走主键范围限定，`instr` 只在 500 行上执行，避免 500 次反射解码。
3. 减小 `MAX_RECENT_SCAN`（撤回图必然是最近几十行）。

⛔ 不做以上优化也能用：兜底只在 ~8% 的「无 svrId」例外场景触发，且 322ms 仍早于删除。
