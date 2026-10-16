# WXGroup — 微信好友自定义分组（M0 骨架）

目标宿主：**Google Play 版微信**（`com.tencent.mm`）
已确认需求：**一个好友可属于多个分组（多标签）** / 加人入口走**通讯录长按 + 分组页批量勾选** / **不做**通讯录过滤 Tab / **不做**云同步

## ✅ M1 状态：锚点已定位（2026-09-21）

不猜类名，靠运行时反射 dump 出来的（方法见 `probe/UiProbe.kt`）。全部常量已固化在 `core/Anchors848.kt`：

| 锚点 | 值 |
|---|---|
| 通讯录 Fragment | `com.tencent.mm.ui.contact.address.MvvmAddressUIFragment` |
| 列表 View | `com.tencent.mm.view.recyclerview.WxRecyclerView` |
| 列表 Adapter | `ju2.s0`（数据字段 `data`，条目里 Contact 在字段 `d`） |
| **联系人实体** | **`com.tencent.mm.storage.h4`** |
| wxid | `field_username`（= `wxid_xxx`），兜底用 `getPrimaryKeyValue()` |
| 昵称 | `field_nickname` |
| 备注 | `field_conRemark` |
| 微信号 | `field_alias` |
| 官方标签 | `field_contactLabelIds`（只读，别写，会同步到服务端） |

logcat 里的验收输出：

```
CONTACT row wxid=wxid_gmorsz6n6v8o21 | nick=A0三秒喇叭-四通易达快递 | remark= | alias=
```

> 换微信版本时：重新跑一遍 UiProbe（打开通讯录页即可），按同样的三步重新定位，
> 在 `Anchors848.kt` 旁边加一组新常量，按 `VersionGate.cacheKey` 路由。

## ✅ M2-B 状态：通讯录长按「加入分组」已跑通（2026-09-21）

进通讯录 → 长按任意好友 → 弹「加入分组」框 → 勾选已有分组 / 顺手新建 → 确定即写入。

实机日志：
```
LONGPRESS -> wxid_gmorsz6n6v8o21 | A0三秒喇叭-四通易达快递
PICKER shown for wxid_gmorsz6n6v8o21 (A0三秒喇叭-四通易达快递), groups=1
PICKER applied wxid_gmorsz6n6v8o21: 已更新：加 1 组 / 移 0 组
```
库核对：`groups=[(1,'TestG1')]`、`group_member=[(1,'wxid_gmorsz6n6v8o21',...)]`、反查 `groupsOf` 也通。

### ⚠️ 微信把 androidx 改过，标准套路全部失效（这条最值钱）

8.0.48 实测：

| 你以为能用 | 实际情况 |
|---|---|
| `RecyclerView.Adapter.bindViewHolder`（final，能 hook 全部 Adapter） | ❌ `androidx.recyclerview.widget.RecyclerView$Adapter` **类根本不存在**，hook 报 ClassNotFoundError |
| `getChildAdapterPosition(View)` | ❌ 不存在，反射调用抛 NoSuchMethod（被吞掉后表现为「永远 -1」） |
| `getChildViewHolder(View)` | ❌ 不存在 |
| `scrollToPosition(int)` | ❌ 不存在（M2-A 的滚动爬取其实是靠时间推移自然加载的，不是真滚动） |
| ViewHolder 里存数据 | ❌ adapter 的 ViewHolder 是 `ki4.m0`，位置访问器 `i()/j()/k()` 全返回 -1，且没有任何数据字段 |

继承链：`WxRecyclerView < X2CRecyclerView < androidx.recyclerview.widget.RecyclerView`（微信 fork 版）。

### 最终采用的反查方案（两级，先准后稳）

1. **准**：调 adapter 上「入参 View、返回非 void 非基本类型」的方法（8.0.48 是 `Z0(View):c3`）拿到 ViewHolder，BFS 挖 Contact
2. **稳**（当前实际生效的）：读条目里 `TextView` 的文本（就是用户看到的名字），回自己的 `friend_cache` 按「备注或昵称」反查

第 2 级只依赖显示名 + 自己的库，**完全不碰微信内部结构**，跨版本最稳。方法名一律按签名特征现找，不硬编码。

**已知边界**：重名好友（2245 个里有 28 组）会命中多个候选，此时宁可不弹框，日志打 `AMBIGUOUS`，绝不猜。

### 弹框的两个约束

- 必须用**宿主的 Activity 上下文**（模块自己那个 uid 的 Context 弹窗会 BadTokenException）→ `core/HostContext.kt`
- 全部**代码构造 View**，不打包 layout，避免资源 id 与宿主冲突 → `feature/GroupPicker.kt`

---

## ✅ M2-A 状态：好友全量入库已验证（2026-09-21）

进通讯录即可自动同步全部好友到 `friend_cache` 表。实机结果：

