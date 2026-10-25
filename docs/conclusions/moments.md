## 朋友圈（SnsGuard 防删除 / SnsTag 标签）—— 已真机全链路通过（2026-10-16）
1. **独立库** `MicroMsg/<md5>/SnsMicroMsg.db`（不是 EnMicroMsg.db）。`SnsInfo` 18 列 / `SnsComment` 16 列。模块库 `wxgroup.db` 表 `sns_archive`。
2. **删动态 = 物理 DELETE，两步**：① `update SnsInfo` 清 `localFlag` 可见位（bit4=16，落成 2）② `DELETE`。只拦②行还在但查不出 → **必须拦完写回 `localFlag |= 16`**（2→18）。时间线查询是白名单枚举（`localFlag in (16,2064,18,2066,24,…)`），每个值都置了 bit4。
3. **别人的动态在本地也会被 DELETE**（归档 userName 全是不同好友）→ **不区分作者一律拦**。
4. **标签落点走 adapter 绑定回调**：新版时间线 `ImproveSnsTimelineUI`；`com.tencent.mm.view.recyclerview.WxRecyclerAdapter` **类名未混淆**，`onBindViewHolder(holder,pos)` + `adapter.getData()`（元素 toString 含 `ImproveInfo(name=…, snsID='无符号十进制')`）。
   ❌ 老版 `View.setTag → TimeLineObject.Id` 链在新版**不存在**（唯一 sns 前缀 tag 只有表名 `sns_table_xxx`）；
   ❌ `getChildAdapterPosition` 在微信重打包的 androidx 里**不存在**，ViewHolder 位置字段恒 -1。
5. **标签实现铁律**：
   - onBind 开头必须 `adapter.javaClass.name.startsWith("com.tencent.mm.plugin.sns.")` 早退（钩子挂基类，全微信列表都进来）。
   - 子类覆写会让基类钩子变死钩 → 补挂 `WxRecyclerView.setAdapter` 抓具体类；2 参/3 参重载都挂。
   - `position` 与 `getData()` 下标**错位**（封面头，offset=-1/-2）→ 用 item 作者文字与 `ImproveInfo.name` 对账，**±2 窗口必须唯一命中**才采用（同作者相邻动态会互相通过校验）。
   - ⚠️ `holder → itemView` **必须排除 RecyclerView**（holder 持有 rv 引用，面积最大会选中它 → 所有 holder 同一个 View、标签加到列表容器上；**往 RecyclerView addView 会把微信搞崩**）。
   - item 根布局实测 **LinearLayout 子类** → 必须 `addView(tv, 0, lp)` 插最前，否则标签跑到底部、看着像贴在**下一条**上。
   - 严格校验：拿不到名字 / 读不到文字 / 名字 <2 字 → 不采信（打错标签比不打标签糟）。
   - `sns_archive` 只查 `sns_id` 一列且**不能加 LIMIT**（无 ORDER BY 时 LIMIT 取任意 N 行 → 静默漏标）。
   - 开关闸门必须在 onBind 最前面（否则关开关后滚动照样打标，2 秒后被 sweep 摘掉 → 反复闪）。
6. **snsId 符号**：库里有符号 Long（`-3447438253133393222`），`ImproveInfo.snsID` 是**无符号十进制 String**（`15033691623173788268`）→ `Long.parseUnsignedLong` / `toUnsignedString` 互转。写入端与读取端位模式必须一致（已有单测锁住）。
7. **探针**：`op=snstag sub=stat|dump|mark|fake|unmark`；`op=snsg sub=flags|fixall|simdel|show|del|cmts|one|stat|dump`。
   ⚠️ **`snsg dump` 里的 `flag` 是归档快照**（恒为 2），**不代表当前值**；查当前用 `snsg flags`。
   ⚠️ `snsg dump` / `one` 的输出**没有统一前缀**，grep 别按前缀过滤。
