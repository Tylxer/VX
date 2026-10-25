## ⑬ 🟢 聊天页 itemView 结构（R21d 角标真机实测，2026-10-25）

### 根容器不是 LinearLayout —— 是 `viewitems.xi : RelativeLayout`

```
python tools/_r13_findclass.py com.tencent.mm.ui.chatting.viewitems.xi
  DEFINED in classes4.dex
  super = Landroid/widget/RelativeLayout;
  <init> (Landroid/view/LayoutInflater;IZ)V
  methods(1): <init>          # 类里没有任何自己的方法，纯布局容器
```

真机 `onBindViewHolder` 的 `holder → itemView` 解析结果：
`host = com.tencent.mm.ui.chatting.viewitems.xi`，**`itemViewOf` 选对了**
（两枚角标挂在**两个不同**的 `com.tencent.mm:id/bt3` 实例上，不是同一个 View）。

### item 骨架（uiautomator 实测，设备 1440×3200，对方语音消息）

```
RelativeLayout#bt3  [0,1697][1440,2091]   1440x394    ← item 根
  ├─ TextView#bxh "15:08"  [667,1740][773,1860]  106x120  ← 时间戳槽（顶部居中，非每条都有）
  └─ RelativeLayout#bqh  [0,1903][1440,2091]   1440x188  ← ⭐ 消息行 = 最后一个子 View
       ├─ RelativeLayout#bq2  [22,1903][209,2090]   187x187  ← 头像
       │    ├─ ImageView#bpz
       │    ├─ View
       │    └─ ImageView
       └─ RelativeLayout      [209,1925][810,2069]  601x144  ← 消息体列（头像右侧）
            ├─ FrameLayout#by8  [209,1925][529,2069]   ← 气泡本体（宽度随内容变：288 / 320 …）
            │    ├─ FrameLayout#by7
            │    │    ├─ TextView#by6   ← 气泡背景层？
            │    │    └─ TextView#bqj   ← 正文 / 语音秒数
            ├─ ImageView#bxd    [558,1925][594,2069]   36x144  ← 状态图标（窄，非人人有）
            └─ RelativeLayout#brv [623,1918][810,2076]  187x158 ← 非人人有
```

关键定量事实：
- **消息行 = item 的最后一个子 View**（`childCount - 1`）。这是打角标最稳的锚点。
- 气泡宽度**随内容变**：同一会话里见过 `#by8` 宽 288 / 320 / 529。
- 「气泡区」那层 `RelativeLayout` 的子项**数量不固定**（状态图标 `#bxd` / `#brv` 只在部分消息上出现）。
- `#bxh` 时间戳槽在 **item 顶部**，不是每条都有（微信只在时间间隔够长时插）。**写「内容下方」
  逻辑时它是陷阱**：任何基于「item 底部对齐」的写法都可能与它并排，看起来像在给下一条打标。

### ⛔ 由此得到的教训（已实际踩过两轮）

`HintBadge.bottomSlot()` 第一行曾是 `if (target !is LinearLayout) return target`，
而 target 是 `xi`（RelativeLayout）⇒ **整个函数体一行没执行**，
`labelParams()` 也因不匹配任何 `is` 分支返回 `null`，
最终 `addView(tv)` 用 ViewGroup 默认 LP ⇒ 角标落到 item 的 `(0,0)` 左上角，
压住时间戳那一行。

⇒ **写位置逻辑前必须先确认根容器的真实类型**，不能从「消息行是横排 LinearLayout」
这个局部事实外推整棵树。局部对 ≠ 根对。

### 相关计数器基线（该会话实测，可作回归对照）

```
binds=269 chat=269 resolved=268 hits=34 added=4 missed=0 verifyFail=16 masked=1
  enabled=true msg=true hint=true rescued=85 bound=2
```
- `resolved/chat = 0.996` ⇒ **pos 坐标系无错位**（`adapter.getItem(pos)` 方案成立）。
- `verifyFail=16` 全部是系统提示条（`没有更多消息了|以下为新消息|<时间>`），
  被 `contentOk` 正确拒绝——这是判据**在干活**，不是故障。
- `masked=1`：`restore_view` 伪装态被 `typeOk` 拦下，符合预期。

---

## ⑭ 🟢 撤回语音后 `voiceinfo` 被删的真实链路（R21c 反汇编定案，2026-10-25）

> 本节取代 `docs/_r21c_voice_root.md` §1.2 里**仅凭 dex 字符串表**得出的旧判定。
> 完整证据链见 `docs/_r21c_voice_sqlform.md`；本节只固化「别再写错」的结论。
> 独立复核脚本：`python tools/_r21c_verify_sql.py`（可重跑，全只读）。