```
SYNC trigger adapter=ju2.s0
SYNC start mode=full(crawl)
CRAWL r=7 itemCount=2132->2249 collected=2247
SYNC collected=2247 in 6287ms
SYNC scan: contacts=2247 friends=2245 gh=0 chatroom=0 other=2
SYNC upsert=2247 db: 0 -> 2247 types=friend=2245, other=2
```

把库拉到电脑上核对（`.check/pull_db.py`）：

| 校验项 | 结果 |
|---|---|
| 总行数 | **2247**，与微信通讯录自己显示的「2245 个朋友」一致（+2 是 `weixin` / `filehelper`） |
| `rows == distinct wxid` | 2247 == 2247 —— **wxid 做主键成立** |
| 有名备注 | 1754 条 |
| 重名昵称 | 28 组 —— 印证了不能用昵称做主键 |

### 两种模式，自动切换

| 模式 | 触发条件 | 行为 |
|---|---|---|
| `full(crawl)` | 库里 < 200 条，或距上次全量 > 30 分钟 | 反复把列表滚到底逼微信加载，8 轮滚完（约 6.3 秒），滚完会回到顶部 |
| `visible-only` | 其余情况 | 静默收集当前已加载的几百条，66ms 完成，不打扰用户 |

**落库是 `upsert` 而不是「全量覆盖」**——这是踩过坑改的：通讯录一次只加载几百条，覆盖写会把上次收的全清掉。

### 为什么必须滚动爬取

通讯录是懒加载的：一次只有几百条 `Contact` 对象在内存里，全量 2245 个好友的昵称**只存在于已经渲染过的对象中**（`rcontact` 表是加密的，读不了）。所以只能滚动触发加载，边滚边收。

`ContactScanner` 是对象图 BFS（不硬编码 `adapter.data -> item.d` 这条路径，中间层改名也不怕），四重保护：IdentityHashMap 去重防环、节点/深度/超时三重限、View 不展开（一个 View 上百字段会吃光预算）、**集合先快照再遍历**（宿主的集合随时被主线程改，直接遍历会抛 `ConcurrentModificationException` 让整个扫描静默中断——这个坑实机撞过）。

---

## ✅ M0 状态：已在实机验证通过（2026-09-21）

设备 `467cf5bc`（ColorOS / KernelSU + LSPosed），实机实测微信为：

| 项 | 实机值 |
|---|---|
| versionName | **8.0.48**（不是原定的 8.0.72） |
| versionCode | **2588** |
| signature SHA-256 | `0fe4ff85c215918396dadc7cd8ce6963339af33d37751a56e54c7206b63a3c7c` |
| 渠道 | **GP 版**（含 `.plugin.fcm.WCFirebaseMessagingService` + Firebase + GMS，国内版没有这些） |
| 安装形态 | 单 APK（`dumpsys` 显示 `splits=[base]`，不是 AAB split） |

最后一行日志 `M0 boot completed.` 已出现，`gate ok: gp`、`db ready, existing groups=0` 均正常。

> ⚠️ 版本号与最初假设的 8.0.72 不符。「按 (渠道, versionName) 分路由」的设计本来就是为此准备的——
> `VersionGate.whitelist` 里现在有 8.0.48 和 8.0.72 两条，**锚点定位必须针对实际安装的版本做**。

### 三个双击即用的脚本

| 脚本 | 作用 |
|---|---|
| `install.bat` | 先检设备 → 编译 → adb install。**双击即可**（`build.bat install` 是命令行版，双击传不了参数） |
| `build.bat` | 只编译。命令行里可传 `install` / `--nopause` |
| `logcat.bat` | 清缓冲 → 强停微信 → 重启 → 只打印 `WXGroup` 的日志 |

三个脚本都是**纯 ASCII**（cmd 按 GBK 解析 .bat，中文注释会变乱码被当命令执行），结尾有 `pause` 不会闪退。

---

## 这个版本能做什么

M0 只做三件事，目的是**证明工具链通了**，不碰任何业务钩子：

1. 在微信主进程被加载，确认包名 + 进程 + 版本
2. 拿到宿主 Context，在本进程可写的目录里建成 `wxgroup.db`
3. 打印宿主的 APK 布局（含 split apk），确认你拉的 dex 没漏

它不会改变微信任何行为，理论上不可能导致崩溃。

---

## 编译前必做的事

当前包名：`com.v.wxgroup`

| 文件 | 位置 |
|---|---|
| `app/build.gradle.kts` | `namespace` 与 `applicationId` → 已设为 `com.v.wxgroup` |
| `app/src/main/assets/xposed_init` | 入口类全限定名 → `com.v.wxgroup.HookEntry` |
| `app/src/main/java/com/v/wxgroup/` | 目录，以及每个文件的 `package` 行 |

源码目录结构：

