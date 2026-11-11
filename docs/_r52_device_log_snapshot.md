# R52 真机日志快照：撤回图片「依然打不开」

> 采集时间 2026-11-11 00:00~00:03，来自 `python tools/dev.py q`。
> ⛔ 落盘原因：LogX 是 5000 行环形缓冲，且 `K7 perf` 每秒刷屏，23:55 的撤回事件
> 几分钟内就会被冲掉。本文件是**原始日志摘录**，不是结论；结论见
> `docs/_r52_revokeimg_rootcause.md`（Pro lane 产出）与本文件末尾「已证事实」。

## 时间线（好友昵称一律用 verity）

| 时刻 | 事件 |
|---|---|
| 23:55:52.184 | **verity** 撤回一条消息，`AR shape type=268445456 low=10000 isHint=true msgId=19640471` |
| 23:55:52.197 | `RRV record ok=true origType=3`（图片），`imgPath=THUMBNAIL_DIRPATH://th_5a9a0b372dbc9b4465e776185700f1a7` |
| 23:55:52.198 | `VFG protectMedia img=5a9a0b372d..(32) ttl=600000ms pool=1` |
| 23:55:52.201 | `RRV imgAudit md5=5a9a0b372d..(32) row=0 status=-999 iscomplete=-999 hdThumb=-999 big=NULL thumb=NULL hd=NA rawBig=null rawThumb=null` |
| 23:55:52.202 | `AR blocked revoke msgId=19640471` ⇒ 消息行保住 |
| 23:55:52.225 | `VFG blocked MEDIA delete img=5a9a0b372d.. path=/data/data/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/5a/9a/th_5a9a0b372dbc9b4465e776185700f1a7` ⇒ 缩略图文件保住 |
| 23:55:52.307 | 同秒第二条撤回（语音，msgId=19640472, origType=34）：`VG blocked voiceinfo delete` / `VFG blocked file delete`。与图片问题无关 |
| 23:56:32.103 | 微信冷启重打四条 installed；其中 **`MTG installed hooks=delete,execSQL enabled=false pool=ok switch=antirevoke.media_delete`** |
| 23:58:12.875 | 用户点开图片：`HDIMG fired msgId=19640498 localId=875524 t=0 img=SERVERID://1487791032802485058 talker=` ⇒ 大图走 **SERVERID 虚拟路径**（非本地文件名） |
| 23:58:47 / 23:59:16 | `kvget antirevoke.media_delete = false` / `kvget antirevoke.msg = true` |
| 00:00:31 | `kvset antirevoke.media_delete=true -> now=true` |
| 00:00:35 | `kvget` 回读 **true**（写入已复核） |
| 00:02:49 | 冷启后 **`MTG installed hooks=delete,execSQL enabled=true pool=ok`** ⇒ 开关已进内存、反射池可用 |

## 关键原文

```
23:55:52.186 [GT]ColdPool#14 AR shape message type=268445456 low=10000 isHint=true isMark=false msgId=19640471 enabled=true alsoSelf=false content="verity" 撤回了一条消息
23:55:52.197 [GT]ColdPool#14 RRV record msgId=19640471 talker=45424365325@ ok=true origType=3 revoke="verity" 撤回了一条消息 imgPath=THUMBNAIL_DIRPATH://th_5a9a0b372dbc9b4465e776185700f1a7 extra=true origExtra=true
23:55:52.198 [GT]ColdPool#14 VFG protectMedia img=5a9a0b372d..(32) ttl=600000ms pool=1 raw=THUMBNAIL_DIRPATH://th_5a9a0
23:55:52.201 [GT]ColdPool#14 RRV imgAudit msgId=19640471 md5=5a9a0b372d..(32) row=0 status=-999 iscomplete=-999 hdThumb=-999 big=NULL thumb=NULL hd=NA rawBig=null rawThumb=null
23:55:52.202 [GT]ColdPool#14 AR blocked revoke msgId=19640471 self=false content="verity" 撤回了一条消息
23:55:52.225 [D]#worker VFG blocked MEDIA delete img=5a9a0b372d.. name=th_5a9a0b372dbc9b4465e776185700f1a7 path=/data/data/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/5a/9a/th_5a9a0b372dbc9b4465e776185700f1a7
23:55:52.231 [D]#worker VFG dir n=10 bc8a901d68b7d37/image2/5a/9a :: th_5a9a11b4e22d6140d0074b4541262aba(4182),th_5a9a2a15e823a63485b0deb272d7e3a6(4685),5a9a2ad02a2dabd863480bf5e948679d.png(40843),th_5a9a2ad02a2dabd863480bf5e948679d(5888),5a9ab42f30239bb60e786cc996775a92.png(140942),th_5a9a71c82162ff0141f330bf5dd8d204(7211),th_5a9a53d5378201d0f6270a62580574ee(8097),th_5a9a7a2c573e7fb8ce715f385a3f449a(7243)
23:56:32.103 main AR installed (antirevoke.msg=true alsoSelf=false)
23:56:32.133 main VG installed hooks=delete enabled=true
23:56:32.145 main MTG installed hooks=delete,execSQL enabled=false pool=ok switch=antirevoke.media_delete
23:56:32.151 main VFG installed hooks=delete,deleteOnExit
23:58:12.875 wxg-hdimg HDIMG fired msgId=19640498 localId=875524 t=0 img=SERVERID://1487791032802485058 talker=
00:00:31.592 main PROBE kvset antirevoke.media_delete=true -> now=true
00:00:35.487 main PROBE kv antirevoke.media_delete = true
00:02:49.062 main MTG installed hooks=delete,execSQL enabled=true pool=ok switch=antirevoke.media_delete
```

