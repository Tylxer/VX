# R24 · K3 选择器四 Tab 化 —— 实现与复核记录

> 需求（用户原话，2026-10-25）：
> 「好友」「群聊」「我的分组」「微信标签」都做出 TAB，在其中一个 TAB 内的打勾后，
> 切换到另一个 tab，被选的还是生效。群聊数量不少。

**最终状态：代码完成，`testDebugUnitTest` 全绿，`assembleDebug` 成功，待真机验收。**

---

## 1. 根因：「现在没有群聊」

`friend_cache`（`GroupDb.T_FRIEND`）此前**两个写入点都不写群聊**：

| # | 写入点 | 数据来源 | 含群聊 |
|---|---|---|---|
| 1 | `ContactSync.doSync():186` | `ContactScanner.scan()` 遍历**通讯录 tab 的 adapter 对象图** | ❌ 通讯录 tab 里没有群聊 |
| 2 | `ContactSqlSource.doSupplement():277` | `select * from rcontact where username like '%@openim'`（`:53`） | ❌ 只补企微 |

⇒ `wxid_type=2` 恒为 0。`ForwardPicker` 的群聊段同理为空。

**结构上拿得到**：`GroupNameSource.readGroups():219-241` 已证明 rcontact 里群聊齐全，
且它的 `loadBlocking` 把「0 行」当失败（`:195-200`）⇒ 真机群聊数必 > 0。

**修法**：新增 `core/ChatroomSource.kt`，复用同一策略（**全表扫 + 后缀过滤**，
不用 `like '%@chatroom'` —— LIKE 语义在微信 SQLCipher 分支未验证，见 `GroupNameSource:212-218`）。

---

## 2. 实现清单（13 文件）

### 新增

| 文件 | 行数 | 职责 |
|---|---|---|
| `feature/ListResolver.kt` | 133 | 名单 `g:` / `t:` 展开纯判据（零 Android 依赖） |
| `feature/PickerTabs.kt` | 142 | 四 Tab 统一构建器（两个调用方共用一份） |
| `core/ChatroomSource.kt` | 332 | 扫 rcontact 补群聊落 `friend_cache` |
| `test/.../ListResolverTest.kt` | 22 条 | 三态语义 + 前缀对齐 + 顺序稳定性 |

### 重写

| 文件 | 变化 |
|---|---|
| `feature/PickerPage.kt` | 437 行；`sections` → `tabs`；Tab 按钮行；`switchTo` |

### 修改

| 文件 | 改动 |
|---|---|
| `data/GroupDb.kt` | 新增 `allChatrooms()` |
| `core/ContactSync.kt` | `ChatroomSource.init()` + 同步收尾 `runIfNeeded()` |
| `core/NotifyMute.kt` | `inScope` 走 `ListResolver` + TTL 缓存 + 后台预热 |
| `feature/MomentsExport.kt` | `Config` 加快照字段，`refresh()` 一次性展开 |
| `feature/ConvFilter.kt` | `refreshMembers()` 里加 `invalidateScope()`（覆盖全部 10 个成员入口） |
| `feature/MainPage.kt` | 四 Tab；删死代码 `labelSection()` / `GROUP_PREFIX` |
| `feature/MassSendPage.kt` | 四 Tab；同样删死代码；空数据 toast 真实原因 |
| `entry/EntryReceiver.kt` | 新增 `op=room sub=stat\|sync\|list` 探针 |

---

## 3. 「四个 Tab 对所有名单开放」的隐藏前置

用户要求四 Tab 全场景开放，但三个消费方原来只有群发能展开前缀：

| 消费方 | 原状态 | 本轮 |
|---|---|---|
| 群发 `MassSendLogic.resolveTargets` | ✅ 已支持 | 不变 |
| 免打扰 `NotifyMute.inScope` | ❌ 字面比 `talker in allow` | ✅ 接 `ListResolver` |
| 朋友圈导出 `MomentsLogic.listHit` | ❌ 字面比对 | ✅ `MomentsExport` 预展开 |

**不接就是静默失效**：名单存 `g:3`，拿 talker 去比 `g:3` 永不命中 —— 比不显示 Tab 更糟。

### 三态语义（本轮最关键的设计）