```
app/src/main/java/com/v/wxgroup/
├── HookEntry.kt          模块入口
├── core/                 VersionGate / Anchors848 / ContactSync / ContactBinder / HostContext / ChatOpener
├── data/                 GroupDb + PrefKeys + Group / GroupMember / Friend / BackupIO（M5）
├── feature/              GroupBar / ConvFilter / ConvMenu / GroupDetailPage / GroupPicker / GroupManager / MemberPage / BatchPicker
├── entry/                EntryReceiver（广播入口）
├── ui/                   UiKit（纯代码构造 View 的公共件）
├── probe/                UiProbe / MenuProbe / DexProbe / ChatListProbe / ChatOpenProbe
│                         ConvProbe / FilterProbe（后两个已完成使命，不再安装）
└── util/                 LogX
```

改完确认 `xposed_init` 里的类名 **与实际 HookEntry 的全限定名完全一致**，这是最常见的「装上了但 LSPosed 不认」的原因。

### 2. 环境要求

- JDK 17（**JDK 21 实测可用**，本机就是 21）
- Android SDK Platform **API 35**
- Android SDK Build Tools **35.0.0 或更高**
- Gradle 8.9

> 项目里已带 `gradlew`，也额外提供了 `build.bat`。
> **推荐双击 `build.bat`** —— 它会优先用本机已解压的 Gradle 8.9，
> 省掉 gradlew 首次运行时那 129MB 的下载。

### 3. 编译

```bat
build.bat                       :: 推荐，双击即可（clean + assembleDebug）
build.bat install               :: 编译完自动 adb install（手机要先连好）
gradlew.bat assembleDebug       :: 首次会自动下载 Gradle，约 129MB
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（约 1.9MB）

> `build.bat` 会自动找 adb：先试 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`，
> 找不到再试 PATH。找到了但没设备时只警告不报错。
> 还会自动探测 JAVA_HOME（`Program Files\Microsoft\jdk-*` / `Java\jdk-*`），
> 所以**双击运行也能拿到 Java**，不依赖 IDE 的环境变量。
> 脚本结尾有 `pause`，双击不会闪退；脚本化调用加 `--nopause` 跳过。

> ⚠️ **两个 Windows 批处理的坑，改 build.bat 前必读**：
> 1. 文件必须是**纯 ASCII**（英文）。cmd 用 GBK 解析 .bat，UTF-8 中文注释会被拆成乱码当命令执行，
>    表现为一堆 `'xxx' 不是内部或外部命令` 然后脚本直接退出（看起来像闪退）。
> 2. `%~dp0` **结尾带反斜杠**，`-p "%PROJECT_DIR%"` 会变成 `...\wxgroup\"`，
>    反斜杠把右引号转义掉，Gradle 收到的路径是 `...\wxgroup" clean assembleDebug`（报 project directory does not exist）。
>    必须先 `if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"`。

---

## 已在本机验证过的三个坑（换机器时照抄）

这台机器首次构建踩了三次，都已修进配置里，别再删掉：

| 现象 | 根因 | 修法（已固化） |
|---|---|---|
| 卡在 `Still waiting for package manifests to be fetched remotely`，最后 `SocketException: Connection reset` | AGP 想联网抓 SDK 组件清单，`dl.google.com` 的 repository 域名不可达 | `gradle.properties` 加 `android.builder.sdkDownload=false`，全部用本地已有组件 |
| `Failed to find Build Tools revision 34.0.0`（10 秒快速失败） | AGP 8.7.3 默认要 34.0.0，本机只装了 35.0.0/35.0.1/36.0.0 | `app/build.gradle.kts` 显式写 `buildToolsVersion = "35.0.0"` |
| `SDK location not found` | 没配 SDK 路径 | `local.properties` 写 `sdk.dir=C:\\Users\\lx\\AppData\\Local\\Android\\Sdk`（该文件已在 .gitignore，换机器要自己建） |

另外没有 `cmdline-tools`（即没有 `sdkmanager`），所以**不要指望 AGP 自动补装 SDK 组件**——
需要的组件必须手动准备齐全。

**adb 是有的**，在 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`，只是不在 PATH，
所以要写完整路径，或者用 `build.bat install`（它会自动定位）：

```bat
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" devices
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 安装与验证

1. `adb install app-debug.apk`
2. 打开 **LSPosed 管理器 → 设置 → 关闭「API 调用保护」**（新版本默认开启，会拦掉部分调用，改完重启）
3. 模块 → 勾选启用 → **作用域只勾 微信**
4. 强制停止微信并重新打开

看日志：

```bash
adb logcat | grep WXGroup
```

**期望输出**（顺序固定）：

```
WXGroup: I package loaded, process=com.tencent.mm
WXGroup: I host version=8.0.72 code=<versionCode>
WXGroup: I host signature=<sha256>
WXGroup: I gate ok: gp
WXGroup: I db ready, existing groups=0
WXGroup: I host apk count=N
WXGroup: I   [0] /data/app/.../base.apk
WXGroup: I   [1] /data/app/.../split_config.arm64_v8a.apk
...
WXGroup: I M0 boot completed.
```

