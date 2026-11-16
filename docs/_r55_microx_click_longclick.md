# R55 · 微X 分组条「点击 / 长按」交互机制与我方改造方案（独立分析 lane）

> 只读分析，不改 `app/src/main`、不跑 adb、不碰设备。
> 证据分档：**[已证实]** 有真机/源码/dex 证据 ｜ **[推断]** 由已知事实外推 ｜ **[证据不足，需真机取证]** 静态判不了。
> 判据一律对着「用户能看见的事实」写；互斥判据不并列。

## ① 结论摘要

- **微X 那边是什么**：点击 = 一个**独立窗口的会话列表页**（微信原生 ActionBar + 原生
  `pmu` ListView + 标准会话行），标题 `组名 (N)`，`N` = **组内会话条目数**（Q1 [已证实]）；
  长按 = 一条 8 项菜单（4 项宿主文案 + 2 项微X + 我方 2 项）。
- **这两个新项是谁加的**：`解散聊天分组 / 归拢设置` **不在微信 8.0.48 的资源表里**
  （连「归拢」二字都是 0 命中，同表里「不显示该聊天/删除该聊天/取消置顶/折叠置顶聊天」
  全部命中 ⇒ 扫描有效）⇒ [推断·高] 是微X 加的，不是宿主原生文件夹菜单自带（Q3）。
  ⚠️ 「我方两项出现在菜单里」**不能**反证菜单是宿主原生的 —— 我方注入只看文案与 4 秒窗口，
  不看 PopupWindow 是谁弹的（Q3 更正了 R53 §11.3 的推理）。
- **我方该走哪条**：推荐**路线 A：点分组行 = `setGroup(gid)` 原地筛选**（零新增 hook，
  复用 R54 已验收的包装链；返回键退筛选已现成）。它把「点进去只剩这组会话」这条骨架对齐了，
  但**没有**标题栏 / `(N)` 计数 / 右上菜单，长按仍是模块对话框（Q4 差距表）。
- **路线 A 的唯一真风险**：`ScrollDock.kt:110` 的 `guardPin` 在 R54 期间**是休眠的**
  （点击走 `GroupDetailPage`，`currentGroupId()` 恒 0），路线 A 一上线就把它唤醒 ⇒
  R53 §10.3 实证的「下拉不跟手」会复现。解法是把判据从「是不是筛选态」收窄成
  「**内容是否不足一屏**」并给 legacy 留原分支（Q4/Q6 有可执行代码）。
- **4 条红线**：路线 A 的 1/2/3/5 项不破；第 4 项（菜单文案）会破红线 ①
  ⇒ 必须写成条件文案（`gconvOn()` 二选一）或延后（Q6）。
- **还需真机才能定的**：①`_cache/mm.apk` 是否等于设备上的包（T0）；②菜单是宿主构建还是
  微X 自绘（T1）；③那两项点下去到底干什么（T2）；④宿主到底有没有「命名文件夹」页（T3/T4）；
  ⑤收窄后 `ScrollDock pin` 计数（T5）。全部给了可直接复制的命令（⑤）。

## ② Q1~Q6 逐条结论 + 证据

> 本节按 Q1~Q6 拆成 6 个独立小节，**每节写完立即落盘**并在节末标 `[SECn DONE]`。
> 判据是「节内容 + `[SECn DONE]`」，不是 `[DONE]` 字样。

### Q1 微X 分组会话列表页：微信原生 UI 还是微X 动态构造的筛选列表？
### 现象（先把「用户能看见的事实」钉死）

`_cache/u_r55_mx_click.xml`、`_cache/u_r55_mx_long2.xml`、`_cache/u_r55_mx_long.xml` 三份 `uiautomator dump`
本轮全部重新解析过（节点级 class / resource-id / text / bounds），**三份都是同一个「分组会话列表页」的结构**，
不是主列表、也不是长按菜单（这点见 Q3 的判据纪律）。逐节点核实到的 [已证实] 事实：

| 事实 | 证据（`_cache/u_r55_mx_click.xml` / `long2.xml`） |
|---|---|
| 是**独立窗口**，不是 LauncherUI 上盖的浮层 | 根节点 `FrameLayout ii7 [0,0][1440,3168]`，内含 `statusBarBackground [0,0][1440,160]` 与 `navigationBarBackground [0,3104][1440,3168]`；内容根节点是 `android:id/content`（Activity 的 content view） |
| 标题栏是**微信原生 ActionBar**，全套都是微信自有 resource-id | `FrameLayout es [0,160][1440,333]` → `ViewGroup ep` → `RelativeLayout cxa` → 左 `LinearLayout actionbar_up_indicator`（`ImageView a7k` 带 `content-desc="返回"` + `TextView q4t txt='133'`）／中 `h3 → pkq → pkp → TextView pkn`／右 `cxx → cxw → TextView g6 txt='菜单'`。`pkn`、`g6`、`q4t` 都是 `TextView` |
| 标题格式稳定复现，`(N)` = **组内会话条目数**，不是未读总数 | `pkn txt='改性 (212)'`（click/long）、`pkn txt='pet片材 (9)'`（long2）。long2 页面恰好 **9 个 `cqz` 行**且最后一行 `[0,2405][1440,2664]` 之后 ListView 到底再无行 ⇒ `(9)` = 组内会话数；首行还有微信原生未读红点 `TextView piu txt='1'`，可见 9 行里未读合计远不到 9 ⇒ 排除「未读总数」 |
| 内容区是**微信原生会话列表容器链** | `ii7 → d0d → content(android:id/content) → kf8 → p0q → keo → ListView pmu [0,333][1440,3104]`（与 prompt 事实 3 一致，本轮补出中间的 `d0d`） |
| 行是**微信主会话列表同一套布局 / 同一套渲染** | 每行 `LinearLayout cqz → RelativeLayout aca → ImageView ac8 → View l7a(标题) → View q5l(时间) → View igs(摘要)`，与 R53 §2.3 主列表行 id 序列逐项吻合；标题是宿主自绘 `NoMeasuredTextView`（`docs/conclusions/conv_list.md` 事实 8、`feature/ConvMenuInject.kt:772`），`uiautomator` 靠 accessibility text 才读得到 |
| 行数据是**真实会话**，不是合成行 | 标题全是真实联系人（奥能采购徐小姐 / 异彩达技术何振兵 / 摩康复合材料赵晓磊Kris / 张丽萍-重庆LGchem…），带真实摘要、真实时间（昨天 / 周二 / 11月5日）、原生未读 `piu` |

[已证实] 排除项：
- 「模块自绘一套 UI」排除：标题栏与行布局全是微信自有 id；我方 `GroupDetailPage` 是自绘 `Dialog`，
  `topResumedActivity` 仍是 `LauncherUI`（prompt 事实 5、`feature/GroupDetailPage.kt:133`），形态完全不同。
- 分组行本身是微信原生 View 类 `ConversationFolderItemView`（R54 真机 `op=xh sub=row`：`pos=14 vt=0`，
  `docs/派发审计台账.md:4155`），与微X `wx__c_` 行同 View 类 ⇒ 「微X 自绘行」的假设 R53 §2.3 已排除。

### 两个候选（页面框架 [已证实]，实现方式 [推断]）

**候选 A [推断 · 实现方式]**：这是**微信原生的「会话文件夹 / 标签分组」页面**（微信自有的 Activity +
ActionBar + `pmu` ListView + 标准会话行）。微X 把 `wx__c_` 伪会话行**注册成微信能识别的文件夹实体**，
点击由微信原生代码打开该页，标题与组内会话由微信原生文件夹数据提供；微X 只负责「写数据」。
- 支撑 [已证实]：上述全套微信自有 resource-id、`android:id/content`、独立窗口、`(N)`=组内会话数、
  行含原生未读 `piu`；R53 §8 已定案微X 走**数据层伪会话**，不是 adapter 包装 / header 注入。
- 反证压力：`wx__c_` 是微X 自造的 username（R53 §8.1），微信原生功能里只见过「折叠的置顶聊天」
  这类**固定**文件夹（R54 §10.1 意外发现 `parentRef=message_fold`），没见过可命名、可 `改性 (212)` 的文件夹。

**候选 B [推断]**：这是**微X hook 后动态构造的筛选列表**——页面框架可能仍是微信某个通用
Activity/Fragment（所以 id 看起来"原生"），但 `pmu` 的数据源、`pkn` 的 `组名 (N)` 标题、右上 `g6` 菜单
是微X 在运行时 hook `getCount/getItem` / `setTitle` 注入的「按标签筛选后的会话集合」，
**微信原生并没有这个命名文件夹实体**。
- 支撑 [已证实]：全库 `parentRef` 分布只有 `officialaccounts(51)/conversationboxservice(23)/
  service_officialaccounts(11)/gh_*(8,1)/appbrandcustomerservicemsg(6)/opencustomerservicemsg(5)`
  及空值，**没有任何指向 `wx__c_` 或 `wxgrp_`**（prompt 事实 2）⇒ 微信主库里不存在「文件夹→成员」归属列。
- 支撑 [推断]：`改性 (212)` / `pet片材 (9)` 这种「组名 + 括号内数字」标题，若不是原生文件夹数据
  提供的，就只能是微X 按自己的标签配置动态拼出来的；`input keyevent 4` 退不出该页（prompt 事实 3）
  也更像模块拦截了返回键而非标准 Fragment 出栈。

