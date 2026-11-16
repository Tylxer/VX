# R55b — ConvBoxServiceConversationUI 静态分析（lane: r55-lane-pro）

> 目标：`_cache/mm.apk`（微信 8.0.48 / versionCode 2588），15 个 dex。
> 工具：`tools/_r13_findclass.py` / `tools/_r12_dexclass.py` + 本轮新增 `_cache/_r55_disasm.py`
> （完整 dalvik 反汇编，已修正 35c/3rc method 位置、22c 寄存器序、Res_value 布局三处坑）、
> `_cache/_r55_arsc.py`（resources.arsc 字符串）、`_cache/_r55_axml.py`（AndroidManifest AXML）。
> 纪律：每条结论标 [已证实]/[推断]/[证据不足]。

---

## Q1 — Intent 契约

### 1.1 类结构

`com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI` 定义于 **classes4.dex**，
super = `BaseConversationUI`（继承自 `MMFragmentActivity`），仅有 3 个方法：`<init>`、`finish`、`onCreate`。

它是一个**纯容器 Activity**：真正的逻辑在内部类
`ConvBoxServiceConversationUI$ConvBoxServiceConversationFmUI`（super = `BaseConversationUI$BaseConversationFmUI`，24 个方法）。

### 1.2 onCreate 不读 Intent

[已证实] `onCreate(Landroid/os/Bundle;)V` @ classes4.dex code=0x564bd8，全量反汇编：

```
0000 invoke-super BaseConversationUI.onCreate(Bundle)
0003 cc.b(Context) -> LayoutInflater
0007 const v0, #2131493438              // R.layout (0x7f0c023e)
000b inflate(2131493438, null) -> contentView
000f iput contentView = 上一步结果
0011 setContentView(contentView)
0014 new ConvBoxServiceConversationFmUI
0016 <init>
0019 iput conversationFm = 该 Fragment
001b getSupportFragmentManager().beginTransaction()
0023 iget conversationFm
0029 i2.h(2131311580, conversationFm, null, 1)   // add(containerId, fragment, tag=null, 1)
002c i2.d()                                        // commit
0031 r34.m1.e(this, contentView)                   // 沉浸式/状态栏适配
0034 d94.n0.c(Nd1W.class) + md1.a.sa(...)          // 事件注册
```

**结论：onCreate 不调用任何 getIntent()/getXxxExtra()，不读任何 Intent extra。**

### 1.3 唯一 Intent extra：「Contact_User」

[已证实] `ConvBoxServiceConversationFmUI.onActivityCreated(Landroid/os/Bundle;)V` @ classes4.dex code=0x5648a4：

```
000b const-string "Contact_User"
000d getStringExtra("Contact_User") -> v4
0011 iput superUsername = v4
0013 TextUtils.isEmpty(v4)?
0017 if-eqz +6
0019 const-string "conversationboxservice"
001b iput superUsername = "conversationboxservice"   // 默认分支
001d initView()
0020 vj0.h8.b().v() -> k74.y0
002c y0.a(adapter)                                    // 注册 adapter 为数据监听
```

**Intent extra 契约（完整）**：

| key | 类型 | 必填 | 取不到/空时的分支 |
|---|---|---|---|
| `"Contact_User"` | `String`（`getStringExtra`） | **否** | `TextUtils.isEmpty` 为真 ⇒ `superUsername = "conversationboxservice"`（字符串常量原文） |

这是**唯一**被读取的 extra。字段 `superUsername` 后续通过 `getUserName()` 暴露
（`ConvBoxServiceConversationFmUI.getUserName()` @ code=0x564640 直接 `return superUsername`）。

### 1.4 关键子问题：extra 能不能喂会话列表？

[已证实] **不能。**

证据链：
1. `initView()` @ classes4.dex code=0x5646ac 中创建 Adapter：
   ```
   004f new-instance t0
   0055 iget superUsername               // 作为第 2 个实参
   005c t0.<init>(Context, superUsername, listener)
   ```
2. `t0.<init>(Landroid/content/Context;Ljava/lang/String;Lcom/tencent/mm/ui/v8;)V` @ classes4.dex code=0x584164：
   ```
   0000 invoke-direct {v0, v1, v3} d2.<init>(Context, v8)   // 只传 this/Context/listener
   000d iput I = new HashMap
   0014 iput J = new ArrayList
   ```
   **第二个形参（superUsername，寄存器 v2）在构造体内从未被读取**（invoke-direct 只传了 {v0,v1,v3}，跳过 v2）。