LSPosed 自己的日志里也会有一份（写在它的日志文件里，`Bridge.log` 双写）。

### 判定标准

| 现象 | 结论 |
|---|---|
| 只出现 `skip process=xxx (not main)` | 正常，微信多进程，其他进程本就跳过 |
| 出现 `DISABLED: version xxx not whitelisted` | 版本不对，去 `VersionGate.whitelist` 加一条当前版本即可（已加 8.0.48） |
| `host apk count=1` | ✅ **正常**（实机已验证）。微信确实有多 split 的形态，但也可单 APK 安装（`dumpsys` 里 `splits=[base]`）；只要路径指向的是真实 base.apk 就没漏 dex。真要确认就跑 `dumpsys package com.tencent.mm` 看 `splits=` |
| 完全无日志 | Scope 没勾对，或 `xposed_init` 类名写错，或 LSPosed 未重启生效 |
| 微信闪退 | 先注释掉 manifest 里的 xposed 元数据排除宿主环境因素，再逐个排查钩子 |

---

## 拿到 `versionCode` 和 `signature` 之后

M0 跑通后日志里会打出这两个值，**把它们回填到 `VersionGate.whitelist`**，做二次校验：

```kotlin
Target(
    channel = Channel.GOOGLE_PLAY,
    versionName = "8.0.72",
    versionCode = 你的值L,
    signatureSha256 = "你的签名sha256",
)
```

微信升级铁定失效，届时：日志会打 `DISABLED`，模块自动停摆，**不会出现混乱行为**。

---

## ✅ M2-C 状态：分组管理页 + 批量勾选加人（2026-09-21）

Q2=b 那条做完。四个页面：

| 页面 | 文件 | 能力 |
|---|---|---|
| 分组管理页 | `feature/GroupManager.kt` | 新建 / 重命名 / 删除（成员关系一并清）/ 清空成员 / 进成员页 |
| 成员页 | `feature/MemberPage.kt` | 看组里有哪些人、点一行移出、跳批量加人 |
| 批量勾选页 | `feature/BatchPicker.kt` | 搜索 + 全选/反选/清空 + 2245 好友一次加完 |
| 公共件 | `ui/UiKit.kt` | dp / 定高 ListView / 输入框 / 确认框（纯代码构造 View） |

### 两条入口

1. **真实用户路径**：通讯录长按任意好友 → 分组框里的「**管理分组**」按钮
2. **广播**（验证 / 以后做通知栏入口用）：
   ```bash
   adb shell am broadcast -a com.v.wxgroup.action.OPEN_GROUPS -p com.tencent.mm
   adb shell am broadcast -a com.v.wxgroup.action.OPEN_GROUPS -p com.tencent.mm --el group_id 1
   ```

### 实机验收（`.check/adb_m2c.py`）

```
MGR create id=4 name=M2CBatch
MEM open group=4(0) name=M2CBatch
BATCH open group=4(M2CBatch) friends=2245 preselect=0 load=25ms
BATCH applied group=4: 新增 1 人 / 移出 0 人（8ms）        ← 搜索单个 wxid 后全选
BATCH applied group=4: 新增 2244 人 / 移出 0 人（64ms）    ← 无过滤全选
db: members=2247 distinct=2245
MGR rename id=4 -> M2CBatchX
MGR delete id=4 name=M2CBatchX
db after delete: groups=[(3,'123'), (1,'TestG1')] members=2   ← 别的组没被误删
```

### 三个踩过的坑

1. **2245 个 CheckBox 不能 `addView` 进 ScrollView**（会卡死/OOM）。必须 `ListView + BaseAdapter` 复用行，
   搜索过滤也只在内存里做，实测 2245 条加载 18~25ms。
2. **ListView 里的 CheckBox 要 `isFocusable=false` + `isClickable=false`**，
   否则它吃掉触摸事件，`onItemClick` 永远不触发（经典「点了没反应」）。
3. **AlertDialog 内容区是 WRAP_CONTENT，LinearLayout 的 `weight` 在这里不生效**（会算出 0 高度、列表不显示）。
   列表高度必须按屏幕比例算成像素写死 → `UiKit.listView(ctx, ratio)`。

### 广播入口的两个硬约束

- 必须**动态注册**（manifest 静态注册在 Android 8+ 收不到隐式广播）
- **Android 13+ 必须带 `RECEIVER_EXPORTED`**，否则默认不导出，`adb shell`（uid 2000）发的广播会被 AMS 丢掉，
  表现为「发了完全没反应」。代码里写字面量 `2` 而不是 `Context.RECEIVER_EXPORTED`——那个常量 API 33 才有，
  在低版本上读它会抛 `NoSuchFieldError`。
