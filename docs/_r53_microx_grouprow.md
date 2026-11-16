# R53 微X 分组条实现原理（取证中）

> 目标：搞清楚微X 的会话列表「分组条」（截图里的 美妆 / 美妆2 / 日化 / 贸易 / 箱包）
> **是怎么进到列表里的**，再决定 wxgroup 要不要跟着改。按要求本轮**不动 wxgroup 的分组实现**。
>
> 状态：静态路已证死（下方第一节）。运行时判据已备好、探针已编译装包，
> **卡在设备离线**（20:0x `adb devices` 为空，`kill-server`/`start-server` 都拉不回来）。

---

## 一、静态路：已证死（别再走一遍）

微X APK 4,423,605B，935 个条目。真实业务代码**不在** `classes.dex` 里：

| 证据 | 值 |
|---|---|
| `classes.dex` | 2,397,988B，可读串仅 12,360 条 |
| 搜 `conversation` / `Conversation` | **0 命中** |
| 搜「分组/标签/未分组」等中文 | **0 命中** |
| `classes.dex` 里出现的微X 类 | 只有 4 个：`XposedInit` / `MainActivity` / `ModuleActiveCheck` / `WxCoreLoader` |
| 装载手段 | `dalvik/system/InMemoryDexClassLoader` + `DexClassLoader` |
| `res/layout` | 0 个 |

业务代码在 `assets/` 下 9 个 hash 命名文件里，其中 **7 个是密文**：

```
92c50100 + 熵 8.00   530707B / 461560B / 353907B / 323411B / 276456B / 258499B / 48191B / 41471B / 4831B
7f454c46(ELF)        276456B(arm32) / 461560B(arm64)  ← 解密器本体，未加密
```

两个 ELF 有 `JNI_OnLoad`，串里引用 `classes.dex`、`InMemoryDexClassLoader`、
`PBEWithMD5AndDES`、`SHA1withRSA`，符号已剥离、无 `decrypt` 类字面量 ⇒ 反汇编收益极低。

**加密形态实测（两个密文文件）**：

| 判据 | 结果 | 结论 |
|---|---|---|
| 逐字节 XOR 后零字节占比 | **0.41%**（1053 / 258499） | keystream **不复用**，每文件各自 key/IV |
| 16 字节块重复数 | 33168 块 **全不同**（`dup=0`） | **不是 ECB** |
| 文件头 4 字节 | 全部 `92 c5 01 00` | 固定容器头，密文从偏移 4 起 |

⇒ 正规流/CTR/CBC 加密，密钥在已剥符号的 .so 里。**离线解不出，不要再花时间。**

---

## 二、已确证的事实（真机，11-11 19:30~19:50）

### 2.1 微X 在微信数据目录里有自己的库，但**是空的**

`op=label sub=sql` 会遍历所有库，日志里露出三个 `FKZ_*` 库（FKZ = fkzhang）：

```
/data/user/0/com.tencent.mm/databases/FKZ_WX_DATA          ← 0 张表
/data/user/0/com.tencent.mm/databases/FKZ_WX_FAKELIKES     ← android_metadata | FAKELIKES
/data/user/0/com.tencent.mm/databases/FKZ_WX_FAKECOMMENTS  ← android_metadata | FAKECOMMENTS
```

`FKZ_WX_DATA` 只有 0 张表 ⇒ **分组配置不在 SQL 里**（大概率在 SharedPreferences）。

### 2.2 那 5 个名字**不在**通讯录/会话表里

`rcontact` 按 `conRemark`/`nickname` 搜「美妆 / 日化 / 贸易 / 箱包」：

```
wxid_s2xbrt3884wp12   顾轶潇销售—有为新材料佳帆贸易   a派啊
wxid_me6o5c14bru621   银座箱包Cancer                 天宇
```

只有这两个「名字里带这两个字」的联系人，**没有精确等于 美妆/美妆2/日化/贸易/箱包 的群或好友**。

### 2.3 行的 View 结构 = **微信原生会话行的结构**

对 `uiautomator` 导出树做子树比对（行容器 = ListView 直接子节点）：

| 行 | 节点数 | 子节点 resource-id 序列 |
|---|---|---|
| **美妆**（微X） | 14 | `cqz, <RelativeLayout>, ac8, <RelativeLayout>, <LinearLayout>, pbz, <LinearLayout>, l7a, q5l, <LinearLayout>, <LinearLayout>, igs, <LinearLayout>, d_l` |
| **chinaplas**（微X） | 14 | 同上 |
| **客户**（微X） | 14 | 同上 |
| **pet片材**（真实） | 15 | 同上 **+ `piu`**（未读红点） |
| **小宁不是笨蛋**（真实） | 16 | 同上 **+ `acl`**（免打扰）+ `hwj` |

⇒ 微X 的行与真实会话行**同布局、同 id**（`l7a`=标题 / `q5l`=时间 / `igs`=摘要 / `d_l`=分隔线），
差异只在条件性子节点（未读、免打扰）。**「微X 自绘一套 UI」这个假设被排除。**

### 2.4 长按这一行弹的是**微X 自己的菜单**

`input swipe 700 265 700 265 900`（长按 美妆 行）后 dump：

```
微X模块
  发送 / 群聊标签 / 批量发送公告 / 通讯录标签
  所有消息标为已读 / 批量删除消息 / 快速切换账号 / 返回
```