3. 真正查库的是 `t0.N()`（见 Q2），其中 parentRef 是**硬编码** `"conversationboxservice"`，与 superUsername 无关。

⇒ **extra 只决定 `superUsername` 字段的值，但该值不进入 SQL、不进入 Adapter 数据源。
页面展示的会话集合完全由「硬编码 parentRef='conversationboxservice'」决定。
无法仅靠 Intent extra 注入自定义会话列表。**

### 1.5 标题是固定资源

[已证实] `initView()` 首行：
```
0000 const v0, #2131759121          // = 0x7f101011
0003 Fragment.getString(2131759121)
0007 BaseConversationFmUI.setMMTitle(String)   -> BaseConversationUI.setTitle(String)
```

[已证实] 资源 2131759121 = **`"折叠的群聊"`**（_cache/_r55_arsc.py 解 resources.arsc，
type=0x10(string) entry=4113，string pool idx 25846）。

`setTitle(String)` @ classes12.dex code=0x2e0cc4 → 存 `title` 字段 + `updateTitle(String)`；
`updateTitle` @ code=0x2e0e94 → `vj0.c2.c(title)`（把字符串当 username 去查会话显示名，
`q4.n(s,true)` 查不到则原样返回）→ `mActionBarHelper.d(...)`。

⇒ **本 Activity 的 ActionBar 标题固定为「折叠的群聊」，不是动态的「名称 (N)」。**
真机观察到的 `美妆 (121)` 之类标题，[推断] 是微X（native 壳）在点击时 hook 了 `setTitle`/`updateTitle`
（微X 业务代码在 assets 密文里，本 lane 无法复核其 hook 实现，见任务纪律）。

### 1.6 AndroidManifest 声明

[已证实] 用 `_cache/_r55_axml.py` 解析 `AndroidManifest.xml`（UTF-16 string pool），
`ConvBoxServiceConversationUI` 的 `<activity>` 节点（@0x4d344）属性全集：

```
android:name = com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI
android:configChanges = 0x4A0 (=1184; 0x400 density|0x80 screenLayout|0x20 keyboardHidden)
android:enableOnBackInvokedCallback = 0x30 (attr 0x0101022b)
```

- **`android:exported`：未声明** ⇒ 因无 `intent-filter`，默认值 `false`（非导出，外部应用/其他 UID 不可显式启动）。
- **`android:launchMode`：未声明** ⇒ 默认 `standard`。
- **`android:taskAffinity`：未声明** ⇒ 默认继承 `<application>`，即包名 `com.tencent.mm`。
- **`intent-filter`：无**（activity 节点后直接是 `END_ELEMENT('activity')`，无子节点，已逐 chunk 确认）。

⇒ 对 Xposed 注入场景：模块代码运行在**微信进程内**，用 `Intent().setClassName("com.tencent.mm",
"com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI")` 显式启动**不受 exported 限制**（同进程/同 UID）。

[SEC1 DONE]

---

## Q2 — ListView 数据源

### 2.1 视图与 Adapter 的创建链

[已证实] 全在 `ConvBoxServiceConversationFmUI.initView()` @ classes4.dex code=0x5646ac：

```
000d findViewById(2131318725) -> appbrandMessageLV (ListView)
004f new t0
0055 iget superUsername
005c t0.<init>(thisActivity(), superUsername, i0(this))   // 字段类型声明为 d2
005f iput adapter = t0 实例
006a appbrandMessageLV.setAdapter(adapter)
0076 adapter.getCount() 拼进日志 "data count:"
008e setOnItemClickListener(j0)
0098 setOnTouchListener(k0)
00a2 setOnItemLongClickListener(q0)
```

类继承链（三个类都在 classes4.dex）：

```
t0  (super = d2)        methods: <init>, N, a0           // 具体 Adapter
d2  (super = w8)        methods: getView, onNotifyChange, ...  // 会话列表通用 Adapter
w8  (super = BaseAdapter) methods: getCount, W(Cursor), N, ...
```

### 2.2 数据源定位（关键 invoke 链）