- 微信在后台时没有前台 Activity，直接弹 Dialog 会 `BadTokenException` → 先把微信拉回前台，延迟 1.5s 再弹。

---

## ✅ M4-B 状态：长按会话 → 菜单里多一项「加入分组 / 移出分组」（2026-09-23）

在聊天列表里长按任意会话，微信自己的菜单最上面多出我们的一条：

```
加入分组      ← 我们插进去的（未在任何分组时）
标为未读
取消置顶
不显示该聊天
删除该聊天
```

点它 → 弹出分组选择框（勾选即加入 / 取消勾选即移出，和其他入口共用 `GroupPicker`）→ 确定写入。

实现：`feature/ConvMenu.kt`，验证：`.check/m4_menu.py`（**8/8**）。

### 关键结论：这个菜单 hook 不到，只能「先拿窗口再塞」

菜单是个 PopupWindow，但**没有任何可以挂 hook 的入口**。实测把这五层全挂上，菜单照弹、一个都没命中：

| 你以为的入口 | 结果 |
|---|---|
| `ListPopupWindow.show()` | ❌ 挂上了，不触发 |
| `PopupWindow.showAsDropDown(View)` / `(View,int,int)` / `(View,int,int,int)` | ❌ 同上 |
| `PopupWindow.showAtLocation(View,int,int,int)` | ❌ 同上 |
| `Dialog.show()` | ❌ 同上（菜单不是 Dialog） |
| `WindowManagerImpl.addView(View,LayoutParams)` / `WindowManagerGlobal.addView(...)` | ❌ 同上 |

于是反过来做，整条链路只依赖「一个长按回调 + 一个窗口表 + 一句菜单文案」：

1. `View.performLongClick` 是**所有长按的必经之路**（不依赖任何微信类名）→ 从被长按的行往上找 `cr0` 父节点，读它的 tag 拿 wxid
2. 延迟 300ms，反射 **`WindowManagerGlobal.mViews`** 枚举当前所有窗口，认「里面有一行写着『删除该聊天』之类文本的非主窗口」就是菜单
3. 把菜单里那个 ListView **包进一层竖向 LinearLayout**，我们的条目放在列表上方

### 为什么不能往菜单的 adapter 里插（这条最重要）

菜单条目容器是个 **AdapterView**（直接 `addView` 抛 `UnsupportedOperationException`），条目由 adapter 提供。
往 adapter 里插数据看起来更简单，但会**打乱微信自己的 position 索引**——
微信是按位置分发动作的（position 0 = 标为未读，3 = 删除该聊天），插一条进去就可能让
「点标为未读」变成「点删除该聊天」。所以**一行都不许动 adapter**，只在列表外面套一层：
微信那边完全无感，索引映射原样保持。

套层之后 ListView 变成包装容器的子 View，坐标平移由 LinearLayout 标准分发完成，
触摸命中不需要像 M3 早期那样手动重映射。

### 三个坑

1. **菜单是异步弹出的**（长按 → 震动 → 弹窗），必须延迟枚举；同步找一定是空。
2. **菜单窗口尺寸是弹之前按内容算死的**，加一行之后窗口不会自己长高，
   新条目会被裁掉 / 把列表挤矮（等于裁掉最后一条）。实测窗口 `601x884`，
   要手动把 `WindowManager.LayoutParams.height` 加上条目高度（884 → 1086）。
3. **View 复用残留会骗人**。会话行里真正的 TextView 只有时间，会话标题是个**自绘 View**
   （实测 id `l7a`，class 显示为 `NoMeasuredTextView`）；而 tag 上还留着**上一次绑定**的
   TextView——实测抓到过「折叠置顶聊天」（属于另一个折叠行），字号还比标题大。
   取名所以改成：先查自己的 `friend_cache` → 再按**无障碍文本**取（和 uiautomator 看到的同源）
   并滤掉「…置顶聊天 / 该聊天」这类微信自己的行文案 → 最后退回 wxid。

### 锚点（8.0.48 实测，已写进 ConvMenu 注释）

| 锚点 | 值 | 怎么确认的 |
|---|---|---|
| 会话行 | `ConversationFolderItemView`，id `cr0` | uiautomator dump |
| wxid | 行 tag（`…conversation.n3`）的字段 `l` | **与会话对象 `r4.field_username` 交叉一致** |
| 会话对象 | `com.tencent.mm.storage.r4` | `list.adapter.getItem(pos)` |
| 菜单窗口 | `android.widget.PopupWindow$PopupDecorView` | 反射 `WindowManagerGlobal.mViews` |
| 菜单条目容器 | `j3`（AdapterView） | 从菜单文本节点往上找 |
| 会话标题 | id `l7a`（自绘/NoMeasuredTextView） | 无障碍文本 |

