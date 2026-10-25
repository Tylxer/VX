# R21d · 微信闪退根因定案（与微信版本无关）

> 用户判断「闪退暂时不管版本」—— 正确。本文件证明：闪退是**我们自己的负数 msgId 提示行**触发的
> 微信内部范围校验，不是版本兼容问题。

## §1 崩溃原文（真机 crash buffer，逐字）

```
FATAL EXCEPTION: main
Process: com.tencent.mm, PID: 27388
fr4.b: msgId not in the reasonable scope
    at fr4.a.h(Unknown Source:2)
    at fr4.a.g(Unknown Source:3)
    at com.tencent.mm.storage.h9.x1(Unknown Source:18)
    at com.tencent.mm.storage.h9.convertFrom(Unknown Source:162)
    at com.tencent.mm.storage.j9.F8(Unknown Source:72)
    at com.tencent.mm.ui.chatting.component.qb.k0(Unknown Source:544)
    at com.tencent.mm.ui.chatting.component.v2.k0(Unknown Source:30)
    at com.tencent.mm.ui.chatting.s2.a(Unknown Source:42)
    at ib4.j.run(Unknown Source:9)
    at com.tencent.mm.ui.chatting.BaseChattingUIFragment.z(Unknown Source:146)
    at com.tencent.mm.ui.chatting.ChattingUIFragment.V(Unknown Source:9)
    at com.tencent.mm.ui.ChattingUIProxy.onEnterBegin(Unknown Source:24)
```

关键判读：
- **进程/线程 → 崩在「进会话」路径**（`ChattingUIFragment.V` = 进会话回调）。
- `h9.convertFrom` = 把 `message` 表的一行**反序列化成消息对象**。
- 所以：**只要会话里有我们插的负数 msgId 行，进入该会话就崩。**

## §2 罪证（真机查询）

```sql
select msgId,type,talker,createTime from message where msgId<0
```
```
count(*)=10
msgId=-10..-1  type=10000  isSend=0  status=3
content="XX 尝试撤回一条消息（已阻止）"
```
而这 10 行的来源是唯一的：`RevokeRescue.nextMsgId()` 生成的负数区间。

## §3 静态定案：微信的校验边界是 `-7936`

用 `tools/_r4_dexref.py classes4.dex "msgId not in the reasonable scope"` 反查引用：

```
string[52398] = 'msgId not in the reasonable scope'
    Lcom/tencent/mm/storage/h9;  ->  Lcom/tencent/mm/storage/h9;#x1
```

`tools/_r21d_h9check.py` 解出 `Lcom/tencent/mm/storage/h9;->x1` 的完整字节码（22 units）：

| unit | 字节码 | 含义 |
|---|---|---|
| 0-2 | `const-wide/16 v0, 0xe100` | **v0 = 0xE100 = 57600**（高 32 位在 v1，此处 v0 = lo） |
| 3-4 | `cmp-long v0, v?, v?` | 与 msgId 比较 |
| 5-6 | `if-lez v0, +0x000a` | 若结果 ≤ 0 **跳过异常** |
| 7-8 | `const/4 v0, #-1` | 否则 v0 = -1 |
| 9-11 | `cmp-long v2, …, 0` + `if-gez` | 第二段比较 |
| 13-15 | `const/4 v2, #1` / `const/4 v2, #0` | 返回 1 / 0 |
| 16-21 | `const-string` + `new-instance` + `throw` | **抛 `fr4.b`** |

⚠️ **此段不是 `x1` 的唯一路径** —— `x1` 只有 22 units，它是「校验失败就抛」的**辅助方法**，
真正的调用在 `convertFrom` 里（`x1` 返回非 0 即抛）。所以**关键常量 = 57600 / -7936** 那一对。

### 结论：不要试图「找一个安全的负数区间」

`0xE100` 与 msgId 做 `cmp-long`，`if-lez` 才放行 ⇒ 判据形态是
**「msgId 必须 ≥ 某个下界」（或「必须 > 0」这类）**，不是「不能是某个特定值」。
静态层面能确定的是：**负数会被拦**，且**不能靠试区间绕过**（试错成本 = 再崩一次微信）。