[已证实] 数据加载入口是 **`onResume()`** @ classes4.dex code=0x564ac0：

```
000c adapter.o0()
0011 adapter.N()      // ★ 刷新数据
0028 i1.N("@placeholder_foldgroup", 1)   // 折叠分组占位处理
0035 setUnread(vj0.h2.c(1), true)
```

`adapter.N()` 是虚调用，静态类型 `w8.N()`，实际对象 `t0` 覆盖了 `N`：

[已证实] `t0.N()V` @ classes4.dex code=0x5841a4：

```
0000 vj0.h8.b() -> vj0.e
0004 e.v() -> (s4) 会话存储
000a const/4 v1, #-1          // limit = -1
000b const/4 v2, #5           // type  = 5
000c const/4 v3, #0           // excluded List = null
000d const-string v4, "conversationboxservice"   // ★ parentRef 硬编码
000f s4.t(5, null, "conversationboxservice", -1) -> Cursor
0013 w8.W(cursor)             // 设置 Cursor
001a listener.a(null)
001d notifyDataSetChanged()
```

### 2.3 SQL 构造（s4.t）

[已证实] `s4.t(ILjava/util/List;Ljava/lang/String;I)Landroid/database/Cursor;` @ classes4.dex code=0x4a3e5c，
拼出的 SQL（逐段还原）：

```sql
select * from rconversation where
  parentRef = 'conversationboxservice'      -- parentRef 非空且 != "*" 时
  <type=5 的过滤片段>                        -- vj0.e2.a(false, 5)
  order by flag desc, conversationTime desc
```

- 参数语义：`(type, excludedList, parentRef, limit)`。
- `parentRef == null` → 走 `P()`（默认过滤）；`parentRef == "*"` → `" 1 = 1 "`；否则 `parentRef = '<转义值>'`。
- `type` → `vj0.e2.a(Z,I)`（@ code=0x88d41c，packed-switch）返回静态字段里的 SQL 片段；
  `type=5` 命中 case 5，返回 `vj0.e2.g`（boolean=false 分支），
  `g` 由 `<clinit>` 用 `k("rconversation.username", ["@qqim"], ...)` 生成（@qqim 会话过滤）。
- 排除列表非空时追加 `and rconversation.username != '<u>'`（此处传 null，不生效）。

### 2.4 标题「名称 (N)」的 N 从哪来

[已证实] `w8.getCount()I` @ classes4.dex code=0x598c7c：

```
0000 iget i（缓存计数）
0002 if-gez 跳到末尾（缓存失效则重算）
0004 q() -> Cursor
0008 Cursor.getCount() -> i       // ★ N = Cursor.getCount()
000e i + A()
```

`w8.W(Cursor)` @ code=0x598e78：`iput f = cursor`，并把 `i = -1`（使 getCount 重算）。

⇒ **N = `Cursor.getCount()` = 上述 SQL 的查询行数 = `parentRef='conversationboxservice'` 的会话条目数**。
这与真机「N = 组内会话条目数（不是未读数）」的观测一致——但这里的「组」是**微信原生的「折叠的群聊」集合**，
不是微X 的自定义分组。

### 2.5 「名称」与「N」的完整结论

[已证实] 微信原生页面里：
- **「名称」** = 固定资源 `2131759121` = `"折叠的群聊"`（Q1.5）。
- **「N」** = `w8.getCount()` = `Cursor.getCount()`（Q2.4），由 `s4.t(5, null, "conversationboxservice", -1)` 的查询结果决定。

[推断] 真机看到的 `美妆 (121)` / `改性 (212)` / `pet片材 (9)`：
微X 分组的名称与数量**不是这个 Activity 原生产生的**。原生只会显示「折叠的群聊」+ 该集合的条目数。
微X 要得到「分组名 (组内会话数)」只有两条路：
(a) hook `setTitle`/`updateTitle` 覆盖标题；(b) hook `t0.N()` / `w8.W()` 换掉 Cursor 数据源。
具体哪条，微X 的 native 壳内业务代码不可读，**本轮静态无法裁定，需真机反证**（见 Q4 验证清单）。

[SEC2 DONE]

---

## Q3 — 它与「折叠置顶聊天」的关系

### 3.1 结论先行

[已证实] **`ConvBoxServiceConversationUI` 是「折叠的群聊」页，不是「折叠置顶聊天」页。点「折叠置顶聊天」不会进这个 Activity。**