菜单里有「**群聊标签**」「通讯录标签」⇒ 微X 确有「标签」体系，这是分组条最可能的来源。

### 2.5 时间与摘要对不上会话表

- 美妆行显示 `16:12`、摘要「杰迪亚杨广梁: 好」（带发送者前缀 ⇒ 群聊形态）。
- `rconversation` 在 16:10~16:14 窗口 **0 行**；12:48~12:52、12:14~12:18 同样 **0 行**。
- 按摘要反查 digest 含「杰迪亚」，只命中一个 1:1 会话 `wxid_12zfu1d66mcl22`
  （digest=`哦哦，杰迪亚的杨总`，内容不同）⇒ **不是它**。
- ⚠️ 这条**证明力有限**，见第三节第 3 点。

### 2.6 微X 读配置用的是 Xposed 的 `XSharedPreferences`

`classes.dex`（外壳）里出现 `Lde/robv/android/xposed/XSharedPreferences;`。
这是 Xposed 提供的**跨进程读「模块自己」prefs** 的通道 —— 说明微X 的配置存在
**它自己 App 的 SharedPreferences** 里，而不是微信数据目录里的 SQL（与 2.1 的
「`FKZ_WX_DATA` 0 张表」互相印证）。

**这条很关键**：同一个 API 我们也能调 ⇒ 设备回来后直接
`XSharedPreferences("com.fkzhang.wechatxposed").getAll()` 就可能把「标签 → 群」的
配置结构整份读出来，不需要解 shell、也不需要读它的 hook 表。
探针已实现为 `op=xh sub=prefs`（同时列微信 `databases/`、`shared_prefs/`、`files/` 三个目录，
确认除 prefs 外还有没有别的落点）。

### 2.7 ⭐ 列表分成**两个块**，不是「按时间混排」

对 `uiautomator` 那份树按 **ListView 子节点顺序**（= 视觉顺序）重排，13 个可见行是：

```
child  0  pet片材            16:27
child  1  美妆               16:12   摘要「杰迪亚杨广梁: 好」
child  2  美妆2              12:50
child  3  日化               12:16
child  4  贸易               昨天
child  5  箱包               11月3日
child  6  chinaplas          10月29日 摘要「上海虹丫化工吴逸飞: 50g」
child  7  客户               2月16日
──────────────────────────────── 时间断崖：2月16日 → 19:45 ──────────────
child  8  小宁不是笨蛋        19:45
child  9  眼子钻粉总部5群      19:45
child 10  订阅号消息          19:44
child 11  牛来！ 3.0（6666）  19:44
child 12  集团军⚽️菜馆        19:44
```

而且列表**就在顶部**（ListView 从 y=0 开始，child0 也在 y=0，没有向上滚动的余地），
所以块 A 不是「滚上去的历史」，是**真的排在最前面**。

⇒ 这是**两个独立 section 首尾相接**，各自内部按时间降序。
19:30 那张截图里的顺序同样符合（`改性 17:30` 在 `pet片材 16:27` 之上，属于同一块）。

**块 A 的 8 个名字全是业务品类**（PET片材 / 美妆 / 日化 / 贸易 / 箱包 / 展会 chinaplas / 客户），
与 2.2「rcontact 里查不到这些名字」合起来看，块 A 就是**微X 的分组块**，不是置顶会话。

> ⚠️ 但必须留一个反面假设：微信的**置顶区**也长这样（置顶会话排在最前、内部按时间降序、
> 与正常列表之间同样没有可见分隔）。两者靠 dump **区分不了**。
> 判别方法见第四节 T5（数 `rconversation` 的置顶数）与 T6（关微X 后看块 A 还在不在）。



---

## 三、被推翻的假设 / 我自己犯的错（判据纪律）

1. **`rconversation` 根本没有 `nickname`/`conRemark` 列**。
   实际列：`msgCount, username, unReadCount, chatmode, status, isSend, conversationTime,
   content, msgType, customNotify, showTips, flag, digest, digestUser, hasTrunc, parentRef,
   attrflag, editingMsg, atCount, sightTime`。
   ⇒ 我之前那条 `where nickname in (...)` 是**非法 SQL**，返回的「0 命中」是**假阴性**，已作废。
   显示名在 `rcontact`（群聊的显示名在 `nickname`，备注在 `conRemark`）。

2. **`>` 在远端 shell 会被当成输出重定向**。
   `--es sql 'select ... instr(..)>0'` 会被设备端 sh 解析成重定向，
   表现是**广播回来了 `result=0` 但探针一行都没跑**（比报错更坑）。
   ⇒ 一律改用 SQLite 真值判断：`where instr(...)`，或加引号保护。

3. **「时间窗 0 行」的证明力有限**。
   同一批 talker 在 16:12 有消息、但它们 19:44 还有更新的消息，所以
   `conversationTime` 早就跳走了。这条只能证明「没有会话的最新时间正好是 16:12」，
   **不能**证明「美妆行不是从会话行派生」。

---

## 四、设备回来后要跑的判据（一条命令）

探针已就绪：`HookListProbe`（新建，纯只读）+ `EntryReceiver` 的 `op=xh`。
编译 **BUILD SUCCESSFUL**；装包因设备掉线未完成（`adb devices` 空）。

一键脚本：`python tools/_r53_xh.py prefs|hook|row`