## 已证事实 / 未证事实（⛔ 别越界）

**已证（只读日志 + kv 回读）**
1. 测试当时 `antirevoke.media_delete=false` ⇒ R51 的 `MediaTableGuard` before 第一行即 return，
   **零拦截、零记账**（全程无一条 `MTG` 业务日志）。
2. 旧链路全部正常工作：消息行保住（`AR blocked`）、缩略图文件保住（`VFG blocked MEDIA delete`）、
   语音侧 `VG`/`VFG` 也正常。
3. 开关写入生效（kvget true），冷启后 `installed enabled=true` 已可见。

**未证（⛔ 不许当结论）**
- ⛔ `ImgInfo2` 索引行「被删了」**没有被证明**。`row=0` 也可能是 `auditImage` 关联口径查不到
  （`msglocalid=? or msgSvrId=?` 绑定 `msgId`）、db handle 未捕获、或该行本就不落此表。
- ⛔ 微信撤回删媒体表究竟走 `delete()` / `execSQL` / 预编译 statement / `INSERT OR REPLACE` / native，
  本设备**未取证**。
- ⛔ 「拦住 DB 行 ⇒ 界面能显示大图」只是外部转引实验（掘金 coder-pig），**非本设备实测**。
- ⛔ `HDIMG fired img=SERVERID://...` 说明大图通道依赖服务端 ID；撤回后服务端是否仍可取，未知。

## 下一步（等用户配合）

1. 请 **verity** 在「测试群聊」重新发一张图，先别点开，3~5 秒后撤回。
   ⛔ 23:55 那张已撤的图**大概率不可逆**（`_r51_media_table_guard_impl.md` S4.2：
   已被删的 DB 行 / 文件没有 restore 能力），别拿它当验收样本。
2. 撤回后隔 3~5 分钟点开看大图，同时主会话拉 `dev.py q 'MTG|RRV|VFG|HDIMG'`。
3. 判据对着**用户能不能看到大图**写，⛔ 不许拿 `seen/matched/blocked/row=0` 当结论。
4. 之后关 `antirevoke.media_delete` 再撤一张做反向对照（自变量唯一）。

---

## 追加：00:13 第二次样本（开关已开 + 冷启 enabled=true 之后）⇒ 用户反馈「还是看不到」