微信有两个**不同**的折叠功能，用两个**不同**的 `parentRef` 值：

| 功能 | 标题资源 | parentRef key | 承载页 |
|---|---|---|---|
| 折叠的群聊 | `折叠的群聊` (2131759121) | **`conversationboxservice`** | `ConvBoxServiceConversationUI` |
| 折叠置顶聊天 | `折叠置顶聊天` (string pool 25847) | **`message_fold`** | 会话列表内联展开（非独立 Activity） |

### 3.2 证据

[已证实] `"conversationboxservice"` 是「折叠的群聊」的 parentRef：
- `t0.N()` 硬编码 `s4.t(5, null, "conversationboxservice", -1)`（Q2.2），SQL 展开为 `parentRef = 'conversationboxservice'`。
- `onActivityCreated` 的默认 superUsername 也是 `"conversationboxservice"`（Q1.3）。
- 页面标题资源 = `"折叠的群聊"`（Q1.5）。
- `vj0.e2.<clinit>` 把 `"conversationboxservice"` 收进系统会话 username 数组 `vj0.e2.p`（与 fmessage/tmessage/officialaccounts 等同列，作为「系统会话」排除项）。

[已证实] `"message_fold"` 是「折叠置顶聊天」的 parentRef：
- classes4.dex 字符串表 idx 2450 存在 SQL 片段原文：
  `"((parentRef is null) or (parentRef in ('', 'message_fold')))"` —— 即「折叠置顶聊天」= 会话的 parentRef 落在 `('', 'message_fold')` 的集合。
- 同 dex 另有 `'insert or update message_fold error!, stack: %s'`（写库逻辑）与 `'message_fold_config2'`。
- arsc string pool：`25847 = '折叠置顶聊天'`、`25846 = '折叠的群聊'`、`25848 = '折叠群聊的消息不再提醒…'`、`25849 = '折叠该群聊'` —— 两个功能词条并存且紧邻，是不同功能。

[已证实] 两个 key 指向不同的查询分支：
- `s4.t()` 的 `parentRef` 参数就是直接拼进 `parentRef = '<值>'` 的字符串。
- `ConvBoxServiceConversationUI` 的 Adapter 只用 `"conversationboxservice"` 一种值，代码里**没有**任何读 `"message_fold"` 的分支。

### 3.3 「它是按某个 key 取一组会话的通用页吗？」

[推断] **是通用页，但 key 被写死，不是可配置的。**

- `s4.t(int type, List excluded, String parentRef, int limit)` 本身是通用的「按 parentRef 取会话」查询器（Q2.3），
  parentRef 是**可变参数**。
- 但 `ConvBoxServiceConversationUI` 这一层把 parentRef **硬编码**成 `"conversationboxservice"`，
  没有把它接到 `superUsername`（extra "Contact_User"）上 —— 这是「通用查询器 + 写死的调用点」的组合。

[推断] 因此「折叠置顶聊天」（`message_fold`）**没有复用这个 Activity**：
它要么在会话列表内联展开（parentRef 过滤在 `ConversationAdapter` 里做），
要么有另一个 UI；反正在 `ConvBoxServiceConversationUI` 的代码路径里找不到 `message_fold`。

[已证实] 佐证：`onResume()` 里调用了 `com.tencent.mm.modelsimple.i1.N("@placeholder_foldgroup", 1)`——
`"@placeholder_foldgroup"`（classes4.dex 字符串 idx 5711）是「折叠分组」的占位符处理，
说明本页与「折叠分组」机制绑定，而与「置顶折叠（message_fold）」无关。

### 3.4 对需求的直接回答

Q3 的「key」问题答案是：**这个页面的 key = `parentRef` 值，且已被写死为 `"conversationboxservice"`**。
它不是一个「传入任意 key 就查任意组」的通用页；`extra "Contact_User"` 名义上是父级 username，
但**没有接进查询**（Q1.4），所以也无法通过 extra 换 key。

[SEC3 DONE]

---

## Q4 — 三种落地方式的可行性裁定

### 4.1 裁定总表

