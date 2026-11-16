# R54 · 分组条改走数据层伪会话 —— 设计方案

> 目标：把分组条目从「adapter 层自绘 `GroupRowView`」改为「微信主库伪会话行 + 原生渲染」。
> 竞品微X 已实测走通（见 `docs/_r53_microx_grouprow.md` 第八节）。本文只出设计，不动 `app/src/main`，不跑 gradle，不碰 adb。
>
> 证据分档：**[已证实]** 有真机/代码证据 ｜ **[推断]** 由已知事实合理外推 ｜ **[未验证]** 没有证据，需真机探针。
> 每条给「判据 + 验证方法 + 失效场景」。

## 1. 命名空间：伪会话 username 前缀

**定案**：我方前缀 `wxgrp_`（6 字符，`probe/GroupConvProbe.kt:50` 的 `PREFIX` 已落盘），username 形如 `wxgrp_<groupId 的 16 位小写 hex>`。**绝不用 `wx__`** —— 那是微信自己的命名空间，微X 用的 `wx__c_` 正是踩在别人的命名空间里（R53 §8.5）。

**结论 1.1 [已证实]** `wxgrp_` 与微信自有前缀不冲突：真机库里真实 username 形态是 `wxid_*`（个人）/ `@chatroom`（群）/ `gh_*`（公众号）/ `wx__c_*`（微信内部，微X 借用），我方前缀不属于任何一类 ⇒ 按前缀隔离的 SQL 不会误伤宿主行。
- 判据：干净机上 `select count(*) from rconversation where substr(username,1,6)='wxgrp_'` = 0；`clean()` 前后「非本方前缀行数」必须一次都不变（`GroupConvProbe.kt:39-40`、`:376` 已实现自证）。
- 验证：`op=gconv sub=stat` 读 `conv total/own/other` 三项，`other` 在 `trial`/`clean` 前后恒定。
- 失效场景：未来微信版本引入 `wxgrp_` 开头的内部 username ⇒ 撞车。缓解是前缀后再带魔数（如 `wxgrp_v1_`），代价是变长；8.0.48/2588 无此风险。

**结论 1.2 [推断]** 字符集与长度安全：`wxgrp_` + 16 位 hex 全落在 `[a-z0-9_]`，无大写、无非 ASCII、无 SQL 特殊字符；`username` 是 TEXT 列无长度约束，24 字符远短于任何隐含上限。
- 判据：insert 不抛 `SQLiteException`；`GroupConvProbe.insertConv()` 两段式（先 7 列靠默认值、失败再显式写全 20 列）至少有一档成功（`:233-259`）。
- 验证：`sub=trial` 看 `insert conv ... OK stage=minimal/full`；`sub=sql --es sql 'select username,length(username) from rconversation where substr(username,1,6)="wxgrp_"'`（只读）回读。
- 失效场景：列实际带 NOT NULL / CHECK / 唯一索引约束而 dump 里没体现 ⇒ minimal 档失败。此时**不许吞异常**，报错原文（no such column / constraint failed）本身就是取证产出（`:34-35`）。⚠️ 这说的是**写路径** `execTry()`（`:493-497`，失败返回 `异常类: message`，原样不吞）；**读路径方向相反** —— §2 的 `query()`/`readAll()`/`runSql()` 吞异常、列名缺失只表现为 `rows=0 cols=0`。别把两条混成一句「探针会报 `no such column`」。

**结论 1.3 [已证实]** 微信对未知 username 有过滤，但**靠 `rcontact` 同名行绕过**：微X 12 条 `wx__c_` 行都配了一条 `rcontact` 行（`type=0`、`showHead=87`、名字/拼音列全空、`createTime=0`、`deleteFlag=0`、`chatroomFlag=0`），目的就是「让微信的会话渲染拿得到联系人对象」（R53 §8.3/8.4）。只插 `rconversation` 不插 `rcontact` ⇒ 渲染期取联系人对象为 null ⇒ 行空白或不显示。
- 判据：两表按同一 `wxgrp_` 前缀的行数必须相等（`stat` 的 `convOwn == contactOwn`）。
- 验证：`sub=trial` 后 `sub=stat`，再回微信看会话列表是否出现这两行。
- 失效场景：微信版本改了联系人对象取值链（不再按 username 查 `rcontact`）⇒ 两表都写了也不显示；此时 `stat` 仍显示行数相等，只能靠界面判据发现。

**结论 1.4 [推断]** groupId ↔ username 必须可双向映射且不依赖额外查表：`wxgrp_` + `groupId.toULong().toString(16)` 定长补零到 16 位小写 hex，反解 `removePrefix("wxgrp_").toULong(16).toLong()`。§2 的标题注入、§4 的点击拦截都要从 username 反查 groupId，不能每行查我方库。
- 判据：`Group.id` 是 `Long`（`GroupDb.kt:1006-1014`），历史脏数据可能为负（`ConvLayout` 注释里明确「分组 id 本身可能就是 -1」）⇒ 必须走 `toULong` / 定长补零，不能用 `toLong(16)` 直接解负数。
- 验证：JVM 单测覆盖 `Long.MIN_VALUE / MAX_VALUE / 0 / -1` 四个边界的往返；`sub=sql` 回读的 username 集合与 `GroupDb.listGroups()` 的 id 集合做差集应为空。
- 失效场景：负 id 被截断 ⇒ 反解出错误 id ⇒ 点错组。缓解：首次启用做一次 clean-then-insert 全量重建（§7），不信任历史行。

[DONE] 第1节

## 2. 组名怎么让微信显示

### (a) 写 `rcontact.nickname` 会不会让伪账号进通讯录 / 搜索 / 群成员选择器？`type=0` 够不够排除？

**结论 2.1 [已证实]** 「名字不写进库」是已被真机走通的形状：微X 12 条 `rcontact` 行的 `conRemark`/`nickname`/`pyInitial`/`quanPin`/`conRemarkPYFull`/`conRemarkPYShort`/`alias`/`encryptUsername` 全空，界面上的「美妆」「日化」是渲染期塞进去的（R53 §8.4）。⇒ 数据层只存一个「空名联系人」是可行基线。

**结论 2.2 [未验证]** 写 `nickname='组名'` 后伪账号是否进通讯录 / 搜索 / 群成员选择器、**`type=0` 是否足够排除，现无证据**。这正是 `GroupConvProbe.trial` 两条对照行要回答的：`wxgrp_t1` 写 `rcontact.nickname='测试组A'`、`wxgrp_t2` 不写昵称，两条 `rconversation` 行完全一致、唯一差别就是昵称（`GroupConvProbe.kt:163-165`）。
- 判据：`sub=trial` 后三处都**不出现 `wxgrp_t1`** 才算 `type=0` 排除成立 —— ① 通讯录列表按 W 字母区（微X 行 `showHead=87`='W'，这个字段本身就是通讯录字母索引）找「测试组A」；② 微信全局搜索「测试组A」；③ 发起群聊 / 转发 / 群成员选择器里搜「测试组A」。
- 验证：用 `wxgrp_t2`（无昵称）做空白对照。若 t1 出现而 t2 不出现 ⇒ 泄露源是 nickname 而非 `type`；再试把 `type` 改成非好友位或 `deleteFlag=1` 看是否消失（但 `deleteFlag=1` 可能连会话行都不渲染，必须同时看列表）。
- 失效场景：通讯录查询不按 `type` 过滤、只按 `deleteFlag/showHead` 过滤 ⇒ `type=0` 挡不住；或搜索走 `rcontact` 全表 FTS ⇒ 连空名行都会以 username 形式冒出来。

**⚠️ 前置校验（复核 P1 后补，[已证实]）**：`trial` 的判别力**完全依赖两条对照行的 `nickname` 确实不同**。我第一轮读到的 `insertContact()` minimal 路径只写 `username/type/showHead`（`:290-292` 旧版），`nickname` 被漏掉 ⇒ 一旦 minimal 插入成功，t1/t2 会变成一模一样，**trial 零判别力**，任何「写昵称能不能显示」的结论都是假的。该缺陷现已修复（`GroupConvProbe.kt:290-292` 的 `minimalCols` 含 `nickname`，并留了「t1/t2 的唯一自变量就是它」的注释）。但**每次跑完 `sub=trial` 之后、回列表看效果之前，仍必须回读确认**，不能假设修复没回退：
- ⚠️ **顺序硬约束**：回读必须在 `trial` **之后**。若在插行前查，`wxgrp_t1/t2` 还不存在，即使是完全合法的 SQL 也会返回 0 行 ⇒ 被误判作废。本节验收命令 ①→② 的先后就是这个意思。
- 判据（一条 `sub=sql`，看 `runSql` 打出的 `sql db=... rows=X cols=Y`，三分支）：
  - `cols=0` ⇒ SQL 没编译成功（列名缺失 / 表不存在 / `rawQuery` 反射失败）→ **本轮作废**；
  - `cols>0 && rows=0` ⇒ 列在、但我方行不在（trial 没插上 / 前缀不匹配）→ **本轮作废**；
  - `cols>0 && rows=2` 且 t1 的 `nlen>0`、t2 的 `nlen=0` ⇒ **通过**。
- 验证：见本节验收命令第 ② 步。**不能靠「有没有报错」判断列名** —— 探针读路径吞异常：`query()`（`:464-475`）是 `runCatching { m.invoke(db, sql, null) }.getOrNull()`，`readAll()`（`:514`）对 null 返回 `emptyList() to emptyList()`，`runSql()`（`:414`）只打 `rows=X cols=Y` 不打异常。⇒ 列名不存在时**不会**报 `no such column`，表现是 `rows=0 cols=0`。用 `cols` 区分才稳。
- 失效场景：minimal 插入失败退到 `full` 档时 `lit["nickname"]` 仍生效（`:278`），所以只有 minimal 成功那一档会暴露漏写；这正是「先看日志 `insert contact ... OK stage=minimal/full` 再回读」的原因。
- 失效场景（复核 lane 补充，**这才是「每次都回读」而非「修完就信」的主因**）：`minimalCols` 末尾带 `.filter { it in names }`（`GroupConvProbe.kt:292`），`names` 来自运行时 `pragma table_info(rcontact)`。若真机该表**没有 `nickname` 列**或列名漂移（大小写 / 别名 / 版本差异），`nickname` 会被**静默过滤掉**，minimal 又退回「t1/t2 一样」，而 insert 仍报 `OK` —— 代码侧修复再正确也挡不住运行时表结构。上面那条 `sub=sql` 回读同时兜住**三类**成因：① 代码回退漏写列（`cols>0 && rows=2` 但 t1/t2 的 `nlen` 相同），② 运行时 pragma 把列静默过滤掉（`cols=0`），③ trial 未插行（`cols>0 && rows=0`）。⚠️ 别写成「`select length(nickname)` 报 `no such column` 顺带做列名校验」—— 探针吞异常，列名缺失只表现为 `cols=0`（见上面验证条），没有报错可读。