8. **`SnsComment` 16 列，无 content 列** —— 评论正文在 `curActionBuf` BLOB 里。列：`snsID/parentID/isRead/createTime/talker/type/isSend/curActionBuf/refActionBuf/commentSvrID/clientId/commentflag/isSilence/springWishFlag/isReminding/msgRelevanceType`。`attrBuf` 实测 235B~1069B。
9. 评论 `[已删除]` 标签：**判据已实现并单测锁住，落点待取证**（2026-10-17 lane/delcomment）。
   - **评论数据在哪（已实证）**：`SnsComment` 表 16 列，**无 content 列**，正文在 `curActionBuf` BLOB；删评论 = `execSQL("delete from SnsComment where snsID=? and commentSvrID=?")`，线程 `[D]#worker`。
   - ⛔ **「删评论是否重写 `SnsInfo.attrBuf`」仍待取证**：探针 `op=snsc sub=traceon`（看 old/new 字节数）。
   - ⛔ **「删评论后本地库那条记录变成哪种形态」仍待取证**：探针 `op=snsc sub=form`（对归档里每条已删评论点查当前行，输出 `form=MISSING|EMPTY|FLAGGED|ALIVE` 分布）。三种形态的判据都写在 `SnsCommentLogic.classify`（纯函数 + 单测），取证结果只影响哪个分支生效，**判据不用改**。
   - ⚠️ `commentflag` 哪一位表示「已删除」**未实证** → `SnsCommentLogic.DELETED_FLAG_MASK = null`，未取证前 `classify` **不产出 FLAGGED**（宁可漏标不误标）。取证到之后填位掩码即可。
   - 标签落点：`SnsCommentTag` 走已取证的 `WxRecyclerAdapter.onBindViewHolder` + `getData()` 映射链（同第 4 条）。⛔ **评论行 View ↔ commentSvrID 的映射未取证**（`PENDING_COMMENT_ROW_CLASS` / `PENDING_COMMENT_ID_METHOD` 留空）。降级路径：**正文对账** —— 归档里删之前的 `curActionBuf` 正文候选 ↔ 界面 TextView 文案（`SnsCommentLogic.textHit`），只用 Android 标准 API，不碰未取证的微信类名。

### 6.4 朋友圈 定时刷新 / 自动加载 / 自动导出（2026-10-17 lane/moments；2026-10-18 首轮真机，部分通过）

**已落地（全部站在上面 1~9 条已验证结论上，没有新增猜测的类名/字段名）：**
- 调度：`MomentsLogic.nextRefreshAt`（单测锁住）；环境 = `BatteryManager.isCharging` / `ConnectivityManager` 的 `TRANSPORT_WIFI`，跑在微信进程里直接拿宿主的 Context（`HostContext.app()`，bootstrap 时已 `setApp`）。
- 自动加载：复用第 4 条的 `WxRecyclerAdapter.onBindViewHolder` + `getData()` 映射链（只取 pos/size，不做名字对账），贴底（最后 3 条）时对列表调滚动 API 替用户滚到底，让微信自己的「滑到底加载更多」继续工作。节流 1.5s、每轮上限 30 次。
- 时间线 Activity：简单类名 `ImproveSnsTimelineUI` 已验证；**全限定名不猜** —— `registerActivityLifecycleCallbacks`（框架主动回调，不受子类覆写 onResume 影响，HostContext 同款）在用户真正进过一次朋友圈后按简单类名捕获，之后才允许自动拉起。
- 自动导出：数据源 `sns_archive`，`GroupDb.listSnsArchiveBefore` 键集分页（`(create_time, sns_id)` 复合游标，一次一页读 BLOB，内存有界）；文本渲染 `MomentsLogic.renderText`；目录 `MomentsLogic.exportDir`；去重 = 扫导出树里 `<snsId>.txt`（`parseExportedId`）；名单 `listHit`；空间下限 `MIN_FREE_BYTES`；tmp + rename 原子落盘；后台守护线程 + 单航班。
- 探针：`op=moments sub=stat|env|act|refresh|load|rvmethods|export|img|scan|path`（`EntryReceiver`）；`kvset` 改开关后热刷。