| # | 动作 | 判据（对着用户能看见的事实写） | 能定什么 |
|---|---|---|---|
| **T0** | `_r53_xh.py prefs` | `getAll()` 非空且 key 里有标签名（美妆/日化…）⇒ 配置就在 prefs | **最直接**：拿到「标签 → 群」的完整配置结构 |
| T1 | `_r53_xh.py hook` | 输出里出现 `com.fkzhang.*` 的 Member | 拿到微X 的 hook 点清单 |
| T1b | `hook com.tencent.mm.ui.conversation` | 列出微X 在会话列表相关类上挂了什么 | 若 T1 空，看它是否根本没进全局表 |
| T2 | `_r53_xh.py row` | 某位置 `vt=-1` 且 `item=null` 但 `view≠null` ⇒ header/伪行；`item` 是 `r4` ⇒ 真会话 | 行是**哪一层**注入的 |
| T2b | 同上，看 `tag=` | tag 类是 `com.fkzhang.*` ⇒ 微X 自建行；是 `conversation.n3` ⇒ 微信原生行 | 真行 vs 伪行 |
| T3 | 关掉微X 的「群聊标签」再 dump 列表 | 行消失且被收纳的会话**出现** ⇒ 合成行 + 收纳；只有文字变 ⇒ 改名 | 行为对照（最强的反证） |
| T4 | 点一下 美妆 行 | 进聊天 ⇒ 绑定了真实会话；展开/进微X 页面 ⇒ 合成行 | 兜底判据 |
| **T5** | `select count(1) from rconversation where flag % 2 = 1` | 结果 ≈ 8（块 A 行数）⇒ 块 A 是**置顶区**；结果 ≪ 8 ⇒ 块 A 是微X 造的分组块 | 排掉「置顶区」这个反面假设 |
| **T6** | LSPosed 里**禁用微X**、重启微信后再 dump | 块 A 消失 ⇒ 微X 造的行；块 A 还在但标题变回群名 ⇒ 微X 只改标题 | 一锤定音（正反对照） |

> ⚠️ T1 有个前提没验证：LSPosed 可能给**每个模块各自的 classloader 一份 `XposedBridge`**，
> 那样我们只能读到自己挂的 hook。`sub=hook` 会打印 `fields=[...=N]` 与本模块已知 hook 数对比，
> 若 size 与我们自己挂的数量吻合，就说明被隔离了 —— 此时以 T2/T2b 为准。

---

## 五、当前机制假设（**未证实**，写下来是为了有个可被推翻的靶子）

最符合现有证据的版本（2.7 之后已修正为「成块置顶」，不是混排）：

1. 微X 维护「群聊标签 → chatroom 列表」的自有配置（`XSharedPreferences`，不在 SQL）。
2. 会话列表**顶部拼一个独立分组块**，块内每行对应一个标签，按「组内最新消息时间」降序；
   块结束后才接微信正常的会话列表（同样时间降序）。
3. 分组行**复用微信原生会话行的布局**（id 全同、头像也是真照片），标题 = 标签名
   ⇒ 所以 rcontact 里查不到这些名字。
4. 被收纳的会话从正常列表里消失（或被压到下面）。

**已排除**：自绘 UI（2.3 同布局）、分组行与会话按时间混排（2.7 两个块）。
**仍可能**：块 A 其实是微信置顶区（T5/T6 排）。
**待定**：行是合成行还是「拿真实行改标题」（T2b/T3/T6 定）。

> ⚠️ 与 wxgroup 现有的差异点（这一条直接影响后续要不要改）：
> wxgroup 的 `ConvFilter` 是**把分组行插进时间线里混排**（与该组最新会话同位），
> 而微X 是**单独一个块放在最前面**。这是两种不同的产品形态，不是同一实现的两种写法。

---

## 六、对 wxgroup 的影响（等结论，不动手）

如果 T1/T2b 证明微X 是**在 adapter 层造行**，那 wxgroup 现有做法（包装 adapter 内混排）方向一致，
差异只在「行视觉」与「收纳策略」；如果是 **hook 宿主 `getView` 改文字**，那 wxgroup 现在这套
包装 adapter 的方案就是另一条路，需要重新评估是否值得跟。

⇒ ⛔ 机制未证实前不改 wxgroup 源码。

---

## 七、设备恢复后的命令速查（直接复制粘贴）

```bash
A="/c/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1          # 否则 /sdcard/... 会被 Git Bash 改写成 Windows 路径

# 0) 装包（必须先装，探针才在）
"$A" install -r wxgroup/app/build/outputs/apk/debug/app-debug.apk
"$A" shell am force-stop com.tencent.mm && sleep 1
"$A" shell am start -n com.tencent.mm/.ui.LauncherUI && sleep 8
"$A" shell am force-stop com.tencent.mm && sleep 1     # 冷启两次，模块才重载
"$A" shell am start -n com.tencent.mm/.ui.LauncherUI && sleep 8

# 1) T0 读微X 配置（模块内）
python tools/_r53_xh.py prefs
# 1b) T0 备用：直接 su 读它的 prefs（不经模块）
python tools/_r53_xh.py prefsx

# 2) T1 全局 hook 表（默认只看 com.fkzhang）
python tools/_r53_xh.py hook
python tools/_r53_xh.py hook com.tencent.mm.ui.conversation

# 3) T2 adapter 链 + 每行 viewType/tag/item
python tools/_r53_xh.py row

# 4) T5 数置顶（⚠️ 不能写 `flag & 1`：`&` 会被 shell 拿去后台运行）
"$A" shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op label --es sub sql \
  --es sql 'select count(1) as n from rconversation where flag % 2 = 1' -p com.tencent.mm"
```