> 定位过程用的临时探针 `probe/ConvProbe.kt` 已完成使命，**不再安装**（锚点结论都在上面了），
> 微信升版本时可以再挂上重新跑一遍。

---

## ✅ M4-C 状态：按分组过滤聊天列表（2026-09-24）

点聊天列表顶部的某个分组行 = **只看这个分组的会话**（再点一次取消）。筛选态下：

- 分组行区域最上面多一条 **「✕ 显示全部会话」**，一键退出
- 被选中的分组行用**品牌绿 + ✓** 标出来
- 筛选状态**持久化**（重启微信仍在），所以必须留着显式退出入口，否则用户会以为会话被删了

实现：`feature/ConvFilter.kt`；验证：`.check/m4_filter.py`（**14/14**）。

### 列表结构（`probe/FilterProbe.kt` 实测）

```
ConversationListView
└── HeaderViewListAdapter            headers=15（14 个微信的 + 我们的分组行） footers=1
    └── mAdapter = com.tencent.mm.ui.conversation.p3     ← 全部会话（实测 2924 条）
        └── getItem() = com.tencent.mm.storage.r4        ← field_username 就是 wxid
```

做法是**只换 `HeaderViewListAdapter.mAdapter` 这一层**，换成我们的过滤包装
（`count/getItem/getView/getItemViewType` 全按过滤后的索引转发）；
微信自己的 adapter、数据源、header/footer 一个都不动。
没筛选时包装里的映射表是 `0..n-1` 恒等表，转发结果与微信原样完全一致。

### 必须做位置重映射（这条是本次最关键的发现）

微信的点击监听是 `com.tencent.mm.ui.conversation.o2`，只实现 `onItemClick`，
而且**握着 ConversationListView** —— 它是用位置去取会话的。
过滤之后位置空间变了，不重映射就会 **点 A 开 B**（比不生效更糟）。

所以把所有点击/长按监听包一层，把位置换算回**未过滤时的位置**再交给微信。
实测证据（`.check/m4_filter_run6.txt`）：

```
筛选可见 = [wx__c_18c13d84792, wx__c_18c13d85c84]     head=[1, 2]   ← 过滤索引 -> 微信 adapter 原始索引
点击第 2 行 -> FILTER click pos=16 -> 17              （15 header + inner 2 = 17）
打开的单聊 = wx__c_18c13d85c84                        ← 正是该行显示的那条
```

不重映射的话微信会把 `pos=16` 解成 inner 1（另一个成员），就会开错聊天。

### 两个坑

1. **不能用 `Class.forName("com.tencent.mm.storage.r4")` 取 wxid 字段** ——
   那走的是**模块自己的类加载器**，根本看不到微信的类，`getDeclaredField` 直接抛异常，
   过滤会**静默退化成「全部显示」**（日志 `shown=2943/2943` 一度把人骗过去）。
   正确做法：拿一个真实的会话对象，从它的**运行时类**上找 `field_username`。
2. **筛选改变了列表条数，adapter 的数据观察者要跟着挪**：ListView 的 `mDataSetObserver`
   原本挂在微信的 adapter 上，换成我们的包装后必须把它挪过来，否则我们
   `notifyDataSetChanged()` 时列表不刷新（`mItemCount` 不更新）。

### 两条语义（都想错过一次）

| 情况 | 正确行为 | 为什么 |
|---|---|---|
| 筛的是**空分组** | **0 条会话** | 一开始把「成员为空」当成「不筛选」，结果筛空分组时列表照旧全显示 —— 用户会以为筛选坏了。只有**读成员表失败**才退化成「不筛选」（宁可不过滤，也不能把会话列表清空） |
| 筛选态下**改了分组成员** | 1.5s 内自动跟上 | 写入点有 5 个（分组选择框 / 成员页 / 批量页 / 分组管理 …），靠各写入点通知必然漏。改成在布局回调里**节流核对**一次成员表，回到聊天列表就自动同步 |

### 交互

| 操作 | 行为 |
|---|---|
| 点分组行（列表模式） | 只看该组会话；再点一次取消 |
| 点「✕ 显示全部会话」 | 退出筛选 |
| 长按分组行 | 菜单最上面多「显示全部会话」（筛选态时），二级分组页在「查看成员」里 |
| 横条模式 | 点胶囊仍是开分组详情页；筛选态下「⋯」菜单里有「显示全部会话」 |
| 广播控制（自动化用） | `adb shell am broadcast -a com.v.wxgroup.action.FILTER -p com.tencent.mm --el group_id 1`（`0` = 显示全部） |

> 筛选只在**分组行模式（默认）**有点击入口；横条模式保留原交互，只补了退出入口。

---

## ✅ M5 状态：分组数据备份与恢复（2026-09-24）

分组数据落在 `/data/data/com.tencent.mm/databases/wxgroup.db`，**卸载模块或换手机就没了**。
M5 把它导出成 JSON 文件，也能从文件恢复回来。