**结论 2.3 [推断 · 待验假设，非既定事实]** 若 2.2 判据通过（写昵称不泄露）**且**上面的前置校验确认 trial 有判别力，**路线 A 才成立**：`rcontact.nickname = 组名`，零渲染 hook、改名只需一条 update，规避了 2.6 全部混淆名风险。**在 `op=gconv sub=trial` 出结果之前，不得把「库里 nickname 直接生效」当既定事实写进实现**（复核 `docs/_r54_probe_review.md` 的 P1）；默认应先按路线 B（§2.5 渲染期注入）实现，A 作为可开关的降级/优化路径。代价：组名会出现在聊天页标题（点进去时）—— 但 §4 会拦掉点击，不让它进聊天页。
- 判据：`select nickname from rcontact where username='wxgrp_t1'` 回读到「测试组A」，且列表该行标题同步显示（不重启）。
- 验证：`op=gconv sub=rename --es val 新名字`（`GroupConvProbe.kt:321-339`）改完**不重启**回列表看名字变没变 —— 变了说明实时读库，没变说明联系人对象有内存缓存（需触发生效或改走路线 B）。
- 失效场景：微信把 `nickname` 缓存进联系人对象 ⇒ 改名要重启/失效缓存；或写昵称触发 2.2 的泄露 ⇒ 必须回退路线 B。

### (b) 渲染期注入：具体候选 hook 点

> ⛔ **本节 2.4~2.6 整段已被 §10.2/§10.3 推翻，仅作调研留档，不得照做实现。**
> 两条硬伤：① `l7a` **不是 `TextView`** 而是宿主自绘 `NoMeasuredTextView`，`setText` 无从下手；
> ② 保留 `GroupedAdapter` 包装链走 passthrough 即可零新增 hook 拿到每行 `item`/`View`，
> 完全不需要去 hook 混淆类 `p3.getView`。最终方案见 §10.3。

**结论 2.4 [前半已证实 · "标题 TextView" 部分已推翻]** 可挂的宿主层是明确的（conv_list.md 首节 + R53 §2.3）：`mAdapter = com.tencent.mm.ui.conversation.p3`，`getItem() = com.tencent.mm.storage.r4`（含 `field_username`），行标题 View 的 resource-id 是 `l7a`（时间 `q5l`、摘要 `igs`、分隔线 `d_l`）。⚠️ 但 `l7a` 是**自绘 View 而非 TextView**，下文按 `findViewById<TextView>` 覆盖标题的做法不成立。

**结论 2.5 [推断] 首选 hook 点（一个就够，最稳）**：
```
com.tencent.mm.ui.conversation.p3.getView(int position, View convertView, ViewGroup parent)   // after
```
回调内：`XposedHelpers.callMethod(param.thisObject, "getItem", position)` 拿 `r4` → 读 `field_username` → `startsWith("wxgrp_")` 时按 §1.4 反解 groupId → `findViewById<TextView>(R.id.l7a)` → `setText(组名)`。
- 判据：`p3` 是 `BaseAdapter` 子类 ⇒ `getView(int, View, ViewGroup)` 签名必然存在；`r4` 有 `field_username`（两者均已证实）。
- 验证：先只在 after 里打 `position/username` 日志、**不 setText**，滚列表确认每个 `wxgrp_` 行都命中且 position 与界面顺序一致；再放开 setText 看标题变成组名。
- 失效场景：微信把标题绑定放在 `getView` 返回之后（异步/预布局）⇒ 我们的 setText 被覆盖。退路：hook `android.widget.TextView.setText(CharSequence)` 的 before，仅在 `this` 的 id == `l7a` 且当前行 username 命中时替换（更重，且必须防递归）。

**结论 2.6 [未验证 · 以下类名/签名全部是猜的，未在任何文档或真机里证实]** 若想从「名字解析服务」层注入（一处改、聊天页标题也跟着对），候选：
- `com.tencent.mm.model.r.a(String, ...)` / `.eX(String)`（历史版本取显示名的静态工具）
- `com.tencent.mm.storage.ContactStorage` / `com.tencent.mm.model.c` 的 `get(String)` 返回联系人对象后改 `field_nickname`
- `com.tencent.mm.ui.conversation.p3.a(r4, ...)`（adapter 内部 bind 辅助方法）
- 判据 / 验证：用 `DexKitLocator` / `DexProbe` 按「`wxgrp_` 前缀字符串 + `l7a` 资源 id 引用」反查调用点；或 hook `TextView.setText` 打调用栈，按 conv_list.md 菜单探针的纪律**剔掉 LSPosed 桥接帧、从第 4 帧起读真实调用点**，拿到确切类名后再回来替换猜测。**在命中前不得把这三条写进实现。**
- 失效场景：混淆名逐版本变（8.0.48 → 2588 → 后续），猜的类名可能一个都对不上 ⇒ 全部静默失配。路线 2.5 只依赖已证实的 `p3`/`r4`/`l7a`，所以是兜底而非备选。

**结论 2.7 [推断]** 摘要/时间：竞品 12 条伪会话 `content`/`digest` 长度**全 0**、`msgType=1`、`status=3`、`chatmode=1`、`isSend=1`、其余数值列全 0、其余字符串列空串 ⇒ 界面上只有「组名 + 时间」，没有灰色摘要；带灰色摘要的 pet片材 / 美妆 / chinaplas 是**真实群聊**。⇒ 我方**不要写 `digest`/`content`**，保持 `''`，行视觉才与竞品一致。
- 判据：`select length(content),length(digest) from rconversation where substr(username,1,6)='wxgrp_'` 全 0。
- 验证：`sub=trial` 后看列表行有没有冒出灰色摘要。
- 失效场景：宿主对 `digest` 为空做兜底渲染（显示 username 或占位）⇒ 视觉多一行；届时用 2.5 的同一个 after hook 把 `igs` 置空。

**验收命令（§2 的 [未验证] 项，真机可直接粘贴）**
```bash
A="/c/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
P="am broadcast -a com.lx.wxgroup.action.PROBE --es op gconv -p com.tencent.mm"
# SQL 值在设备端 sh 里已包一层单引号 ⇒ 前缀用 char(...) 代替带引号的字面量；
# 不用 > / &（会被 sh 当重定向/后台），不用 like 'wxgrp_%'（_ 是单字符通配）
W="substr(username,1,6)=char(119,120,103,114,112,95)"

# ① 插两条对照行：t1 写 nickname='测试组A'，t2 不写
"$A" shell "$P --es sub trial"
# ② 回读 rcontact（⚠️ 必须在 ① 之后，此时 t1/t2 才已插入）：
#    先看日志 sql db=... rows=X cols=Y 做三分支判定，再看 t1 有昵称(nlen>0)、t2 空(nlen=0)、type/showHead 一致
"$A" shell "$P --es sub sql --es sql 'select username,nickname,length(nickname) as nlen,type,showHead from rcontact where $W order by username'"
# ③ 回微信看列表标题（t1 是否显示「测试组A」、t2 是否无名字）；
#    再去 通讯录 W 字母区 / 全局搜「测试组A」/ 群成员选择器 三处看 t1 有没有冒出来
# ④ 改名不重启是否实时生效
"$A" shell "$P --es sub rename --es val '测试组B'"
"$A" shell "$P --es sub sql --es sql 'select username,nickname from rcontact where $W order by username'"
# ⑤ 收尾（幂等）
"$A" shell "$P --es sub clean"
```
判据：② 按三分支判定 —— `cols=0` 作废 / `cols>0 && rows=0` 作废 / `cols>0 && rows=2` 且 t1 `nlen>0`、t2 `nlen=0` 才通过；任一作废都先修探针、本轮不下结论（探针吞异常，不会给 `no such column`，只看 `rows/cols`）。③ 三处都不出现 `wxgrp_t1` ⇒ §2.2 的 `type=0` 排除成立；出现 ⇒ 回退路线 B（§2.5 渲染期注入）。④ 名字变了 ⇒ 实时读库，否则联系人对象有内存缓存。§2.6 那三条猜的 hook 点**不在本轮验**，用 `DexKitLocator` 反查调用点命中后再说，绝不先写进实现。

[DONE] 第2节

## 3. 刷新链路：写完库怎么让列表立刻看到

**结论 3.1 [已证实]** 冷启必可见：数据层行的持久化是天然的（R53 §8.5），写进 `EnMicroMsg.db` 后重启/杀进程都在；竞品 12 条跨多次冷启仍在。⇒ 「立刻看到」失败时的降级路径是下次 `onResume` / 冷启，不会永久丢。
- 判据：重启后 `sub=stat` 的 `convOwn` 不减。
- 验证：`sub=trial` → 强停微信 → 冷启两遍（R53 §七的装包纪律）→ 看列表。
- 失效场景：微信冷启时按「会话必须有配对消息/联系人」清掉孤立行 ⇒ 冷启也看不到；`stat` 的 `convOwn=0` 即暴露。

**结论 3.2 [推断]** 纯写 DB **不会**让当前列表刷新：`p3` 的 `getCount()`/`getItem()` 读的是 `ConversationStorage` 的内存会话列表（K7 的 `readSnaps` 走 `getItem(i)` 拿 `r4`，conv_list.md 首节），写库不经过宿主自己的 insert/update 路径 ⇒ 内存缓存里没有新行。必须主动触发「缓存失效 + adapter 通知」，只 `notifyDataSetChanged()` 是假修法。
- 判据：`sub=trial` 后**不重启**直接看列表：没出现新行 = 缓存未失效。
- 验证：hook `com.tencent.mm.ui.conversation.p3.notifyDataSetChanged()` 的 after 打栈，看写库后有没有被调到；同时 hook 宿主会话查库方法看有没有重查。
- 失效场景：只调 notify 但内存列表没重查 ⇒ `getCount()` 不变、行仍不出现。判据就是「notify 日志打了但列表没变」，别把它当成功。

候选（按侵入性从低到高，逐个验，不要一次全上）：

**结论 3.3 [推断] 候选 A — 让宿主自己重查**：hook 宿主 `ConversationStorage` 的「取会话列表」方法（类名/混淆名**未证实**，用 `DexKitLocator` 按 `select ... from rconversation` 字符串反查），写库后调它一次 `doNotify` / 发 `ContentObserver` 等价通知，触发微信自己的 observer 链（内部是 `MStorage`/`MStorageEx` 的 `doNotify`）。好处：宿主缓存与 adapter 一起更新，顺序不会错。
- 判据：调用后 `p3.getCount()` 增加我方行数。
- 验证：`sub=trial` → 触发一次该通知 → 看列表（探针里可加一个实验档 `sub=notify`，不动 `sql` 的只读白名单）。
- 失效场景：混淆名随版本变；或宿主在查库后又按「未知 username」过滤掉行 ⇒ §1.3 的 `rcontact` 同名行必须先写好。

