# R30 §6.2 好友个人相册：完整门槛链（定案）

> ⛔ **本文 §0–§7 多条结论已被真机实测推翻，勿直接采信。**
> 读前先跳到文末 **「R31c 订正」** —— 那里用实测数据否掉了：
> §0「全是视频所以进不了相册」、§1「三道门槛」、§3「type=2 是视频」。
> 原文保留只为追溯推导过程，**执行请以订正节为准**。

结论日期 2026-10-28 01:0x。结论来源：dex 反汇编（`_cache/r28_dd7_full.txt`）+ 真机 `SnsInfo` 实测 + runtime SQL 旁听。

## 0. 一句话结论

**好友个人相册的图片网格只收「图片类动态」（`type ∈ {1,15}`）。**
被删动态要在这里显示，必须**同时**满足三个门槛，缺一不可；而 `verity` 的 11 条动态
**全部 `type=2`（视频）**，所以他的相册网格**本来就是空的** —— 与防删除无关，
与 `sourceType` 补位无关。这是微信的设计取舍，不是 bug。

## 1. 三道门槛（按执行顺序）

| # | 层 | 判据 | 证据 |
|---|---|---|---|
| 1 | **SQL** | `userName = <目标>` + `sourceType in (…64 个值…)` + `type in (1,2,3,4,5,9,12,13,14,15,…,101,50)` | runtime 旁听抓到微信原文（`SnsSqlSpy`） |
| 2 | **Java 装载 `qg3/k.b()`** | `type` 必须 == 1 或 == 15，否则 `continue` | 逐条过滤唯一处；`d2.H5` SQL 里的 type 白名单是**宽**的，Java 侧再收窄 |
| 3 | **Java 装载（仅首屏 F1）** | `isDieItem()` 为真则 `continue` | `isDieItem() = (localFlag & 32) != 0 && snsId != 0` |

`sourceType` 白名单那 64 个值**全部含 bit3(8)**（8,10,12,14,24,26,28,30,72,74,…,222 及其 +256 变体）
⇒ 上一轮定的「补 bit3」方向正确，但它只是三道门槛之一。

## 2. ⛔ 订正：`isDieItem()` 判的是 bit5(32)，不是 bit1(2)

既有结论（`_r30_lane2_album_page.md` §6.2、`_r27_m3_timeline_source.md:311`）写的是
`(localFlag & 2) != 0`。**错的。** 字节级实测：

```
code 0x3bdbf0:
  0x001c  54 21 ...   const v1, #0x20       # 32，不是 2
  0x0020  66 30 ...   and-int v0, v0, v1    # localFlag & 32
```

（更早的版本可能用 `iget v0`+`const/4 v1, #2` 的写法，本版本 2588 已改成立即数 32。）

真机样本对得上：
- `localFlag=2` → **不是** die item（verity 那 4 条，时间线上正常显示 ✓）
- `localFlag=50`（=32+16+2）→ 是 die item（`wxid_myzsbcur81xr21` 那 6 条）

## 3. `type` 的语义（Q5，证据充分）

| type | 含义 | 证据 |
|---|---|---|
| **2** | **视频（Sight）** | `type=2` 行的 `content` blob 里是 `sight_…`/`.mp4` 文件名，`length` 字段指向 mp4 长度；`type=1` 行则是 `img`/`.jpg`/`.pic` |
| 1 | 单图 | 同上对照 |
| 15 | 多图 / 长文（图文） | 相册网格的主要来源 |
| 3、28 | 其它（未见相册侧消费） | — |

⛔ 因此**不能**把 `type` 从 2 改成 1 来「骗」进相册：type 决定渲染器，按图片渲染器去解视频行会渲染错乱。

## 4. 当前 12 条被删动态的逐个命运

| 作者（昵称） | 条数 | type | localFlag | 判定 |
|---|---|---|---|---|
| **verity**（`wxid_xlrbelf6hv0w22`） | 4 | **2（视频）** | 2 | ⛔ **永远进不了相册网格**（微信不收视频），非模块问题 |
| `wxid_myzsbcur81xr21` | 6 | 3 / 1 | **50** | ⛔ 卡在 `isDieItem`（bit5=32）；其中 type=1 那条还带私密位 `sourceType=6` |
| `wxid_lx9vwt2ucse022` | 1 | 28 | 50 | ⛔ type 不在 {1,15} + die item |
| 物理 DELETE | 1 | — | — | ⛔ `SnsInfo` 已无行 |

⇒ **现有样本里没有一条能验证 6.2**。必须造新样本：让好友发一条**图片**动态再删除。

## 5. 6.2 若要做成，补位必须一次过三道闸

对 `fixed_orig IS NOT NULL`（被删又救回）且 **`type in (1,15)`** 的窄集合：

1. `sourceType |= 8`（bit3）—— 已实现（`SnsTimelineSourceFix.fixAlbum()`）
2. **清 `localFlag` 的 bit5(32)** —— **尚未实现**，但改动 `localFlag` 语义未完全摸清（见过 4098/5122），
   ⛔ **盲改风险高，必须等样本到位再动**
3. `type` 不动

⛔ 判据作用域纪律（00:20 事故的教训）：以上只能喂**十几条的窄台账**，
绝不能喂全表 —— 库里有 **176,230 条正常的 `sourceType=2`** 和大量 `localFlag` 带位的行，
广谱扫表会在几分钟内污染几十万行。