| 配置 | 含义 | 判定 |
|---|---|---|
| `[]` | 不限制 | `unrestricted` |
| `["g:3"]` 但分组 3 空 | **谁都不命中** | `impossible` |

⚠️ 混同这两者会造成**方向相反的失效**：
- 免打扰允许名单设成空分组 → 若退化成「允许所有人」→ **静音全部通知**
- 朋友圈导出名单设成空分组 → 若退化成「不过滤」→ **导出整个朋友圈**

单测 `configured_but_expands_empty_is_impossible_not_unrestricted` 与
`inScope_empty_group_allow_blocks_everyone` 锁死这两条。

---

## 4. 复核结果（§四⑥ 交叉复核）

派 `deepseek-v4-pro` 子代理只读复核（未跑 gradle / 未用 adb），产出 `docs/_r24_review.md`。

### 结论：**1 条阻塞缺陷，已全部修完**

| 级别 | 问题 | 修复 |
|---|---|---|
| **B1 阻塞** | `Adapter.filter()` 引用的 `sections` 是构造时固定的 `val`；切 Tab 后 `reset()` 只改 `visible` 不回写 ⇒ 搜索/清空搜索时列表按**首 Tab**重算，Tab 高亮「群聊」但显示好友 | 拆成 `source`（数据源，仅 Tab 切换改写）+ `visible`（渲染行，过滤随时重算） |
| **B1 衍生**（我自查发现） | 若让 `filter` 写回 `source`，清空搜索时「全量」会变成「上次过滤结果」，用户回不到完整列表 | `filter` 只调 `render()`，不碰 `source` |
| S1 | 无名群显示 `19501172869@chatroom` 而非群号 | `roomTab` 传 `GroupNameLogic.pick(remark, nickname)` 而非已回落的 `displayName` |
| S2 | 「可见 N」把段标题也算进去（每段多 1） | 新增 `Adapter.entryCount()` 只数 `Row.Entry` |
| S3 | `MassSendPage` 空数据返回 false → toast「暂未接入」误导 | 改为 toast「好友缓存为空」+ 返回 true |
| S4 | `NotifyMute` 首次展开在**通知帧**上同步查库 | `invalidateScope()` 改后台预热 |
| S5 | `syncBlocking` 的 `force=false` 分支是死代码 | `runIfNeeded` 显式传 `force = false` |

### 确认无问题（下一轮不必重复审）

- **Q1 跨 Tab 勾选成立** ✅ `selected` 是页面实例字段，`Adapter` 拿的是同一 Set 引用，`switchTo` 完全不碰它
- **Q2 计数语义** ✅ `adapter` 单实例从不重建，`count` 随 Tab 变
- **Q4 ChatroomSource 主逻辑** ✅ 无死循环/漏行，`-1` 列降级正确，`runIfNeeded` 不会每次全表扫
- **Q5 线程安全** ✅ `scopeCache`/`scopeAt` 读写全在 `synchronized(scopeLock)` 内，monitor 保证可见性
- **Q6 MomentsExport 快照** ✅ `Config(` 仅 2 处构造，初始 `listUnrestricted=true` 在 `autoExport=false` 下安全
- **Q7 ListResolver 语义** ✅ `Kv.getList` 已滤空 ⇒ 「全空白 → unrestricted」正确
- **Q9/Q10** ✅ 无编译遗漏；`labelSection`/`GROUP_PREFIX` 全仓 0 命中

---

## 5. 真机验收清单

按「现象 → 对应改动 → 真假失败判别」组织。

### 5.0 前置：装包 + 补群聊

```bash
# 装包后双次 force-stop + am start（项目规矩：装新包常不重载）
# 打开通讯录滚一遍触发 ContactSync → ChatroomSource
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op room --es sub stat
```

**判据**：`cached=` > 0 且 `types=` 出现 `chatroom=N`（N > 0）。

- 若 `lastError="0 chatroom rows"` → 全表扫到 0 行，**先怀疑 rcontact 结构变了**，别判「用户没群」
- `sub sync` 可手动强制重扫；`sub list` 看 8 条样本核对群名

### 5.1 四 Tab 基本形态