**结论 3.4 [推断] 候选 B — piggyback 我方已有 observer**：`ConvFilter` 已有 250ms 去抖的 `onDataChanged()`（conv_list.md「observer 不能走 `refresh()`」那条：`refresh()` 会 `setSelectionFromTop` 把列表拽回顶部，收消息时打断阅读）。把「写库后通知 + §5/§6 的补偿写」挂到同一条去抖队列，**不新增常驻轮询**。
- 判据：`K7 perf ...` 仍压在 `PROBE_WARN_MS = 8L` 闸门内，不因新增通知多一次全量重建。
- 验证：`dev.py q 'K7'` 看写库后是否只触发一次去抖、无重复 rebuild。
- 失效场景：gconv 开关打开后 `GroupedAdapter` 已被直通（§8），`onDataChanged` 里持有的宿主 adapter 引用要指向 `p3` 而不是包装层，否则通知打空。

**结论 3.5 [未验证] 候选 C — 直接反射通知**：取 `HeaderViewListAdapter` 的 `mAdapter`（= `p3`，conv_list.md 首节）反射调 `notifyDataSetChanged()`，并手动把新 `r4` 塞进宿主内存列表。侵入最大，只在 A/B 都失败时试。
- 判据 / 验证：需先知道宿主容器类型（`ArrayList` / `SparseArray`）与 `r4` 构造方式，均未取证；`sub=sql` 只读 SQL 帮不上，得靠 `DexProbe` dump 字段。
- 失效场景：塞错位置会打乱 2900 项顺序 ⇒ 点错人（conv_list.md K7 的 R1 根因）。这一档必须最后做，且要有冷启兜底。

**验收命令（§3 的 [推断]/[未验证] 项，真机可直接粘贴；前置变量同 §2 的 `A` / `P` / `W`）**
```bash
# ① 写两条对照行后【不重启】，直接回微信看会话列表有没有出现
"$A" shell "$P --es sub trial"
"$A" shell "$P --es sub sql --es sql 'select username,flag,conversationTime from rconversation where $W order by username'"
# ② 看宿主有没有把行读进内存：hook com.tencent.mm.ui.conversation.p3.notifyDataSetChanged 打栈
#    （日志 0 条 ⇒ 候选 A 不成立；「notify 打了但 getCount() 不变」= 只通知未失效缓存的假修法）
# ③ 冷启兜底判据：强停 + 冷启两遍后再 stat（convOwn 不减即数据层持久化成立）
"$A" shell am force-stop com.tencent.mm
"$A" shell am start -n com.tencent.mm/.ui.LauncherUI
"$A" shell "$P --es sub stat"
# ④ 清场
"$A" shell "$P --es sub clean"
```
判据：① 不重启就能看到 = 刷新链路成立；只有 ③ 冷启才看到 = 必须补 §3.3 / §3.4 的缓存失效。`sub=sql` 是**只读**通道（`GroupConvProbe.kt:385-397` 只放行 `select` / `explain` / 只读 pragma 且禁 `load_extension`），不要试图用它写库触发刷新。

[DONE] 第3节

## 4. 点击与长按拦截

**结论 4.1 [已证实]** `performItemClick` **零命中**，行点击走**每行自己的 OnClickListener**（conv_list.md 首节；`ConvFilter.kt:474-476` 与 `:942-945` 两处 KDoc 均写明，那条三层 `hookItemClick` 已于 2026-10-25 作为死代码删除）。⇒ 伪会话行由微信原生 `p3.getView` 绑定监听器，点击会打开 `username=wxgrp_xxx` 的**空白聊天页**，必须拦；**不要再挂 `performItemClick` / `onItemClick`，也不要试图改 position 映射**（conv_list.md 明确「改 realPosition / 位置映射治不了点错人」）。

**结论 4.2 [推断]** 拦截落点 = §2.5 的**同一个** `p3.getView(int, View, ViewGroup)` after hook：微信在 `getView` 内 `setOnClickListener`，我们在 after 里覆盖行 View 的 listener 即可（View 级覆盖，不碰 ListView 级）：
```
// after getView，r4.field_username 以 wxgrp_ 开头时：
XposedHelpers.callMethod(rowView, "setOnClickListener", ourClick)
XposedHelpers.callMethod(rowView, "setOnLongClickListener", ourLongClick)
```
- 判据：覆盖前先只打日志，确认每个 `wxgrp_` 行的 `getView` 都命中、且 `param.args[0]`（position）与界面顺序一致。
- 验证：**真手点**该行（⚠️ adb 长按会被识别成点击，conv_list.md 菜单节），看是否还进空白聊天页；覆盖后应进我们的页面。
- 失效场景：微信在 `getView` 返回**之后**再绑 listener（异步 bind）⇒ after 覆盖被冲掉。退路：hook `android.view.View.setOnClickListener(View.OnClickListener)` 的 before，按 `this` 是当前 `wxgrp_` 行时替换 `param.args[0]`（更重、要防递归）。另：convertView 复用导致微信只在首次绑定 listener，after 每次覆盖正好解决，不会漏。

**结论 4.3 [推断]** 拦到后打开 `GroupDetailPage.open(ctx, groupId, groupName)`（`GroupDetailPage.kt:44`），**不能走 `ConvFilter.setGroup(gid)`**：`setGroup` 生效路径是 `rebuild()` 里 `filterId > 0` 分支的 `ConvLayout.filtered(...)`（`ConvFilter.kt:1277-1282`），而 gconv 开关打开后 `rebuild()` 恒返回直通 layout（§8），筛选链路已停用 ⇒ 走它会「点了没反应」。产品形态上竞品也是换页（R53 §10.5「微X 点分组是换页」）。
- 判据：gconv 开时点分组行，日志应是 `DETAIL open group=...`（`GroupDetailPage.kt:62`），而不是 `K7 full rebuild ... layout=1` 的筛选态日志。
- 验证：两个组来回点，确认 `DETAIL open group=` 的 id 与点中的组名一致。
- 失效场景：`GroupDetailPage.open` 的 ctx 回退链是 `HostContext.activity() ?: context ?: HostContext.app()`，而 `HostContext.activity()` 靠 `Activity.onResume` hook、LauncherUI 覆写 onResume 后**恒 null**（conv_list.md 菜单节、GroupRowMenu KDoc）⇒ **必须传行 View 的 `getContext()`**（必是 Activity），与 `GroupRowMenu.show` 的处置一致，否则 BadTokenException。

**结论 4.4 [已证实]** 长按走 View 级 `OnLongClickListener` + `GroupRowMenu.show(viewCtx, groupId)`，复用现有 A5 逻辑（`GroupRowView.kt:260` 现状就是这么做的；`GroupRowMenu.kt:60`）。**不要注入微信的 PopupWindow 长按菜单**：那是自绘窗口、`MenuBuilder.add` / `createContextMenu` 零命中（conv_list.md 菜单节），且包装 ListView 级 listener 会让微信 4 项菜单消失（`Keys.CHAT_CONV_LONGCLICK_REMAP` 默认 false）。
- 判据：长按回调**必须 return true**（否则冒泡到 `ListView.OnItemLongClickListener`，微信按会话处理弹「删除该聊天」/ 变点击，`GroupRowView.kt:27-29`）。
- 验证：真手长按该行，看弹的是我方 5 项菜单（置顶/重命名/管理成员/标记已读/删除分组）；`GroupRowMenu.activityOf()` 从 `View.getContext` 沿 `baseContext` 剥 Activity（`GroupRowMenu.kt:41-49`），不会 BadTokenException。
- 失效场景：`conversation.z2` 的 `onItemLongClick` 回环（conv_list.md）只在**包装 ListView 级 listener** 时触发；我们只挂 View 级、不碰 `setOnItemLongClickListener` ⇒ 不触发。若误把 remap 开关打开则必现无限回环 ANR，保持默认 false。

**结论 4.5 [推断]** groupId 来源 = 从 `r4.field_username` 按 §1.4 反解，**不用 position 映射**（4.1 已证 position 映射治不了点错人）。`GroupRowMenu` 内部 `db.listGroups().firstOrNull { it.id == groupId }` 也直接吃这个 id，链路自洽。
- 判据：点击/长按回调拿到的 username 能与 `GroupDb.listGroups()` 某条 id 往返对上。
- 验证：两组来回点 + 长按，看菜单标题（`GroupRowMenu.kt:92` `.setTitle(g.name)`）与点中的行一致。
- 失效场景：username 被宿主改写/截断 ⇒ 反解失败 ⇒ **保守放行（打日志、不打开页面）**，绝不能按 position 猜一个组出来。

[DONE] 第4节

## 5. 置顶位回收（flag 被重置）

**结论 5.1 [已证实]** 置顶位**会被微信重置**：竞品插入 12 条 `wx__c_` 后，置顶区 20 条里只剩 **9 条** `wx__c_`（另 3 条已掉出置顶区）⇒ 微信会把伪会话行的 `2^62` 位清掉，`flag` 从 `2^62 + time` 退回 `time`，行掉进普通区。这与朋友圈 `sourceType` 某 bit 被宿主重置是同一类教训：**不能假设「写一次就永久有效」**，必须设计重修机制。
- 判据（一条只读 SQL 自检）：
```sql
select username, flag, conversationTime from rconversation
where substr(username,1,6)='wxgrp_' and flag < 4611686018427387904;
```
返回非 0 行 = 有行掉出置顶区。`2^62 = 4611686018427387904`（`GroupConvProbe.kt:53` `PIN_BASE`），`flag = PIN_BASE + conversationTime` 逐位吻合（flag=4611687812816844372 ↔ time=1794389456468，毫秒）。
- 验证：`op=gconv sub=stat` 读 `pinned(flag>PIN_BASE) ... ours=`（`:157` 已实现）；在微信里做一次「置顶/取消置顶别的会话」「清空聊天记录」「切账号 / 会话迁移」再 `stat`，看 `ours` 是否减少。
- 失效场景：微信不是「清 bit」而是**删整行** ⇒ 上述判据返回 0 行（行没了），会误判成「没掉」。必须同时校验存在性（5.3）。

**结论 5.2 [推断]** 修复用**幂等补偿写**，只在错时写：
```sql
update rconversation
set flag = 4611686018427387904 + conversationTime
where substr(username,1,6)='wxgrp_'
  and flag <> 4611686018427387904 + conversationTime;
```
⚠️ `conversationTime` 必须取**当前库里的值**，不能用插入时缓存的 time（宿主可能同时改了 time）；`flag <> ...` 让重复执行影响 0 行，天然幂等，也把与宿主的写冲突窗口压到最小。
- 判据：执行后 `select count(*) ... and flag < PIN_BASE` = 0。
- 验证：手动把某行 `flag` 改成 `time` → 跑一次补偿 → `stat` 里 `ours` 回到应有条数；连跑两次，第二次影响 0 行。
- 失效场景：宿主把 `conversationTime` 也重置成 0 ⇒ 补偿写把行顶到 `2^62 + 0`（1970 年）⇒ 在置顶区里排到最末。必须连同 §6.3 的组内 max 时间一并回填，不能只修 bit。