### 交互

| 操作 | 行为 |
|---|---|
| 分组管理页 → 顶部「备份与恢复」 | 打开备份页（列出已有备份，显示各自的组数/人数/时间） |
| 「导出备份」 | 写成 `wxgroup-backup-<时间戳>.json`，Toast 报告组数/人数 |
| 点列表里某个备份 | 弹「合并 / 覆盖」；**覆盖还要再确认一次** |
| 命令行（自动化用） | `adb shell am broadcast -a com.v.wxgroup.action.BACKUP -p com.tencent.mm --es op export` |
| 命令行恢复 | 同上，`--es op restore --es file <绝对路径>`，加 `--ez replace true` 才是覆盖 |

### 三条设计约束

1. **格式自描述**：文件带 `format: "wxgroup-backup"` + `version`，扫描时靠它认文件，
   不会把目录下无关的 json 也列成备份。
2. **按分组名匹配，不按 id**：分组 id 是自增主键，换机后根本对不上；
   分组名有 UNIQUE 约束，是天然的业务主键。成员仍以 wxid 为键。
3. **默认合并、覆盖要显式选**：合并只补进缺失的成员，现有数据一条不动；
   覆盖会先删光所有分组再按备份重建 —— 破坏性的，必须二次确认。

### 两个坑

1. **导出目录要探测，不能写死**：Android 11+ 上能不能写 `/sdcard/Documents`
   取决于宿主有没有 MANAGE_EXTERNAL_STORAGE（微信各版本不一样）。
   所以按「Documents → Download → 微信外部私有目录 → 微信内部私有目录」依次
   **试写一个探针文件**，第一个成功的就用，并记进 `kv` 表的 `backup_dir`（下次直接用）。
   后两个一定可写，所以导出永远失败不了，只是用户得花点工夫找文件。
2. **Gson 不走构造器**：反序列化是 Unsafe 分配对象，Kotlin 的非空默认值（`= ""`）不生效，
   文件里缺字段时该字段就是 null。所以模型字段一律声明成可空，读出来统一
   `hydrate()` 清洗一遍再交给业务代码（见 `data/BackupIO.kt`）。

### 验证（`.check/m5_backup.py` 18/18，2026-09-24）

造数据（G1=3 人、G2=1 人、G3=空）→ 导出 → 取回本地校验 JSON → **破坏数据**
（清光成员 + 删掉 G2）→ 合并恢复 → 加脏数据 G9 → 覆盖恢复 → UI 入口 → 异常扫描：

- 导出：文件落盘、`format/version` 自描述、`{'G1':3,'G2':1,'G3':0}` 与库一致
- 合并：`groupsAdded=1 membersAdded=4`，G1 成员回来、被删的 G2 重建、空组 G3 仍在
- 覆盖：G9 被清掉，恢复后与备份**完全一致**
- UI：管理页顶部入口 → 备份页列表/导出按钮都正常

> 跑这条脚本的两个环境坑：**手机锁屏后 `am start` 拉不起微信**，广播没人接收，
> 表现为「导出全过、恢复全挂」，必须先解锁；`adb logcat -d` 在设备忙时会超时，
> 用 `adb shell logcat -d`（设备端过滤完再回传）。

---

## ✅ M3 状态：分组行常驻聊天页顶部（2026-09-21，2026-09-22 定稿）

打开微信就是聊天页，顶部常驻分组行：点组名 → 打开独立分组页（成员列表，点成员直接进单聊）。

### 定稿形态（**优先看这里**，下面那段是早期方案，已被取代）

分组行最终是**真·列表头**（插在微信自己的通知条之下、会话之上），长得和原生会话行一模一样：

```
MainUIView
└── ConversationListView
    └── HeaderViewListAdapter
        ├── 微信自己的 14 个 header（通知条「网页版传输助手已打开」等）
        ├── 我们的分组行（G1–G4）← 反射追加 FixedViewInfo 进来的
        └── 会话行（原生）
```

| 项 | 定稿做法 | 为什么 |
|---|---|---|
| 注入方式 | 反射往 `HeaderViewListAdapter.mHeaderViewInfos` 追加 `FixedViewInfo` | `addHeaderView` 在 adapter 定型后无效（抛 `IllegalStateException`） |
| 行样式 | 复合成员头像 48dp + 组名 + 右侧时间，行高 72dp（260px，原生会话行 259px） | 与原生会话行同款 |
| 行背景 | **运行时从第一个可见会话行（cr0）采样背景 Drawable**，克隆一份贴上 | 自己画底色会和微信的动态背景/深色模式打架（从小程序返回时整块发黑，踩过） |
| 折叠联动 | 扫列表文字判定微信置顶区折叠态，联动做 340ms 高度动画收起 | 用户要求「点折叠置顶聊天，分组行一起折叠」 |
| 折叠后 | 高度设 **1px**（不是 GONE / 0） | `ListView` 对 header 按 `lp.height > 0 ? EXACTLY : UNSPECIFIED` 测，设 0 或 GONE 会退回自然高度 1040px，**留一大块空白** |