A 与 B 的**真正分界**：微信 8.0.48 里是否存在「可由模块写入的、可命名的会话文件夹」这一原生实体。

### 能区分 A/B 的唯一真机判据（互斥，不并列）

> 唯一判据：**禁用微X 前后，用微信原生入口打开的「会话文件夹 / 折叠置顶聊天」子页，
> 与 `_cache/u_r55_mx_click.xml` 的 resource-id 序列逐项比对**。

```bash
A="/c/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1

# ① 打开微X 分组页（真手点，adb 长按会被识别成点击），dump 并抽 id 序列
"$A" shell uiautomator dump /sdcard/r55_folder.xml >/dev/null
"$A" shell "cat /sdcard/r55_folder.xml | tr '<' '\n' | grep -iE 'resource-id=\"[^\"]*(ii7|content|kf8|p0q|keo|pmu|es|ep|cxa|actionbar_up_indicator|a7k|q4t|h3|pkq|pkp|pkn|cxx|cxw|g6)\"'"

# ② 反向对照：LSPosed 禁用微X → 强杀冷启两遍 → 在微信原生功能里找
#    「会话文件夹 / 折叠置顶聊天」入口（长按置顶会话），打开后同样 dump 抽 id
"$A" shell am force-stop com.tencent.mm
"$A" shell am start -n com.tencent.mm/.ui.LauncherUI
"$A" shell uiautomator dump /sdcard/r55_native.xml >/dev/null
python tools/_r53_xh.py row          # 现成探针：确认分组行已消失（R53 T6 已定案「行是微X造的」）
```

- **② 能打开原生命名文件夹页，且 `ii7/content/kf8/p0q/keo/pmu` + `es/ep/cxa/.../pkn/g6` 逐项一致、
  标题同样带 `(N)`** ⇒ **A**（同一微信原生页，微X 只接数据）。
- **② 找不到这样的原生入口，或 id 序列不一致** ⇒ **B**（该页语义由微X 构造）。

辅助佐证（**不并列成独立判据**，只用来给上面的结论加固）：
```bash
"$A" shell dumpsys activity activities | grep -i resumed     # 取分组页 Activity 类名 C
"$A" shell dumpsys package com.tencent.mm | grep -i "<C>"     # C 是否在微信 Manifest 注册、是否含 folder/fold/group 语义
```
若 C 是微信已注册的类且 intent extras 里带的是文件夹 id ⇒ 佐证 A；若 C 是通用 `LauncherUI` /
`MMFragmentActivity` 且无对应 extras ⇒ 佐证 B。**单看类名不定案**（微X 也可能复用微信通用 Activity 壳），
定案只靠上面的 id 序列互斥判据。

⚠️ 取证纪律：该页 `input keyevent 4` 退不出（prompt 事实 3），取证完**强杀冷启回主列表**，
别用返回键；`_cache/u_r55_mx_long.xml` 与 click.xml 是同一个 `改性 (212)` 页，就是卡在该页里的证据。

[SEC1 DONE]

### Q2 `wx__c_` 行点击转成该页的候选 hook 点（groupId 反解 / 组内成员来源）
### 先排除两条走不通的路（[已证实]）

- **ListView 级点击**：`performItemClick` / `onItemClick` 真机零命中，行点击走**每行自己的
  `OnClickListener`**（`docs/conclusions/conv_list.md:9-13`；R54 §4.1；`feature/ConvFilter.kt:474-476`
  与 `:942-945` 的 KDoc，那条三层 `hookItemClick` 已于 2026-10-25 作死代码删除）。
  ⇒ 微X 的点击 hook 一定落在**行 View 级或数据层**，不会是 `onItemClick`。
- **枚举微X 已挂 hook**：`op=xh sub=hook` 读不到任何已挂 hook（LSPosed 按模块隔离 classloader，
  R53 已定案「以 `sub=row` 为准」，prompt 事实 7、`probe/HookListProbe.kt:151-154` 的 verdict 分支）。
  ⇒ 下面候选只能靠**源码/真机行为**推定，不能靠读微X 的 hook 表证实。这一条决定了 Q2 整体置信度偏低。

### 候选 hook 点（≥2，类名 + 方法签名 + 证据来源）

**候选 ① [已证实宿主类 · 推断微X 挂点]**
```
com.tencent.mm.ui.conversation.p3.getView(int position, View convertView, ViewGroup parent)   // after
```
在 after 里 `callMethod(param.thisObject,"getItem",position)` 拿 `com.tencent.mm.storage.r4`
→ 读 `field_username` → 命中 `wx__c_` 前缀时覆盖行 View 的 `OnClickListener`（View 级覆盖，不碰 ListView）。
- 证据来源 [已证实]：`docs/conclusions/conv_list.md:2`（`mAdapter=p3`、`getItem()=r4(field_username)`）；
  R54 §4.2 标的就是这个点；**我方 `feature/ConvFilter.kt:1804-1846` 用「保留包装链 + `wrapped.getView()` 之后覆盖」，
  零新增 hook 已真机走通同一效果**（R54 §10.2/§10.3 定案），说明该落点对 8.0.48/2588 确实有效。
- 证据来源 [推断]：微X 不包装 adapter（R53 §8 定案走数据层），所以它要覆盖行 listener 就得自己 hook `p3.getView`；
  或在数据层让微信原生绑定文件夹点击（见候选 ③）。

**候选 ② [推断]**
```
android.view.View.setOnClickListener(View.OnClickListener l)   // before
```
全局替换：当 `this` 是 `ConversationFolderItemView` 且其绑定行的 username 以 `wx__c_` 开头时，
把 `param.args[0]` 换成微X 自己的 listener（需防递归）。
- 证据来源：R54 §4.2 的「失效场景退路」明确写了这条 —— 若微信在 `getView` **返回之后**异步绑 listener，
  after 覆盖会被冲掉，只能 hook `setOnClickListener` 的 before。属合理外推，**无 dex 证据**。

**候选 ③ [推断 · 数据层，非点击 hook]**：微X **根本不 hook 点击**，而是把 `wx__c_` 行注册成微信
原生「会话文件夹实体」，让微信原生 `ConversationFolderItemView` 自带的点击回调打开文件夹页
（对应 Q1 候选 A）。此时「hook 点」其实是**文件夹数据查询/注册方法**（类名/签名静态判不了，
需真机反查，见 Q2 取证）。
- 证据来源 [已证实]：分组行 View 类就是微信原生 `ConversationFolderItemView`（R54 台账 `:4155`，
  与我方 `wxgrp_` 行同 View 类）；全库 `parentRef` 无任何指向 `wx__c_`（prompt 事实 2）
  ⇒ 归属**不在 `parentRef` 列**，一定另有宿主数据源或 hook 注入点。
- 证据来源 [推断]：Q1 已证页面框架是微信原生 Activity + ActionBar + `pmu` ListView，若 A 成立，
  点击链路本就该由微信原生完成，微X 无需自己拦点击。

> 三个候选不是并列的「三选一判据」，而是同一问题的三种实现可能；**互斥定案靠 Q2 取证里的
> `clickstack`（打调用栈拿真实类名）**，不是靠这里挑一个。

### hook 之后 groupId 从哪反解

- 微X：`wx__c_<hex>` → 标签 / 文件夹 id。R53 §8.1 已列出 12 条 `wx__c_<hex>` 与界面 12 个组名
  （改性 / pet片材 / 美妆 / …）**一一对应、秒级吻合 `conversationTime`** ⇒ hex 是组标识的可逆编码，
  hook 拿到 `r4.field_username` 即可反解，不必查表。
- 我方对应实现 [已证实]：`data/GroupConvPlan.kt:23-24 usernameOf()`（`wxgrp_` + `toULong` 定长 16 位小写 hex）
  / `:30-35 groupIdOf()`（`toULongOrNull(16)` 反解，非法值返回 null）。
  ⛔ 必须用**窄口径** `groupIdOf != null`（等价 `isOurs`，`:42`）而不是只看 `wxgrp_` 前缀 ——
  宽口径会命中 `wxgrp_t1/t2` 探针残留行，它们反解不出 gid，硬接管会拿 null 去开 `GroupDetailPage`
  （`feature/ConvFilter.kt:1814-1818` 的注释、R54 §10.3）。反解失败一律**保守放行**，绝不按 position 猜组
  （R54 §4.5）。

### 组内成员（分组页里那些真实会话）从哪取

- [已证实] 分组页行是**真实会话**（真实联系人名 / 摘要 / 时间 / 原生未读 `piu`，见 Q1 表）
  ⇒ 「组内成员」= 被打上该标签的真实 `rconversation` wxid 集合，不是合成行。
- [已证实] 这个归属**不在微信主库的 `parentRef` 列**（prompt 事实 2 的全库分布；R54 §10.1
  另发现我方行会被微信自己填 `parentRef=message_fold`，那是「折叠置顶」标记，与分组归属无关）。