**结论 5.3 [推断] 触发时机 —— 不要常驻轮询**，事件驱动 + piggyback：
1. 复用 §3.4 的 `onDataChanged()` 250ms 去抖链（消息到达 / 切前后台 / 模块启动各触发一次），在每次回调末尾做一次「存在性 + flag + time」补偿；
2. hook 宿主写 `rconversation` 的 after（`insert`/`update`，类名用 `DexKitLocator` 按表名反查，**未证实**），宿主动过库就校验一次；
3. 冷启 / 开关打开时做一次全量 clean-then-insert（§7），顺带重建历史掉出的行。
- 判据：24h 日志里补偿写次数 ≈ 宿主写库 / 前后台切换次数，**没有固定周期的定时器日志**。
- 验证：`dev.py q 'GCONV'` 看有没有按固定间隔刷的 repair 行；若有 ⇒ 说明误写成轮询，回退。
- 失效场景：宿主后台长连接收消息但只改内存、不写库 ⇒ 该次不触发补偿，但既然没写库也就没重置 flag，无害。真正危险的是「写了库却不触发任何我们挂得到的回调」⇒ 兜底靠冷启 / 前后台切换那一次全量校验。

**结论 5.4 [推断] 最小代价方案**：补偿逻辑只有「一条存在性查询 + 一条幂等 update」，挂在我方已有刷新链上，**不新增 Service / Handler / Timer / 轮询**；`GroupConvProbe.clean()` 的「非本方前缀行数前后自证」（`:376`）同时用来保证补偿 SQL 的条件没写漏。
- 判据：`update` 影响行数 ≤ 我方行数；补偿前后 `other` 恒定。
- 验证：`sub=stat` 的 `other` 在任意一次补偿前后不变。
- 失效场景：条件写成 `like 'wxgrp_%'`（`_` 在 LIKE 里是单字符通配）⇒ 可能命中别的行。一律用 `substr(username,1,6)='wxgrp_'`（`GroupConvProbe.kt:69-70` 的 `OWN_WHERE` 已按此落盘）。

**验收命令（§5 的置顶位回收，真机可直接粘贴；前置变量同 §2 的 `A` / `P` / `W`）**
```bash
# ① 掉出判据（只读）：返回非 0 行 = 有行被微信清掉 2^62 位
"$A" shell "$P --es sub sql --es sql 'select username,flag,conversationTime from rconversation where $W and flag < 4611686018427387904'"
# ② 置顶区计数（stat 把 total / ours 分开打）
"$A" shell "$P --es sub stat"
# ③ 主动制造一次「宿主重置」：在微信里置顶/取消置顶别的会话、或清空一次聊天记录，再跑 ① 与 ②
# ④ 触发我方补偿写（走 GconvManager，不是 sub=sql），再跑 ① 应为 0 行；连跑两次第二次影响 0 行
#    补偿语句：update rconversation set flag=4611686018427387904+conversationTime
#              where <W> and flag <> 4611686018427387904+conversationTime
# ⑤ 清场
"$A" shell "$P --es sub clean"
```
判据：③ 后 ① 变非 0 即复现竞品「12 → 9」；④ 后 ① 回 0 且 `stat` 的 `ours` 恢复应有条数。⚠️ `sub=sql` 只放行只读 SQL，补偿写必须走 `GconvManager`；SQL 里不用 `>` / `&`（远端 `sh` 会当重定向/后台，广播照样回 `result=0` 但探针一行没跑）。

[DONE] 第5节

## 6. 一致性：改名 / 增删成员 / 删分组 / 时间 / 未读

**结论 6.1 [已证实]** 写库形状照抄竞品（12 条逐列实测）：`content`/`digest` 长度全 0、`msgType=1`、`status=3`、`chatmode=1`、`isSend=1`，其余数值列全 0、其余字符串列空串；`rcontact` 侧 `type=0`、`showHead=87`、名字/拼音列全空、`createTime=0`、`deleteFlag=0`、`chatroomFlag=0`。`GroupConvProbe.insertConv()` / `insertContact()` 已按此形状落盘（`:233-313`）。⇒ 一致性维护**只碰 `flag` / `conversationTime` /（路线 A 的）`nickname` 三列**，其余列不动。

**结论 6.2 [已证实] 改名**：`GroupDb.renameGroup(id, name)`（`GroupDb.kt:401-406`，`trim` 后 `update group set name`）之后，同步 `update rcontact set nickname=? where username=?`（路线 A），或只更新渲染期内存映射（路线 B）。`GroupConvProbe.rename`（`:321-339`）就是判「改名要不要重启微信」的真机工具。
- 判据：`select nickname from rcontact where username=<该组>` 与 `GroupDb.listGroups()` 的 `name` 一致。
- 验证：改完不重启回列表看标题变化；若不变说明联系人对象有缓存（同 §2.3）。
- 失效场景：路线 A 下漏同步 `rcontact` ⇒ 列表显示旧名（库里留脏数据）；路线 B 下漏刷内存映射 ⇒ 同样旧名，但库里无脏数据、重跑一次注入即恢复 —— 这是路线 B 的隐性优势。

**结论 6.3 [推断] `conversationTime` 由组内最新消息时间决定**（竞品界面时间逐个吻合 `conversationTime`，R53 §8.1；置顶区内部按 `conversationTime` 降序）：
```sql
select max(conversationTime) from rconversation
where username in (<memberWxids(gid) 的成员 wxid 集合>)
  and substr(username,1,6) <> 'wxgrp_'     -- 排除自身伪会话（否则自引用把 time 顶住不动）
  and substr(username,1,6) <> 'wx__c_';   -- 排除竞品/微信内部伪会话
```
- `GroupDb.memberWxids(gid)` / `countMembers(gid)` 已存在（`GroupDb.kt:679` / `:754`）；`Group` 是 `id/name/color/sortOrder/createdAt/pinned` 的 data class（`:1006-1014`），**成员增删不改 `groups.hashCode()`** ⇒ 必须显式按成员集合重算，不能靠 `configStamp`/`groups.hashCode()` 触发（conv_list.md K7 定案）。
- 判据：界面上该组行的时间 = 组内最新一条会话的时间（真机逐行对，R53 §8.1 的方法）。
- 验证：给组内某成员发一条消息 → 看伪会话行时间是否跳到最新。
- 失效场景：组内成员全删 / 全都没聊过 ⇒ `max` 为 null ⇒ 用 `Group.createdAt` 兜底或保留最后一个 time（竞品空组显空旷，R53 §10.5），**别写 0**，否则行掉到 1970 年。
- **增删成员**：`group_member` 表的 6 处写入点全都不通知会话列表（conv_list.md K7 的 R2）⇒ 数据层路线下这些点必须补一次「time 重算 + 补偿写」，否则成员变了时间不跟。判据同 6.3 的逐行时间对照。

**结论 6.4 [已证实] `unReadCount` 不写**（竞品 12 条数值列全 0）。伪会话不是真聊天：写未读会进微信未读总数 / 桌面角标，且没有消息可点开清；若产品要「组未读」，在 §2.5 的 after hook 里自己往行上画角标，不落库。
- 判据：`select unReadCount from rconversation where substr(username,1,6)='wxgrp_'` 恒 0。
- 验证：`sub=stat` / 只读 SQL 回读；再看微信桌面角标有没有被凭空加数。
- 失效场景：宿主把 `unReadCount=0` 当成「未读会话」参与某些统计 ⇒ 需真机确认；若出现，改 `isSend`/`status` 组合或干脆不写该列靠默认值，别动其它已证实列。

**结论 6.5 [推断] 组内有新消息时由我方负责更新 `flag`/`conversationTime`**：微信只更新真实成员会话行，不会动 `wxgrp_` 行。挂点 = §3.4 的 `onDataChanged()` 去抖回调（或宿主写 `rconversation` 的 after），在回调里对受影响的组做 6.3 的 max 查询 + 幂等 update：
```sql
update rconversation set conversationTime=?, flag=4611686018427387904+?
where username=? and conversationTime<>?;
```
- 判据：每次收消息后该组行的 `flag - 2^62` == 组内 max time。
- 验证：连续给两个不同组的成员各发消息，看两行时间各自跟进、互不串。
- 失效场景：宿主写完库、去抖回调还没跑时用户切走 / 杀进程 ⇒ 下次冷启或前后台切换的全量校验兜住（§5.3）。

**结论 6.6 [推断] 删组**：`GroupDb.deleteGroup(id)`（`GroupDb.kt:409-419`，事务内删 `group_member` + `group` 两表）之后，删两张宿主表的对应行：
```sql
delete from rconversation where substr(username,1,6)='wxgrp_' and username=?;
delete from rcontact      where substr(username,1,6)='wxgrp_' and username=?;
```
幂等（delete 本身幂等）；条件带 `OWN_WHERE` 双保险，防止 username 拼错删到别人的行。
- 判据：`sub=stat` 的 `convOwn`/`contactOwn` 各减 1，`other` 不变。
- 验证：删组前后各跑一次 `stat`，再回列表看该行消失。
- 失效场景：只删 `rconversation` 不删 `rcontact` ⇒ 孤儿联系人行（通讯录 / 搜索可能冒出来，§2.2）。两表必须同一个事务。

> ⚠️ **本节 6.7 是阶段1 设计稿 [推断]，第 1 / 2 条已被阶段0 真机 + 装包前复核推翻**（2026-11-11 定案，见 §10.4）：
> ① 「串行到**主线程**」⇢ 改为「串行到**单一后台线程**」：阶段0 真机已证 `io{}` 后台写 wcdb 可行，
> 而主线程全表读 3120 行（约 9000 次反射）确定掉帧，与本轮「修 R53 体感不流畅」初衷相悖；
> ② 「绝不 delete+insert」⇢ **已被真机实测推翻**：`username` 有唯一索引，先删再插才幂等，
> 保留 Store 现做法，靠**事务**保证原子；③「两表写包一个事务」**本轮补上**。
> ④（wcdb 反射、不缓存 Method）与 ⑤（SQL 不用裸 `>` / `&`）**继续有效**。