### 结论先行（四条，全部反汇编实证）

1. **撤回删 `voiceinfo` 走单行参数化** `FileName= ?` + `whereArgs=[26位hex imgPath]`，
   经 `SQLiteDatabase.delete()`，**不是**子查询、**不是** `execSQL`。
2. **时序：先 `UPDATE message`，之后才排程删 voiceinfo**。⇒ `AntiRevoke.record()` 已完成，
   `rescued` 行必已存在。
3. **wcdb 的 `SQLiteDatabase` 没有 `deleteWithOnConflict`**（与 AOSP 不同）。
4. 子查询 `FileName IN (SELECT imgPath FROM message WHERE talker IN ...)` **确实存在**，
   但属于 `deleteMsgByTalkerSync`（删会话/清空记录），**与撤回无任何调用关系**。

### ⛔ 两个已证伪的误判（写代码/写文档前务必先看这条）

| 误判 | 真相 | 为什么会错 |
|------|------|-----------|
| 撤回删 voiceinfo 走子查询 `IN (SELECT imgPath ...)` | 走 `FileName= ?` 单行参数化 | 旧文档只证明了「dex 里**存在**这条 SQL」，没证明「撤回**调用**它」。存在 ≠ 被调用。 |
| DELETE 在 UPDATE **之前** | DELETE 的 runnable 在 UPDATE **之后**才 `executor.submit` | 由「`rescued` 只在 UPDATE 阶段写入」推「时序若反则方案作废」是**推理**，不是取证 |

### 撤回链（每层都有反汇编实证）

```
vj0.s.b9  (revokemsg 系统消息消费者, [475] log MM_DATA_SYSCMD_NEWXML_SUBTYPE_REVOKE)
  → vj0.s.i  (doRevokeMsg)
      [532] com.tencent.mm.storage.j9.va(J,h9)   ← ★ UPDATE message（AntiRevoke 拦这里 + record）
      [576] new vj0.p                            ← deleteLocalFile runnable
      [579] executor.submit                      ← ★ 排在 [532] 之后
  → vj0.p.run  (log '[deleteLocalFile]')
  → vj0.a9.e(h9,true)                [39] modelbase.r0.Ab() = onPreDelMessage
  → em0.x0.Ab  (VoiceMsgExtension, log 'onPreDelMessage ')
  → em0.v0.f   (deleteRecordFile, log 'deleteRecordFile() called with')
  → em0.e1.f   (VoiceStorage)  const 'FileName= ?' → z84.z.delete('voiceinfo',…)
  → z84.z$$c.a [34] com.tencent.wcdb.database.SQLiteDatabase.delete()I
```

`j9.va` 本体只做 `z84.z.c(table, values, 'msgId=?', [msgId])`（`[72][74]`），**不碰 voiceinfo**。

### `voiceinfo` 写路径穷举（全 15 dex 搜 `voiceinfo` 字符串）

| 路径 | 类/方法 | 形态 | 用途 |
|------|---------|------|------|
| INSERT | `em0.e1.P0` | ContentValues | 下载完成写记录 |
| UPDATE | `em0.v0.x` | ContentValues | 续传进度 |
| DELETE 单行 | `em0.e1.f` | `FileName= ?` + args | **单条删除（含撤回）** |
| DELETE 子查询 | `vj0.a9.j` | `execSQL` + 子查询 | 整会话删除（删会话/清记录） |

⇒ 没有第二条参数化 DELETE，也没有别的 execSQL 删 voiceinfo 的路径。

### `SQLiteDatabase` 方法表实测（`tools/_r21c_verify_sql.py` V1）

```
delete(String,String,String[])I          ← 唯一 delete 重载，纯 Java
deleteDatabase(File)Z
insert / insertOrThrow / insertWithOnConflict
update / updateWithOnConflict
execSQL(String) / execSQL(String,Object[]) / execSQL(String,Object[],CancellationSignal)
⛔ 无 deleteWithOnConflict
```
⇒ `VoiceGuard.install()` 里那个 `deleteWithOnConflict` hook 是**死代码**（返回空集）。
日志只会是 `VG installed hooks=delete` —— **这是正常的，别据此判断 hook 没装全**。
`delete()` 纯 Java、入口 `param.result=1` 即可跳过 `executeUpdateDelete()`，所以拦得住。

### 一个易被忽略的字符串细节

dex 里是 `'FileName= ?'`（`=` 后有空格），**不存在** `'FileName=?'`。
`VoiceGuardLogic.EQ_OR_IN = (?:filename|msglocalid)\b\s*(?:=|\bin\b)` 的 `\s*` 正好吃掉空格才命中
⇒ 这条判据能成立**依赖正则里那个 `\s*`**，改正则时别删它。