- [推断] 微X 最可能落在**自有配置**：`de/robv/android/xposed/XSharedPreferences`
  （R53 §2.6：`classes.dex` 里出现 `Lde/robv/android/xposed/XSharedPreferences;`，包名
  `com.fkzhang.wechatxposed`）、`FKZ_WX_ChatroomTag`（16KB，R53 §8.4，打不开）、或加密
  `FKZ_WX_DATA`（R53 §2.1，0 张表）。**具体表名 / 字段名 / 是数据层还是纯内存 Map，静态判不了
  ⇒ 证据不足，需真机取证**（Q5 的 T2）。
- [已证实] 我方对应数据源（模块自有 `group_member` 表，Q4 三条路线都从这里取）：
  `data/GroupDb.kt:421 listGroups()` / `:679 memberWxids(gid)` / `:705 membersOf(gid)` /
  `:754 countMembers(gid)` / `:738 groupsOf(wxid)`；`Group` 是 `id/name/color/sortOrder/createdAt/pinned`
  的 data class（`:1006-1014`），**不含成员字段** ⇒ 成员增删不改 `groups.hashCode()`
  （`docs/conclusions/conv_list.md:59`、R54 §6.3）。

### Q2 取证（静态判不了的部分，命令写具体）

```bash
A="/c/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
P="am broadcast -a com.lx.wxgroup.action.PROBE"

python tools/_r53_xh.py prefs     # 现成：XSharedPreferences getAll()，看有没有「标签→会话」配置
python tools/_r53_xh.py row       # 现成：行 View 类 / vt / tag / item / user
python tools/_r53_xh.py hook      # 现成：读不到也留一行 verdict（classloader 隔离）

# 只读 SQL：确认全库 parentRef 无指向 wx__c_ / wxgrp_（不用裸 > / &，用 instr 真值判断）
"$A" shell "$P --es op gconv --es sub sql \
  --es sql 'select username,parentRef from rconversation where instr(username,char(119,120,95,95,99,95))' -p com.tencent.mm"
```
**需新增只读探针**（`op=r55`，不改产品代码）：
- `sub=tables`：遍历微信所有库 / 表，打印任何含 `wx__c_` 的行 ⇒ 定位「标签→会话」映射落点
  （不是 `parentRef`）；零命中 ⇒ 走 hook / 内存 Map。
- `sub=clickstack`：hook 分组行点击入口打调用栈，**剔掉 LSPosed 桥接帧、从第 4 帧起读真实调用点**
  （`docs/conclusions/conv_list.md:77` 的纪律），拿到确切类名 / 签名后回来给候选 ①②③ 定案。
- 判据互斥：`tables` 命中某张宿主表 ⇒ 候选 ③（数据层归属）；零命中且 `clickstack` 命中
  `p3.getView` / `setOnClickListener` ⇒ 候选 ①/②。**两者不并列**。

[SEC2 DONE]

### Q3 长按菜单「解散聊天分组 / 归拢设置」的来源与注入点

### 先给结论（分档）

- **[已证实]** 这两条文案**不是微信原生的字符串资源**：8.0.48 的 `resources.arsc` 里
  「解散聊天分组」= 0 命中、「归拢设置」= 0 命中，连「归拢」二字都 **0 命中**；
  同一份 arsc 里「不显示该聊天 / 删除该聊天 / 取消置顶 / 折叠置顶聊天」全部命中
  ⇒ 扫描方法本身有效，不是「中文搜不到」的假阴性。
- **[推断 · 高置信]** 「解散聊天分组 / 归拢设置」是**微X 加进这条长按菜单的项**，
  不是「微信原生文件夹菜单自带」。
- **[证据不足，需真机取证]** 微X 具体是「往宿主那条 PopupWindow 里插两项（prepend）」
  还是「自己画一个仿原生的 PopupWindow、把宿主那 4 项文案照抄进去」—— 静态分不开。
  ⚠️ 这两者的**共同点**已经足够支撑我方决策：**我方拿不到「宿主原生文件夹菜单」这个东西**，
  想「像微X」只能自己在 `GroupRowMenu` 里对齐文案/顺序。

### 证据表（字符串 × 落点，可复现）

扫描方式：`zipfile` 逐 entry 读字节，UTF-8 与 UTF-16LE 双编码计数（`_cache/mm.apk` 380MB /
15 个 dex + `resources.arsc`；`_mods/wechatxposed.apk` 4.4MB / 1 dex + arsc）。

| 字符串 | 宿主 `resources.arsc` | 宿主 dex | 微X apk（全 entry） |
|---|---|---|---|
| 解散聊天分组 | **0** | 0 | 0 |
| 归拢设置 | **0** | 0 | 0 |
| 归拢 | **0** | 0 | 0 |
| 聊天分组 | **0** | 0 | 0 |
| 解散 | 35 | 1（`classes9.dex`） | 0 |
| 分组 | 18 | 1（`classes7.dex`） | 0 |
| 不显示该聊天 | 1 | 0 | 0 |
| 删除该聊天 | 1 | 0 | 0 |
| 取消置顶 | 4 | 1（`classes9.dex`） | 0 |
| 折叠置顶聊天 | 1 | 0 | 0 |
| 微X模块 | 0 | 0 | **1**（arsc） |
| 群聊标签 | 0 | 0 | **0** |

读法（三条，缺一条结论就塌）：

1. **宿主中文字面量主要在 `resources.arsc` 的 UTF-8 池**，dex 里只有零星几条
   （「取消置顶」是唯一 dex/arsc 双命中的）⇒ 判「宿主有没有这条文案」必须扫 arsc，
   只扫 dex 会得出「宿主什么文案都没有」的错结论。
2. 宿主里「解散」有 35 处（上下文实抽可见「只有群主具备设置管理员、解散群聊的能力」
   「群聊已被解散」「解散后，群成员和群主将被移出群」），「分组」有 18 处
   （「分组 %s已经存在」「分组成员」「删除分组」「%s_朋友圈分组」）
   ⇒ 宿主**有**「解散 / 分组」这两个词，但没有「解散聊天分组」「归拢」这种组合。
3. 微X 侧**扫描无结论力**：它连自己面板上的「群聊标签」都 0 命中
   （「微X模块」只命中 1 次，是外壳标题）。原因在 `tools/_r53_xh.py` 头部注释里已写过：
   **微X 是 native 壳，业务代码在 assets 下 7 个熵 8.00 的密文里，`classes.dex` 只剩
   `WxCoreLoader` + `InMemoryDexClassLoader`**。⇒ 不能在微X 侧「搜不到」就反推它没写。

### 反方假设（写出来是为了知道自己可能错在哪）

- **反方 1：宿主运行时拼接文案**。宿主有「解散」+「分组」，理论上能拼出「解散聊天分组」。
  ⇒ 被「归拢」在宿主里 0 命中打掉 —— 拼接假设解释不了「归拢设置」这个词。
  另外「归拢」不是腾讯的用语文风（宿主对同类动作一律用「解散 / 删除 / 清空 / 折叠」）。
- **反方 2：我扫的 `mm.apk` 不是设备上那份**（版本 / 渠道差异，8.0.48 有多个 2588 包）。
  ⇒ 这条**没法静态排除**，因此上面的结论按 [推断·高] 记，不升格为 [已证实]；
  校验命令见 ⑤ T0，跑完若 `versionCode=2588` 且 apk sha 一致，结论可升级为 [已证实]。

### 微X 最可能的注入点（逐条对照 `ConvMenuInject` 的既有做法）

先把我方这条链**已真机走通**的事实钉住（这是判「哪条落点可行」的唯一实证依据）：

| 环节 | 我方实现 | 行号 |
|---|---|---|
| 长按最早期抓目标行 | hook `View.performLongClick`（`hookAll` 扫全部重载）**before** | `ConvMenuInject.kt:106-130`（`:127`） |
| 从行 View 找宿主 ListView | `findListView` 沿 parent 上溯 ≤12 层 | `:757-765` |
| 行 → position | `list.getPositionForView(v)` | `:113` |
| position → wxid（窄口径） | `ConvFilter.wxidAt` → `groupIdAt != NOT_A_GROUP` 一律 null | `ConvFilter.kt:569-577`、`:953-956` |
| 兜底取 wxid | `list.adapter.getItem(pos)` + `ConvReader.username` | `ConvMenuInject.kt:137-140` |
| 4 秒窗口内才动手 | `lastWxid/lastName/lastAt` | `:91-97`、`:181` |
| 真正的注入点 | `PopupWindow.showAsDropDown` / `showAtLocation` **after** | `:159-168` |
| 认菜单容器 | 同一父容器里 **≥2 个** `MENU_WORDS`（标为未读/不显示该聊天/删除该聊天） | `:306-330`（判据 `:151-154`、`:315-318`） |
| 克隆样式 | `newItem` 抄样本文本的字号/颜色/背景/内边距 | `:444-466` |
| 塞进去 | ListView → `addFooterView(isSelectable=false)`；别的 AdapterView → 加到父容器；普通 ViewGroup → `addView` | `:355-441`（`:376-432`） |
| 重排高度 | `box.post { popup.update(w, ph + need) }` | `:230-252` |

由此得到三条**已被真机否定**的落点（别再走）——`ConvMenuInject.kt:44-50` 的三条硬证据：
`MenuBuilder.add` 全挂零命中；`View.createContextMenu` 沿链 16 层每层 `size=0`；
`Dialog.show` / `WindowManager.addView` 不命中。