**结论 6.7 [推断 · 部分已推翻] 写库竞争**（宿主 wcdb 多线程写）：
1. 我方所有写**串行到主线程单一 `Handler` + `synchronized` 闸门**，不与宿主并发；
2. 全部用幂等 `update ... where <> 目标值` / `insert ... or ignore`，**绝不 delete+insert**（宿主可能正持有该行游标）；
3. `rconversation` + `rcontact` 两表写包在一个事务里；
4. 主库实例是 `com.tencent.wcdb.database.SQLiteDatabase`，**不是** AOSP 的 `android.database.sqlite.SQLiteDatabase`，`rawQuery`/`execSQL` 一律反射，**绝不跨库缓存 `Method`**（`GroupConvProbe.kt:20-27`：强转恒 null 会静默白跑一轮；缓存 Method 调另一库实例抛 `IllegalArgumentException`）；
5. SQL 里不用 `>` / `&`（远端 `sh` 会当重定向 / 后台运行，广播照样回 `result=0` 但探针一行没跑 —— R53 §三/§七），改用 `instr()` / `% 2` / `<>`。
- 判据：高频收发期间 `dev.py q 'GCONV'` 无 `SQLiteException` / `FATAL`，`stat` 的 `other` 恒定。
- 验证：连续 50 条消息 + 多次改名 / 删组，看有无异常与行数漂移。
- 失效场景：宿主在事务里锁表，我方 `execSQL` 超时抛异常 ⇒ 必须 catch 并留到下一次去抖回调重试，**不能吞掉后不补**（同 §1.2「失败即停、报错原文」纪律）。

[DONE] 第6节

## 7. 生命周期：开关关闭 / 卸载 / 备份恢复 的幂等清理

**结论 7.1 [已证实]** 清理动作已有现成实现：`GroupConvProbe.clean()`（`:350-378`）删两张表所有 `wxgrp_` 行，删前打待删数、删后回读必须为 0，并前后各打一次「非本方前缀行数」自证不变。**幂等**：重复 `clean` 影响 0 行。
- 判据：日志 `clean done convLeft=0 contactLeft=0` 且 `clean 非本方行 conv N->N`。
- 验证：`op=gconv sub=clean` 连跑两次，第二次 `待删 convOwn=0 contactOwn=0`。
- 失效场景：条件漏写前缀 ⇒ `other` 变化，自证行会立刻暴露。这是唯一能提前发现「删错别人数据」的判据，**不能省**。

**⚠️ 别照搬探针的「备份」（复核 P1 后补，[已证实]）**：`GroupConvProbe` 的 `gconv_backup.txt`（`BACKUP_NAME` 于 `:56`，`backupRows()` 于 `:428-447`）是 **append-only 的 `col=val` 文本 + 时间戳**，只 dump 整行给人看；`run()` 的 sub 白名单只有 `stat|trial|rename|clean|sql`（`:113`），**没有 `restore` 子命令**，也没有可回放的 SQL。它的定位是「写脏了能人工还原的**线索**」，**不是可自动恢复的备份**。`rename()` 现已补上写前 `backupRows(db, CONTACT, user)`（`:329`），但「能备份」≠「能恢复」这点没变。
- 判据：文件里只有 `# <时间> before-write ...` + `列=值` 行，没有任何 `insert`/`update` 回放语句。
- 验证：`op=gconv sub=clean` 后 `adb shell cat` 该文件，看能不能直接拿来重建行 —— 不能。
- 失效场景：若把 §7 的「备份恢复」理解成「从 `gconv_backup.txt` 还原」，回滚/恢复会**无路可走**。⇒ **§7 的恢复机制只能是 7.4 的 clean-then-insert 全量重建**（配置源 = `GroupDb.listGroups()` + `Kv.importSome()`），探针文本文件只作为事故后的取证线索，不进恢复链路。

**结论 7.2 [推断] 开关关闭**（`chat.gconv_enabled=false`）= ① `ConvFilter.rebuild()` 恢复自绘分组行（§8 的互斥开关）＋ ② 一次 `clean()`。顺序必须是「先恢复 UI 再清库」或「同一临界区内」，不能出现「UI 已直通但库里还留着伪会话」的中间态 —— 那段窗口里列表会**同时**出现原生伪会话行和自绘分组行。
- 判据：关闭后 `convOwn=0` 且列表分组行是自绘的（无 `wxgrp_` 原生行）。
- 验证：开关来回切 3 次，每次 `stat` + 截图看有无重复行 / 残留。
- 失效场景：用户 `kvset` 改开关但没重启 ⇒ `ConvFilter.rebuild()` 每次读 `Kv.getBool`（`:1195`），UI 会生效；但清理动作若只在模块加载时跑就漏了 ⇒ 清理必须挂在 `rebuild()` 的**开关变化沿**上，而非只在冷启。

**结论 7.3 [已证实] 卸载必然残留**：模块卸载后我们的代码不再运行，没法自清；库里的 `wxgrp_` 行变成「点不开的聊天」（R53 §九「卸载残留：伪会话留在库里，点不开」）。这是数据层路线相对 adapter 层最硬的代价，也是「关开关即恢复」这个优势的反面。
- 判据：卸载后 `select count(*) from rconversation where substr(username,1,6)='wxgrp_'` > 0。
- 验证：卸载模块 → 重启微信 → 看列表那几行点进去是不是空白 / 报错。
- 失效场景（缓解，不是消除）：
  1. 设置页在「关闭 / 卸载引导」处强制跑一次 `clean()` 并提示用户先关模块；
  2. 保留 `op=gconv sub=clean` 广播入口，卸载后可用任意能发广播的工具手动清；
  3. **残留无害化**：不写 `rcontact.nickname`（走 §2 路线 B）⇒ 残留行没有组名，用户不易误点；这是路线 B 相对路线 A 的一个决定性优势；
  4. 备份文件里记一个 `gconv_was_on` 标记，恢复时若模块已不在，提示用户手动清。
  ⇒ 只要 3 落实，最坏结果从「一堆点不开的组名」降到「几条无名空会话」。

**结论 7.4 [已证实] 备份恢复**：`Kv.exportAll()` 遍历 `Prefs.ALL`、`Kv.importSome()` 只覆盖已登记 key（未登记 key 直接忽略，`Kv.kt:71-88`）⇒ 备份只带我方配置（分组定义 / 开关），**不带微信主库行**。恢复后必须由模块按当前 `GroupDb.listGroups()` 做一次 **clean-then-insert 全量重建**，不能假设库里还在。
- 判据：恢复后 `convOwn == contactOwn == listGroups().size`（每组一行）。
- 验证：导出 → 手工删/加一行改库 → 导入 → `stat` 回到应有条数；重复导入两次结果相同。
- 失效场景：备份来自「未启用 gconv」的旧版本 ⇒ 导入后 `gconv_enabled` 保持 false，不应凭空插行；`importSome` 忽略未知 key 的语义正好兜住。

**结论 7.5 [推断]** 微信覆盖安装 / 会话迁移 / 清空聊天记录同样按全量重建处理：这三件事都可能重写或清掉 `rconversation`（§5.1 的 3 条掉出置顶区就是宿主动过库的证据）。统一入口 = 冷启 / 前后台切换时跑一次「存在性 + flag + time + 两表配对」校验，缺什么补什么。
- 判据：校验后 `convOwn == contactOwn == 组数`，且 `flag < PIN_BASE` 的行数为 0。
- 验证：覆盖安装微信后冷启，`stat` 与界面逐行核对。
- 失效场景：宿主把整行删了而不是改 flag ⇒ 存在性检查必须包含「按 groupId 反查缺哪几行」，否则只修 flag 会漏重建（同 5.1 的失效场景）。

[DONE] 第7节

## 8. 与现有代码的关系：删什么 / 留什么 / 迁移与回滚

先把本轮新增的两条代码事实钉死：

- **事实 A [已证实]** `feature/ConvFilter.kt:1269-1313` 的 `rebuild()` 里 `layout = when { ... }`：当 `groups.isEmpty()` 时（`:1284-1288`）已经调用 `ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)` **退化成纯会话直通** ⇒ **停用自绘分组行已有现成切口**：新开关只需让这个分支恒真，**不必删 `ConvLayout`，也不必删 `GroupedAdapter`**。
- **事实 B [已证实]** `probe/GroupConvProbe.kt`（599 行，已落盘）：`op=gconv sub=stat|trial|rename|clean|sql`；`trial` 插两条对照行 `wxgrp_t1`（写 `rcontact.nickname='测试组A'`）与 `wxgrp_t2`（不写昵称）；`clean` 只删 `substr(username,1,6)='wxgrp_'` 的行。它是回答 §2(a)「组名能不能直接写 nickname」的真机工具，验收期保留。

### 8.1 能停用（**先别物理删除**，回滚要用）

**结论 8.1 [推断]**

| 部件 | 为什么数据层不需要 | 处置 |
|---|---|---|
| `feature/GroupRowView.kt`（431 行，自绘行 / 头像 / 角标 / `Holder`） | 行由微信原生会话布局渲染，不再需要一个额外 viewType | 保留编译，gconv 开时不调用 |
| `feature/ScrollDock.kt`（238 行，`trackMotionScroll` after 钳制 + 吞 `ListView.correctTooHigh`） | 只服务 adapter 包装态的巨型 header / 筛选态；R53 §10.3 已实证「下拉不跟手」就是它每帧反射 `offsetChildrenTopAndBottom` 归零造成的。数据层不包装 adapter ⇒ 无筛选态、无该 bug | gconv 开时 `install()` 不挂（`pinBroken`/`guardPin` 依赖 `ConvFilter.wraps`，直通后自然失效，但别留钩子吃每帧开销） |
| `ConvFilter` 的 `GroupedAdapter` 包装链、`hookListeners` / `LongClickRemap`、`hookBackExit`（筛选态返回） | 直通后无插入行、无位置映射、无筛选态；`CHAT_CONV_LONGCLICK_REMAP` 本来就默认 false | 由 gconv 开关短路（`rebuild()` 恒直通），代码留着 |
| `data/ConvLayout.kt` | 事实 A 已给出退化口，**明确不必删** | 保留，走 `enabled=false` 分支 |

⚠️ **别误删**：`GroupBar`（另一套分组栏 UI）、`GroupManagerPage` / `MemberPage` / `GroupPicker`（配置入口）、`ConvMenuInject`（普通会话长按注入）与 gconv 无关，保持原样。

### 8.2 必须保留并复用