**2026-10-18 真机实证（设备 467cf5bc，微信 8.0.48）：**
- ✅ rv 捕获：**onBind 时 itemView 还没 attach**（先 bind 后 layout，真机日志实证 `p=null`），从 item 父链往上找 rv 永远失败。改从 `onCreateViewHolder(ViewGroup parent, int)` 的 **parent 参数**抓（create 必先于 bind）；另发现时间线 Activity 被系统恢复时 `setAdapter` 早于钩子安装就调用完 → onBind 首次见到具体 adapter 类时**自愈补挂**它的钩子。
- ✅ 类层次（`op=moments sub=rvmethods` 反射枚举）：`ImproveSnsTimelineUI` 的列表是 `com.tencent.mm.plugin.sns.ui.improve.view.ImproveRecyclerView` → extends `com.tencent.mm.view.recyclerview.WxRecyclerView` → extends `androidx.recyclerview.widget.RecyclerView`。**微信剥掉了公有的 `fling(int,int)`**（callMethod 恒 `NoSuchMethodError`），可用的是 `smoothScrollBy(int,int)`（androidx，动画滚）与 `scrollBy(int,int)`（WxRecyclerView 自己声明）。
- ✅ grow 自观测生效：`MOMENT autoload grow 0 -> 10` / `10 -> 20`（getData().size 变大就是硬判据）。
- ✅ 手动 refresh 强制拉起生效（`launchDisabled` 后 force=true 绕过闸门）；广播 `--ei n` 钳位（`clampProbeN`）在 img / snsg ls 两个入口实证（999999 → 50，微信不崩）。
- ✅ 导出幂等实证：`export done rows=6 written=0 skipped=6`（scanExportedIds 命中 6 个既有文件；snId 命中即跳过）。写盘通路由既有文件 + skip 计数证明。⚠️ Android 11 作用域存储：app 只能写自己的 Android/data 目录，`/sdcard/xxx`  mkdirs 失败；shell 用户对 Android/data 下目录 Permission denied 数不了文件（曾误判 written=0）。

**⛔ 仍待取证（按优先级）：**
1. **`smoothScrollBy(0, 2400)` 触发链闭环**：日志出现 `MOMENT autoload smoothScrollBy pos=...` 且随后有 grow（枚举可见 ≠ callMethod 可达）。
2. **显式 Intent 重进时间线是否真的触发服务端拉取**（还是只走本地 onResume）。探针：`op=moments sub=refresh` 后看 grow 与 SnsMicroMsg.db 写入。若无效，退路是时间线已前台时注入「真实下滑手势」，有误触风险需单独评估。
3. ~~**朋友圈缓存图片的磁盘命名规则**~~ **⛔ 已定死（2026-10-18 两轮真机，别再查）**：本地缓存路线**证伪**（4 个 md5 全盘 find 搜不到，图从未落盘）；改走 url 下载后**只有视频/大图能下，图片恒 400**。详见下节 §6.4b 原图下载。对上之前 `COPY_IMAGES=false`，只导文本。
   ⚠️ **2026-10-20 补充**：上述三条**没有覆盖「批量下载通路」**（⑫ 段），`COPY_IMAGES` 的结论**未最终定案**，R9 待真机验。
   ⛔⛔ **2026-10-24 02:3x 真机直证 —— 本条第 3 项彻底作废，图片确实落盘了**：
   图就在 `/sdcard/Android/data/com.tencent.mm/cache/<hash32>/sns/<hex1>/<hex2>/`，
   文件名 `snst_<id>`（缩略）/ `snsu_<id>`（压缩）/ `snsb_<id>`（**大图 1.1~1.5MB**）/ `sight_*`（视频），
   文件头 `ff d8 ff e0 00 10 "JFIF"` ⇒ **明文 JPEG，不需解密**。滚屏那一刻新写 37 个文件（实时写入）。
   ⇒ 「4 个 md5 全盘搜不到」是**真的**，但推论「图从未落盘」是**错的** ——
   **文件名不用 md5，用另一个 20 位 id**，所以按 md5 找必然落空。详见 `docs/_r14_sns_cache.md`。
   `COPY_IMAGES` 的路线改为「**SNS 缓存直读**」，方案见 `docs/_r15_sns_cache_plan.md`。
4. ~~从归档反插 `sns_archive → SnsInfo`~~ **✅ 架构前提已真机取证（2026-10-18）**：18 列全名已知、`notNull` 全 false、`snsId` 无唯一约束。详见下节 §6.4c 前置。

### 6.4b 原图下载（url 路线）—— ✅ 视频/大图端到端已通，❌ 图片恒 400（2026-10-18 R3 真机）

> ⛔⛔ **2026-10-24 定案作废**：本节「图片存不了」的结论**已被真机推翻**。
> 图一直在 `cache/<hash32>/sns/<a>/<b>/` 下、是明文 JPEG，只是**文件名不用 md5** 导致按 md5 找必然落空。
> 详细真机证据（含 `od` 文件头）见 `docs/_r14_sns_cache.md`；新方案见 `docs/_r15_sns_cache_plan.md`。
> 下面内容**保留作历史**，其中「md5 搜不到」「裸 url 恒 400」两条实测**仍然为真**，只有推论作废。