**所以微X 若要「往这条菜单里加项」，可走的路只剩两条（与上面同源）：**

- **候选 ① [推断]** `PopupWindow.showAsDropDown/showAtLocation` 的 `after` 里改容器内容
  （和我方完全同一条路，只是项名不同、可能插在前面而不追加在末尾）。
- **候选 ② [推断]** 在 `View.performLongClick` 阶段自己 `show` 一个自绘 PopupWindow，
  把宿主那 4 条文案照抄进去（因此「像原生」但**不是**原生）。

**⚠️ 判据纪律（R53 §11.3 的说法要改）**：现菜单里出现我方的「加入分组 / 标记已读」
**不能**反证菜单是宿主原生的 —— 我方注入只认「4 秒内长按过一行 + 那个 PopupWindow 的
容器里有 ≥2 个文案」（`:181`、`:315-318`），**完全不看这个 PopupWindow 是谁弹的**。
所以 microX 自绘的菜单一样会被我方追加两项。R53 §11.3 写成「加入分组/标记已读 是本模块
追加 ⇒ 其余项是宿主的」这一步推理**不成立**，本轮更正。

### 定案判据（互斥，一条即可）

> **同一次冷启、微X 开启状态下，分别真手长按 (a) 微X 分组行 与 (b) 任意普通会话行，
> 比对日志里 `CMI popup injected ... box=<类名> ... kind=<footer|parent|addView>` 两行的
> box 类名与 kind。**

- (a)(b) **box 同类、kind 同值** ⇒ 两条菜单出自**同一个宿主构建器** ⇒ 微X 是「往宿主菜单
  里插项」⇒ 候选 ①，且「宿主有文件夹菜单构建逻辑」被证实。
- (a) 的 **box 类名不同 / kind 不同** ⇒ 微X 自绘了一套 PopupWindow ⇒ 候选 ②，
  「微信原生文件夹菜单」假设彻底排除。

这条判据**零新增代码**：`ConvMenuInject.kt:433-439` 已经把 `box` 的类名、`kind`、
`sampleH`、`isListView`、`parent` 全打出来了。

```bash
A="/c/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
DBG=/sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt

"$A" shell am broadcast -a com.lx.wxgroup.action.PROBE --es op logclear -p com.tencent.mm
# → 真手长按微X 分组行（等菜单完全弹出再松手，别用 adb input：本机会被识别成点击）
# → 关掉菜单，再真手长按任意普通会话行
"$A" pull "$DBG" _cache/_r55_cmi.txt
grep -nE "CMI target|CMI popup injected|CMI relayout" _cache/_r55_cmi.txt
```

**辅助佐证（不并列成判据）**：LSPosed 里禁用微X → 强杀冷启 → `python tools/_r53_xh.py row`
确认 `wx__c_` 行消失（R53 T6 已定案「行是微X 造的」）。
⚠️ 这意味着「禁用微X 后长按同一行看这两项是否消失」在本机**做不了**（行本身没了），
R55 之前写的这条判据是空转。替代做法：禁用微X 后长按宿主自带的「折叠置顶聊天」行
（R54 §10.1 意外发现 `parentRef=message_fold`），看它有没有同类「分组项」。

### 顺手发现的一个既有缺陷（与本改造无关，但长按对齐时会被用户看见）

微X 分组行的 `wxid` 是 `wx__c_<hex>`（伪会话，真实存在的 `rconversation` 行），
`ConvFilter.wxidAt` 对它返回的正是这个伪 wxid（`:569-577` 只挡我方分组行，不挡别人的伪会话），
于是长按它时我方也会给「加入分组 / 标记已读」两项；点「加入分组」走
`onJoin → db.findFriend(wxid)` 查不到 → `Friend(wxid = wx__c_…)` → `GroupPicker`
（`ConvMenuInject.kt:732-742`）⇒ **可以把一条微X 伪会话加成我方分组的成员**。
数据上不碰宿主表（红线 ④ 不受影响），但成员表会被污染。一行修法：`wxidAt` 返回值加
`takeIf { !it.startsWith("wx__c_") }`（待用户拍板，本轮不改代码）。

[SEC3 DONE]

### Q4 我方改造路线（含「零新增 hook」路线，及离「跟微X一样」的差距）

### 路线 A：零新增 hook —— 点分组行 = `setGroup(gid)` 原地筛选

**这条路线不是我发明的**：它就是 E 轮之前「自绘分组行」的行为，代码还在
（`ConvFilter.kt:1766` `onTap = { runCatching { setGroup(gid) } }`、
`GroupBar.kt:1281` `onTap = { ConvFilter.toggle(g.id) }`），用户已经用过很久。
本轮只是把**数据层伪会话行**的点击从 `GroupDetailPage.open` 改绑到同一条老路。

| 项 | 内容 |
|---|---|
| 改动面 | ① `feature/ConvFilter.kt:1842`：`v.setOnClickListener { GroupDetailPage.open(v.context, gid, name) }` → `v.setOnClickListener { runCatching { setGroup(gid) } }`（与 `:1766` 同口径；`getView` 本就在主线程，`setGroup` 内部只写库 + `refresh()` 去抖 post，不跨线程碰 View —— `:599-617`）<br>② `feature/ScrollDock.kt:108-111`：`guardPin` 判据收窄（见下，**这是路线 A 的上线前置条件**）<br>③ `feature/GroupRowMenu.kt:83-89`：菜单文案对齐（**必须条件化**：`gconvOn()` 时才用新文案，否则破红线 ①，见 Q6）<br>④（可延后）`data/ConvLayout.kt:169` + `ConvFilter` 的 `getView/getItemViewType/isEnabled/groupIdAt`：筛选态插一条 `组名 (N)` 标题行 |
| 新增宿主 hook | **0**。复用 R54 已验收的 `GroupedAdapter` 包装链（`ConvFilter.kt:1746-1853`），不碰微信混淆类（R54 §10.2 定案） |
| `GroupDetailPage` | 点击不再调用它；**代码保留不删** —— `GroupBar` 的长按菜单「查看成员」(`GroupBar.kt:1455`) 和横条模式 chip (`:1471`) 仍在用它，删了等于断两条入口 + 断回滚 |
| `GroupRowMenu` | 保留（长按仍是它）。只改**文案**，且**按开关二选一**：`gconvOn()` 时 `删除分组 → 解散聊天分组`（语义等价：只取消归组、成员不丢，`GroupRowMenu.kt:116-126`），OFF 时保留旧文案（否则破红线 ①）；`归拢设置` **暂不对齐**，等 ⑤ T2 真机点出它到底干什么再定 |
| `GroupedAdapter` | **不动**。`filterId > 0` 分支早就现成（`ConvFilter.kt:1333-1338` → `ConvLayout.filtered`），这次只是让它第一次被「点分组行」触发 |

#### ⚠️ 正面回答 `ScrollDock.kt:110` 的回归（这是路线 A 唯一真风险）

先纠一个容易看漏的事实：**R54 期间 `guardPin` 实际是休眠的**。R54 的交互下点分组行走
`GroupDetailPage`、`FILTER_GROUP` 从不被写 ⇒ `currentGroupId()` 恒 0 ⇒ `guardPin` 恒 false
（R54 台账「滚动期间无 `ScrollDock pin`」就是这个原因，不是它被修好了）。
⇒ 路线 A 一上线就会**唤醒**这条休眠路径。所以「只筛选不激活 ScrollDock」不是优化项，
**是路线 A 的上线前置条件**。

为什么不干脆 `return false`（彻底不 pin）：`pin` 是给「筛选后内容不足一屏」兜底的
（`ScrollDock.kt:37-44`、`:96-107`）。那种列表下拉之后巨型 header 糊屏，而填充行早被删掉
（`ConvFilter.kt:1872-1906`）⇒ `cannotScrollUp` 为真 ⇒ **拉不回来**，就是 R53 §10 之前
那个「下拉再上拉页面空白」的老 bug。

正确改法：判据从「**是不是筛选态**」换成「**内容是不是不足一屏**」（仍是 O(1)，仍是结构判据）：

```kotlin
private fun guardPin(list: AbsListView): Boolean {
    if (!ConvFilter.wraps(list)) return false
    if (ConvFilter.currentGroupId() <= 0L) return false
    if (!ConvFilter.gconvOn()) return true      // legacy（gconv OFF）：逐字节保持 R53 行为
    if (list.firstVisiblePosition != 0) return false
    val last = list.lastVisiblePosition
    return last >= 0 && last >= list.count - 1  // 最后一项已可见 ⇒ 内容不足一屏，才需要钳
}
```

配套一处极小改动：`ConvFilter` 加 `@Volatile private var gconvOn = false` +
`internal fun gconvOn(): Boolean`，在 `rebuild` 里与 `:1243` 的 `gconvEnabled` **同一行赋值**
（不要每帧读 `Kv` —— R54 已定「每行读 Kv 会退化成查库」）。

效果与回归面：

- **短组**（一屏放得下）：行为与今天**完全一致**（该钳还是钳）⇒ 不回归「下拉糊屏拉不回」。
- **长组**（超过一屏）：顶部下拉不再每帧 `offsetChildrenTopAndBottom` ⇒ 恢复原生
  rubber-band + 原生「下拉露出顶栏/弹小程序面板」；R53 §10.3 实证的「不跟手」根因被摘掉。
  代价：长组在顶部下拉会**像原生一样**露出顶栏并可能弹小程序面板 —— 这是微信原生行为，
  要用户验收接受（⑤ T5）。
