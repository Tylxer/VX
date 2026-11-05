# R42 · 路线甲实测：写时拦截 `sourceType`（before hook）

> 目标：回答 W1（微信会不会校正回来）、W2（清位是否只有一条通道）、W3（ContentValues 是整行还是部分列）。
> 参考实现：`docs/_r42_wechatmagician.md`。
> ⛔ 本轮只**实现 + 编译**，不装包、不 adb、不 commit。

## 待办

- [ ] 子步 1：`Keys.ANTIREVOKE_SNS_BLOCK` 开关 + 独立 before 钩子（摘 `sourceType`）
- [ ] 子步 2：`SnsDeleteEventLedger` 事件账本（环形 50）+ 限流日志
- [ ] 子步 3：W2 通道覆盖（execSQL / update / insert / delete 四路计数 + 原文采样）
- [ ] 子步 4：探针 `op=snsg sub=block|blockstat|trace`
- [ ] 构建 `assembleDebug`

---

## 实施记录

### [DONE] 子步 0：文档 + 探针骨架

- 新建 `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsBlockProbe.kt`
  （约 480 行）：纯内存事件账本（环 50）、4 路通道计数、`readBits` 点查、`trace` 30s 采样器、
  `blockstat` 输出、`switch(on|off)`。
- `data/Keys.kt`：新增 `ANTIREVOKE_SNS_BLOCK = "antirevoke.sns_block"` + `PrefSpec`
  （SWITCH，默认 `"false"`，域「防撤回」）。

设计要点：
- 计数**分项独立**：hit / blocked / nonZero / skipOff / skipEmpty / skipNoApi / skipNoId /
  parseFail / updNoSrcKey / ins* / del* / sql* / afterOk|Fail。
- 日志限流只在「同一 snsId 的 BLOCK hit」和 60s 汇总，**计数不去重**。
- `pending` 走 ThreadLocal，配对 before（摘键）与 after（回查），after 里查完即清。

### [DONE] 子步 1：开关 + before 拦截

- `data/Keys.kt:142-157` 新增 `ANTIREVOKE_SNS_BLOCK = "antirevoke.sns_block"`（含设计注释）。
- `data/Keys.kt:400-405` 注册 `PrefSpec(SWITCH, "false", "防撤回", "实验：拦截朋友圈删除写库…")`。
- `feature/SnsGuard.kt`：
  - `Config` 增 `blockEnabled`（`refresh()` 读新 key，3s TTL 生效）；
  - `install()` 追加 `hookAllMethods(cls, "updateWithOnConflict", snsBlockGuard)`
    （**第二个回调**，与已有的 after 钩子 `snsUpdateGuard` 并存，互不调用）；
  - `snsBlockGuard`（`SnsGuard.kt:307`，before + after 两半）+ `CvApi` 反射面（`get/remove/size/put`，按 class 缓存）。

拦截分支顺序（全部带原因日志）：
`table != SnsInfo` → 返回（零反射）→ 本线程残留 pending 清掉 → `cvApiOf` 非 ContentValues 返回
→ values 无 `sourceType` 只计数 → 解析失败 `BLOCK skip: sourceType unparsable …` → 非 0 只计数
→ `==0` 命中 → 现查库取 before/localFlag/serverExtFlag/createTime → 开关关：只记账
→ `remove` 方法缺失：`BLOCK skip: no ContentValues.remove …` → 摘键后 `size()==0`：
`put` 回原值 + `BLOCK skip: empty values …`（**绝不让 update 抛异常**）→ 否则真正拦截。

### [DONE] 子步 2：事件账本 + 限流日志

`feature/SnsBlockProbe.kt`（新文件，571 行）：
- 环形 50 个 `Event`：`snsId / before(现查) / keys / hasContent / hasAttr / blocked / HH:mm:ss /
  lf / sef / ct / after(写后回查)`；
- `BLOCK hit snsId=… before=… keys=… hasContent=… hasAttr=… at=…`：同一 id **5s 节流**（计数不节流）；
- `BLOCK stat hit=… blocked=… nonZero=… last=…`：**60s 最多一条**；
- `BLOCK after snsId=… before=… after=…`：after 钩子里的写后回查（同样 5s/id 节流）。

### [DONE] 子步 3：W2 四路通道