**⛔ 路线一（本地缓存）已证伪**：BLOB 里的 32 位 md5 在 `/sdcard` + `/data/user/0/com.tencent.mm`
全盘 `find` **一个都搜不到**；`image/` 最近写入是 2 天前的公众号配图；近两天 `/sdcard` 下
**没有任何图片落盘** ⇒ 朋友圈的图**从未下载到本机**，只在内存里画缩略。
`picked=NONE` 是必然，**不是 bug，别再调目录规则**。

**⛔⛔ 路线一·补（「点开大图就会缓存」）也已证伪（2026-10-19 R4）**
之前的疑虑是「R2/R3 只滚了列表看缩略图，缩略图不下原图」。本轮**真的点开了大图**验了一次，
判据不用 md5 假设（避免循环论证），改用**时间戳 diff**：
```
点开前：find /sdcard/Android/data/com.tencent.mm -type f -newermt "-3 minutes"  → 空（基线干净）
点开后：SnsBrowseUI 提示「第1页共3页，轻触两下关闭图片」⇒ 图【确实加载完成】
等 8s：find /sdcard /data/user/0/com.tencent.mm -type f -newermt "-6 minutes"  → 【零新增】
```
⇒ **朋友圈大图根本不落盘**，连点开看过都不存。这条把「触发缓存 → md5 命中」也堵死了。

**⛔⛔⛔ 至此三条路全部关闭，6.4 图片定案：存不了**

| 路线 | 状态 |
|---|---|
| ① md5 = 本地缓存文件名 | ❌ 全盘 find 搜不到（R2） |
| ② 点开大图触发缓存后命中 md5 | ❌ **图加载完了也不落盘**（R4 本轮） |
| ③ url 直下图片 CDN | ❌ `shmmsns.qpic.cn/mmsns/…` 恒 400（R3，8 样本） |
| 视频/大图 url（带 `token` 的 `stodownload`） | ✅ 唯一可行，2.8MB 实测落盘 |

> ⛔ **2026-10-25 R20 补订正（本节中「按 md5 找本地缓存」的部分整体作废，保留作历史）**
>
> 上表 ① ② 两条（md5 全盘 find 搜不到 / 点开大图也不落盘）**实测本身仍为真**，
> 但它们支撑的推论——「按 md5 找本地缓存 ⇒ 图没落盘」——**已在 R15/R19 被推翻**：
> 图其实一直在 `cache/<hash32>/sns/<a>/<b>/` 下且是明文 JPEG，**只是文件名不用 md5**
> （R19 实测是 **20 位十进制数字串** = mediaId 原值或 `fliterPre` 变体），
> 按 md5 找**必然**落空，与「有没有下载」无关。
>
> ✅ **现行正解 = SNS 磁盘缓存直读**：mediaId 解析路径 `content → 字段8 → 字段5 → 字段1`
> （R15 原定 `6 → 8 → 1` 已被 R19 真机推翻），与缓存文件名精确匹配；
> R19 真机实测 **`rows=10 rowsHit=4 ids=7 hit=6`**。完整取证见 **`docs/_r19_a4_probe.md`**。
>
> ⇒ 旧 md5 路线的代码（`MomentsLogic.mediaKeys/md5sOf/md5sFrom/imageCandidates/pickExisting`
> 与 `SnsCdnImage.Cfg.rewritePath`）自 R20 起物理删除，逐条零调用点证据见 `docs/_r20_a8_del.md`。
>
> ⚠️ 上表 ③（裸 url 拉图片 CDN 恒 400）**仍然成立**，只是与「能不能留存图片」无关了 ——
> 因为图片根本不需要联网下载。

> **记档但不采纳的第四条路**：`SnsBrowseUI` 里有个占满整屏的
> `android.widget.ImageView [0,0][1440,3168]`，理论上可 `drawable → bitmap` 存 jpg。
> 缺点：只能存「用户点开看过的那张」，**无法批量导出**，对「朋友圈备份」意义不大。
> 若将来坚持要图片，这是唯一低成本入口（不需 CDN 鉴权）。

**✅ 路线二（CDN url）部分可行 —— 差别在「带不带 token」**

| url 形态 | 真机结果 |
|---|---|
| `http://wxapp.tc.qq.com/251/**20302**/stodownload?encfilekey=…&token=…` | ✅ **200 video/mp4 2826203** |
| `https://wxapp.tc.qq.com/251/**20304**/stodownload?…` | ❌ https `000` / http 降级 `000`~`400`（**资源本身不可用，不是协议问题**） |
| `http://shmmsns.qpic.cn/mmsns/<46位>/0`（**图片**） | ❌ **8 个样本全部 400**，含当场新抓的；加 `Referer`、加 UA、加 `?tp=jpg` **均无效** |
| `https://mp.weixin.qq.com/s?__biz=…`（文章） | 噪音，302，`mediaUrls` 已过滤 |
| `http://dldir1.qq.com/…_icon_….png`（资源图标） | 噪音，404，`mediaUrls` 已过滤 |