- **legacy 开关（`GCONV_MODE=false` + `chat.group_row_enabled=true`）**：走
  `if (!gconvOn) return true` 分支，与今天逐字节相同 ⇒ 红线 ① 不受影响。
- `guardTooHigh`（吞 `correctTooHigh`，`:126-130`）**不动**：它按结构判据生效，
  「点分组行整页下移 2000px」不会回来。

#### 路线 A 离「跟微X一样」还差多远（逐项对）

| 用户能看见的点 | 微X | 路线 A | 差距 |
|---|---|---|---|
| 点分组行之后看到什么 | 独立页（`ii7` + `android:id/content`）里只有该组会话 | **主列表原地**只剩该组会话 | 交互骨架**一致**，容器不同 |
| 怎么退出 | 页面返回（**真机实测返回键退不出**，要强杀） | 返回键退筛选（`ConvFilter.kt:394-417` 已实现，且真能吃键） | A **更好**，不算差距 |
| 顶部标题 | 原生 ActionBar `组名 (N)`（`pkn`） | 无标题（只剩微信那个 3046px 巨型 header + 顶栏「微信」） | **缺**，A2 才能补 |
| `(N)` 计数口径 | 组内**会话条目数**（Q1 已证） | 可实现同口径：筛选后 `layout.size`（`ConvLayout.kt:169-176`） | 口径可对齐，但要 A2 才显示 |
| 右上一个「菜单」按钮 | 有（`g6`） | 无 | 缺（只能靠长按） |
| 会话行本身 | 宿主 `cqz/l7a/q5l/igs` 原生行 | **同一个列表的宿主原生行** | **完全一致** |
| 未读红点 | 宿主 `piu` | 宿主 `piu`（同列表） | **一致** |
| 长按菜单 | 8 项、原生样式（4 项宿主文案 + 2 项微X + 2 项我方） | 模块 `AlertDialog` 5 项 | 形态差；文案可对齐 1 项 |
| 滚动手感 | 独立页无巨型 header、无 ScrollDock | 长组按上面收窄后接近；短组仍被钳 | 收窄后接近 |

**结论一句话**：「点进去 → 只剩这组会话 → 返回退出」这条骨架能对齐；
「标题栏 / `(N)` 计数 / 右上菜单 / 长按菜单形态」四项对不齐 —— 前两项加 A2 能补，
后两项只能自绘（路线 C）才补得上。

#### 风险清单

1. **ScrollDock 唤醒**（已给解，必须同批上线，不能拆开）。
2. **筛选后列表停在哪**：`refresh()` 会 `rememberAnchor/restoreAnchor` + `notifyDataSetChanged`
   （`ConvFilter.kt:626-637`），列表可能停在中间而不是顶部。要不要 `post { list.setSelection(0) }`
   属于**动锚点**，R53 已警告锚点操作可能把列表顶成一次「下拉」从而弹面板
   （`ConvFilter.kt:629-635`）⇒ 先不加，真机看现象再定（⑤ T6）。
3. **空组**：`filtered()` 返回 0 行（`ConvLayout.kt:165-168`），界面一片空白，出口只有返回键
   （`ConvLayout.kt:160-167` 明确写了「不放返回全部行」）。微X 空组显示什么未知（⑤ T7）。
4. **计数口径别写错**：`(N)` 必须用 `layout.size`，**不能**用 `db.countMembers(gid)`
   （成员可以没有会话，两者真机上不相等）。
5. **长按菜单不要照抄**：微X 那 8 项里「不显示该聊天 / 删除该聊天」对一条**文件夹行**
   语义上不成立（它们出现在那里大概率是宿主把 `wx__c_` 当成普通会话处理的结果）。
   照抄 = 用户点了不知道会发生什么（⑤ T2）。

#### 真机判据（路线 A）

- 点分组行 → `topResumedActivity` 仍是 `LauncherUI`（`dumpsys activity activities | grep -i resumed`），
  列表只剩该组会话，**返回键一次退回收纳态**（`logcat | grep "K7 back: exit filter"`）。
- 长组顶部下拉：`logcat | grep -c "ScrollDock pin"` 应为 **0**（长组）；短组应为 >0。
- 滚动后点普通会话行：**不得**进筛选（红线 ② 回归，见 Q6）。

### 路线 B：数据层复刻微X（把分组写进宿主的「文件夹实体」）

| 项 | 内容 |
|---|---|
| 改动面 | 新增一套写宿主表的代码（`GroupConvPlan` 旁并列一个「文件夹计划」）：写入文件夹行 + **宿主自己的成员归属表**；`ConvFilter` 的行级 listener 覆盖可整段删除；`GroupDetailPage`/`GroupRowMenu` 下线 |
| 新增 hook | 0（如果宿主原生支持文件夹页）—— 但**取证期要先反查 schema**，那一步需要临时 hook / 全库扫描 |
| `GroupDetailPage` / `GroupRowMenu` | 全部下线，长按交给宿主原生菜单 |
| 风险 | **极高**。① `parentRef` 已证与归属无关（R53 §11.4），归属在别的表/别的机制 → schema 未知；② 写宿主业务表会与「清空聊天记录 / 会话迁移 / 微信升级」抢数据（R54 §8.5 已列「侵入性高」）；③ **微X 自己都没走这条** —— 它选了「伪会话 + 渲染期标题注入」（R53 §8.3/8.4），这是「连竞品都不愿走」的强反证；④ 我方的 `wxgrp_` 命名空间隔离（`GroupConvPlan.kt:9-14`）会被打破，红线 ④ 失去保护 |
| 真机判据 | Q1 的 id 序列判据（禁用微X 前后比对）+ `dumpsys activity` 取页面 Activity 类名 + 全库 `sqlite_master` 扫「有没有一张表同时含 folder/group 与 member 语义」 |
| 结论 | **不作为本轮路线**。只有 Q1 判定候选 A 且 T3/T4 真拿到可写 schema 才重启这条 |

### 路线 C：自绘全屏页仿原生（保留自绘，改形态）

| 项 | 内容 |
|---|---|
| 改动面 | `GroupDetailPage` 从 `Dialog`（`GroupDetailPage.kt:133`）改成全屏页 + 仿原生顶栏（高度对齐 `es` 的 173px、左返回、中 `组名 (N)`、右菜单）；行从「两行 TextView」（`:329-362`）改成「原生会话行样式」（标题/时间/摘要/未读点 + 头像，`AvatarCompose` 现有）；`GroupRowMenu` 的项并进该页右上菜单 |
| 新增 hook | 0（纯模块 UI），但要拿到每个会话的时间/摘要/未读 —— 需要确认 `ConvReader`/`ConvEntry` 是否已带这些字段，否则要新增只读取值 |
| 风险 | ① 做出来是「像原生但不是原生」，头像加载、置顶样式、深色模式都差一口气；② 仍是 Dialog，`topResumedActivity` 还是 `LauncherUI`（`GroupDetailPage.kt:133`），不参与宿主返回栈/手势；③ 维护成本最高；④ 用户点「返回」是关 Dialog，与微X「返回键退不出」的坑不一样，反而更好但也不「一样」 |
| 真机判据 | 像素级比对：行高 259px、`l7a` 位置、顶栏 173px；再用 uiautomator dump 比对 resource-id 序列（一定**不**一致，这是预期的，因为它是自绘） |
| 结论 | 只在用户「必须要看到标题栏」时才做，作为 A2 的替代方案；不做则是 A + A2 |

[SEC4 DONE]

### Q5 推荐方案、最小改动清单、静态判不了需真机取证的清单

### 推荐：路线 A（第一批只做 1~3 项），A2 列为第二批

理由三条，按权重：

1. **只有路线 A 是「今天就能上、且能一键回滚」的**：改动 3 处、零新增宿主 hook、
   全部落在 R54 已验收的包装链上；回滚就是 `kvset chat.gconv_enabled=0`（与 R54 同一条命令），
   不需要改回任何 UI 代码。
2. **交互骨架正好是用户要的那件事**：微X 的「像」在用户眼里首先是「点开只剩这组会话」，
   而不是「标题栏长得一样」。路线 A 把这件事做到了，并且**返回键真能用**（微X 那个页
   返回键退不出，是它自己的坑，不该抄）。
3. **路线 B 被静态证据挡死，路线 C 成本/收益不划算**：B 的 schema 未知且微X 自己都没走
   （R53 §8.3/8.4）；C 做出来是「像原生但不是原生」，观感差一口气且维护成本最高。

先 A 还有一个战术好处：A 上线后，用户能用**真实分组**去点、去退出、去感知滚动，
再决定「要不要为标题栏付 A2/C 的成本」—— 这个决定不该在没上手前做。

### 最小改动清单（可直接执行；本轮**未**执行，只写清单）

按落地顺序，1~3 项必须**同批**上线（2 是 1 的前置条件，见 Q4）：

