# R49 · R-C 复用写时拦截 · 阶段二真机决定性观测记录（2026-11-10）

> 本文只记录**可复算的原始事实**（N0 / N1 / N3 + 阴性对照）与设备前置，判定结论见
> `docs/_r49_rc_stage3_verdict.md`（lane I 独立裁决）。`docs/_r49_rc_stage2_verdict.md`
> 是上一轮（只有阴性对照、未进实验组）的推理原貌，保留作证据但**不再作为结论来源**。
>
> 用户诉求：只验证 **R-C 复用写时拦截** 能否突破好友「允许查看朋友圈的时间范围（三天可见）」，
> 不考虑 R-A/R-B。实验组 **verity**（三天可见）；阴性对照 **不岛剧本·粥粥**（微信号 Buudaooo，全部可见）。

## 0 · 设备前置（这一步不做会全盘假阴性）

| 项 | 实测 |
|---|---|
| APK | `lastUpdateTime=2026-11-09 00:21:58`（阶段二装包；818 单测全绿、装包前 diff 复核 PASS） |
| 本轮改动 | **零源码 / 零新增装包 / 零 R42 开关改动 / 零微信库写**；唯一配置写 = 探针自有 `sns.write_probe` 的 `arm` |
| adb | daemon 版本不匹配（server 36 / client 41），重启后恢复正常；首次 `am start` 报 `device unauthorized`，重试成功 |
| ⛔ 踩坑 | 微信原本停在 **LSPosed 管理器后方** ⇒ 后台广播**不投递**，日志文件只剩两行 `skip process=...(not main)`，所有 `op=` 静默失败。按项目规则 `am force-stop` + **冷启两次**（pid 25263）后才生效 |
| 模块加载确认 | `MOMENT installed hooks=4 bgSync=true refresh=true load=true interval=1h`、`MOMENT export installed`、`K7 installed`、`SNS sns db captured via execSQL` |
| 账本 | 进程内结构**冷启清零** ⇒ `WP stat armed=false seen=0`；重新 `sub=reset` + `sub=arm` 后 `armed=true memArmed=true` |

开关真值（`op=kvget`）：`antirevoke.sns=true`、`antirevoke.sns_block=true`、
`sns.write_probe=true`、`moments.auto_load=true`。探针 `t0=1794059483`（运行时按当前库
`max(createTime)` 算，**非写死常量**）。

## 1 · N0（纯只读 `op=sql`，进主页前）

```
全库: count=263355  max(createTime)=1794318683   ⇒ t0 = 1794318683 - 3*86400 = 1794059483
verity 全量: c=33  mn(createTime)=1793016603  mx=1794053683
             wc(length(content)>0)=33  wa(length(attrBuf)>0)=33
verity 三天外 sourceType 分布: 0|20 ; 10|8 ; 2|4 ; 8|1     （共 33 行，全部在三天外）
verity 三天外命中面: older3d=33  bit3=9  bit1=12  src0=20
             snsId 范围 -3405830652137369075 ~ -3397130994131660261
含 bit3 的 9 行:
  sourceType=8  ×1 : -3405064359876226540  ct=1793107952  clen=338  alen=174
  sourceType=10 ×8 : ct 1793973491~1794053683  clen 972~994  alen=174（全部完整）
```

⛔ **口径纠正**：上一版脚本误用阶段一的旧 `T0=1793884838`（偏早 2 天），算出「三天外 21 行、
bit3=1」。按探针运行时 `t0` 重算才是 33 行 / 含 bit3 命中面 9 行。
⇒ `S_A` 非空、`content/attrBuf` 全完整、白名单命中面 9 行 ⇒ **T 前提成立**。

**身份坐实**：`com.tencent.mm/.plugin.profile.ui.ContactInfoUI` 个人资料页显示昵称
**verity**、微信号 `wxid_xlrbelf6hv0w22`、「朋友圈个人相册,共4张」——与
`docs/conclusions/moments.md:270-275` 的 R42 记载同一人 ⇒ lane H 标的 **B8 身份映射盲区消除**。
（R42 当时记「名下 3 条动态清一色 `type=2 src=0` 死来源」，本轮库里已增至 33 行、
20 行 `src=0`，教训仍成立：死来源不在 64 值含 bit3 白名单，天然不参与 `& -9`。）