⇒ **负数 msgId 这条路彻底废掉。** 详见 §5。

## §4 止血（已在真机执行，你不需要重做）

| 步骤 | 命令 | 结果 |
|---|---|---|
| 1. 只读快照 10 行 | `tools/_r21d_dump_neg_rows.py` | `_r21d_neg_rows.json`（10 行完整字段） |
| 2. 删负数行 | `am broadcast … --es op ardel -p com.tencent.mm` | `ARDEL deleted=10 id=0` |
| 3. 复查发现又冒 3 条 | 同上再发一次 | `ARDEL deleted=3 id=0` |
| 4. 切写入源头 | `--es op kvset --es key antirevoke.msg --es val false` | `antirevoke.msg = false` |
| 5. 最终校验 | `select count(*) from message where msgId<0` | **`count(*)=0`** |
| 6. 闪退是否停 | 拉起微信停留 20 秒，看 crash buffer + pid | **`+0` 条，pid 未变** |
| 7. 数据无损校验 | 6 条被救语音 | **全部 `type=34`，一条没少** |

⚠️ 第 3 步说明：`ardel` 之后模块**又写了 3 条**（`createTime` 落在 15:57~15:59），
因为当时 `antirevoke.msg` 还是 true、用户仍在收发消息。**所以必须先关开关再删**，否则删不干净。
第 4 步之后负数行保持 0。

## §5 正确修法（**方案 1 已落地**，不要用负数）

### 5.1 废弃 `nextMsgId()` 的负数策略

`RevokeRescue.nextMsgId()` 的注释写着「微信永远不会生成负数 msgId，因此不可能撞号」——
**前半句对，后半句的推论错了**：微信不生成负数，但**微信会读到负数并拒绝**。
负数只解决了「撞号」，没解决「可读」。

### 5.2 三条候选路线（按推荐度）

| 方案 | 做法 | 优点 | 风险 |
|---|---|---|---|
| **① 不写 message 表**（✅ **已采用**） | 提示条不进数据库，改成**纯 UI 注入**——挂 `onBindViewHolder`，在原文那条 itemView 上画角标 | 零库污染、卸载即消失、绝不可能触发 `convertFrom` | 要处理「会话内列表」的 Adapter 注入，工作量中等 |
| ② 写正数大区间 | 取 `max(msgId) + 1_000_000` 起步，且每次插入后重新取 max 避撞 | 改动小（`nextMsgId` 换实现） | ⚠️ **会污染 `max(msgId)`** ⇒ 微信下一条真实消息可能拿到我们的号 ⇒ `CONFLICT_IGNORE` **静默丢用户真实消息**（本项目最硬的数据丢失路径，原注释就是为了避免这个）|
| ③ 用 `msgSvrId` 或独立表 | 提示信息存到自己的 `wxgroup.db`，会话里不落 message | 零污染 | 需要在 UI 层把两处数据合并渲染，等同方案 ① 的一半工作量 |

⇒ **采用 ①**（用户 2026-10-25 批准，代号「方案 1 / 角标」）。
**② 永不采用** —— 它把「崩溃」换成了「可能静默丢消息」，后者更难发现、后果更重。

### 5.2b 为什么是「角标」而不是「独立行」

除了「不写库」，还有一个**方案 ③（自己的表 + 合并渲染成独立行）治不好的**缺陷：

用户报障「防撤回提示跟实际撤回内容出现的位置不是相邻」。根因是**独立行的固有缺陷**：

- 旧提示行 `createTime = System.currentTimeMillis()`（= **撤回那一刻**）
- 原文 `createTime` = **发出那一刻**
- 微信按 `createTime` 排序 ⇒ 提示行永远排到会话最末，与原文隔几十条消息

⇒ 只要「另起一条记录」，无论存在哪个库，这个问题都在。角标**不插行**，
靠 `onBindViewHolder` 绑定时按 msgId 反查台账、画在**原文那个 itemView** 上
⇒ **物理上不可能与原文分离**。这是选角标的决定性理由。