**两条硬约束**（这轮都踩到过）：

- `--es` 的**值**必须再包一层单引号，否则设备端 `sh` 会二次解析：`(` 直接语法报错、
  `>` 被当**输出重定向**（广播照样回 `result=0`，但探针一行没跑，**没有任何报错**）。
- SQL 里尽量不用 `>` / `&`：前者换 `instr(...)` 真值判断，后者换 `% 2`。

---

## 八、机制定案（真机实测 21:07~21:22）

> 结论：微X 的分组条是**数据层注入**——往 `rconversation` 写「伪会话行」、往 `rcontact` 写「空名联系人」，
> 然后让微信**用原生渲染**把它画出来；标题/摘要在渲染期注入。**不是** adapter 包装，也**不是** header 注入。

### 8.1 决定性数据：界面上的时间与库里的 `conversationTime` 逐个吻合

`rconversation` 里 username 形如 `wx__c_<hex>` 的行共 **12 条**，与界面块A 一一对应（秒级精确）：

| 界面显示 | 界面时间 | `conversationTime`（ms） | 换算 | username |
|---|---|---|---|---|
| 改性 | 17:30 | 1794389456468 | 17:30:56 | `wx__c_18c13d8a134` |
| pet片材 | 16:27 | 1794385655000 | 16:27:35 | `wx__c_19d94cc78fb` |
| 美妆 | 16:12 | 1794384767190 | 16:12:47 | `wx__c_18c13d85c84` |
| 美妆2 | 12:50 | 1794372625622 | 12:50:25 | `wx__c_18c13d87346` |
| 日化 | 12:16 | 1794370616403 | 12:16:56 | `wx__c_18c13d886b1` |
| 贸易 | 昨天 | 1794281400000 | 11-10 11:30 | `wx__c_18c13d8bbf6` |
| 箱包 | 11月3日 | 1793695628000 | 11-03 16:47 | `wx__c_19abf3df093` |
| chinaplas | 10月29日 | 1793264147078 | 10-29 16:55 | `wx__c_19dae8ea92a` |
| 客户 | 2月16日 | 1771246105000 | 02-16 20:48 | `wx__c_18c13d9069d` |

### 8.2 为什么它们排在最前面：`flag` 就是排序键

```
total = 3120
其中 flag == conversationTime        → 3100 条   （普通行：flag 就是排序时间）
其中 flag == 2^62 + conversationTime →   20 条   （置顶行）
```

`2^62 = 4611686018427387904`。**这 20 条就是置顶区**，里面是：12 条 `wx__c_` 伪会话
+ 7 个真实联系人（`wxid_*` / `pangzi*` / `fengfeiwuzhihen`）+ 1 个公众号（`gh_a629f5142723`）。
置顶区内部按 `conversationTime` 降序 ⇒ 恰好是块A 的顺序。

⇒ 微X 把分组行**写成置顶伪会话**，于是它们恒在列表最前，与真实置顶会话混在同一区。
（块A 看起来连续，是因为那几个真实置顶会话被 wxgroup 收纳隐藏了。）

### 8.3 微X 具体写了什么

**`rconversation` 插一行**（12 条）：
```
username = wx__c_<hex>
flag     = 2^62 + conversationTime      ← 置顶 + 排序
conversationTime = 该标签下最新消息时间
attrflag=0  status=3  showTips=0  msgCount=0  isSend=1  digest/content 为空
```

**`rcontact` 插一行同名记录**（11~12 条，`type=0`）：
```
username=wx__c_<hex>  alias=  conRemark=  nickname=  pyInitial=  quanPin=  …  全部为空
```
⇒ 让微信的会话渲染拿得到「联系人对象」，但**名字一个都没存**。

### 8.4 标题从哪来：渲染期注入（库里没有）

`rcontact` 那 12 行的 **所有名字列都是空**（`conRemark`/`nickname`/`pyInitial`/`quanPin`/
`conRemarkPYFull`/`conRemarkPYShort`/`alias`/`encryptUsername` 全空），而界面上显示「美妆」「日化」。
⇒ **标题是微X 在渲染时塞进去的**，数据层没有。分组定义（标签 → 群）在它自己的
`FKZ_WX_ChatroomTag`（16KB）与 prefs 里。

### 8.5 与 wxgroup 的实现差异（这才是能抄/不能抄的部分）

| 维度 | 微X | wxgroup 现状 |
|---|---|---|
| 行从哪来 | **写进 `rconversation` 的伪会话**（数据层） | **包装 adapter 自己算行**（UI 层，`ConvFilter$GroupedAdapter`） |
| 排名 | 复用微信排序键 `flag`，置最高位 ⇒ **恒在最前** | 插进时间线，与该组最新会话**同位混排** |
| 联系人对象 | 写 `rcontact` 空名记录，微信原生渲染 | 自建 `GroupRowView`，`getItemViewType` 返回 **1** 区分 |
| 持久化 | 重启后仍在（就在库里） | 每次冷启重建 |
| 侵入性 | 高（改宿主业务库，微信版本变化/清理会踩） | 低（只包 adapter，不写宿主库） |
| 风险 | 伪会话会被「清空聊天记录」「会话迁移」等宿主逻辑处理；且 `wx__c_` 前缀是微信自己用的，**有撞车风险** | 不碰宿主数据 |