**结论 8.2 [推断]**
- `data/GroupDb.kt`（`listGroups()` / `memberWxids(gid)` / `countMembers(gid)` / `renameGroup()` / `deleteGroup()` / `setGroupPinned()`）—— gconv 的配置源，§6 全靠它；`Group` data class 不带成员字段这一事实仍成立。
- `data/Keys.kt` / `Prefs` / `Kv.kt` —— 新开关与互斥（`Prefs.ALL` 加一行即自动渲染设置页 + 自动进备份，`Kv.kt:71-88`）。
- `feature/GroupDetailPage.kt` 的 `open(ctx, groupId, groupName)` —— 伪会话点击落点（§4.3）。
- `feature/GroupRowMenu.kt` 的 `show(viewCtx, groupId)` —— 长按菜单（§4.4）。
- `ConvFilter.rebuild()` 的 observer / 去抖链、`PROBE_WARN_MS = 8L` 性能闸门 —— §3.4 / §5.3 / §6.5 的事件驱动全部 piggyback 在它上面。
- `probe/GroupConvProbe.kt` —— 事实 B，验收工具。
- `core/HostContext.kt` / `DexKitLocator.kt` —— §2 / §3 定位宿主方法用。

### 8.3 最小改法：新旧两套用 `Keys` 开关互斥

**结论 8.3 [推断]（逐条可执行）**

> ⚠️ **命名口径（2026-11-11 定案）**：本节的 `CHAT_GCONV_ENABLED` 是**早期草案名，已废弃**。
> 实际落盘常量为 `Keys.GCONV_MODE`，字符串键值仍是 `chat.gconv_enabled`。
> 另外第 2 条的「代码层强制互斥」**第一版未实现**：`gconvEnabled` 为真时
> `groups.isEmpty() || gconvEnabled` 已让自绘分组行不出现，两套行不会并存；
> 两个开关同开的实际表现留作装包前复核项 G，真机确认。

1. `Keys.kt` 加 `const val GCONV_MODE = "chat.gconv_enabled"`；`Prefs.ALL` 加一行 `PrefSpec(Keys.GCONV_MODE, PrefKind.SWITCH, "false", "聊天", "数据层分组行（实验，默认关；切换后冷启微信生效）")` —— **默认 false**，装包后一切照旧走老路，回滚无需发版。
2. **互斥**：开 `gconv` 时把 `Keys.CHAT_GROUP_ROW_ENABLED` 写 false，反之亦然（在 `SettingPage` 写入回调或 `Kv.putBool` 外包一层）；两个都 true 时以 gconv 为准并强制纠正，避免两套分组行同时出现。
3. **`ConvFilter.kt:1284` 那个分支怎么改**（事实 A 的落点，最小改法）：
```kotlin
// 原：if (groups.isEmpty()) {
if (gconvEnabled || groups.isEmpty()) {
    memberSets = emptyMap()
    models = emptyMap()
    lastSpecs = emptyList()
    ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)
} else {
    ...原自绘逻辑（:1290-1311）原样不动...
}
```
其中 `gconvEnabled = runCatching { Kv.getBool(Keys.GCONV_MODE) }.getOrDefault(false)`，与 `:1195` 的 `enabled`（`Kv.getBool(Keys.CHAT_GROUP_ROW_ENABLED)`）**并列在 `rebuild()` 开头读一次**放入局部变量 —— 别塞进 `getView` 热路径每行读（`Kv.getBool` 是一次 SQLite 查询，`ConvFilter.kt:106` 的旧坑）。首个分支 `db == null || !enabled`（`:1270`）保持不变：那是总开关，gconv 只影响 `else` 分支。
   - 判据：gconv 开时 `K7 full rebuild ... layout=<snaps.size>`（不插任何分组行），而 `sub=stat` 里的 `wxgrp_` 行由微信**原生**渲染出现在列表顶部。
   - 验证：开关各态跑 `ConvLayout.build()` 的 JVM 单测（已有套路）+ 真机 `dev.py q 'K7'` 看 `layout=` 与会话数一致。
   - 失效场景：若在 `filterId > 0` 分支（`:1277`）也强制直通会破坏老筛选态；gconv 开时 `setGroup()` 不该被调用（§4.3 已拦），但要防旧入口残留 ⇒ 建议在 `setGroup(id)`（`ConvFilter.kt:595`）里对 gconv 开时直接 `return`。
4. **新增文件，不改现有文件**：`GconvManager`（clean / insert / repair / rename / delete / observer 补偿，全部走 `OWN_WHERE = substr(username,1,6)='wxgrp_'`，写库反射照抄 `GroupConvProbe`）、`GconvRowHook`（hook `p3.getView` after 做 §2.5 / §4 的标题注入与监听器覆盖）。
5. **迁移顺序**：`sub=trial` 验 §2(a) → 验 §3 刷新 → 验 §4 点击/长按 → 默认关发布 → 用户手动开 → `stat` 验收 → 稳定后再考虑把默认值翻 true。

### 8.4 回滚路径（不重新发版）

**结论 8.4 [推断]** 把 `chat.gconv_enabled` 置 false（或恢复备份里的旧值）→ 事实 A 的 `groups.isEmpty()` 分支恢复原语义 → 自绘分组行回来；同时 `GconvManager.clean()` 删掉所有 `wxgrp_` 行 → 微信列表恢复。因为 `ConvLayout` / `GroupRowView` / `GroupedAdapter` / `ScrollDock` **都没有物理删除**，回滚 = 一个开关 + 一次 clean，最坏情况重启微信即可。
- 判据：回滚后 `convOwn=0` 且列表分组行是自绘的、无重复行。
- 验证：开 → 关 → 开 → 关 各一次，`stat` + 截图逐次核对。
- 失效场景：若迁移时已经物理删了 `GroupRowView` / `ScrollDock` ⇒ 回滚编译不过 / 老路功能缺失 —— 这正是 8.1 要求「先停用不删」的原因。

> **复核联动（已闭环，[已证实]）**：本设计初稿期间静态核查出的三处探针缺陷，均已由复核 lane / 代码侧修进 `GroupConvProbe.kt`，当前版本（599 行）逐项核对无误：
> 1. `insertContact()` 的 minimal 漏写 `nickname` ⇒ 现 `minimalCols` 含 `nickname`（`:290-292`），trial 的 t1/t2 对照恢复判别力（对应 §2 的前置校验）；
> 2. `rename()` 无写前备份 ⇒ 现 `rename()` 先调 `backupRows(db, CONTACT, user)`（`:329`）；但备份仍是 `col=val` 文本、**无 `restore` 子命令**，故 §7 明确「恢复走 clean-then-insert 全量重建，不照搬探针备份」；
> 3. `pinWx` 用 `substr(username,1,7)='wx__c_'`（前缀只有 6 字符）⇒ 现改为 `substr(username,1,6)`（`:154`），§9 风险 9 的「竞品置顶数对照」判据恢复可用。
>
> ⚠️ 复核报告 `docs/_r54_probe_review.md` 的结论基于**修复前**的探针快照；本设计已按修复后版本刷新行号，并对 §2 / §7 补了「待验假设」「不可照搬」两条注记。**唯独置顶位模板（flag=2^62+ctime、msgType=1/status=3/chatmode=1/isSend=1、showHead=87）与真机实测逐项吻合，可直接引用，不受修复影响。**

[DONE] 第8节

## 9. 风险清单（按后果严重度排序）

每条给「怎么提前发现」。严重度从 1（可毁数据）到 10（仅跨版本适配）。

| # | 风险 | 后果 | 档位 | 怎么提前发现 |
|---|---|---|---|---|
| 1 | **写坏微信主库**（SQL 条件漏前缀 / 约束不符 / 事务半途失败 / 反射调错库实例） | 宿主数据损坏，甚至微信起不来 | [推断] | 所有写/删只带 `substr(username,1,6)='wxgrp_'`；`clean` 前后各打一次「非本方前缀行数」，该数一次都不能变（`GroupConvProbe.kt:39-40,376`）；`sub=sql` 只放行 select / explain / 只读 pragma（`:385-410`）；写前把待删行 append 到 `gconv_backup.txt`（`:55-56`）；两段式 insert 失败即停且**不吞 SQLiteException 原文**（`:34-35`）；先用 `trial` 两条对照行验，不批量写 |
| 2 | **卸载 / 禁用残留 = 点不开的聊天** | 用户可见，且模块卸载后无法自清 | [已证实]（R53 §九） | 卸载引导处强制 `clean()`；把「不写 `rcontact.nickname`」列为验收项让残留行无名（§7.3）；`sub=clean` 后 `convLeft=0 contactLeft=0`；备份记 `gconv_was_on`，恢复时若模块不在就提示手动清 |
| 3 | **伪会话被宿主原生操作处理**（清空聊天记录 / 会话迁移 / 删除该聊天 / 置顶重排） | 行丢失或掉出置顶区，分组入口消失 | [推断] | §5.3 事件驱动的「存在性 + flag + time」全量校验；`stat` 的 `pinned ... ours=` 前后对比；每次宿主动库后立刻 `stat` 看 `convOwn` 是否减少 |
| 4 | **组名写 nickname 泄露到通讯录 / 搜索 / 群成员选择器** | 伪账号出现在不该出现的地方，用户可搜到/选到 | [未验证]（§2.2） | `sub=trial` 后用 `wxgrp_t1`（有昵称）/ `wxgrp_t2`（无昵称）对照：dump 通讯录 W 字母区、全局搜「测试组A」、发起群聊/转发选择器三处；t1 出现而 t2 不出现即坐实 nickname 是泄露源 → 回退路线 B |
| 5 | **点击伪会话进空白聊天页 / 崩溃** | 点了没反应或闪退 | [推断]（§4.2） | getView after hook 先只打日志不覆盖 listener，确认每行命中；再覆盖并 return true；**真手点**（adb 长按会被识别成点击）；`DETAIL open group=` 日志确认落点；username 反解失败时保守放行、绝不按 position 猜 |
| 6 | **置顶位 `2^62` 被微信重置**（已实测 12 → 9） | 分组行掉进普通区、排序乱 | [已证实]（§5.1） | 只读判据 `flag < 4611686018427387904` 非零即掉出；幂等补偿写 `flag <> PIN_BASE+time`；**禁止常驻轮询**（查 `dev.py q 'GCONV'` 有无固定周期 repair 行）；同时查存在性，防「删行」被误判成「没掉」 |
| 7 | **刷新链路不生效，写完库列表看不到** | 必须重启才出现，用户以为没生效 | [推断]（§3.2） | `sub=trial` 后**不重启**直接看列表；hook `p3.notifyDataSetChanged` 打栈确认有无触发；「notify 打了但 `getCount()` 不变」= 只通知未失效缓存的假修法 |
| 8 | **写库竞争 / 主线程卡顿**（2900 项 observer 每 250ms） | ANR、掉帧、SQLiteException | [推断]（§6.7） | 我方写串行到主线程单一 Handler + `synchronized`；幂等 `update ... where <> 目标值` 减少写窗口；复用 `PROBE_WARN_MS=8` 闸门与 250ms 去抖；高频收发 50 条看日志有无异常与行数漂移；绝不缓存 `Method` 跨库（wcdb vs AOSP） |
| 9 | **与微X 等其它模块共处**（两套伪会话共置顶区、排序交错） | 行序混乱、互相误删 | [推断] | `stat` 已分开打 `pinWx` 与 `pinOurs`（`:152-157`）—— ⚠️ 但 `pinWx` 的前缀长度写错（见 §8 末尾静态核查），验收前须修或改读 `total - ours`；`OWN_WHERE='wxgrp_'` 不会碰 `wx__c_`；同装两个模块跑一次 `trial`/`clean` 看 `other` 恒定 |
| 10 | **微信版本升级列名 / 约束漂移** | insert 失败、行不显示 | [推断] | 两段式 insert：先 7 列靠默认值，失败再按运行时 `pragma table_info` 显式写全 20 列（`GroupConvProbe.kt:62-67,233-313`）；失败打报错原文、不吞；`sub=stat` 先看库形状再写；列名清单不硬编码到业务代码 |