| 时刻 | 事件 |
|---|---|
| 00:02:49 | `MTG installed hooks=delete,execSQL enabled=true pool=ok` |
| 00:13:20.783 | **verity** 又撤一张新图，msgId=19640543，md5=`336477acaf3e8c069c9ba6357acbcd3e` |
| 00:13:20.788 | `RRV record ok=true origType=3`，`VFG protectMedia pool=1` |
| 00:13:20.789 | `RRV imgAudit row=0 status=-999 big=NULL thumb=NULL`（与 23:55 同样哨兵值） |
| 00:13:20.790 | `AR blocked revoke`（消息行保住）；00:13:25 界面角标 `已阻止撤回 · 图片` |
| 00:13:20.802 | `VFG blocked MEDIA delete`（缩略图文件保住） |
| **00:13:20~00:13:26** | **⛔ 全程零 `MTG` 记录**（`dev.py q 'MTG'` 只有两条 installed，无任何业务日志） |
| **00:13:26.067** | **`HDIMG fired msgId=19640543 localId=0 t=0 img=null talker=`** ⇒ 点开被撤回图时大图组件拿到的**图片路径是 null** |
| — | 对照：正常未撤回图 `HDIMG fired msgId=19640498 localId=875524 t=0 img=SERVERID://1487791032802485058` |

### 只读 SQL 取证（`op=label sub=on` + `sub=sql`，主库 `EnMicroMsg.db`）
```
select count(*) as n from ImgInfo2                     -> n=842300   （表活着，比基线 842272 多 28 行）
select msgId,type,imgPath from message where msgId=19640543
  -> msgId=19640543 | type=3 | imgPath=THUMBNAIL_DIRPATH://th_336477acaf3e8c069c9ba6357acbcd3e
select id,msglocalid,msgSvrId,bigImgPath,thumbImgPath,origImgMD5 from ImgInfo2 order by id desc limit 3
  -> id=875536 | msglocalid=- | msgSvrId=277239618635419627 | bigImgPath=SERVERID://277239618635419627 | thumbImgPath=THUMBNAIL_DIRPATH://th_e42cbbcce83635996bfeb1b92584e6f7 | origImgMD5=-
  -> id=875535 | msglocalid=- | msgSvrId=-                 | ... 同图 ...
  -> id=875534 | msglocalid=- | msgSvrId=1851185280730641352 | thumbImgPath=THUMBNAIL_DIRPATH://th_f63419f76c970c7429ddf2c1f4d668a9 | origImgMD5=-
```
（`-` = 该列 NULL。）

### ⭐ 硬结论：`row=0` 被证伪为「行被删」的证据
`RevokeRescue.kt:268-277`：`auditImage` 把 **`msgId.toString()`** 绑到 `msglocalid=?`，
`imgAnchorOf(msgId)?.svrId` 绑到 `msgSvrId=?`（拿不到就 `svr=0`，只按 `msglocalid=?` 查）。
但实测 `ImgInfo2.msglocalid` **恒 NULL**、`msgSvrId` 是 **19 位长 ID**（277239618635419627），
而 `msgId` 是 **8 位**（19640543）⇒ **关联口径根本对不上，`row=0` 是必然**。
⛔ 它不是「索引行被删」的证据；`status=-999/big=NULL` 只是 `:253-258` 的哨兵初值。
⇒ R51 候选 B 的**前提假设之一未被证实**，且 `auditImage` 只是观测、不影响拦截。

### 已证 / 未证
**已证**：① 开关 true + 冷启 `enabled=true` 后，新图依然打不开；② `MTG` 零业务记录
（但 `stat()` 未接广播，`seen/matched/skipped/blocked` 全部不可见 ⇒ **连钩子有没有被调用都不知道**）；
③ `message.imgPath` 仍在、磁盘缩略图被 `VFG` 保住 ⇒ **数据在，微信渲染层不再用它**；
④ 点开时 `img=null localId=0` 是用户可见失败的直接表现。

**未证**：微信撤删媒体表走哪条通路（`delete()` / `execSQL` / 预编译 statement / `INSERT OR REPLACE`
/ native）、`ImgInfo2` 目标行撤前撤后是否存在快照、`HDIMG t1` 构造参数为何变 null。

⛔ 按 `CLAUDE.md` §一·五 **D3 判据连塌即停**：连续两轮（开关关 / 开关开）都失败，
不再改判据继续让用户重发图，回头问「是不是问题选错了」。

### 追加取证：`ImgInfo2` 目标区间精确查询（00:31，零装包只读）
```
select id,msglocalid,msgSvrId,bigImgPath,thumbImgPath from ImgInfo2
where id between 875515 and 875545 order by id
```
实存 id：`875515 875516 875517 875518 875519 875520 875521 875522 875524 875525
875526 875527 875528 875529 875531 875532 875533 875534 875535 875536 875537
875538 875539 875540`
⇒ **875523 与 875530 确实不存在**（用 `where id between` 直查，⛔ 不是 `limit`/输出上限截断）。
两个目标 md5（`336477acaf` 00:13 新图、`5a9a0b372d` 23:55 老图）在该区间**均 0 命中**。