| 通道 | 计数 | 采样 |
|---|---|---|
| `updateWithOnConflict` | `upd seen/zero/nosrc` | 事件账本 + `BLOCK hit` |
| `insert(WithOnConflict)` | `ins seen/zero/nz/nosrc` | 无（只计数） |
| `delete` | `delSns/delRow` | 前 5 条打 `BLOCK w2 delete snsId=… where=…`（≤200 字符） |
| `execSQL` | `sqlSns/sqlSrc/sqlClear/sqlOwn/sqlUnmatched` | `sqlUnmatched` 原文**去重 + 上限 20**，`BLOCK w2 sql-unmatched …` |

⭐ `sqlUnmatched` 是「抓出第二条通道」的唯一入口：改 `sourceType` 但既不是 `& -3` 形态、
也不是我们自己 `| 2|10` 的 SQL，全部算未识别并留原文。

### [DONE] 子步 4：探针

`entry/EntryReceiver.kt:609-617`（snsg 分支，+13 行）：
- `--es sub block --es sub2 on|off` → `Kv.putBool` + `SnsGuard.refresh()` + 回读，
  日志 `BLOCK switch on readback=true`；参数不对时回 `BLOCK switch bad arg=… now=…`。
- `--es sub blockstat` → 4 行计数 + 最近 10 个事件（**≤16 行**）。
- `--es sub trace --el id <snsId>` → `HandlerThread("sns-trace")`，每 30s 一行
  `TRACE HH:mm:ss id=… src=… lf=… sef=…`，60 次后 `TRACE done`；`id 0` 停止；再次调用替换。

### [DONE] 构建

`./gradlew assembleDebug --rerun-tasks` → `BUILD SUCCESSFUL in 55s`（35 tasks executed）。
产物 `wxgroup/app/build/outputs/apk/debug/app-debug.apk`（未安装、未 adb、未 commit）。

---

## 与任务书的偏差（都已在代码注释里写明）

1. **取值分布计在 before 而不是 after**：spec 建议「已有 after 钩子内记 0/非 0 分布」。
   但拦截模式 in before 就把 `sourceType` 摘掉了，after 读不到 ⇒ 分布会恒 0。
   所以 `upd seen/zero/nosrc` 记在 before；after 钩子保持原样只做补位。
2. **`onSnsUpdateAfter` 加了一处 4 行早退**：`sourceType` 为 null 时，先按 ContentValues
   身份问 `SnsBlockProbe.isBlockedValues(values)`，是则直接 return（不打「re-fix disabled」误报）。
   补位逻辑本身**一行没动**（拦截模式下本来就没有位被清，不需要补）。
3. **`hasContent` 用 `get("content") != null` 判**：ContentValues 里 `putNull` 的列会被误判 false。
   content/attrBuf 不会 putNull，风险可忽略；但这是「W3 判据」的一个已知软点。

## W3 静态判断

- `onSnsUpdateAfter` 里 snsId 是从 **whereArgs** 抠（`snsIdOf(a)`），不是从 values ⇒
  **update 的 ContentValues 不含 snsId**，所以严格意义上**不是整行**。
- R27 的注释写的是「微信落库是服务器对象整行 upsert」——那是**现象描述**，不是这一层的证据。
- 真正的判据是 `blockstat` 里每条事件的 `keys=` / `C=` / `A=`：
  `keys` 只有 1~3 且 `C=0` ⇒ 只带改动列（摘键安全）；
  `keys >= 10` 且 `C=1 A=1` ⇒ 整行 upsert（摘键同样安全，但意味着 `sourceType` 本来就该被覆盖）。
- 代码里**没有**任何地方能静态证明，必须靠真机 `keys=` 读数。

## 可能让实测结论失真的偏差

1. **`sourceType=0` ≠ 「一条动态被删」**：广告位 / 新行初始化历史上也写过 0（R30 `srcmap`
   实测分布含 0）。判据必须用事件里的 `before`：`before` 含 bit2(2) 才是「从可见被清成 0」。
2. **摘键会改变微信自身的后续行为**：`update` 影响行数仍为 1（其余列照写），但
   `sourceType` 列没变。若微信按「本行是否真的变了」判断是否重试/补发，会出现重试风暴或
   另一条补偿路径 —— 那会被记成额外 `BLOCK hit`（有 5s 节流，但计数会涨）。
3. **开拦截时 `SNS srcguard re-fix` 日志会消失**：这是预期（没有位被清），别误判成补位坏了。
   对照实验必须：先关拦截跑一轮看 `re-fix`，再开拦截跑一轮看 `BLOCK blocked` 增长。

[ALL-DONE]