| 方式 | 裁定 | 一句话理由 |
|---|---|---|
| 方式1：startActivity + extra 喂会话列表 | **不可行** | extra 里没有会话列表入口（Q1.4），唯一 extra `Contact_User` 不参与查询 |
| 方式2：startActivity + 传 wxgrp_hex 让它自己查 | **不可行** | 页面把 parentRef 硬编码为 `conversationboxservice`，不按传入值查库（Q2.2/Q3.3） |
| 方式3：写 parentRef 归属 + 放行原生点击 | **需真机验证，且「放行原生」半条死路** | 写 parentRef 是原生机制可行；但①放行原生点击进的是 ChattingUI（任务已证实）②即使进本页也只查 `conversationboxservice` 而非 `wxgrp_hex`，必须再叠加 hook |

### 4.2 逐条论证

#### 方式1 —— 不可行

[已证实] 依据 Q1.3/Q1.4：
- 该 Activity 全链路只读一个 extra：`"Contact_User"`（String）。
- 它只决定 `superUsername` 字段值；`superUsername` 传入 `t0.<init>` 但构造体内**从不读取**（invoke-direct 跳过该形参）。
- 真正查库的 `t0.N()` 硬编码 `"conversationboxservice"`。
- ⇒ 不存在「用 extra 塞 `List<username>` 或 `List<r4>`」的任何入口，塞了也没代码读。

#### 方式2 —— 不可行

[已证实] 依据 Q2.2/Q3.3：
- `t0.N()` → `s4.t(5, null, "conversationboxservice", -1)`，parentRef 是**编译期常量**。
- 没有「从 Intent/字段读一个 key 去查库」的分支；`superUsername`（可承载 wxgrp_hex）与查询路径**物理断开**。
- ⇒ 只传 `wxgrp_<hex>` 进去，页面仍会查 `conversationboxservice` 并显示「折叠的群聊」。

#### 方式3 —— 半条死路 + 侵入性说明

「写 parentRef 归属」本身：
- [已证实] `s4.t()` 的查询就是 `parentRef = '<值>'`，所以给组内会话写 `parentRef = wxgrp_<hex>` 后，
  用**自定义 parentRef 查库**能取回组内会话（这是微信原生的数据模型，`conversationboxservice`/`message_fold` 都是这么用的）。
- 但「放行原生点击」这半条是死路：
  - 任务背景已真机证实：我方裸 `wxgrp_` 行放行给微信原生 → 打开 **ChattingUI**，不是本 Activity。
  - 即使强行走本 Activity，它也只查 `conversationboxservice`，不会自动查 `wxgrp_<hex>`。

**侵入性与回滚难度（写宿主业务列 parentRef）**：
- `parentRef` 是 `rconversation` 的宿主业务列，已被微信两处折叠功能占用（`''`/`message_fold`、`conversationboxservice`）。
- 我方写 `parentRef = wxgrp_<hex>` 属于**第三类归属**，风险：
  1. 与微信原生折叠判定冲突（`s4.t` 的 `parentRef in ('','message_fold')` 片段、以及可能的「非空 parentRef 即折叠」判定）。
  2. 微信升级后 parentRef 语义可能变化，需要迁移。
  3. 回滚 = `UPDATE rconversation SET parentRef=原值 WHERE parentRef='wxgrp_...'`，需保存原值（多数是 `''` 或 null），可逆但**必须逐条留档**。
- 结论：写 parentRef **可接受但需留档回滚**，且它只是数据准备，不是交互入口。

### 4.3 唯一推荐方式

**推荐 = 方式2 的启动 + 方式3 的数据准备 + 三处最小 hook。**

不依赖任何「新 Activity 注册」，全程用微信已注册类 + 反射/hook 修正其行为。

#### 我方要改的代码清单（只列清单，不执行）

前置数据准备（一次性/分组变更时）：
1. `GroupConvPlan`（我方现有）扩展：给组内每个真实会话写 `parentRef = wxgrp_<hex>`。
   - 涉及：`rconversation` 表 UPDATE；写前先读原 `parentRef` 留档（回滚用）。

点击交互（我方点 wxgrp_ 行）：
2. 点击处理（现有分组行点击回调）：改为
   `startActivity(new Intent().setClassName("com.tencent.mm",
   "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI")
   .putExtra("Contact_User", "wxgrp_<hex>"))`。
   - 注意：无 `FLAG_ACTIVITY_NEW_TASK`（同 task 内 push）；`exported=false` 不影响同进程显式启动。