格式观察：每张图通常成对出现（低 id 行 `msgSvrId=-`、高 id 行有 19 位 `msgSvrId`，
两行 `thumbImgPath` 同一个 md5），如 `875526/875527`、`875528/875529`、`875531/875532`。
`875529` 与 `875522` 各只剩单行 ⇒ 其配对行可能已消失。

⚠️ **[推断，非定论]** 与「撤回时 `ImgInfo2` 索引行被删」一致，但 id 跳号也可能来自
插入失败 / 非图片消息 / 其它业务删除。⛔ 决定性判据要靠装包后的探针：
撤前/撤后各查一次同一 md5 的行数（`n≥1 → n=0`），并看 `MTG stat` 与全量记账
分清「钩子没被调用」还是「调用但保守放行」。
⛔ 这条同时说明：`RRV imgAudit row=0` 的查询口径错（见上）与「行可能真被删」是**两件独立的事**，
不能互相替代 —— 前者是观测 bug，后者是待证事实。

---

## ⭐ 决定性证据：装包探针版第 3 次样本（01:03:59，verity，msgId=19640720，md5=3fadbde02a…）

装包 `lastUpdateTime 00:59:05`、冷启两次、测前 `op=logclear` + `op=mtg sub=reset`
（账本归零 `seen=0 NO_EVENT`）、撤前基线 `ImgInfo2 max(id)=875555`。

```
01:03:59.041 AR shape ... msgId=19640720 content="verity" 撤回了一条消息
01:03:59.051 RRV record ok=true origType=3 imgPath=THUMBNAIL_DIRPATH://th_3fadbde02a26c4ab4034c3ab3a604e51
01:03:59.053 VFG protectMedia img=3fadbde02a.. ttl=600000ms pool=1
01:03:59.055 RRV imgAudit msgId=19640720 md5=3fadbde02a.. row=0 status=-999 big=NULL thumb=NULL
01:03:59.057 AR blocked revoke
01:03:59.072 VFG blocked MEDIA delete img=3fadbde02a.. path=.../image2/3f/ad/th_3fadbde02a26c4ab4034c3ab3a604e51
01:03:59.080 [D]#worker MTG delete table=ImgInfo2 where=id=? reason=shouldBlock-false gen=1   ★
01:04:37.180 main MTG stat enabled=true gen=1 seen=43 matched=1 blocked=0 skipped=1
             verdict=REAL_EVENT pool=ok tokens=1 events=1 switch=antirevoke.media_delete
```

### 已证结论（⛔ 非推断）
1. **微信撤回删媒体索引行确实走 wcdb `delete()`** —— 钩子亲眼看到 `table=ImgInfo2`。
   「钩子没被调用」「删走 `execSQL` / 预编译 statement / `INSERT OR REPLACE` / native」全部证伪。
2. **`matched=1` / `tokens=1` / `gen=1`** ⇒ 目标表命中、保护池有 md5 token、
   `invalidate()` 已跑（撤回窗口已开）⇒ 不是「没进窗口」也不是「tokens 空」。
3. ⛔ **`reason=shouldBlock-false`** ⇒ 判据自己把这条**放行**了，
   `blocked=0 skipped=1` ⇒ **`ImgInfo2` 索引行被真删掉**。
4. **根因 = 判据覆盖缺口**：`MediaDeleteLogic` 只在 `where`/`args` 里找 26/32 位 md5 token，
   而这条删除是 **`ImgInfo2 where=id=?`（数值主键、args 无 md5）** ⇒ 天然匹配不上。
   ⇒ **R51 的 Hook 链路是对的，错的是判据**。
5. 撤后 `id between 875550 and 875600` 实存 8 行（875550~875557），
   **目标 md5 `3fadbde02a…` 0 命中** ⇒ 与「该行被删」一致。
   ⚠️ 撤前只取了 `max(id)`，没取目标 md5 行数（图是用户现发、事先不知 md5）
   ⇒ 验收清单这一处有缺口，下一轮修：撤前按「最新 N 行全量」留档。

