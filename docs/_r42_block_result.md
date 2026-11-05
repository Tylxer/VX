# R42 路线甲（写时拦截）真机验证结果 —— 定案

日期：2026-11-05 21:41 ~ 21:54
设备：467cf5bc（PJD110）｜微信 8.0.48/2588 GP｜模块 0.2.8
实验方案：`docs/_r42_block_test.md`
对照实现：WechatMagician（GPL-3.0）`updateWithOnConflict` **before** 摘 `sourceType` 键，
见 `docs/_r42_wechatmagician.md`

## 0. 结论（一句话）

**路线甲在本机可复现成功**：好友删除动态时，微信的写库调用被 `.before` 钩子摘掉
`sourceType` 键，`SnsInfo.sourceType` 保持 `2`，动态在时间线上仍然可见。
微信**会重试**（47 秒内重试一次，同样被拦），不是"侥幸躲过一次写"。

## 1. 实验对象

| 项 | 值 |
|---|---|
| snsId | `-3398534434663755253` |
| 好友 | verity（`wxid_xlrbelf6hv0w22`） |
| 初始 `sourceType` | `2`（好友可见，`&2!=0`） |
| `localFlag` | `2` |
| `createTime` | `1793886380` = 2026-11-05 21:46:20 |
| 唯一性核验 | `createTime > 1793885700` 全库仅 4 条，该条为最新，且 21:45 之后只有它 |

基线（21:29）：`&2==0` 共 41,281 条（`8`=25,053 / `0`=16,043 / `20`=122 / `84`=50 / `4`=10 / `68`=3）。
开关：`antirevoke.sns=false`（**补位关**，故意隔离，保证看到的任何变化只可能来自写拦截）、
`block=on`（`readback=true`）。

## 2. 核心证据

### 2.1 拦截命中（两次）

```
21:52:15.512 #worker BLOCK hit snsId=-3398534434663755253 before=2 keys=18 hasContent=true hasAttr=true
21:52:15.517 #worker BLOCK after snsId=-3398534434663755253 before=2 after=2 (键已摘)
21:53:02.271 #worker BLOCK hit snsId=-3398534434663755253 before=2 keys=18 hasContent=true hasAttr=true
21:53:02.279 #worker BLOCK after snsId=-3398534434663755253 before=2 after=2 (键已摘)
```

字段含义（源码 `SnsBlockProbe.kt:137-139`，**非推测**）：

| 字段 | 含义 | 本次取值 | 推论 |
|---|---|---|---|
| `before` | 写之前库里 `sourceType` | `2` | 微信这次写是要把它改成 0 |
| `keys` | `ContentValues` 键数 | `18` | = 表全部 18 列，**整行覆盖**（W3 已答） |
| `C` | 是否含 `content` 列 | `1` | 内容列也在这次写里 |
| `A` | 是否含 `attrBuf` 列 | `1` | 同上 |
| `blk` | 是否真的摘键成功且摘完非空 | `1` | 拦截动作发生（非 `SKIP_EMPTY`/`SKIP_NO_API`） |
| `after` | 写完之后回查库里的值 | `2` | **写入没有生效，位没被清** |

### 2.2 计数器

```
21:53:49 BLOCK stat hit=2 blocked=2 nonZero=32 skipOff=0 last=21:53:02
21:53:49 BLOCK skip empty=0 noApi=0 noId=0 parseFail=0 afterOk=2 afterFail=0
21:53:49 BLOCK upd seen=34 zero=2 nosrc=0 | ins seen=22 zero=0 nz=22 nosrc=0
```

- `hit=2 blocked=2`：命中的两次全部拦住，零漏。
- `nonZero=32`：同一时段另有 32 次「带 `sourceType` 但非 0」的更新**原样放行** ——
  证明拦截不是"见 sourceType 就拦"的粗暴实现，误伤面为 0。
- `parseFail=0 noApi=0 noId=0 afterFail=0`：四条失败通道全空。

### 2.3 时间线语义（拦截的后果）

```
21:52:15.424 WIN new round=1 n=10 gap=266624161ms same=false ctTop=1793886729
21:52:18.427 WIN gate round=1 respN=10 upsertN=20 insertN=1 subset=true
21:52:18.431 WIN src  round=1 vis=10/10 zero=[]
21:53:02.144 WIN new round=2 n=10 gap=46719ms same=true  ctTop=1793886729
21:53:05.155 WIN src  round=2 vis=10/10 zero=[]
```