**跨条目两条硬纪律**：
- **SQL 里不用 `>` / `&`**：远端 `sh` 会把 `>` 当输出重定向、`&` 当后台运行，广播照样回 `result=0` 但探针一行没跑、**没有任何报错**（R53 §三 / §七）。用 `instr()` / `% 2` / `<>` 替代。
- **`_` 在 LIKE 里是单字符通配**：一律用 `substr(username,1,6)='wxgrp_'`（`GroupConvProbe.kt:69-70`），不用 `like 'wxgrp_%'`。

[DONE] 第9节

---

## 10. 阶段0 真机定案（2026-11-11 22:11~22:20，主会话实测）

> `op=gconv sub=stat|trial|rename|clean` + `op=xh sub=row` + `uiautomator dump`。
> 装包 `lastUpdateTime=22:10:21`，冷启两次（`pid 6610 / 8393`），模块 `installed` 全在。
> **本节优先级高于前面 9 节**：前 9 节是静态推断，本节是实测，冲突时以本节为准。

### 10.1 九项结论

| # | 问题 | 实测结论 | 证据 |
|---|---|---|---|
| 1 | 写两表能不能出分组行 | **能，但必须冷启微信**。纯写库后 `count=1593`/`wrappedCount=1578` 纹丝不动、UI 无新行；冷启后 `count=1596`、置顶区 20→22，两行出现 | 22:11:43 `trial` 日志；22:11 与 22:13 两次 `xh row` |
| 2 | 组名怎么显示 | **写 `rcontact.nickname` 就够了，零 hook**。t1 写 `测试组A` → UI 显示「测试组A」；t2 留空 → UI 回落成原始 `wxgrp_t2` | UI dump `[274,1593]'测试组A'` / `[274,1334]'wxgrp_t2'` |
| 3 | 通讯录泄露 | **无**。`type=0` 已足够排除，切到通讯录 Tab 零命中 | 22:15 `u_ab.xml` |
| 4 | 搜索泄露 | **有，且与 nickname 无关**。搜 `wxgrp` 命中两条：t1 显示「测试组A / 微信号: wxgrp_t1」，**没写昵称的 t2 也显示「wxgrp_t2 / 微信号: wxgrp_t2」** ⇒ 只要 `rcontact` 有行就会进搜索 | 22:17 `u_s.xml` |
| 5 | 改名要不要重启 | **要**。`rename 测试组B` 后库里 `nickname=测试组B`（`nlen=4`），不重启 UI 仍是「测试组A」 ⇒ 微信有联系人内存缓存 | 22:17:42 rename 日志 + 22:18 `u_r3.xml` |
| 6 | 点击会怎样 | 进入一个「**对方还不是你的朋友**」的空聊天页，**不崩**。⇒ 实现必须拦点击 | 22:19 `u_cl.xml`，日志无 FATAL/Exception |
| 7 | 置顶位稳不稳 | 写入 9 分钟后（22:18:59）两条 `flag` 仍是 `2^62+conversationTime`，**未被重置**；但微X 的 12 条里仍有 3 条在置顶区外（`wxC=9`）⇒ 长期会被重置，重修机制仍要留 | `stat pinned total=22 wxC=9 ours=2` |
| 8 | 行几何 | 两行都是宿主原生 `ConversationFolderItemView`，pitch **259px**、时间显示 `22:11`、排在微X 伪会话（改性 17:30）之前 ⇒ 置顶位生效、零视觉差异 | UI y=1334/1593 |
| 9 | 清理/回滚 | `clean` 幂等：`convLeft=0 contactLeft=0`，非本方行 `3121→3121` / `23481→23481` 不变；冷启后 UI 归零、置顶区 20 | 22:20 `clean` + `stat` |

**意外发现**：`clean` 前 dump 里两条行的 `parentRef` 已变成 **`message_fold`**（插入时是空串）—— 微信在这 9 分钟里自己填了它（"折叠置顶聊天"标记）。机制上没拦住显示，但实现时不能假设 `parentRef` 恒空。

### 10.2 对前 9 节的修正（推翻三处预设）

- **§2 推翻**：设计里默认「走路线 B（hook `p3.getView` 渲染期注入）」是**多余的**。写 `nickname` 就能让微信原生渲染标题，零 hook、零自绘。**微X 不写 nickname 的真实原因不是"写不了"，而是它要避开联系人缓存导致的改名不即时（见 #5）和搜索泄露（见 #4）**。
- **§3 修正**：「纯写 DB 不刷（内存缓存）」已证实；但**冷启即可**，不需要找 `doNotify` / observer 通知点。运行期变更（改组名/成员）由 `GroupConvStore.sync()` 写回 `rcontact.nickname`，**不在 UI 层覆盖标题**（标题是宿主自绘 View，见 §10.3），改名同样下次冷启生效；不要去撬微信的联系人缓存。
- **§8 修正**：设计里写「停用 `GroupedAdapter` 包装链」**不要照做**。**保留包装链、只让它走 passthrough，是最优解**：
  - `ConvFilter.kt:1284` 已有 `groups.isEmpty()` → `ConvLayout.build(..., enabled=false)` 的现成直通分支，新开关只需让这个分支恒真；
  - 保留包装链 ⇒ **零新增 hook**：`GroupedAdapter.getView()` 本来就会拿到每一行的 `item` 与 `View`，标题覆盖、点击拦截、长按全在这里做，不必去 hook 微信混淆类 `p3.getView`（设计 §2/§4 标的「未证实 hook 点」直接绕开）。

### 10.3 最终实现方案（定案）

**数据层**（`GroupConvStore`）：每个 Group 一行 `wxgrp_<gid 定长 hex>`；`rconversation` 按实测模板写 7 列（`flag=2^62+time` / `msgType=1` / `status=3` / `chatmode=1` / `isSend=1` / `conversationTime` / `username`），`content`/`digest` 留空；`rcontact` 写 `username/type=0/showHead=87/nickname=组名`。⚠️ `pragma table_info` 实测 `rconversation` 是 **30 列**（比 R53 记的多 10 个：`unReadMuteCount/lastSeq/UnDeliverCount/UnReadInvite/firstUnDeliverSeq/editingQuoteMsgId/hasTodo/hbMarkRed/remitMarkRed/hasSpecialFollow`）、`rcontact` 27 列，新增列全有默认值 ⇒ **7 列 minimal insert 已实测成功，别硬编码 20 列**。

**视图层**（改 `ConvFilter.GroupedAdapter`，**零新增 hook**）：`getView` 拿到 `item` 后按**窄口径**
`GroupConvPlan.groupIdOf(username) != null` 认领我方伪会话行（宽口径 `wxgrp_` 前缀会命中
`wxgrp_t1/t2` 这类探针残留，它们反解不出 gid，硬接管会拿 null 去开 `GroupDetailPage`）：
1. **不覆盖标题**（初稿第 1 条「覆盖标题 TextView」已作废，理由见下）；
2. 覆盖行 `OnClickListener` → `GroupDetailPage.open(v.context, gid, name)`（解决 #6；
   `performItemClick` 真机零命中，必须改 View 自己的 listener，且要在 `wrapped.getView()`
   **之后**覆盖才有效）；
3. 长按 → `GroupRowMenu.show(v.context, gid)`，`return true`（不 return true 会冒泡成点击/弹原生菜单）。

⚠️ **标题不可覆盖（2026-11-11 定案）**：会话标题**不是 `TextView`**，而是宿主自绘 View
（`feature/ConvMenuInject.kt` 踩坑注释：实测 id `l7a` 是 `NoMeasuredTextView`），没有可靠的
`setText` 落点；`setContentDescription()` 只改无障碍文本、不影响屏幕绘制。
⇒ §2.4/§2.5 里「`findViewById<TextView>(R.id.l7a).setText(组名)`」整条路线**作废**。
第一版改名统一由 `GroupConvStore.sync()` 写 `rcontact.nickname`，**下次冷启微信才生效**
（微信联系人内存缓存，#5）；界面上以开关标题「切换后冷启微信生效」提示用户，不做即时覆盖。

**开关与回滚**：`Keys.GCONV_MODE = "chat.gconv_enabled"`，默认 **false**（旧自绘分组行保持现状）；
ON 时 `ConvFilter.kt` 的 `groups.isEmpty()` 分支恒真（不插自绘行）+ 60s 节流的
`GroupConvStore.sync()` 同步伪会话行。OFF 或卸载 → `clean` 两表（幂等，`substr(username,1,6)='wxgrp_'`）。
⚠️ **运行时 `kvset` 翻转开关不生效**：`refresh()→rebuild()` 的 `configStamp` 指纹未变会被短路，
layout 不重算 ⇒ 自绘分组行不会撤。真机验收顺序必须是「`kvset` → **冷启微信**」，
冷启时 `lastStamp=""` 才走全量分支；不要拿「kvset 即时切换」当判据（实现 lane 上报，已核对）。

**已知代价（接受）**：搜索里会看到伪账号（#4）；只在前缀字符串或组名被搜到时出现，正常搜索中文群名/好友不会撞上。若后续要根治，单独 hook 搜索结果过滤，别为此推翻数据层方案。

**仍未验证**：`GroupDetailPage.open()` 的确切签名与 Context 来源；长按菜单在 `ConversationFolderItemView` 上的可用性；`conversationTime` 随组内新消息更新的写库竞争；`parentRef=message_fold` 的长期影响。这四条进实现前各配一条真机判据。

### 10.4 装包前交叉复核与修复（定案）

独立复核报告 `docs/_r54_view_review.md`（345 行，全程只读）verdict：**需先修 4 条**（P0×1 / P1×3）；
PASS＝**D**（passthrough 下 `r == position` 确证，`ConvLayout.kt:86/:213-214/:233-234`）、
**F**（默认 false 时 `|| false` 等价、`AntiRevoke.wxDb()` 因 `&&` 短路不调、
`getItemViewType`/`getViewTypeCount`/`configStamp` 未被 diff 改动 ⇒ 回滚生命线成立）。