### 判据纪律
⛔ `seen/matched/blocked/skipped/row=0` 只作**定位/归因**，不作功能判据；
主判据始终是「用户点开能否看到完整大图」。
⛔ 已被删的 DB 行**不可回滚** ⇒ 修复判据后必须请 **verity** 重新发一张新图再验收，
不能拿 23:55 / 00:13 / 01:03 这三张老图。

⏳ 待办：用户隔几分钟点开 01:03 那张图 → 读 `HDIMG trace`（确认「库里有 imgPath、
组件却拿到 `img=null`」这一支）→ 按 `docs/_r52_fix_design.md` 选修复路线。

---

## ⭐ 最后一环：点开大图后的 `HDIMG trace`（01:06:34，用户反馈「点开了还是看不到」）

```
01:06:32.206 VFG blocked MEDIA delete img=3fadbde02a.. name=th_3fadbde02a…_tmp.jpg   ← 连临时文件都保住
01:06:34.877 HDIMG fired msgId=19640720 localId=0 t=0 img=null talker=
01:06:34.879 HDIMG trace msgId=19640720 localId=0 img=null t=0 talker= |
              ctor=n=5 type=0 a[0]=Long:0 a[1]=Long:19640720 a[2]=Integer:0
              a[3]=m:com.tencent.mm.modelimage.m@25821f6 a[4]=Integer:0
              fld[h=0 i=0 j=0 o=0 q=19640720] stack=m.h|m.b|u0.x|u0.h|d0.D
01:06:34.881 HDIMG trace db msgId=19640720 imgPath=THUMBNAIL_DIRPATH://th_3fadbde02a26c4ab4034c3ab3a604e51
```
同批还触发了 **00:13 老图**（19640543 / md5=336477acaf…）与 **23:55 老图**
（19640471 / md5=5a9a0b372d…）各一套，形态**逐字一致**：
`a[0]=Long:0`、`fld[h=0 i=0 j=0 o=0 q=<msgId>]`、`img=null`，
而 `HDIMG trace db` 三张图都能读出**完整的 `THUMBNAIL_DIRPATH://th_<md5>`**。

### 因果链闭合（已证）
| 环节 | 证据 | 状态 |
|---|---|---|
| ① `message` 表原图消息行 | `AR blocked revoke` + `HDIMG trace db imgPath=THUMBNAIL_DIRPATH://th_…` | ✅ 在 |
| ② 磁盘缩略图 / 临时文件 | `VFG blocked MEDIA delete`（`th_…` 与 `th_…_tmp.jpg`） | ✅ 在 |
| ③ `ImgInfo2` 索引行 | `MTG delete table=ImgInfo2 where=id=? reason=shouldBlock-false` ⇒ `blocked=0` | ❌ **被删** |
| ④ 大图组件构造参数 | `a[0]=Long:0` / `fld[h=0 i=0 j=0 o=0]` / `img=null`（正常图 `h/i/j=875524`、`img=SERVERID://…`） | ❌ 全 0 |
| ⑤ 用户可见结果 | 「点开了还是看不到」 | ❌ |

⇒ **③ 是唯一断点**：索引行没了 ⇒ 微信 `com.tencent.mm.modelimage.m` 对象取不到
`bigImgPath` / `msgSvrId` ⇒ 构造 `t1` 时 `h/i/j/o` 全填 0、`img=null` ⇒ 大图组件
拿不到 `SERVERID` ⇒ 无法下载/显示原图。
⛔ `HDIMG trace db` 明确排除「微信从 message 表拿不到 imgPath」这一支；
也排除「文件被删」这一支（`VFG` 连 `_tmp.jpg` 都拦住了）。

### 对修复路线的直接含义
**只需让 ③ 不成立**（拦住 `ImgInfo2 where=id=?` 这条 delete），
按上表①③④ 的因果方向，④ 应恢复为正常的 `h/i/j=<localId>` + `img=SERVERID://…`
⇒ **预期不需要改渲染层 / 不需要重建消息 / 不需要额外写库**。
⚠️ 这是 `[需真机]` 的预期，不是已验证结论：修复装包后必须请 **verity** 重新发一张新图
（老图行已删不可回滚），以「能否看到完整大图」收口。