### 8.6 唯一没验的

- 微X 渲染期注入的**具体 hook 点**（改标题的那个方法）。LSPosed **v101** 的 hook 注册表不在
  `XposedBridge` 的可反射静态字段里（`fields=[]`），所以拿不到它的 hook 清单；
  要定位只能逐方法比对，收益不高。
- `FKZ_WX_ChatroomTag` 打不开（`FKZ_WX_DATA` 也是 0 张表）⇒ 配置大概率是加密库或 key 在 native 里。

---

## 九、两条路线的优劣（选型对照）

| 维度 | 微X：数据层伪会话 | wxgroup：adapter 层包装 |
|---|---|---|
| 写宿主库 | **是**（`rconversation` + `rcontact`） | 否（一行不写） |
| 卸载残留 | **有**（伪会话留在库里，点不开） | 无（关开关即恢复） |
| 持久化 | 天然（冷启/杀进程都在） | 每次重建（靠快照缓存压到 2~4ms） |
| 依赖宿主混淆名 | 只依赖 4 个**稳定列名** | 依赖 `HeaderViewListAdapter.mAdapter` / `p3` / `r4` 字段（每版会变） |
| 跨版本鲁棒性 | 高 | 中低（已多轮适配） |
| 行视觉自由度 | 低（只能用宿主行布局） | 高（自绘，可放成员数/拼合头像） |
| 交互完整度 | 高（宿主原生：长按/滑动/红点/置顶样式） | 中（要自己补，做不全就"像但不对"） |
| 排序控制 | 借用 `flag` 排序键（置顶位） | 完全自算（混排/前置块/筛选态随便改） |
| 与别的 adapter 模块冲突 | 无（不碰 adapter） | 有（多层包装顺序敏感） |
| 名字可见范围 | 只有列表（聊天页标题/搜索/转发看不到） | 同左（自绘行同样只活在列表） |
| 排查成本 | 高（要区分宿主写的 vs 模块写的） | 中（行序列可单测复现） |
| 崩溃/白屏风险 | 低 | 有（`getView` 返回 null 白屏、observer 未挪不刷新，都踩过） |
| 数据一致性责任 | **模块承担**（conversationTime 要随消息实时改写，与宿主写库竞争） | 无 |

**数据层独有的、adapter 层拿不到的**：冷启零重建、宿主原生交互全量继承、置顶位免费。
**adapter 层独有的、数据层拿不到的**：零残留可回滚、行内容自由、排序/筛选逻辑可单测、不动宿主数据。

**结论**：要达到"分组行成块置顶"这个**产品形态**，adapter 层加一个前置块即可，不必付数据层的
"写宿主库 + 卸载残留 + 一致性自负"这三笔代价。数据层只有在"要让分组行参与微信自己的置顶/免打扰/
原生长按菜单"时才值得。若要抄数据层，**必须换自己的前缀**（不能用 `wx__c_`，那是微信命名空间）。

---

## 十、"体感上微X 更流畅"的归因（真机实证，21:32~21:38）

### 10.0 先说清两个**不能当判据**的数

| 数 | 值 | 为什么不能用 |
|---|---|---|
| `dumpsys gfxinfo` 百分位 | 50th 61ms / janky 19.51% | 统计窗口含**静止间隔**（6 次 swipe 只出 82 帧，每帧之间隔着 `adb shell` 的几百 ms）|
| `gfxinfo framestats` 每帧成本 | 中位 53.7ms，`FrameInterval=8.278ms`（→ **120Hz**） | 手势是 `input swipe` **合成**注入的，帧节拍被注入节奏牵着走，不代表真实渲染成本 |

⇒ **帧率层面没有可用的 A/B**：两个模块同时装着，拆不开；合成输入又不可信。
⇒ 下面三条归因全部落在**代码/日志可证的结构差异**上，不靠帧率。

### 10.1 已证伪：行高不是原因（顺带修掉一条过时注释）

同一屏 dump（滚到 `已登录Windows电脑` 通知条之下）的 y 坐标：

| 行 | 类型 | 顶边 y | 与上一行差 |
|---|---|---|---|
| G1 头像 | **我们自绘** | 549 | — |
| G2 头像 | 我们自绘 | 808 | **259** |
| G3 头像 | 我们自绘 | 1067 | **259** |
| 改性 | 微X 伪会话 | 1334 | **267**（含 8px 分隔） |
| pet片材 | 微X 伪会话 | 1593 | **259** |
| 美妆 | 微X 伪会话 | 1852 | **259** |

⇒ 分组行 pitch **259px = 宿主会话行 259px**（`density=640` ⇒ 64.75dp）。
⇒ ⚠️ `GroupRowView` KDoc 第 34 行「高度跟微信会话行对齐（实测 72dp）」**是错的**，
   72dp = 288px，实测是 259px。**实际情况是对的，注释是旧的**——别照着注释去改尺寸。

### 10.2 已证伪：滚动期间我们没有额外计算

`grep -cE 'K7 (full rebuild|debounce)'` 在连续滚动窗口内 = **0**。
但**非滚动**态每 10~20 秒会来一次（收消息 / 切前后台）：

```
21:32:43.641 main K7 full rebuild n=1576 snaps=2ms total=5ms layout=1578
21:33:05.333 main K7 full rebuild n=1576 snaps=3ms total=7ms layout=1578
21:34:41.931 main K7 full rebuild n=1576 snaps=2ms total=7ms layout=1578
```