| # | 文件:方法 / 行 | 改什么 | 为什么必须 |
|---|---|---|---|
| 1 | `feature/ConvFilter.kt` `getView` **:1842** | `v.setOnClickListener { GroupDetailPage.open(v.context, gid, name) }` → `v.setOnClickListener { runCatching { setGroup(gid) } }` | 点击语义对齐（与 `:1766` 旧自绘行同口径）。`name`/`v.context` 就地处不再需要，`:1834` 的 `groupNames` 查询保留给长按用 |
| 2 | `feature/ConvFilter.kt` 字段区 + `rebuild` **:1243** | 新增 `@Volatile private var gconvOn = false` 与 `internal fun gconvOn(): Boolean`；在 `:1243` 那行**同一处**赋值 `gconvOn = gconvEnabled` | 让 `ScrollDock` 能 O(1) 拿到开关，**不能**每帧读 `Kv`（R54 定案：热路径读 Kv 退化成查库） |
| 3 | `feature/ScrollDock.kt` `guardPin` **:108-111** | 按 Q4 给的 6 行替换（`gconvOn` 分支 + 「内容不足一屏」结构判据） | 路线 A 会唤醒休眠的 pin；不改就会复现 R53 §10.3「下拉不跟手」。`guardTooHigh` 不动 |
| 4 | `feature/GroupRowMenu.kt` **:88** | `"删除分组"` → `"解散聊天分组"`（`:116-126` 的确认文案同步改标题，语义不变） | 文案对齐；`归拢设置` 等 ⑤ T2 结果再定 |
| 5 | （第二批 A2，可选）`data/ConvLayout.kt` `filtered` **:169-176** + `ConvRow` + `ConvFilter` 的 `getView/getItemViewType/isEnabled/groupIdAt` | 筛选态插一条标题行 `组名 (N)`，N = `layout.size`（**不是** `countMembers`）；右侧保留「✕ 显示全部」 | 补「标题 + 计数」两项差距；不动宿主、不加 hook，但要让 `isGroupRow/convIndexOf/groupIdAt` 对新行类型保守返回 |

明确**不做**的（写下来防止手滑）：

- 不删 `GroupDetailPage` / `GroupRowMenu`（`GroupBar.kt:1455`、`:1471` 还在用前者）。
- 不动 `ConvFilter.kt:1799-1801` 的 `tag + cv=null` 防御（红线 ②）。
- 不动 `GroupConvStore` / `GroupConvPlan`（红线 ③④）。
- 不在 `getView` 里加 `setText`/改标题（会话标题是宿主自绘 `NoMeasuredTextView`，
  `ConvMenuInject.kt:769-792` 已踩过）。
- 不 `post { list.setSelection(0) }`（动锚点会制造一次「下拉」，`ConvFilter.kt:629-635`）。

### 静态判不了、必须真机取证的清单（Q5 版；运行顺序与前置见 ⑤）

| # | 验什么 | 怎么验（命令 / 探针） | 判据怎么读 |
|---|---|---|---|
| T0 | `_cache/mm.apk` 是不是设备上那份 | `adb shell dumpsys package com.tencent.mm \| grep -E "versionName\|versionCode\|lastUpdateTime"`；`adb shell pm path com.tencent.mm` + `adb shell sha1sum <path>` | `versionName=8.0.48` 且 `versionCode=2588` 且 sha 与本地一致 ⇒ Q3 的 arsc 结论可升 [已证实]；否则降级为「版本未知，结论待验」 |
| T1 | Q3：菜单是宿主构建还是微X 自绘 | 长按微X 分组行 → 长按普通会话行，对比 `CMI popup injected ... box=` 与 `kind=`（`ConvMenuInject.kt:433-439` 现成日志） | box 同类 + kind 同值 ⇒ 微X 插项（候选 ①）；不同 ⇒ 自绘（候选 ②） |
| T2 | 「解散聊天分组 / 归拢设置」点下去干什么 | 真手点第一项、再点第二项，各录一次 `adb shell screenrecord` 或 dump 前后界面 + `logcat -s wxgroup`；**别**在真实分组上点「解散」前先导出备份 | 前者弹确认框/直接删分组 ⇒ 我方「删除分组」可改文案；后者打开某个设置页 ⇒ 记下页名，映射到我方 `MemberPage`/`GroupDetailPage` |
| T3 | Q1：宿主有没有「命名文件夹」页 | 禁用微X → 强杀冷启 → 打开宿主「折叠置顶聊天」子页 → `uiautomator dump` 抽 `ii7/content/kf8/p0q/keo/pmu` + `es/ep/cxa/.../pkn/g6` | id 序列逐项一致 ⇒ 候选 A；不一致/打不开 ⇒ 候选 B（详见 Q1 判据） |
| T4 | 微X 分组页的 Activity 类名与 extra | `adb shell dumpsys activity activities \| grep -iE "mResumedActivity\|topResumedActivity"`（在该页时执行；**取证后强杀冷启，别按返回键**） | 是 `com.tencent.mm` 已注册类且类名含 folder/group 语义 ⇒ 佐证 A；是通用 `LauncherUI`/`MMFragmentActivity` ⇒ 佐证 B |
| T5 | 路线 A 上线后 ScrollDock 是否还每帧钳 | `adb logcat -c` → 在长组（>10 条会话）顶部下拉 3 次 → `grep -c "ScrollDock pin"`；再在短组（≤5 条）重复 | 长组应为 **0**、短组 >0 ⇒ 收窄生效；长组仍 >0 ⇒ `gconvOn()` 没生效或 `lastVisiblePosition` 判据写错 |
| T6 | 点进筛选后列表停在哪 | 点分组行后立即 `uiautomator dump`，看第一屏第一条会话的 `bounds` 与 `child0.top` | 停在顶部（`child0.top` ≈ -2713 附近）= 正常；停在中间 ⇒ 需要另想办法（但**不许**直接动锚点） |
| T7 | 空组 / 未读数一致性 | 建一个 0 成员的临时分组 → 点它 → dump；再对 `pet片材` 这类的微X 组核对「标题 N vs 会话行数」 | 空组一片空白且返回键能退出 = 可接受；标题 N ≠ 行数 ⇒ 我方的 `(N)` 口径也要按该组真实行数算 |
| T8 | 红线 ② 回归（`tag + cv=null`） | 开筛选 → 滚动到底再滚回顶 → 点**普通**会话行、长按它 | 点：进对应聊天；长按：宿主菜单 + 我方 2 项；**不得**进分组筛选 / 弹 `GroupRowMenu` |

[SEC5 DONE]

### Q6 改造会不会破坏 R54 已验收的 4 条红线？

先给总表，再逐条给行号依据。**结论：路线 A 的 1/2/3/5 项全部不破红线；第 4 项（菜单文案）会破红线 ①，必须改成条件文案或延后。**

| 红线 | 路线 A 是否碰到 | 判定 | 关键依据 |
|---|---|---|---|
| ① `GCONV_MODE=false` 零差异 | 1 项在 `gconvEnabled` 块内；`ScrollDock` 新分支 OFF 走旧表达式；**但菜单文案会漏** | **1/2/3/5 不破；4 破** ⇒ 文案必须按开关二选一 | `Keys.kt:448`、`ConvFilter.kt:1243/1780/1824`、`ScrollDock.kt:108-111` |
| ② `tag + cv=null` listener 污染防护 | 不改那 3 行 | **不破** | `ConvFilter.kt:93`、`:1799-1801`、`:1837` |
| ③ `GroupConvStore` 单线程 + 事务 | 不改；但 `setGroup → refresh → kickGconvSync` 会多一次**节流内**的 sync 投递 | **不破**（有 60s 节流） | `GroupConvStore.kt:59-61`、`:89-108`、`:400-434`；`ConvFilter.kt:639`、`:651-654` |
| ④ `other` 行数不变 | 不新增任何宿主写库语句 | **不破** | `GroupConvStore.kt:180-184`、`:198-215`、`:240-241` |

### ① 默认 `GCONV_MODE=false` 的 OFF 零差异

- 默认值在 `data/Keys.kt:448`：`PrefSpec(Keys.GCONV_MODE, PrefKind.SWITCH, "false", …)`；
  常量 `:81`。`ConvFilter` 每次 `rebuild` 读一次（`:1243`，`getOrDefault(false)`）。
- 三处生效点全在开关里，OFF 时**整块不执行**：
  `:1780`（`rowGid` 取证）、`:1824`（listener 覆盖的唯一入口）、`:1344`（ON 才走 passthrough）。
- **改动 1 的位置在 `if (gconvEnabled) { … }`（`:1824-1849`）内部**（`:1842`）
  ⇒ OFF 时这行代码根本不会被执行 ⇒ 与今天逐字节相同。✅
- 改动 3（`guardPin`）的 OFF 路径：新代码先 `if (!ConvFilter.gconvOn()) return true`
  再走结构判据 —— OFF 时 `gconvOn=false` ⇒ 与今天 `return ConvFilter.currentGroupId() > 0L`
  等价（前面的 `wraps` / `currentGroupId<=0` 两道门原样保留）。✅