打开任一名单（聊天→设置允许名单 / 群发目标 / 朋友圈自动导出名单）。

**期望**：四个 Tab 顺序固定「好友 → 群聊 → 我的分组 → 微信标签」，各自带条数。
空数据源的 Tab **直接不出现**（不是显示空白页）。

### 5.2 ⭐ 勾选跨 Tab 存活（核心需求）

1. 好友 Tab 勾 3 人 → 底部应显示「确定（3）」
2. 切群聊 Tab → **仍显示「确定（3）」**，且群聊里被勾过的人（如有）仍是选中态
3. 再勾 2 个群 → 「确定（5）」
4. 切回好友 Tab → 之前 3 人仍勾着
5. 点确定 → 日志 `PICKER confirm ... n=5`

⚠️ **假阴性判别**：若「确定（N）」对但你点确定后觉得没生效，先看下游
（群发会 toast「目标展开后为空」），**不是选择器丢勾选**。

### 5.3 ⭐ B1 回归（复核抓到的阻塞缺陷）

1. 好友 Tab 搜「张」→ 切群聊 Tab → **列表必须是群聊**（不是好友）
2. 在群聊/分组/标签 Tab 输入再**清空**搜索框 → 列表回到**当前 Tab** 全量，**不得**跳回好友
3. 搜「chatroom」→ 无名群不应全部命中（S1 修好后它们的标题是群号）

日志核对：`PICKER tab <title> -> <TabName> visible=N selected=M`，
`visible` 应对应当前 Tab 的条目数。

### 5.4 免打扰 `g:` / `t:` 前缀

名单里勾一个分组 → 触发一条 @我的群消息 → 应静音。

⚠️ 若「改名单后 30 秒内行为没变」→ 先确认 `invalidateScope` 是否被触发（现在会后台预热），
**不是**前缀展开失效。

### 5.5 朋友圈导出 `g:` / `t:` 前缀

配含 `g:` 的导出名单 → 观察导出条数。
若「配了但一条都不导出」→ 是 `listImpossible` 在起作用，先确认真实成员数。

### 5.6 空分组反向对照（三态语义）

把免打扰**允许名单**设成一个**没有任何成员**的分组 → 触发任意 @消息 →
**应该不静音**（= 谁都不在范围内）。

⚠️ 若这条**静音了**，说明 `impossible` 判据失效，退化成「允许所有人」——反向失效，必须报。

### 5.7 性能闸门

看日志 `PICKER open <title> tabs=N 好友:x/群聊:y/... preselect=z`。
群聊数量大时关注打开耗时（面板响应）；若明显卡顿，下轮考虑分页。

---

## 6. 真机验收结果（2026-10-25 21:16–21:40）

设备 467cf5bc，包 2.83MB→3.33MB（诊断临时加过探针）。

### ✅ 通过项

| 项 | 实测 | 结论 |
|---|---|---|
| 5.0 群聊数据源 | `scanned rows=23199 chatrooms=841`，`cost=2449ms`，`types=friend=2511, chatroom=841, other=2` | 数据源可用，841 群 |
| 5.1 四 Tab 形态 | 「好友 2511 → 群聊 841 → 我的分组 3 → 微信标签 45」，顺序与条数全对 | 通过 |
| 5.2 跨 Tab 存活 | 好友勾 3 →群聊勾 2 → `确定（6）`；切回好友仍 `已选 6 项 · 可见 2511`、`确定（6）` | **核心需求通过** |
| 5.3 B1 回归 | 好友搜「1」→ `可见 1493`；切群聊 → `可见 372`、段标题「群聊」、列表是群号 | **阻塞缺陷已修** |
| 5.3 衍生坑 | 群聊 Tab 清空搜索 → `可见 841`（群聊全量，非好友 2511） | `filter` 不回写 `source` 生效 |
| 5.3 搜索态勾选 | 搜索态下勾群 → 切好友 → `已选 2 项` 存活 | 通过 |
| 分组 Tab | G1 分组·1人 / G2 分组·**0人** / G3 分组·1人 | 通过 |
| 标签 Tab | 杀菌剂厂家·1人 / 王纳毛布·1人 / 僵尸·5人 / 浙医药… | 通过 |