三处最小 hook（LSPosed）：
3. **Hook 数据源 parentRef**：hook `com.tencent.mm.storage.s4.t(int, List, String, int)`
   （或更稳：hook `com.tencent.mm.ui.conversation.t0.N()`，直接拦 `s4.t` 的第 3 参）。
   当宿主前台 Activity 是 `ConvBoxServiceConversationUI` 且其 Intent extra `Contact_User` 形如 `wxgrp_` 时，
   把第 3 参 `"conversationboxservice"` 替换成 `"wxgrp_<hex>"`（hex 从 extra 取）。
   - 这是让页面「查我们自己的组」的关键一步，因为原生写死了 key。
4. **Hook 标题**：hook `BaseConversationUI.setTitle(String)` / `setCustomTitle`（或 `updateTitle`），
   当标题 == `"折叠的群聊"` 时替换成 `"<分组名> (<N>)"`；N 取 `adapter.getCount()`（或 hook 前先反射读）。
   - 因为原生标题是固定资源（Q1.5），不 hook 就是「折叠的群聊 (N)」。
5. **（可选）Hook 空态文案**：`emptyTipTv` 文本固定 `"暂无群聊"`（资源 2131759120），如需自定义可一并换。

#### 为什么不是「只 hook 一处」就能成

[已证实] 页面三处「写死」彼此独立，缺一不可：
- 数据 key 写死（Q2.2）→ 必须 hook 数据源。
- 标题写死（Q1.5）→ 必须 hook 标题。
- 空态写死（资源 2131759120 `暂无群聊`）→ 可选 hook。

#### 次优备选（若不允许 hook s4.t）

[推断] 反射直接造数据：在 Activity 创建后反射拿到 `ConvBoxServiceConversationFmUI.adapter`（字段），
反射调 `s4.t(5, null, "wxgrp_<hex>", -1)` 得到 Cursor，再反射调 `w8.W(cursor)` + `notifyDataSetChanged()`。
但页面 `onResume()` 会再次调 `t0.N()` 覆盖（硬编码 conversationboxservice），
所以仍需 hook `t0.N()` 阻止其覆盖——本质上还是回到「hook 数据源」，反射法更绕。

### 4.4 静态判不了、必须真机验证的清单

1. **微X 的真实实现是 hook 标题还是 hook 数据源？**
   - 判据怎么读：真机上，把微X 的分组行标题改成与「分组名」明显不同的字符串（或清空分组内某会话的 parentRef），
     观察进入 `ConvBoxServiceConversationUI` 后：
     - 若标题仍显示微X 自定义「美妆 (N)」→ 微X hook 了标题；
     - 若列表条目数随 parentRef 清空而减少 → 微X hook 了数据源。
2. **hook `s4.t` 第 3 参是否影响微信原生「折叠的群聊」页**（副作用面）。
   - 判据：真机开「折叠的群聊」（原生入口），看列表是否被我们替换的 parentRef 污染；应只在
     `Intent extra Contact_User` 为 `wxgrp_` 时替换，否则透传。
3. **`superUsername`（extra "Contact_User"）是否真的在其它时机被消费**（本 lane 已证实不进入 SQL，
   但 `getUserName()` 的调用方未穷举——框架层可能用它刷新标题/未读数）。
   - 判据：真机在 `onActivityCreated` 打点，或 hook `getUserName()` 返回值，观察是否有调用且影响 UI。
4. **`s4.t` 的 type=5 过滤（`vj0.e2.g`，@qqim 片段）会不会把我方 wxgrp_ 组内会话滤掉**。
   - 判据：真机用 `s4.t(5, null, "wxgrp_<hex>", -1)` 直接查，确认返回行数 == 组内会话数；
     若被 type=5 过滤削减，需改用 `s4.t` 的 `parentRef="*"` 分支或另写 SQL。
5. **写 parentRef 后，微信原生「会话列表」是否会把我方组内会话误判为折叠而隐藏**。
   - 判据：真机回会话列表首页，看组内会话是否还在原位置正常显示；若消失/折叠，说明 parentRef 非空被当作折叠，
     则需改为「不动 parentRef，改用额外映射表（如我方自己的 gid↔wxid 表）驱动数据源 hook」。

[SEC4 DONE]

[DONE]