- **改动 4（菜单文案）是一个真破口**：「删除分组 → 解散聊天分组」改的是
  `GroupRowMenu.kt:83-89` 的字面量，而 `GroupRowMenu` 在 OFF 态**照样会被用**
  （旧自绘分组行的长按 `ConvFilter.kt:1769`）。⇒ 用户关掉 gconv 后，长按旧分组行
  会看到新文案 = 「OFF 零差异」被破。
  **修法（二选一，推荐前者）**：把文案写成条件式
  `if (ConvFilter.gconvOn()) "解散聊天分组" else "删除分组"`；
  或者把改动 4 整项**延后到用户明确接受「文案也对齐」之后**，并同步修订红线 ① 的措辞。
- 回归判据：`kvset chat.gconv_enabled=0` + `op=gcs clean` + 冷启 ⇒
  `op=xh sub=row` 出现 `vt=1 tag=GroupRowView$Holder`（旧自绘行）、
  长按菜单仍是 5 项旧文案、点它仍是 `setGroup`（旧行为本来就是 setGroup，`ConvFilter.kt:1766`）。

### ② `tag + cv=null` 的 listener 污染防护

- tag key `ConvFilter.kt:93`（`0x47525643`）；打标 `:1837`；
  丢弃判据 `:1799-1801`（`rowGid == null && cv?.getTag(...) != null ⇒ cv = null`）；
  窄口径反解 `:1814-1831`。
- 路线 A **不改这三处任何一行**，只改 `:1842` 绑的 listener 内容。✅
- 一条容易忽略的连带影响：进了筛选态后 `layout` 变成 `ConvLayout.filtered`（全 `Conv` 行），
  ListView 会大量复用**带 tag 的 View**；`rowGid == null` 时它们会被逐个丢弃并让微信新建
  ⇒ 复用率下降、首帧变慢一点点，但**防护逻辑本身不受影响**（这正是它设计要处理的场景）。
- 回归判据：⑤ T8（滚到底再滚回顶 → 点/长按普通会话行不得进筛选、不得弹 `GroupRowMenu`）。

### ③ `GroupConvStore` 单一后台线程 + 事务

- 单线程：`GroupConvStore.kt:59-61`（`Executors.newSingleThreadExecutor`，线程名 `gconv-store`）；
  三个 API 全部只 `worker.execute`：`postSync :89-108`、`postClean :111-119`、`postStat :122-130`
  ⇒ 调用方（主线程）只投递、不等待。
- 事务：`sync` 里 `inTransaction(db, out) { … }`（`:153`），实现 `:400-434`：
  `beginTransaction/setTransactionSuccessful/endTransaction` 反射三件套，
  失败收集 `failures` 非空即抛 ⇒ 不标记成功 ⇒ 整段回滚；反射不可用时降级为无事务直写并打 WARN（`:406-415`）。
- 路线 A 一行都不碰这条链。✅
- 唯一副作用要如实记：`setGroup` → `refresh()` 末尾 `kickGconvSync()`
  （`ConvFilter.kt:639` → `:651-654`）会在开关 ON 时再投一次 `postSync()`；
  受 `SYNC_THROTTLE_MS = 60_000`（`GroupConvStore.kt:72`、`:95-96`）节流
  ⇒ 绝大多数点击不会真的触发全表读；即便触发，也在 `gconv-store` 线程串行执行，
  **不会**和主线程列表渲染抢同一线程。要不要把 `setGroup` 这条路径的 sync 摘掉，
  属于「真机看 `GCONVSTORE sync start` 频率」再决定（不是必须改）。

### ④ `other` 行数不变

- 对账口径：`sync` 结束打 `other`（`GroupConvStore.kt:180-184`，SQL 是
  `select count(*) from rconversation where not (ownWhere)`）；
  `clean` 前后各打一次（`:198-215`）；`stat` 再打（`:240-241`）。
- 路线 A **不新增任何宿主写库语句**：点击只调 `setGroup(gid)` → `db.setPref(FILTER_GROUP, …)`
  （模块自有库，`ConvFilter.kt:608`），`GroupConvPlan` 的 SQL 一条都不会被调用
  ⇒ 宿主两表行数原样。✅
- 与本改造相关但**不破红线**的一个已知污染（见 Q3 末）：长按 microX 伪会话行点
  「加入分组」会把 `wx__c_<hex>` 加成**我方**分组成员。它写的是模块库 `group_member`，
  不碰 `rconversation/rcontact` ⇒ ④ 的 `other` 不变；但会让分组里多出一条打不开的成员。
  建议在路线 A 同批顺手挡掉（`wxidAt` 里加一行 `wx__c_` 排除），否则用户长按测试时会踩到。

[SEC6 DONE]

## ③ 路线对照表

| 路线 | 改动面 | 新增宿主 hook | 离「跟微X一样」的差距 | 风险 | 判据（真机） |
|---|---|---|---|---|---|
| **A 零新增 hook（原地筛选）** | `ConvFilter.kt:1842` 改 listener；`ConvFilter` 加 `gconvOn()`；`ScrollDock.kt:108-111` 收窄；`GroupRowMenu.kt:88` 文案 | **0** | 骨架对齐（点进只剩该组会话 + 返回退出）；**缺**标题栏、`(N)` 计数、右上菜单；长按仍是模块对话框 | 中（**唤醒休眠的 ScrollDock**，必须同批改判据）；筛选后停位、空组空白 | 点后仍 `LauncherUI`、列表只剩该组；返回键打 `K7 back: exit filter`；长组 `ScrollDock pin` = 0 |
| **A2 = A + 筛选态标题行** | 追加 `ConvLayout.kt:169-176` 插标题行 + `ConvRow` 新类型 + `ConvFilter` 的 `getView/getItemViewType/isEnabled/groupIdAt` 四处分派 | **0** | 只剩「右上菜单 + 长按菜单形态」两项差距；标题 `组名 (N)`（N=`layout.size`）与微X 同口径 | 中高（动 `layout` 的行类型 = 动位置映射，R1~R34 的「点错人」历史都在这块） | 标题 N 与可见会话行数一致；点标题右侧「✕」退回收纳态；滚动/点击不错位 |
| **B 数据层复刻（宿主文件夹实体）** | 新增「文件夹计划」写宿主表 + 归属表；下线 `GroupDetailPage`/`GroupRowMenu` | 0（落地时）／取证期需临时 hook | **理论上完全一样**（真·宿主页） | **极高**：schema 未知、写宿主业务库、破坏 `wxgrp_` 命名空间隔离（红线 ④ 失去保护）、升级即崩 | Q1 id 序列判据 + 全库 `sqlite_master` 命中「folder/group × member」表 + `dumpsys activity` 拿到已注册页面类 |
| **C 自绘全屏页仿原生** | `GroupDetailPage` 改全屏 + 仿 `es/ep/cxa` 顶栏 + 原生行样式 + 右上菜单 | **0** | 形态像、**不是**原生：头像/置顶/深色模式差一口气；仍是 Dialog（`topResumedActivity=LauncherUI`） | 中（工作量最大、维护成本最高、观感天花板低） | 行高 259px / 顶栏 173px 像素比对；uiautomator id 序列**必然**不同（自绘，预期内） |

> 不建议的第五种：「删掉我方接管、交回微信原生」。R53 §11.5 已实证：裸 `wxgrp_` 行不接管时
> 微信会打开「对方还不是你的朋友」空聊天页 —— 必须拦，不能不拦。

## ④ 推荐方案与最小改动清单

**推荐路线 A（第一批 1~3 项同批上线；4 项改成条件文案；5 项 A2 待用户验收后再定）。**

最小改动清单（与 Q5 同表，此处只留执行要点）：

1. `feature/ConvFilter.kt` `getView` **:1842** →
   `v.setOnClickListener { runCatching { setGroup(gid) } }`。
2. `feature/ConvFilter.kt` 加 `@Volatile gconvOn` + `gconvOn()`，在 `rebuild` **:1243** 同处赋值。
3. `feature/ScrollDock.kt` `guardPin` **:108-111** → gconv 分支 + 「内容不足一屏」结构判据
   （`firstVisiblePosition == 0 && lastVisiblePosition >= count-1`）。
4. `feature/GroupRowMenu.kt` **:88** → 条件文案
   `if (ConvFilter.gconvOn()) "解散聊天分组" else "删除分组"`（不条件化就破红线 ①）。
5. （第二批 A2）`data/ConvLayout.kt` `filtered` 插标题行，N 用 `layout.size`。

上线纪律：

- 1~3 必须**同一次装包**，中间不拆（2 是 3 的前置，3 是 1 的前置）。
- 装包后先跑 ⑤ T5（ScrollDock 计数），再让用户手动验手感；T5 不过就整批回滚。
- 一键回滚（与 R54 同一条）：
  `python tools/dev.py probe kvset --key chat.gconv_enabled --val 0` →
  `python tools/_r54_gconv.py gcs clean` → `python tools/dev.py restart` →
  `python tools/_r54_gconv.py stat` 应见 `own=0 other=3120/23482`（R54 台账基线，以当次实测为准）。
- ⚠️ 回滚后 `FILTER_GROUP` 可能残留非 0（旧自绘行时代会持久化筛选）。它存在**模块自有库的
  pref 表**里（键名 `filter_group`，`data/GroupDb.kt:32`；写入点 `ConvFilter.kt:608`
  `db.setPref(...)`），**不是** `Kv` 那套 SharedPreferences ⇒ 不能用
  `tools/dev.py probe kvset --key chat.filter_group` 去清。正确做法是让模块自己写一次 0：
  调 `ConvFilter.setGroup(0)`（旧「显示全部会话」走的就是它，`GroupBar.kt:1454`），
  或直接 `db.setPref(PrefKeys.FILTER_GROUP, "0")`。否则冷启后列表可能仍停在筛选态，
  看起来像「会话被删了」。