**已实现并真机落盘**（`op=moments sub=imgdl`，探针）：
```
MOMENT imgdl rows=3 rowsHit=1 tried=5 ok=1 bytes=2826203
  out=/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments/_imgdl
→ vid_-3412979636140764498_0.mp4（与 curl 字节级一致）
```
判据：`MomentsLogic.kindOfUrl / mediaUrls / extFromContentType / fileNameFor`（+12 单测）。
⚠️ 文件名前缀按**最终扩展名**定（Content-Type 优先于预判 kind），避免 `img_xxx.mp4` 这种误导名。

**⚠️ 导出根目录**不是 `/sdcard/WxGroupExport`，是
`/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments`（`Prefs.DEFAULT_EXPORT_PATH`）。

**结论：6.4 原图留存目前只有视频能存，图片存不了。** 图片只剩两条路（都待取证）：
① hook 微信自己的图片下载器拿带鉴权的请求；② 触发微信缓存（用户点开大图）后走 md5 快速路径。
⛔ 别再加「https 降级 http 重试」—— 实测无效（见上表第 2 行）。

**进朋友圈的姿势**（`SnsTimeLineUI` 未 exported，`am start` 会 `Permission Denied`）：
UI 导航 点「发现」(900,3060) → 点「朋友圈」(293,435)，实际 Activity 是
`com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI`；滚动加载 `input swipe 700 2500 700 700 500`。

### ⚠️ 朋友圈库句柄纠正（2026-10-18，子代理实锤）
`AntiRevoke.wxDb()` **不是朋友圈库** —— 它只在 `table == "message"` 时记 `msgDb`，那是
`EnMicroMsg.db`（`rcontact` 所在）。`SnsInfo` / `sns_archive` 相关在独立的
**`SnsMicroMsg.db`**（`SnsGuard.kt:55` `DB_MARK = "SnsMicroMsg.db"`）。
拿错句柄 = `no such table: SnsInfo`。
⇒ 「查群名/标签用 `wxDb()`」是对的，**查朋友圈一律走 `SnsGuard.snsDb`**。

### 6.4c 前置：从归档恢复 `sns_archive → SnsInfo`（2026-10-17 lane/restore 落地；2026-10-18 架构前提真机取证）