## 6. 下一步（阻塞在真机样本）

1. 让好友发一条**带图片**的朋友圈 → 删除 → 跑 `op=snsg sub=srcfix sub2=album` → 进他相册页看
2. 期间 `sqlspy` 开着，我抓真 SQL 复核 `userName` 与 type 白名单
3. 若图片类被删动态仍不显示，再动 `localFlag` bit5（届时有样本可验，不再是盲改）

## 7. 待验证（低优先级）

- F2（翻页路径）的 `type` 过滤**未独立确认**（子代理只确认 F1 的 `d2.H5`；F2 判据「高度同源」属推断）
- `localFlag` 高位位（4098 / 5122）语义未知

---

# R31c 订正（2026-10-28 02:4x，真机实测推翻本文件多处结论）

本节优先级**高于**上文。上文 §0/§1/§3 的错误在此逐条订正。

## A. 用户看的页面是 `SnsUserUI`，不是 `SnsAlbumUI`

- `topResumedActivity = com.tencent.mm/.plugin.sns.ui.SnsUserUI`（实测）
- `qg3/k.b()` 的 `type ∈ {1,15}` 判据只属 **`SnsAlbumUI` 九宫格网格**，
  **不能**套到 `SnsUserUI` 个人主页。§1 的三道门槛对 `SnsUserUI` 不成立。

## B. `type=2` 是**纯文字**，不是视频（订正 §3）

删掉 §3 整节结论。证据：截图里 `type=2` 的动态正文就是一段文字「11」，
时间线/个人页都正常渲染。

## C. `limit 11` 窗口假设 —— **已被真机数据否掉**

新探针 `op=snsg sub=srcfix sub2=rank --ei n 40`：拿微信**自己**那句
`select snsId from SnsInfo WHERE SnsInfo.userName=… AND (sourceType in …) AND type in (…) limit 11`
原文，只把 `limit` 放大再执行。实测：

```
SRCRANK expand=40 got=6 tagged=229
SRCRANK # 1 IN  snsId=-3405064359876226540
SRCRANK # 2 IN  snsId=-3405721048722501111 [已删台账]
SRCRANK # 3 IN  snsId=-3405726322886831589 [已删台账]
SRCRANK # 4 IN  snsId=-3405736005952990706 [已删台账]
SRCRANK # 5 IN  snsId=-3405753000984047083 [已删台账]
SRCRANK # 6 IN  snsId=-3405768558946217460 [已删台账]
```

- 该用户满足谓词的**总共只有 6 条**（expand 到 40 仍是 6）⇒ `limit 11` **没起作用**
- 5 条已删全部排 #2~#6，**都在窗口内** ⇒ SQL 层没问题
- 每条 id 之后都有 `select *,rowid from SnsInfo where snsId=X limit 1`（旁听实测 6 条全查）
  ⇒ 行被读出来了，是**之后**被丢

## D. 库层逐列对比：显示的 vs 不显示的 —— **完全相同**

新探针 `sub2=cols --es val <snsId>` 打全 18 列。`#1`（显示）vs 一条已删：

| 列 | #1 | 已删 |
|---|---|---|
| localFlag / type / sourceType | 2 / 2 / 10 | 2 / 2 / 10 |
| localPrivate / pravited / likeFlag | 0 / 0 / 0 | 0 / 0 / 0 |
| withTa / withTaHasOther / subType / serverExtFlag | null / 0 / 0 / 1 | null / 0 / 0 / 1 |
| **content (blob)** | **338 B** | **338 B** |
| **attrBuf (blob)** | **174 B** | **174 B** |
| createTime | 1793107952 | 1793029668 |
| head | 20261027 | 20261026 |
| stringSeq | 0000015041679713833325076 | 0000015041023024987050505 |

⛔ **教训（本课题栽的第三次）**：`Cursor.getString()` 对 **BLOB** 列返回 null，
那是**取值失败**不是"列为空"。必须先 `select length(col)` 才能下结论。
差点据"attrBuf 全 null"推出"正文永久丢失、6.2 无解"的相反方向。

## E. `sns.db` 共 16 张表，只有 `SnsInfo` 有 `snsId` 列

`sub2=sch` 实测（`SnsInfo` rows=259628）。`scanTables` 对 6 个 id 扫全表：
`SnsInfo hit=111111` —— 没有任何"正文副表"缺行。正文就在 `SnsInfo.attrBuf`。

## F. 收敛后的根因（待最后一步证据）

排除掉：**SQL 谓词 / limit 窗口 / sourceType / type / localFlag / 正文缺失 / 副表缺行**。

唯一剩下的实字段差异是 `createTime`（差 21.8h）+ `head`；但用户亲述
「点进去的**一瞬间有**，马上消失」—— 首屏能渲染 5 条，说明**本地数据足够渲染**，
是**之后的网络回包**把列表覆盖成服务端那份（服务端已删，只剩 1 条）。

⇒ 6.2 的正确解法不是改库，是**干预 `NetSceneSnsUserPage` 的响应合并**（`SnsAlbumMerge` 方向，
代码仍在注释停用状态）。下一步取证：hook 该 NetScene 的 `onGYNetEnd` 看服务端返回条数。