## ⑤ 需真机取证清单

前置（每次取证都做，缺了数据不可信）：微信在前台 + `adb logcat -c`；
该页 `input keyevent 4` 退不出（R55 事实 3）⇒ **取证后 `am force-stop com.tencent.mm` 强杀冷启**，
不要用返回键。

| # | 验什么 | 怎么验 | 判据怎么读 |
|---|---|---|---|
| **T0** | 静态证据的版本前提：`_cache/mm.apk` 是否就是设备上那份 | `adb shell dumpsys package com.tencent.mm \| grep -E "versionName\|versionCode"`；`adb shell pm path com.tencent.mm`；`adb shell sha1sum <path>` | `8.0.48` / `2588` / sha 与本地 `_cache/mm.apk` 一致 ⇒ Q3 的 arsc 结论可升 [已证实]；不一致 ⇒ Q3 降级，先重拉 apk 再重扫 |
| **T1** | 长按菜单是谁构建的（Q3 定案） | ① 长按微X 分组行；② 关掉菜单，长按普通会话行；③ `adb pull /sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt` → `grep -E "CMI popup injected\|CMI relayout"` | 两行 `box=<类名>` 同类 + `kind=` 同值 ⇒ 微X 往宿主菜单插项（候选 ①）；不同 ⇒ 微X 自绘（候选 ②） |
| **T2** | 「解散聊天分组 / 归拢设置」的真实语义 | 真手分别点这两项，点前 `screenrecord` 或前后各 dump 一次；「解散」前先导出分组备份 | 弹确认/直接消失 ⇒ 映射到我方「删除分组」；打开某个设置页 ⇒ 记页名，映射到我方 `MemberPage`/`GroupDetailPage`。**点完没反应也是一种结论**（说明该项对文件夹行是死项） |
| **T3** | Q1：宿主是否存在「命名文件夹」页（A/B 互斥判据） | 禁用微X → 强杀冷启 ×2 → 打开宿主「折叠置顶聊天」子页 → `uiautomator dump` → 抽 `ii7/content/kf8/p0q/keo/pmu` 与 `es/ep/cxa/actionbar_up_indicator/pkn/g6` | id 序列逐项一致 + 标题带 `(N)` ⇒ 候选 A（宿主原生文件夹）；找不到入口或序列不一致 ⇒ 候选 B（微X 构造） |
| **T4** | 微X 分组页的 Activity 类名与 intent extra | 停在该页时 `adb shell dumpsys activity activities \| grep -iE "mResumedActivity\|topResumedActivity"`；`dumpsys package com.tencent.mm \| grep <类名>` | 已注册且类名含 folder/group 语义 ⇒ 佐证 A；是 `LauncherUI`/通用 `MMFragmentActivity` 且无对应 extra ⇒ 佐证 B。**单看类名不定案** |
| **T5** | 路线 A 是否摘掉了 ScrollDock 每帧钳制 | 长组（>10 条会话）顶部下拉 3 次 → `adb logcat -d \| grep -c "ScrollDock pin"`；短组（≤5 条）重复 | 长组 **0**、短组 **>0** ⇒ 收窄生效；长组 >0 ⇒ `gconvOn()` 未生效 / 判据写反 |
| **T6** | 点进筛选后列表停位 | 点分组行后 1s 内 `uiautomator dump`，看 `child0.top` 与首条会话 `bounds` | `child0.top ≈ -2713`（停靠位）= 正常；明显大于该值（停在中间）⇒ 记下来，**不要**直接加 `setSelection(0)`（会制造一次「下拉」） |
| **T7** | 空组与计数口径 | 建 0 成员临时分组 → 点开 → dump；对微X `pet片材 (9)` 核对「标题 N vs 实际行数」 | 空组一片空白且返回键可退 ⇒ 可接受；N 恒等于行数 ⇒ A2 的 `(N)` 必须用 `layout.size`（不能用 `countMembers`） |
| **T8** | 红线 ②③④ 回归 | 开筛选 → 滚到底再回顶 → 点/长按普通会话行；再 `python tools/_r54_gconv.py stat` | 点：进对应聊天；长按：宿主菜单 + 我方 2 项；`stat` 的 `other` 与改造前基线一致（3120 / 23482） |
| **T9** | 长按菜单里我方两项是否错标到伪会话 | 长按微X 分组行 → 点「加入分组」→ 看 `GroupPicker` 顶部出现的名字 | 出现 `wx__c_<hex>` ⇒ Q3 末那个既有缺陷仍在，需按清单加一行排除 |

## ⑥ 实际读过的文件清单

源码（`wxgroup/app/src/main/java/com/lx/wxgroup/`）：

- `feature/ConvFilter.kt`（全文抓取关键区：`:93` tag key、`:380-460` 返回键退筛选、`:555-690`
  对外接口与 `setGroup/refresh/kickGconvSync/refreshMembers`、`:940-976` 位置重映射、
  `:1230-1360` `rebuild` 分支与指纹、`:1720-1928` `getView` 的点击/长按接管与 `tag+cv=null`）
- `feature/GroupDetailPage.kt`（全文 364 行 —— `open :44-49`、Dialog 构造 `:133`、
  成员行 `:329-362`、`memberMenu :247-279`、`setOnDismissListener :152`）
- `feature/GroupRowMenu.kt`（全文 132 行 —— 菜单项 `:83-89`、`activityOf :41-49`、删除分组 `:116-126`）
- `feature/ConvMenuInject.kt`（`:29-75` 三条硬证据与做法、`:76-195` 安装/长按抓取/注入入口、
  `:203-260` 轮询与重排、`:300-341` 认容器取样本、`:343-466` 三路塞入与克隆、
  `:732-754` 加入分组/标记已读、`:756-805` 找 ListView 与 `titleOf`/`a11yText`）
- `feature/ScrollDock.kt`（全文 238 行 —— `guardPin :108-111`、`guardTooHigh :126-130`、
  `pin :209-237`、`install :132-136`）
- `feature/GroupConvStore.kt`（`:50-130` 单线程与三个投递 API、`:140-190` sync 与 other 对账、
  `:198-241` clean/stat、`:395-456` `inTransaction` 与逐条执行日志）
- `feature/GroupBar.kt`（`:160-280` 安装与 setPadding/dispatchDraw、`:352-390` 主界面挂载、
  `:1231-1323` 旧分组行/退出筛选行、`:1434-1478` 旧长按菜单与横条 chip）
- `data/GroupConvPlan.kt`（`:12-57` 命名空间/`usernameOf`/`groupIdOf`/`isOurs`/`flagOf`）
- `data/ConvLayout.kt`（`:160-176` `filtered` 与「无返回全部行」的定案、`:202-250` 顺序判定与
  位置映射、`:216-244` `Layout` 的位置/分组 id 语义）
- `data/Keys.kt`（`:56/:81/:91` 三个开关常量、`:444-456` 默认值表 —— `GCONV_MODE` 默认 `"false"`）

文档 / 数据：

- `docs/_r55_microx_click_longclick.md`（本文件 Q1/Q2，上一 lane 成果，未改动）
- `docs/_r53_microx_grouprow.md` §2.4、§8.3、§8.4、§8.5、§10.3、§11、§11.1~§11.6
- `docs/派发审计台账.md`（只读 `sed -n '4130,4210p'` 一段：R54 装包验收 + R55 派发/收尾）
- `docs/conclusions/conv_list.md`（**本轮未直接打开**；Q2/Q4 引用的 `:2`、`:9-13`、`:77`、`:82-84`
  全部沿用 Q1/Q2 已核过的行号，未二次核对）
- `.workbuddy/memory/2026-11-12.md:32`（只 grep 命中一行：R55 那条「标题 `pkn=组名 (N)` +
  `g6=菜单`、长按菜单 `解散聊天分组/归拢设置/...`」）
- ⛔ 按 prompt 要求**未读** `docs/派发审计台账.md` 全文（470KB）与 `docs/_r54_gconv_design.md` 全文

二进制（只读，`zipfile` 逐 entry 扫 UTF-8 / UTF-16LE 字节，未执行、未安装、未改）：

- `_cache/mm.apk`（微信 8.0.48：15 个 `classes*.dex` + `resources.arsc` 34.5MB）
- `_mods/wechatxposed.apk`（微X 4.4MB：1 dex + arsc；业务代码在 assets 密文里）

脚本（只读用法，未执行）：

- `tools/_r53_xh.py`（`prefs` / `hook` / `row` / `prefsx` / `all`；头部注释记载微X 是 native 壳）
- `tools/_r54_gconv.py`（`gcs` + `stat/trial/rename/clean/sql`）
- `tools/dev.py`（`probe kvset` / `restart`）

<!-- R55 分析完成：Q1~Q6 与 ③④⑤⑥ 全部落盘，各节已标 [SECn DONE]，末尾 [DONE] 为终态标记。 -->
[DONE]