| 项 | 级别 | 结论要点 |
|---|---|---|
| A 并发/异步 | **P0** | `sync` 两入口并发（主线程 250ms 去抖回调 vs `op=gcs` 的 `io{}`）共用同一 wcdb 连接；Store 无锁/无事务、`Insert` 是 4 条独立 execSQL ⇒ read-modify-write 非原子。一次 sync 全表 3120 行 ≈9000 次反射压主线程 ⇒ **确定掉帧** |
| B convertView 污染 | P1 | 命中行覆盖 listener、非命中行零复位；伪会话行与普通行 **viewType 相同、共用回收池**（确证）。但「微信复用时是否重设 listener」**证据不足需真机验证**，两种宿主行为下正确复位动作相反 ⇒ 改为源头阻断 |
| C 首次 sync | P1 | `lastGconvSyncMs=0L` 把开机 <60s 的首次吞掉，且 `install()` 末尾只有 `fa.rebuild()+notify`、**不触发**挂在 `inner` 上的转发 observer |
| H 热切换 | P1 | `kvset` 会调 `ConvFilter.refresh()` ⇒ 字段热更新 + 列表重绘，并非「完全被吞」；但 `configStamp` 不含 `gconvEnabled` ⇒ layout 不重算 ⇒ **「字段热 / layout 冷」半生效中间态** |
| E/G/F/D | P2 | 命中行每行 `listGroups()` / 双键同开空跑且注释与实现不符 / OFF 多一次 `Kv.getBool` / 窄口径对纯 hex 残留行的固有限制 |

**修复方案（lead 综合，交实现 lane 执行）**
1. **A**：`GroupConvStore` 建单一后台 `gconv-store` 线程，`postSync/postClean/postStat` 全部 post 到它串行；
   两入口只 post；`sync`/`clean` 的 `rconversation`+`rcontact` 写包进 **wcdb 反射事务**
   （拿不到三个方法时打 WARN 降级为无事务直写，绝不静默）。节流状态下沉 Store、
   初值 `Long.MIN_VALUE`，`op=gcs sync` 用 `force=true` 绕过节流。
2. **⭐ G + H 一招同解**：`effEnabled = enabled && !gconvEnabled` 同时喂 `:1241` 的 `configStamp`
   与 `:1282` 的 light 路径 ⇒ 翻转开关即改指纹、破 `:1269` 短路（消半生效态），
   且 gconv ON 时跳过 `rebuildModels` 的 O(组数×2900) 空跑；`:1296` 保持原 `enabled`，
   **不改 `PinFoldLogic.configStamp` 签名**。
3. **B**：命中行 `v.setTag(GCONV_ROW_TAG_KEY, gid)`；`getView` 预判本行 gid，
   若「convertView 带我方标记」且「本行非伪会话行」⇒ `cv = null` 强制微信新建干净 View。
   ⛔ 不在非命中行无条件 `setOnClickListener(null)`（会清掉微信刚设的 listener）。
   tag key 用 `0x47525643`（AOSP 要求 `key >>> 24 >= 2`），不用 `generateViewId()`、不用无 key 版 `setTag`。
4. **C**：节流初值改 `Long.MIN_VALUE` + `refresh()`/`install()` 各补一次 `postSync` 兜底
   （`kvset` 已调 `refresh()` ⇒ 开开关后即使无 onDataChanged 也会同步）。
5. **E**：组名缓存进 `rebuild()` 已有的 `groups` 快照（`configStamp` 已含 `groups.hashCode()`，失效逻辑现成）。

**真机验收 SOP（修复后口径）**
1. `op=gconv sub=stat` 记基线（`convOwn`/`contactOwn`/`other`）；有残留 `wxgrp_` 行先 `op=gcs sub=clean`。
2. `kvset key=chat.gconv_enabled val=1`。`chat.group_row_enabled` 保持默认 `1` 即可
   （`effEnabled` 已消空跑、同开不会两套行）；**若手动改过它，回滚必须一起复位**。
3. **强杀冷启微信**（不是切后台）：组名依赖 `rcontact.nickname` 内存缓存，纳入指纹**不能免冷启**。
4. `op=gcs sub=sync`（force，立即灌库）→ `dev.py q GCONVSTORE` 看 `sync done own=<分组数>`
   → `op=gconv sub=stat` 复核 `other` 与基线一致。
5. **再冷启一次**让联系人缓存重读组名。
6. 验收：伪会话行显示组名 / 点击开 `GroupDetailPage` / 长按 `GroupRowMenu` / 滚动流畅度；
   **重点回滚 B**：滚动后点、长按普通会话是否被污染（误开分组页 / 弹分组菜单）。
7. 回滚：`kvset gconv=0`（改过 group_row 的一并复位 `1`）→ `op=gcs sub=clean` → 冷启
   → `stat` 确认 `own=0`、`other` 不变。

**实现期补强（坑，值得记）**：节流用 `Long.MIN_VALUE` 当「从未同步」哨兵时，
`now - Long.MIN_VALUE` 会因 **Long 减法溢出成负数** ⇒ 首次被永久吞掉，比原来的 `0L` 更糟。
✅ 正确写法是先短路哨兵：`lastSyncMs == Long.MIN_VALUE || (now - lastSyncMs >= WINDOW)`，
或改用加法 `now >= lastSyncMs + WINDOW`（加法不溢出）。实现 lane 自己抓到并补强，
见 `GroupConvStore.kt:93-94`，架构不变、初值仍为 `Long.MIN_VALUE`。

**二次复核结论（`docs/_r54_fix_review.md`，201 行）**：verdict **需先修 1 条 P1**；P0=0 / P2=2。
首轮 P0 的实质危害（两入口并发写同一 wcdb 连接 + 主线程全表读 3120 行）**已彻底闭环**；
并发、节流哨兵、`effEnabled`、OFF 零差异、tag 方案、红线全部 PASS。复核独立跑构建
`OK (919 tests)`，并单跑 `GroupConvPlanTest = OK (18 tests)` + 全量目录 45 个 `*Test.class`
⇒ 919 不是旧基线假判据。

- ⭐ **P1 且比原推演更重**：`execLog()` 吞掉每条 SQL 异常 ⇒ 事务形同虚设；更关键的是
  **半行无法被下一次 sync 自愈** —— `readExisting()` 取两表并集、缺侧补空，`plan()` 对已存在
  username 只产 `Update`，而 `repairSql`/`renameSql` 都是 `UPDATE`、对缺失行影响 0 行 ⇒
  「conv 缺 / contact 缺」**永久残留**，只能 `clean()` 全删重建。⇒ 否掉
  「失败留到下一次去抖重试 / 全量对账兜得住」的假设。
- 修法：`execLog` 返回 `Throwable?`，`inTransaction` 的 block 收集失败并 `throw` ⇒ 跳过
  `setTransactionSuccessful` ⇒ `finally endTransaction` 回滚；`sync()` 与 `clean()` 两个 block
  同样适用；外层 `runCatching{}.getOrElse{FATAL}` 已存在，零新增异常处理路径。
- P2：`:1796` 去掉 `gconvEnabled` 前置（消 ON→OFF 热切换 listener 残留）；`:83-85` 注释改事实。
- ⛔ **上机纪律**：日志一旦出现 `op=... FAIL`，或 `stat` 的 `convOwn != contactOwn` /
  `own` 与分组数不符 ⇒ 立即停止，先 `op=gcs sub=clean` 再 `sync`(force) 确认两表成对，
  **不得据该轮下结论**；正式发布前必须补齐 P1。
- **5 项需真机验证**：wcdb 单连接 / 一次 sync 耗时、宿主复用时是否重设 listener、
  异步日志 `WAIT` 够不够、tag key 是否撞宿主、`GroupDetailPage`/`GroupRowMenu` 在
  `ConversationFolderItemView` 上的可用性。

**P1 最终实现（已落盘）**：`execLog()` 返回 `Throwable?`；`inTransaction()` 的 block 改
`(MutableList<Throwable>) -> Unit`，`failures` 非空时先打 `FATAL 事务内 N 条 SQL 失败` 再
`throw` ⇒ 跳过 `setTransactionSuccessful` ⇒ `finally endTransaction` 整段回滚；反射降级分支
同样收集并 `throw`、不静默。`sync()` 8 处 + `clean()` 2 处共 **10 处** `execLog` 全部改为
`?.let { failures += it }`（Grep 交叉验证：`execLog(` 11 行 = 10 调用 + 1 定义、
`failures += it` 10 行 ⇒ 零遗漏）。`throw` 后外层 `runCatching{}.getOrElse{FATAL}` 兜住、
**不打印 `sync done own=`** ⇒ 正好构成「FAIL 即停」的可观测信号。
P2 同步：`ConvFilter.kt:1799` 去掉 `gconvEnabled` 前置。
最终验证结论见 `docs/_r54_final_review.md`：**verdict 可装包**，P0/P1 清单 0 条、
A~I 全 PASS、独立构建 `OK (919 tests)`；并用「源码 mtime `00:14:01` < class mtime `00:15:41`
+ `javap` 显示 `execLog` 返回 `Throwable`、`inTransaction` 的 block 为
`Function1<? super List<Throwable>, Unit>`」佐证修复确实进了字节码，不是陈旧 class 冒充。

⚠️ **降级路径不具原子性（互斥分支，不可并列）**：主路径是「有事务 ⇒ `throw` 后 `end` 回滚」；
反射拿不到三个事务方法的**降级路径**是「无事务 ⇒ `throw` 只让外层打 `FATAL` 并停止后续 SQL，
**已逐条提交的 SQL 不回滚**」。真机一旦出现 `WARN 事务方法…反射失败`，即便随后有 `FATAL`
也**不得**按「整段已回滚」处理，必须 `clean` + `sync`(force) 重建、用 `stat` 核对两表成对。
把「失败即 throw」等同于「失败即回滚」是误读。

「任一 SQL 失败 ⇒ 不调 `setTransactionSuccessful`」**补 JVM 单测判定：不可行 / 不必补**
（`inTransaction` 是 private；`GroupConvStore` 依赖 Android `LogX` / `AntiRevoke.wxDb()` /
wcdb 反射；Xposed 依赖是 `compileOnly`、唯一 test 依赖 junit、无 mock 框架与 android.jar；
wcdb 事务真实回滚语义非 JVM 可模拟）⇒ 静态逐行核对 + 真机 `op=... FAIL` 日志与 `stat`
两表成对验证即可。**可与不可的判据要写清，不要含糊带过。**

**装包就绪状态**：APK `wxgroup/app/build/outputs/apk/debug/app-debug.apk`
（`00:15:45` / 3,860,500 B），源码最晚 `00:14:53` ⇒ 产物含全部修复。
按项目规则「调设备需用户明确通知」，此处停手待命，不自行 `adb install`。