`zero=[]` 表示本轮响应的 10 条里**没有一条** `&2==0`。即：好友删了，但同步回来的
可见集合仍然 10/10 可见，我们这条没被摘出去。这是"库里是真值、不是 UI 缓存"的证明。

## 3. W 清单回答

| 编号 | 问题 | 结论 | 证据 |
|---|---|---|---|
| **W1** | 微信被拦后会不会重新校正？ | **会重试，但每次都被拦**。47 秒内两次，原因待定（可能 sync 双阶段），均 `blk=1 after=2` | §2.1 |
| **W2** | 是否只有一条清位通道？ | ⛔ **不是**。存在一条**未被接管**的裸 SQL 批量通道，见 §4 | §4 |
| **W3** | `ContentValues` 是整行还是部分列？ | **整行**（`keys=18`，含 `content`/`attrBuf`）⇒ WechatMagician 的 `remove("sourceType")` 做法在本版本安全，摘掉一个键还剩 17 个，不会撞 `SKIP_EMPTY` | §2.1 |

## 4. ⛔ 新发现：raw SQL 通道（本次未造成影响，但是个洞）

删除动作期间，`execSQL` 钩子观测到一条**按好友批量**的清位 SQL：

```sql
UPDATE SnsInfo SET sourceType = sourceType & -9
WHERE SnsInfo.userName="wxid_xlrbelf6hv0w22"
  AND (sourceType in (8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,72,328,74,330,76,332,78,334,88,3…))
```

- `-9` = `~8` ⇒ **只清 ALBUM_BIT(8)**，不动 `TIMELINE_BIT(2)`。
- 本次对象 `sourceType=2`，不含 bit 8 ⇒ 不受影响（`after` 仍为 2）。
- 计数：`sqlSns=17 sqlSrc=6 sqlClear=0 sqlOwn=0 sqlUnmatched=6` —— 6 条带
  `sourceType` 的 SQL 全部**未匹配**任何已知模式，也没被归属到我们的 snsId。
- 我们的拦截**只**挂在 `updateWithOnConflict`，这条 SQL 走 `execSQL`，**完全不拦**。

**风险等级：中。** 只要微信哪天把「好友删动态」也做成 `sourceType & -3` 的按人批量
SQL，路线甲会静默失效，且我们连"被绕过"都看不出来（现在靠 `sqlUnmatched` 计数才有痕迹）。
⇒ 后续必须补：`execSQL` 里识别 `SnsInfo` + `sourceType` 的 UPDATE，记台账并告警。

## 5. 与路线乙（补位）的关系 —— 并非二选一

| | 路线甲（写时拦截） | 路线乙（事后补位 `srcfix`） |
|---|---|---|
| 时机 | 写入前 | 写入后 |
| 覆盖 | 模块在进程内时的**所有**新删除 | 只救"已经变成 0"的行 |
| 失效场景 | 模块未激活期间发生的写、raw SQL 通道 | 无法恢复 `content` 已被服务端改写的情形 |
| 写库代价 | **零额外写**（只是不写一个键） | 高（R41 实测 13 分钟改写 636 行） |

甲解决"新删除"，乙兜"历史已损"。**建议两者共存**：甲默认开、乙作为可选回填。

## 6. 尚未闭环

1. **30 分钟观察窗**（trace `every=30s max=60`，起于 21:50:40）：微信是否会在更晚
   （如再次进入朋友圈、或 5 分钟节流后）把这条重新写回 0。**待测**。
2. **冷启后是否仍为 2**：进程重启后微信会不会重新拉一次全量并纠正。**待测**。
3. raw SQL 通道的接管（§4）。**待实现**。
4. `content` 列虽在 `values` 里，但未被改动 —— 尚未逐字节确认内容与删除前完全一致
   （UI 仍显示原文可作弱证据）。

## 7. 收尾

未做，仍处于实验态：`block` 开关 `on`、`trace` 运行中、`winreset` 后账本已重建。
收尾命令见 `docs/_r42_block_test.md` §5。