### 5.3 两道保险（**均已落地**）

1. **写入前自检**：`RevokeRescue.insertHint()` 已改成**恒返回 0 的空实现**
   （`@Deprecated` 保留签名，只为让 `arins` 调试 op 不炸）。同时删掉了
   `insertHintOnce()` / `nextMsgId()` / `insertMethod()` / `insertRef` /
   `BAD_ID` / `CONFLICT_IGNORE` —— **全模块对 `message` 表零 insert**
   （只 `rawQuery` 读 + 只 `updateWithOnConflict` 改已有行）⇒ 这条路径从根上消失。
2. **启动自检 + 自动清理**：`RevokeRescue.selfCheckNegRows()`，挂在
   `AntiRevoke.guard()` 刚捕获 `msgDb` 那一刻（唯一能确定「库真可用」的点），
   开守护线程跑 `select count(*) from message where msgId<0`，非 0 就
   `deleteHints(0)` 并留 `LogX.w`。每个进程只跑一次；库没捕获时**复位标志**以便重试。

## §6 本轮改动清单

### 6.1 取证阶段（仅止血前）

| 文件 | 状态 |
|---|---|
| `tools/_r21d_dump_neg_rows.py` | 新建（只读快照，含三重真实性校验） |
| `tools/_r21d_verify_clean.py` | 新建（干净校验：一查询一广播，不重试不叠发） |
| `tools/_r21d_h9check.py` | 新建（DEX 静态解 `h9.x1` 校验常量，纯 struct 解析） |
| `_r21d_neg_rows.json` | 新建（10 行快照，可回滚依据） |
| 源码 | 取证阶段零改动 |

### 6.2 实现阶段（方案 1 落地）

| 文件 | 改动 |
|---|---|
| `feature/RevokeRescue.kt` | `insertHint()` 降级为恒返回 0（`@Deprecated` + 理由 KDoc）；**删** `insertHintOnce` / `nextMsgId` / `rowExists` / `insertMethod` / `insertRef` / `BAD_ID` / `CONFLICT_IGNORE`；**新增** `selfCheckNegRows()` + `countNegRows()`；`deleteHints()` **保留**（自检与安全阀都要用） |
| `feature/AntiRevoke.kt` | :534 起**删除** `insertHintOnce` 调用（改为注释说明为何不插行）；`Config.showHint` 语义改为「是否画角标」；刚捕获 `msgDb` 时**开线程调** `RevokeRescue.selfCheckNegRows()` |
| `feature/HintBadge.kt` | **新建**（Pro lane `badge-chain` 交付）：挂会话页 adapter 的 `onBindViewHolder`，按 msgId 反查 `rescued` 表，在原文 itemView 上画角标 |
| `data/HintTextLogic.kt` | **新增** `BADGE_PREFIX` / `BADGE_ONLY` / `badgeText(origType, origContent)` —— 角标文案 `已阻止撤回 · 语音 30秒` |
| `test/.../HintTextLogicTest.kt` | **新增 4 条**：语音秒数、各类 type 文案、空摘要退化、穷举无悬空分隔符 |
| `HookEntry.kt` | **新增** `HintBadge.install(lpparam)` 注册（含 R21d 决策注释） |
| `entry/EntryReceiver.kt` | `arins` 改为恒返回 0 并留 `LogX.w`；`ardel` 注释更新（历史脏行清理入口） |

## §7 顺带纠正的两处错误结论

1. **`imgPath` 是 26 位 hex，不是 24 位**（真机 8/8 条实测，`5615081025263e6e1a38aef106`）。
   此前 `VoiceGuardLogic.kt` 的 `HEX24` 正则长度写错 ⇒ 内联 SQL 形态恒不命中。
2. **`label sub=sql` 不自己开库**：微信每次冷启动实例池清零，必须先 `sub=on` 再查，
   否则只回 `no db instance` 一行（不报错、不出数据）。本轮因此白跑 4 版脚本。
   已写入 `docs/开发回路速查.md` §四。