`total=2~7ms` 打在 **main** 线程。120Hz 的帧预算是 8.3ms ⇒ **一次重建吃掉半帧到一整帧**。
（上一轮记的"滚动期间 0 次"没错，但不能推成"我们滚动时没开销"——开销在**消息到达那一刻**。）

### 10.3 已实证：滚动路径被我们改写（**这就是"不跟手"的来源**）

微X 对列表滚动 **零 hook**；我们 hook 了 `AbsListView.trackMotionScroll`（基类，全微信所有列表），
`after` 里调 `pin()`，越界就反射 `offsetChildrenTopAndBottom` 把内容**硬拽回**停靠位。

分组态（点 G3 后 `currentGroupId()>0`）下拉，日志原文：

```
21:38:13.495 main ScrollDock pin -13px: top -2700 -> -2713 (dock=-2713)
21:38:13.522 main ScrollDock pin -90px: top -2623 -> -2713 (dock=-2713)
21:38:13.546 main ScrollDock pin -39px: top -2674 -> -2713 (dock=-2713)
21:38:13.572 main ScrollDock pin -36px: top -2677 -> -2713 (dock=-2713)
21:38:13.595 main ScrollDock pin -37px: top -2676 -> -2713 (dock=-2713)
21:38:13.646 main ScrollDock pin -38px: top -2675 -> -2713 (dock=-2713)
21:38:13.745 main ScrollDock pin -43px: top -2670 -> -2713 (dock=-2713)
21:38:13.770 main ScrollDock pin -41px: top -2672 -> -2713 (dock=-2713)
```

**250ms 内 8 次**，每次位移 13~90px，每次一次**反射 + 参数装箱 + 整块子 View 偏移**。
可感表现三条，缺一不可地加起来就是"不顺"：
1. 手指往下推 → 内容先跟着走 13~90px → 下一帧被拽回 ⇒ **没有跟手感**；
2. 原生 rubber-band 回弹动画被"每帧归零"吃掉 ⇒ **没有回弹，只有一顿一顿**；
3. 微信原生「下拉露出顶部栏 + 弹小程序面板」在分组态被完全禁用。

⚠️ 这条**只在分组态生效**（`guardPin` 要求 `currentGroupId()>0`）——正好就是拿来做对比的那个状态。
不筛选态实测**不会**触发（下拉到 raw top 时日志 0 条）。

### 10.4 结构性差异（解释"为什么它天生更顺"，无需再测）

| | 微X | wxgroup |
|---|---|---|
| 分组行是谁的 View | **微信自己的会话行**（DB 伪会话） | `GroupRowView` 自绘，**多占一个 viewType** |
| `getView` 里做什么 | 无（不介入） | 类型判定 `viewTypeOf(convertView)` → **每次反射** `AbsListView.LayoutParams.viewType` |
| convertView 复用 | 全池同类型，100% 可复用 | 分组行 ↔ 会话行**类型不同 ⇒ 不能互相复用** |
| 数据变了怎么更新 | 微信自己增量刷新那一行 | `rebuild()` 全量重算 1576 项 + `notifyDataSetChanged()` 全屏重绑 |
| 滚动边界 | AOSP 原生判据成立 | 吞 `correctTooHigh` + 每帧钳制（见 10.3） |

### 10.5 顺带发现：分组态"点进去只剩一行"

点 G3 后：`wrappedCount=1`，`K7 full rebuild ... layout=1`。
即 G3 只有 1 个会话 ⇒ 整页 = 3046px 巨型 header + 1 行会话。
G1 的摘要显示 `11`、G2 显示 `分组 · 0 位成员`。
微X 点分组是**换页**（它自己的组内 Activity），我们是**原地过滤**——空组/少成员的组会显得很空旷。
这是产品形态差异，不是 bug，但会被读成"卡了一下、没反应"。

### 10.6 修复清单（按性价比，全部是 adapter/滚动层小改，不碰数据层）

| # | 改什么 | 位置 | 收益 | 风险 |
|---|---|---|---|---|
| 1 | **下拉改成"事前拦截"**：停在停靠位且手势向下时，在 `dispatchTouchEvent` 里把 dy 吃掉，位移恒为 0 | `ScrollDock` 新增 hook | 每帧反射归零、手感=原生"已到顶" | 中（要在手势层动，需真机 A/B） |
| 2 | `pin()` 的 `offsetChildrenTopAndBottom` 反射 **缓存成 `Method`** | `ScrollDock.pin` | 去掉每次按名查找 | 低 |
| 3 | `viewTypeOf()` 的 `XposedHelpers.getIntField` **缓存成 `Field`** | `ConvFilter` 1711/1729 | 去掉每行一次反射 + 字符串拼接 | 低 |
| 4 | `groupType` 从 `get()` 访问器改成**构造时算一次** | `ConvFilter` 1161 | 去掉每次 `wrapped.viewTypeCount` 的 runCatching | 低 |
| 5 | 分组行没用到 convertView 时，**留着传给下一条会话行**（现在直接丢弃 ⇒ 那一次滚动要多新建/另取一个 View） | `ConvFilter.getView` | 滚动时少一次 inflate | 低 |
| 6 | 想"真流畅" ⇒ 仿微X 走数据层（换自己前缀） | — | 冷启零重建、原生交互 | **高**（见第九节代价） |

