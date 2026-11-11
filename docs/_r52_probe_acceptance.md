# R52 探针装包后真机验收清单（阶段 3）

> 目的：一次装包、一次用户配合，把三个问题同时抓完 ——
> ① `MediaTableGuard` 钩子到底有没有被调用、被谁放行；② 微信撤删媒体表走哪条通路；
> ③ 被撤回图点开时大图组件为什么拿到 `img=null / localId=0`。
> ⛔ 主判据始终是**用户能不能看到完整大图**，不拿任何内部计数当结论。

## 0. 前置开关（00:47 已 `kvget` 回读复核，全部满足）

| key | 真值 | 为什么必须 |
|---|---|---|
| `antirevoke.media_delete` | **true** | 本轮 R51 钩子总开关；关了 before 第一行就 return |
| `antirevoke.msg` | **true** | 消息防撤回（`AR blocked revoke`） |
| `debug.log` | **true** | 不开连 `installed` 都看不到 |
| `chat.auto_hd_img` | **true** | ⭐ lane B 第一复核项：`AutoHdImg.kt:192` 的 `if(!Kv.getBool(Keys.CHAT_AUTO_HD_IMG)) return` 挡在 `HDIMG sig/fired/trace` **全部之前**；默认 `false`，关了 `HDIMG trace` 一行都拿不到 = 白装 |
| `chat.auto_hd_allow` / `chat.auto_hd_exclude` | `[]` / `[]` | 非默认会让 `:463-470` 名单分支提前 return，`trace` 不执行 |

⛔ 微信**必须在前台**（`am start -n com.tencent.mm/.ui.LauncherUI`）；后台时 AMS 回
`Broadcast completed` 但模块不收，日志静默不变 —— 00:47 已实证踩过一次。

## 1. 装包后验挂（主会话）

1. `adb install -r` 后 **冷启两次**（`dev.py restart`），拉 `dev.py q 'installed'` 确认：
   `AR installed` / `VG installed` / `VFG installed` / **`MTG installed hooks=delete,execSQL enabled=true pool=ok`** /
   `HDIMG installed`。
2. `op=logclear` 清 LogX。**⭐ 措辞修正（lane A 补核 `LogX.kt:22,79-88,99-100`）**：
   「满 5000 行」不是逐行环形挤掉，而是 `writeText` **整文件清空重来**，并留一行
   `---- log rotated: 已达 5000 行，清空重来 ----` 标记。两个新探针共用同一 `lines` 计数，
   会更快触顶 ⇒ 测前必须清、观测窗口尽量短。
3. 探活：`op=mtg sub=stat` ⇒ 应出现 `MTG stat enabled=true ... seen=0 matched=0 ...`；
   `op=mtg sub=reset` 可归零账本（⛔ 不许清 `enabled`/`cachedTokens`/`gen`/`lastIssuedMs`）。
   ⭐ **账本在进程内存、不受日志清空影响**，这是本轮最硬的兜底证据。
4. `op=label --es sub on --ei sec 180` 开主库捕获窗口（`dbs>=1` 才算抓到 `EnMicroMsg.db`）。
5. ⭐ **「只有 installed、拉不到业务行」先别急着判「钩子没被调用」**：
   先 `dev.py q 'log rotated'` 看有没有那行标记 —— **有标记 = 业务日志确实打过但被整批清空**，
   可直接排除「没调用」；无标记再回头查开关 / 广播投递。这条正是本轮痛点
   （00:13 那次只能看到 installed）的解法。

## 2. 撤前快照（⭐ 必须，否则判不了「行被删」）

按 `thumbImgPath` 的 md5 查 `ImgInfo2`，**零单引号**（用 `order by id desc limit 60` +
本地 grep，或 `where id between A and B`）：
```
select id,msglocalid,msgSvrId,bigImgPath,thumbImgPath from ImgInfo2 order by id desc limit 60
```
记下「这次新图的 md5 在库里出现的行数 `n_before`」。
⛔ 别再用 `msglocalid=?` 绑 8 位 `msgId` 的口径（`RevokeRescue.kt:268-277` 已证恒查不到）。

## 3. 请用户配合（唯一人工步骤）

请 **verity** 在「**测试群聊**」发一张图 → **先别点开** → 3~5 秒后撤回 → 告诉我已撤回。
⛔ 别拿 23:55 / 00:13 那两张老图验收（已不可逆）。

## 4. 撤后立即抓（主会话，一次拉完）

```
dev.py q 'MTG|RRV|VFG|AR |HDIMG|no db instance' 160
op=mtg sub=stat          # seen / matched / blocked / skipped / tokens
```
再查一次 `ImgInfo2` 同一 md5 的行数 `n_after`。
判读：`n_before>=1 → n_after=0` 才证「索引行被删」；配合 `MTG stat` 分清
「`seen=0`（钩子没被调用 / 删走别的通路）」vs「`seen>0 skipped>0`（调用但保守放行）」。

## 5. 隔 3~5 分钟点开大图（用户配合）

立刻 `dev.py q 'HDIMG' 40`（⛔ 别等，日志会被环形覆盖）。三种读法：
- 无 `HDIMG fired` ⇒ 大图组件压根没被构造；
- `HDIMG trace db imgPath=no row / null col` ⇒ 微信从 `message` 表拿不到 `imgPath`；
- `db` 行有 `THUMBNAIL_DIRPATH://th_…` 却仍 `img=null localId=0` ⇒ **拿到了但被上层丢弃**
  （手工 SQL 已证库里有路径，此支最可能 `[推断]`）。

`no db handle` 只代表探针没抓到主库句柄、判不了，需先 `op=label sub=on` 补抓 `[需真机]`。

## 6. 反向对照（自变量唯一）

只把 `antirevoke.media_delete` 设回 `false`（⛔ 其它开关一个都不动），再撤一张新图，
重复 §2~§5。用户可见判据同为「能否看到大图」。
⚠️ 对照完记得把开关设回 `true` 并 `kvget` 回读（R42 踩过「只关一个开关其实还照拦」的坑，
不过本功能只有一个开关，仍需回读确认）。

## 7. 回滚路径

- lane B：`git checkout -- wxgroup/app/src/main/java/com/lx/wxgroup/feature/AutoHdImg.kt`
- lane A：`MediaTableGuard.kt` 是 **untracked**（R51 新建、无 HEAD 版）⇒ ⛔ **不能 `rm` 整文件**
  也不能 `git checkout --`；按 `docs/_r52_probe_a_impl.md` T5 精确移除本轮新增块
  （`resetLedger()` / `obsLogAt` / `observe()` / `emitThrottled()` / `onBefore` 记账 /
  `EntryReceiver.kt` 的 `"mtg"` 分支），**保留 R51 基线**。
  ⚠️ `EntryReceiver.kt` 的 diff 混有 **R49 阶段二 `SnsWriteProbe` 的 11 行**，⛔ 别整文件 checkout。
  （复核 lane 指出本节原写「删掉即回」与 a_impl T5 冲突，**以 a_impl 为准**。）
- ⛔ **严禁** `git checkout .` / `git reset --hard` / `git clean -fd`：工作区还有
  R48/R49/R50/R51 大量在途未提交改动，全量回退会毁掉别的功能。