## 2 · N1（决定性：进 `SnsUserUI` 实验组主页）

`sub=reset` 后点进相册主页，等 4.5s：

```
WP stat armed=true seen=1 matched=1 verdict=有真实写事件(matched>0)
WP ledger size=1/400 dedupKeys=1 clear9=1 rewritten=0 execSql=1
WP hit: t0=1794059483 events=1 hitOlder3dTotal=33 (只读 select,库值不变)
WP dump total=1 show=1
WP ts=1794319087950 kind=EXECSQL table=SnsInfo
  sql=UPDATE SnsInfo SET sourceType = sourceType & -9
      WHERE SnsInfo.userName="wxid_xlrbelf6hv0w22"
        AND (sourceType in (8,264,10,266,12,268,14,270,24,280,26,282,28,284,30,286,
                            72,328,74,330,76,332,78,334,88,3...) [日志单行截 1500 字符]
  cvKeys=- cvSourceType=- snsIds=- userName=wxid_xlrbelf6hv0w22
  caller=...XC_MethodHook.callBeforeHookedMethod
  hitOlder3d=33 hitSample=-3405830652137369075
BLOCK w2 delSns=1 delRow=1 sqlSns=10 sqlSrc=3 sqlClear=0 sqlClear9=3 sqlBlock9=3
  blkOff=0 blkFail=0 rng=1 max=3x@? sqlOwn=0 sqlUnmatched=0
```

⇒ 三条独立证据闭环：① 探针在**改写前**捕获 `& -9` 原文（`rewritten=0`）；
② `BLOCK sqlClear9=3 sqlBlock9=3` 证明 R42 B 层识别并拦下；③ SQL 的 WHERE 精确指向
实验组、无 `createTime` 窗口、行级命中 33 条三天外行。
**R-C 的写时拦截不空转，且现网已经在跑。**

## 3 · N3（界面判据 · 用户可见事实）

```
首屏 SnsUserUI: 「07」「11月」+ 5 个「图片」节点 + 昵称 verity
逐屏 swipe 8 次: 每屏日期标记恒为「06 | 11月 | 11 | 2026年」，图片节点 8
末屏完整文本含: 「朋友仅展示最近三天的朋友圈」   ← 微信原生截断提示
翻页后 WP stat 与 N1 逐项一致: seen=1 matched=1 clear9=1 hitOlder3dTotal=33（无新增写事件）
翻页后 BLOCK w2 与 N1 逐项一致: sqlClear9=3 sqlBlock9=3
翻页后 op=sql 查 verity 分布: 0|20 10|8 2|4 8|1 —— 与进主页前**逐项一致**
```

截图留证：`_kb/r49_verity_album.png`。
⇒ **bit3 确实被保住，但翻页穷尽后 `older3dShown=0`**，界面仍只显示三天内动态。
「拦住写 ⇒ 界面显示」这条因果链被实测切断。

### 3.1 复位时回读（22:15，判定窗口之外）

`sub=disarm` 前最后一次 `WP stat`：
```
WP stat armed=false seen=322 matched=12 verdict=有真实写事件(matched>0)
WP ledger size=12/400 dedupKeys=12 clear9=6 rewritten=0 execSql=11
WP hit: t0=1794059483 events=6 hitOlder3dTotal=1014
```
即 N1/N3 判定窗口（22:00 前 `seen=1 matched=1 hitOlder3dTotal=33`）**结束之后**，
微信后台又自发产生大量写事件。⛔ 这些事件**不参与本轮判定**：判定只取
`sub=reset` → 进主页 → 逐屏翻页 8 次这一段封闭窗口内的快照，窗口内账本
`seen=1 matched=1` 且翻页后无新增。
它反而说明 `& -9` 并非只在「进三天可见好友主页」时触发（后台同步 / 其它好友也会发），
但**改变不了**「verity 主页翻页穷尽仍不显示三天外动态」这一用户可见终点事实。