**建议先做 2/3/4/5（纯清理，可单独 A/B），再评估 1。** 1 是唯一会**改变手感**的改动，
也是唯一能对上"下拉时一顿一顿"的；但它是改手势语义，必须真机反向对照。

---

## 十一、R55 补充：微X 分组条「点击 / 长按」的真机事实（2026-11-12 00:47~00:53）

起因：用户要求「点开和长按 123 分组条，希望跟微X一样」。我方 R54 数据层分组条已验收通过，
但点击/长按仍是**模块自绘浮层**，与微X 不是一套链路。以下为**纯事实记录**（不含链路推断；
链路分析见 `docs/_r55_microx_click_longclick.md`）。

### 11.1 我方现状（`wxgrp_` 行，开关 ON）
- 点击 → `123 · 1 人 / 张松毅 / wxid_6wjz2ehi0i9x22 / + 添加成员 / 群发消息`
  = 模块 `GroupDetailPage` 浮层（`topResumedActivity` 仍是 `LauncherUI`）。
- 长按 → `123 / 取消置顶 / 重命名 / 管理成员 / 标记已读 / 删除分组 / 取消`
  = 模块 `GroupRowMenu`。
- 行本身渲染成 **`ConversationFolderItemView`**（`op=xh sub=row pos=14 vt=0`），
  与微X 行同一原生 View 类；`l7a` 自绘标题 accessibility text = `123`。

### 11.2 微X 分组行点击 = 微信原生「带标题栏的会话列表页」
点 `改性` 分组行后 dump（`_cache/u_r55_mx_click.xml`）：
- `ListView com.tencent.mm:id/pmu [0,333][1440,3104]`，行是标准三件套
  `l7a`(标题) / `q5l`(时间) / `igs`(摘要)，标题全是真实联系人
  （奥能采购徐小姐、异彩达技术何振兵、摩康复合材料赵晓磊Kris、奥世达研发David、
  宁波敏实泰甬方飞、张丽萍-重庆LGchem、李明娟-重庆LG采购、李刚-宁波lg甬兴abs技术…）。
- 顶部标题 `com.tencent.mm:id/pkn` = **`改性 (212)`**；左侧 `q4t`=`133`；
  右侧 `g6`=`菜单`；标题栏容器 `es [0,160][1440,333]`。
- 容器链：`FrameLayout ii7 → android:id/content → kf8 → p0q → keo → ListView pmu`。
- 另一次取证打开 **`pet片材 (9)`** 同类页面（`_cache/u_r55_mx_long2.xml`）
  ⇒ 标题格式 `组名 (N)` **稳定复现**，N 疑为该组未读总数。
- ⚠️ `input keyevent 4` **退不出该页**（返回后 dump 仍是分组页），
  可能拦截返回键或需点左上角；取证要回主列表请直接强杀冷启。

### 11.3 微X 分组行长按菜单
主列表对 `1` 分组行长按（有效取证）弹：
`解散聊天分组 / 归拢设置 / 标为未读 / 取消置顶 / 不显示该聊天 / 删除该聊天 / 加入分组 / 标记已读`
- 「加入分组 / 标记已读」是**本模块 `ConvMenuInject`** 追加项（对微X 行也生效）。
- 「解散聊天分组 / 归拢设置」疑为微X 对文件夹行加的项。
- ⚠️ `_cache/u_r55_mx_long.xml` / `_r55_mx_long2.xml` 两次长按**都没弹菜单**
  （一次返回没成功、一次被识别成点击打开了分组页），**不得**当长按证据。

### 11.4 库层：`parentRef` 排除法
- 微X `wx__c_18c13d84792` 行全字段：`msgCount=0 / chatmode=1 / status=3 / isSend=1 /
  msgType=1 / content= / digest= / parentRef=` 空 / `flag=2^62+conversationTime`。
- 全库 `parentRef` 分布：`officialaccounts(51) / conversationboxservice(23) /
  service_officialaccounts(11) / gh_777ce52154c6(8) / gh_f9ea3544f97b(1) /
  appbrandcustomerservicemsg(6) / opencustomerservicemsg(5)` 及空值（2971 + 45）。
  **没有任何指向 `wx__c_` 或 `wxgrp_` 的值** ⇒ 微X **不是**靠「把子会话 `parentRef`
  挂到分组行下」实现分组页，点击链路必在 hook / UI 层。
- `op=xh sub=hook` 读不到任何已挂 hook（LSPosed classloader 隔离，R53 §2 已定案
  「以 `sub=row` 为准」）⇒ 不能靠枚举 hook 列表反查微X。

### 11.5 不能简单「删掉我方接管」
R54 阶段0 实证：裸 `wxgrp_t1/t2` 测试行不接管点击时，微信原生会打开
「对方还不是你的朋友」空聊天页（不崩但必须拦）。所以对齐微X 必须给出替代交互。

### 11.6 我方「零新增 hook」备选：现成能力与首要风险（源码事实，非方案结论）
- `ConvFilter.setGroup(id)` 在 `ConvFilter.kt:605`，`currentGroupId()` 在 `:579`，
  toggle（再点同组退出）在 `:596`；**旧自绘分组行的点击本来就是**
  `onTap = { runCatching { setGroup(gid) } }`（`:1766`）；
  `GroupBar.kt:1454/1534` 的「显示全部会话」→ `setGroup(NONE)` 退出筛选。
  ⇒ 把伪会话行点击从 `GroupDetailPage.open` 换成 `setGroup(gid)`，即可在**主列表原地**
  只显示该组会话，零新增宿主 hook、天然复用微信原生会话行渲染。