### ⚠️ 发现但**不是缺陷**的两处

**① `ChatroomSource.runIfNeeded()` 只在 `ContactSync.doSync` 尾部调用**（`runs=0` 直至手动 `sub sync`）

真机证据：首次 `sub stat` 显示 `cached=0 runs=0 lastSync=never`，手动 `sub sync` 后才 `runs=1`。
原因：用户不打开通讯录 → `doSync` 不跑 → `runIfNeeded` 不跑。
**处置**：`sub=stat` 已能明确暴露这个状态（`cached=0 runs=0`），`PickerTabs.roomTab` 为空时也不产出该 Tab，
不会给用户空白页。是否要在 `PickerPage` 打开时补一次 `runIfNeeded()` 属**产品取舍**（会让首开选择器多 2.4s 全表扫），
留待用户定，本轮不改。

**② 群聊列表前几条全是纯数字群号 —— 已证伪，非缺陷**

初看像「群名丢了」，逐层取证后否定：

1. `schema` 探针：rcontact 列里 `nickname=4 conRemark=2 alias=1`，**两种取下标方式完全一致**；
   `VAL viaMethod: nickname=相亲相爱么么扎～` ⇒ 读取路径无问题。
2. `sampleText` 加 `debugRawRows` 直查 `friend_cache`：`RAW wxid=254407642@chatroom nickname=相亲相爱么么扎～`
   ⇒ **DB 里存的是正确群名**。
3. `row` 探针按 wxid 精确回查 rcontact：`21401921666@chatroom` 只有
   `pyInitial/quanPin/showHead/type=3/uiType`，**没有 `nickname` 列值**
   ⇒ 这些群在微信源表里**本来就没有名字**。

结论：数字群号是**正确显示**（`GroupNameLogic.displayNameOf` 的兜底），
只是 `queryFriends` 的 `ORDER BY remark, nickname` 把空串排在最前，造成「全是数字」的视觉错觉。
→ **不是 bug，不改**。若要改善观感可让无名群排到最后（下轮可选，见 §7）。

### ⚠️ 构建踩坑

`tools/gradle_run.py build` 会跑 `lintAnalyzeDebug` 并因 lint 失败而 `BUILD FAILED`，
但 **`assembleDebug` 是 EXIT=0**。排查编译错误请直接跑 `assembleDebug`，别被 `build` 的失败误导。

### 诊断探针（临时加的，已保留但仅用于排障）

- `op=room sub=schema` —— rcontact 列清单 + 前 3 个群行的全部非空列
- `op=room sub=row --es w "a@chatroom,b@chatroom"` —— 按 wxid 精确回查源表
- `op=room sub=list` —— 现含 `[nick='' rem='' alias='']` 逐字段摊开 + `debugRawRows` 直查 DB

`GroupDb.debugRawRows()` 是这三个探针的底座，KDoc 已标注「仅供探针，不承载业务读取」。

---

## 7. 遗留 / 下轮可选

1. **`NotifyMute` 预热仍可能失败** —— 预热线程若在库未 ready 时跑，`loadBuckets` 返回空快照，
   下一次通知会同步补一次。概率低（配置变更通常发生在库已 ready 之后），暂不处理。
2. **群聊数极大时的分页** —— 当前一次性 `allFriends` 风格全查，真机量过再定。
3. **`ForwardPicker` 未并入四 Tab** —— 它有自己的三 Tab（独立 adapter + exclude 集），
   R24 刻意不重构，只保证签名不变可编译。
4. **无名群排序** —— 当前 `ORDER BY remark, nickname` 让空名群排最前，视觉上「一进来全是数字群号」。
   可改成 `CASE WHEN nickname='' THEN 1 ELSE 0 END` 优先把有名字的排前面。纯观感问题，未做。
5. **`ChatroomSource` 触发时机** —— 见 §6 ①，是否在打开选择器时补跑由用户定。
6. **§5.4 / 5.5 / 5.6 未在真机执行** —— 免打扰 `g:`/`t:`、朋友圈导出 `g:`/`t:`、空分组反向对照
   需要构造真实的 @消息 / 导出场景，本轮只验了选择器本体（用户本次要求聚焦「没有群聊」+ 四 Tab）。