> 下面这段「叠层 + padding/translationY + canvas 平移 + 触摸重映射」是早期方案，
> 现在只剩横条模式（`bar`）还在用其中一部分；列表模式请按上面的定稿形态理解。

### 交互（已拍板）

| 项 | 决定 |
|---|---|
| 显示样式 | **chip（横向胶囊）/ row（分组行）两种，可随时切**，「⋯」里切换 |
| 展开方式 | **统一走独立分组页**，不在列表里内联展开（避免 ListView 嵌套） |
| 点成员 | **直接打开和他的单聊**；长按才弹「移出本组 / 加入其他分组」 |
| 收起 | 标题行常驻，收起后只剩一行「我的分组 + 展开」，状态存库 |

### 注入方式：叠在列表父容器上 + padding 下推

```
MainUIView
├── ConversationListView   ← 会话列表，paddingTop += 分组条高度，clipToPadding=true
└── GroupBar               ← 我们加的，translationY = 列表内容顶部
```

**为什么不用 `addHeaderView`**：列表上已经有微信自己的 14 个 header，
其中一大半是 0 高度占位（搜索框、折叠会话之类）。实测加进去的 header 量不出高度（`BAR probe` 打出来是 `0x0`），
界面上完全看不见。叠加 + padding 的行为是确定的。

**位置不能写死**：微信是沉浸式，列表顶到屏幕最上沿，上面压着状态栏 + ActionBar。
分组条放错位置要么盖住状态栏，要么被 ActionBar 压住。
现在的做法是注入后等一帧，**先把 padding 复位再量**列表里第一条会话的 top，
拿它当偏移，再叠一个「状态栏 + 64dp」的下限兜底。

| 实测坐标（1440x3200） | y |
|---|---|
| ActionBar「微信」 | 205 |
| 分组条 | 374 ~ 631 |
| 第一条会话 | 643 |

### 实机验收

```
BAR inject list=ConversationListView parent=MainUIView paddingTop=0
BAR probe 1440x257 raw=333 offset=374 padTop=0
BAR refresh groups=2 style=row  collapsed=0     ← 广播切样式
BAR refresh groups=2 style=chip collapsed=1     ← 收起
BAR refresh groups=2 style=chip collapsed=0     ← 展开
DETAIL open group=1 name=TestG1 members=1       ← 点组名开分组页
CHAT open wxid=wxid_gmorsz6n6v8o21              ← 点成员跳单聊
```

端到端 9 项 + 补验 4 项全过（`.check/adb_m3_e2e.py`、`.check/m3_click.py`）：
注入 / UI 可见 / 切 row / 收起 / 展开 / 切回 chip / 开分组页 / 成员行 / 点成员跳聊 / 真实点击组名 / 关闭后刷新。

### 单聊怎么拉起来

模块没有 Activity，只能显式启动宿主的：

```
com.tencent.mm.ui.chatting.ChattingUI + extra Chat_User=<wxid> (+ Chat_Mode=1)
```

类名是 `am start` 试出来的：**`com.tencent.mm.chatting.ui.ChattingUI` 不存在**（报 class does not exist），
别照抄网上那些旧包名。→ `core/ChatOpener.kt`

### 广播入口（调试用）

```bash
adb shell am broadcast -a com.v.wxgroup.action.BAR -p com.tencent.mm --es style row
adb shell am broadcast -a com.v.wxgroup.action.BAR -p com.tencent.mm --ei collapse 1
adb shell am broadcast -a com.v.wxgroup.action.OPEN_GROUPS -p com.tencent.mm --el group_id 1
```

> 界面偏好（样式、收起）存在 `wxgroup.db` 的 `kv` 表里，而不是 SharedPreferences
> —— SP 文件在宿主私有目录，adb pull 不出来，出问题只能靠猜。

### 下一步：M4 收尾 / M5

- **分组页群发消息**：M2-C 就预留了入口，还没做
- **M5 备份与恢复**：分组数据导出/导入 JSON（现在纯本地库，卸载即清空）

---

## 两点必须知道

- **DexKit 是 LGPL-3.0**（`org.luckypray:dexkit` 的 Core 部分）。自用不受影响；**一旦分发 APK，许可证会传染**。如果将来只想闭源自用/分享，要么换掉 DexKit，要么开源。
- 依赖已声明但目前**零调用 DexKit**：`libdexkit.so` 打在模块 APK 里，而代码跑在微信进程，宿主 native 库目录里没有它；`createPackageContext` 又被 Android 11+ 包可见性挡住（直接 `NameNotFoundException`）。不影响进度——所有定位都是纯反射做的。留到将来做「跨版本自动重新定位」时再解决。