复位结果：`sns.write_probe=false`（回读 `PROBE kv` 确认）、`memArmed=false`、
账本停止增长；`antirevoke.sns=true`、`antirevoke.sns_block=true` 保持未动。
⛔ 期间前台一度被切到 Twitter，后台广播不投递 ⇒ 拉回微信前台后重做才生效
（项目规则「后台广播不投递」再次复证）。

## 4 · 阴性对照（上轮已完成）

进「不岛剧本·粥粥」（Buudaooo，全部可见）`SnsUserUI` 后：
`WP stat seen=11 matched=0 clear9=0 rewritten=0`、`BLOCK w2 sqlClear9=0 sqlBlock9=0`；
账本明细仅 1 条 `CREATE TABLE IF NOT EXISTS StarSnsInfo` + 1 条 `cvSourceType=10 where=snsId=?`
的 UPDATE ⇒ **全部可见好友不发 `& -9`**，与「服务端这次没返回、本地有 bit3 ⇒ 清掉」的
触发语义一致（`docs/_r42_bit_paths.md:79`）。

## 5 · 结论与边界

- **判定：`R-C 证伪（证死）`，置信度高** —— 详见 `docs/_r49_rc_stage3_verdict.md` V1/V8。
  决定性判据是「**机制确认生效后，界面是否显示三天外动态**」，不是「有没有写事件」。
  lane H 的三态表把「零写事件」写成证死、把「机制生效但界面不显示」悬成 E2 证据不足，
  属范畴错误，已由 lane I 修订。
- **根因**：三天可见边界在**服务端响应组装**（`docs/未完成项与计划.md:345/348`「超出范围对象
  根本不下发」、`docs/conclusions/moments.md:259-260` 首屏由 `w5.onSceneEnd` 响应内存对象
  重建且全方法无 `d2` 存储层调用），R-C 只能改写 DB 层 `SnsInfo.sourceType` 的 bit3 缓存位，
  二者不同通路。解释 A「服务端不下发 id」与解释 B「本地三门槛过滤」尚未以响应 dump 区分
  （`[需真机]`，工具为只读旁听 `SnsSqlSpy` / `SnsHuntProbe`），但**都不改变本轮档位**。
- **不必再做**（lane I V3/V4 裁定）：① 关 `antirevoke.sns` + `antirevoke.sns_block` 两个开关的
  反向对照（会真清 bit3、磁盘不回滚、收益≈0）；② 请 verity 删一条动态的 P-10 阳性对照
  （`matched=1` + SQL 原文 + 双探针已正向自证）。
- **三档边界**：进入档、返回档（翻页 8 次 + 账本无新增）已观测；**复位档未下真机**
  （关开关 / 对方改回全部可见 / 清缓存 / 冷启），故结论不外推到这些场景。
  ⛔ 已写进库的 bit3 是磁盘状态，不随开关 / 冷启回滚；进程内账本冷启清零是预期而非 bug。
- **盲区**：native 层写库、服务端响应组装、UI 与写事件的因果关联、`hitOlder3dTotal=33`
  是几何镜像 count 而非真实 `changes()`（lane I V5 已核 `fillHits` 口径）。

## 6 · 本轮产物

| 文件 | 内容 |
|---|---|
| `docs/_r49_rc_stage2_result.md` | 本文（实测原始记录） |
| `docs/_r49_rc_stage3_verdict.md` | lane I 最终裁决（V1~V9，279 行） |
| `docs/_r49_rc_stage2_verdict.md` | lane H 上轮推理原貌（保留，末尾加后续指针） |
| `_kb/r49_verity_album.png` | 界面截图留证 |
| `docs/派发审计台账.md` ㉕/㉖ | 派发与观测留痕 |

⛔ 本轮未提交任何 git commit；工作区另有 R48 文档回写、R49 阶段二源码（探针）
与 R50 的未提交改动，是否一并提交推送待用户指示。