- ⚠️ **首要风险**：`ScrollDock.kt:110` = `return ConvFilter.currentGroupId() > 0L`
  ⇒ 一旦进入 `setGroup` 筛选态，`ScrollDock` 立即激活。R53 §10 已真机实证它在分组态
  下拉时每帧反射 `offsetChildrenTopAndBottom` 把列表钳回停靠位，正是「下拉不跟手 /
  无 rubber-band」的体感根因 —— 也正是本轮改数据层想甩掉的东西。
  能否「只筛选、不激活 ScrollDock」属于方案层问题，见 `docs/_r55_microx_click_longclick.md`。

## 十二、R55 决定性取证：微X 分组页 = 微信自己的 `ConvBoxServiceConversationUI`（2026-11-16 19:00~19:05）

> 本节推翻 §11.6 隐含的一个前提（「微X 那个页要么是宿主原生文件夹实体、要么是微X 自绘」），
> 并给出一个此前没人想到的第三解：**微X 只是 `startActivity` 了微信已有的一个 Activity**。

### 12.1 事实一：点微X 分组行 → 前台是微信自己注册的 Activity

```
adb shell input tap 700 684          # 会话列表第 2 个分组行「美妆」
adb shell "dumpsys activity activities | grep -m1 topResumedActivity"
→ topResumedActivity=ActivityRecord{216547992 u0 com.tencent.mm/.ui.conversation.ConvBoxServiceConversationUI t2670}
```

`mCurrentFocus` 同步变成该 Activity 的窗口。**判据价值**：Xposed 模块**不能注册新 Activity**
（改不了宿主 `AndroidManifest`），所以这个类必然是微信自己的。
⇒ §11.2 里那套「`ii7` + 原生 ActionBar + `ListView pmu`」的页面骨架**不是微X 画的**，
微X 最多只是把它启动起来、并从某处喂数据。

### 12.2 事实二（反向对照）：关掉我方接管后，我方行的点击**掉到 `ChattingUI`**

零新增代码的对照实验（只改一个开关，不动库、不动 UI 代码）：

```
python tools/_r6_kv.py set chat.gconv_enabled 0     # 只停「视图层接管」，行还在库里
am force-stop com.tencent.mm && am start -n com.tencent.mm/.ui.LauncherUI
# 行仍在：l7a text=123 bounds=[274,1679][1148,1761]
adb shell input tap 700 1720
→ topResumedActivity=.../ui.LauncherUI（未变），但界面已变成「标题 123 + 对方还不是你的朋友 + 输入框」
```

即微信把 `wxgrp_0000000000000099` 当成**一条普通会话**打开了一个空聊天页，
**没有**走 `ConvBoxServiceConversationUI`。

**结论 [已证实]**：微X 的行能进那个页，靠的是**微X 自己在点击时启动它**（或替换了点击 listener），
而不是「宿主看到 `flag` 带 `2^62` 就自动开文件夹页」。所以：
- 「把我方 listener 去掉、放行给原生」= **死路**（只会开出一个空聊天）。
- 路线选择从「A 原地筛选 / B 复刻宿主文件夹实体 / C 自绘全屏页」扩出第四条：
  **D = 我方点击时 `startActivity` 微信这个现成 Activity，并喂我方分组的会话列表** ——
  若它的 Intent 能接收列表，这就是「与微X 完全同款」且零自绘的最优解。

### 12.3 字段对照：微X 行与我方行只差 `parentRef`

```sql
select * from rconversation where username in ('wx__c_19d94cc78fb','wxgrp_0000000000000099')
```

| 列 | 微X `wx__c_19d94cc78fb`（美妆） | 我方 `wxgrp_0000000000000099`（123） |
|---|---|---|
| `flag` | 4611687813246225318 = `2^62 + 1794818837414` | 4611687813008995092 = `2^62 + 1794581607188` |
| `parentRef` | **空串** | **`message_fold`** |
| 其余 28 列 | `msgCount=0 / chatmode=1 / status=3 / isSend=1 / msgType=1 / content=空 / digest=空 / attrflag=0 / sightTime=0 …` | **逐列相同** |

- `parentRef=message_fold` 是**微信自己填的**（R54 §10.1 已记录：插入时是空串，9 分钟后变成 `message_fold`），
  含义是「这条会话被折叠置顶聊天收纳」。
- ⚠️ **不要**据此把 `parentRef` 当成「点击行为不同」的原因 —— 12.2 的反向对照已经把差异锁定在
  「有没有那层 hook」，与数据列无关。`parentRef` 的长期影响仍是**未验证项**（见 R54 §10 清单）。

### 12.4 工具坑（本轮实测，会重复踩）

1. `python tools/dev.py restart` 会打印「微信已重启（LogX 计数归零）」，但**可能根本没重启**：
   `am force-stop` 被吞，`ps -A -o PID,ETIME` 显示进程已活 7 分钟。
   ⇒ 冷启必须自己验：`am force-stop` → `pidof` 应为空 → `am start` → `pidof` 应是**新 pid**。
2. 本会话每次 `adb` 调用都会打 `daemon not running; starting now at tcp:5037`（服务在两次调用之间被杀），
   但命令**仍然生效** —— 不要把它当失败判据，也别为此加 `adb start-server`。