**已落地（判据全部进 JVM 单测 `SnsRestoreLogicTest`，28 例全绿）：**
- 纯判据在 `feature/SnsRestoreLogic.kt`（零 Android 依赖）：列白名单 / 归档行→INSERT 参数映射 / 幂等判定 / 批量计划 / 倒序回滚 / 汇总。写库仍在 `SnsGuard`（那里才拿得到 wcdb 的 db 实例，且每条都包 `runCatching`）。
- 新增 `UNARCHIVED`（5 个列名：`localPrivate / likeFlag / pravited / withTa / withTaHasOther`，归档里没有值 ⇒ 永不写入）与 `alienColumns()`（schema 漂移检测：拿 `pragma table_info` 实况列名与白名单对账，多出的列直接报出来，防止微信改表后静默错位）。
- **写入列 = 归档行有值的那 13 列**（`snsId/userName/createTime/type/sourceType/localFlag/subType/serverExtFlag/head/stringSeq/content/attrBuf/postBuf`），证据是 `SnsGuard.readRow` 真机逐列 `getColumnIndex` 读出来又归档的，跑通过。`SnsInfo` 实测 **18 列**（名字与 notNull 约束**均已真机取证**，见下方「已取证」1、2 条）：剩下的 `localPrivate/likeFlag/pravited/withTa/withTaHasOther` 5 列**名字已知但不写入**（归档里没存它们的值，写进去等于编数据）。
- 幂等三态：`classify(localFlag)` → 行没了=INSERT / 行在但被清位=SET_FLAG(补 bit4) / 行在且可见=SKIP（不写）。批量 `plan()` 去重 + `clampBatch`（`--ei n` 外人可控，上限 50，防把整库 BLOB 捞出来）。
- 失败策略：**一步失败即停 → 倒序回滚已成功步骤**（INSERT 的逆=删掉刚插的行，SET_FLAG 的逆=写回原 flag）。⚠️ 回滚删行**必须 `bypass.set(true)`**：我们自己的 delete 钩子会把对 `SnsInfo` 的删除拦下来，不 bypass 回滚静默失效。
- ⚠️ **回滚只删「本次确认新插入」的行**：写库前逐条用 `readFlag` 查存在性三态（`PRESENT/ABSENT/UNKNOWN`），只有 `ABSENT` 的 snsId 才进 `undoableInserts` 白名单；`UNKNOWN`（预检查询失败）宁可在微信库里留一行也不删。**回滚失败不吞**：失败清单以 `⚠️ UNDONE snsId=...` 打进返回值与告警日志，留现场给人工核对。
- ⚠️ 插入走 **4 参 `insertWithOnConflict(..., CONFLICT_IGNORE=4)`**（对照 `RevokeRescue.kt:52`，那里已上过真机）。早先写成 `1` —— 那是 `CONFLICT_ROLLBACK`，撞主键会回滚**整批**（不只是冲突那一行），且日志完全看不出来。冲突算法全表：`0=NONE 1=ROLLBACK 2=ABORT 3=FAIL 4=IGNORE 5=REPLACE`。**只写 13 个已知列**（见上条 `UNARCHIVED` 那 5 列一律不写）。
- ✅ **反插落库已补齐（`SnsGuard`）**：新增 `updateFlag(db, snsId, flag)` 作为**统一改可见位路径** —— 按 `parameterTypes[3]` 分流 `String[]` / `Object[]` 两种 bind 形态，修掉「补可见位静默失效」的隐患（传错数组类型时 wcdb 不报错但 params 对不上）。`restoreVisibility` 改为**以 update 实际改动行数为准，≤0 直接返回 0**，不再谎报成功。
- 探针（挂在既有 `op=snsg` 上，没重开一套）：`sub=plan`（只算计划打印、**不写库**）、`sub=apply`（真反插，`--ei n` 钳到 MAX_BATCH=50）、`sub=pragma`（= `cols` 别名：打 `pragma table_info(SnsInfo)` + `BLOCKING` 判定）、`sub=restore --es id <snsId>`（单条）。旧的 `sub=restoreall` / `sub=cols` 仍保留且与 `apply` / `pragma` 等价。

**✅ 已取证（2026-10-18 `snsg sub=cols` + DDL，下面 1、4 两条已定论，勿再当"待取证"）：**

1. **18 列全名已知**（原先"未知 5 列"已解开）：
   `snsId / userName / localFlag / createTime / head / localPrivate / type / sourceType /
   likeFlag / pravited / stringSeq / withTa / withTaHasOther / content / attrBuf / postBuf /
   subType / serverExtFlag`。
   原先未知的那 5 列 = `localPrivate`、`likeFlag`、`pravited`、`withTa`、`withTaHasOther`。
   ⚠️ `pravited` 是**微信自己的拼写错误**（不是 `privated`），照抄别改。
   **且 18 列的 `notNull` 全部为 false**（日志 `COLS total=18 known=13 ... BLOCKING none`）
   ⇒ 只写 13 个已知列的 INSERT **不会因为缺列失败**，"有 NOT NULL 列会挂"这个担忧不成立。
   ⚠️ 列名虽已知，**仍不写入**那 5 列：归档里没存它们的值，写进去等于编数据。

2. **`SnsInfo.snsId` 没有唯一约束**（真机 DDL）：建表语句是
   `CREATE TABLE SnsInfo ( snsId LONG, ...)`，`snsId` **既不是 PRIMARY KEY 也没有 UNIQUE**，
   表上 6 个索引的 `unique` **全为 0**。
   ⇒ ⚠️ **`CONFLICT_IGNORE` 在这里拦不住重复插入**（它只在撞到唯一约束时才退，这里无约束可撞）。
   所以「插入前先查存在性」是**唯一**的去重手段，不是多余保险。
   按 `snsId=?` 回滚删行仍**只限 `ABSENT` 白名单**（同一 snsId 可并存多行，瞎删会连带删掉用户自己的动态）。

**⛔ 仍待取证（剩这两条，都要用户配合）：**
3. **插回去之后微信界面是否真的显示**：INSERT 成功 ≠ 界面出现（时间线还可能依赖 `SnsComment` / 缓存 / 服务端对账）。判据：`snsg sub=restore` 后进朋友圈看这条有没有回来。
4. 恢复出来的动态**刷新后会不会被服务端再次抹掉**（本地有、服务端已删）。

