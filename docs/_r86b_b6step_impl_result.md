# R86b B6 checkExist 最小步只读取证探针 · 落码与三闸结果

> 任务：落码 `com.lx.wxgroup` 的 B6 checkExist 最小步只读取证探针（设计 `docs/_r86b_b6_reimpl_design.md` §5.3）。
> 纪律：只读验证，C2 不调 j6 / 不调 send / 不写宿主字段 / 不改 m.a / 不发消息；不写 field_fullpath。
> 本文件先建骨架，每闸完成追加结果 + `[DONE 闸n]`，全绿后 `[DONE ALL]`。

## 改动文件

- 新增 `wxgroup/app/src/main/java/com/lx/wxgroup/probe/B6CheckExistProbe.kt`
- 改 `wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt`（op=b6step 分支）
- 改 `wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt`（install 接线）
- 新增 `wxgroup/app/src/test/java/com/lx/wxgroup/probe/B6CheckExistProbeTest.kt`

## 三闸进度

- **闸1 assembleDebug：PASS** — `sh gradlew :app:assembleDebug --no-daemon --console=plain`，BUILD SUCCESSFUL in 37s（新增探针 + 两处接线编译通过，仅 3 条既有 deprecated 警告）。`[DONE 闸1]`
- **闸2 全量单测：PASS** — `bash tools/_junit.sh` → `OK (1165 tests)`，0 失败（基线 1153 + 新增 12）。中途发现 `arm sent` 日志跨两个字符串字面量导致单测判红，已改为单字面量并重编译复跑通过。`[DONE 闸2]`
- **闸3 真实源码变异：PASS** — 逐条改真实源码、跑 `bash tools/_junit.sh com.lx.wxgroup.probe.B6CheckExistProbeTest` 确认判红，恢复后全量绿：
  - 变异 A 漏写 field_fileId：`put(task, "field_fileId", fileId)` → `field_fileIdX` ⇒ `taskFieldsCompleteAndNoFullpath` + `mutationMissingFileIdWriteIsDetected` 判红（12 例 2 失败）。`[DONE 变异A]`
  - 变异 B 塞入 field_fullpath：TASK_FIELDS 插入 `"field_fullpath"` ⇒ `taskFieldsCompleteAndNoFullpath` 判红（12 例 2 失败）。`[DONE 变异B]`
  - 变异 C Proxy 只处理两个方法：`"z" -> null` → `"Z" -> null` ⇒ `listenerMethodsLocked` + `mutationProxyTwoMethodsIsDetected` 判红（12 例 2 失败）。`[DONE 变异C]`
  - 恢复后重编译 + 全量单测 `OK (1165 tests)`。`[DONE 闸3]`

## 结论

三闸全绿：`assembleDebug` PASS；全量单测 1165 例 0 失败；3 个真实源码变异全部判红、恢复后全绿。
未装机、未真机测试、未 git commit。产物：`wxgroup/app/build/outputs/apk/debug/app-debug.apk`。
`[DONE ALL]`

---

## R86b 真机首跑发现的 bug + 修复（2026-11-24 00:1x）

装机后首次真机跑 `op=b6step sub=on --es talker 44408694054@chatroom --el svrid 19733550`，arm **失败**：
```
B6X class ltj.i FAIL XposedHelpers$ClassNotFoundError
B6X arm failed: IllegalStateException: ltj.i missing
B6X STATUS enabled=true installed=true classes=true   ← 绿，但只算 d3/g9/tj.k/mq.k 四色，不含 listener
```
- **根因**：DEX 类型描述符 `Ltj/i;` 的 Java 类名是 `tj.i`（前导 `L` 是描述符标记，不是包名首字符）。子代理把 design 文档里的描述符 `Ltj/i` 直译成 `ltj.i`，`findClass` 永远找不到 ⇒ arm 在取 xml 之前就抛出，checkExist 根本没下发。旁证：同 dex 的 `Ltj/k;` 被正确写成 `tj.k`（ForwardCdnFieldHook:64 真机验证过），同批类一个对一个错。
- **修复**：抽 `LISTENER_CLS = "tj.i"` 常量，3 处引用（install findCls / arm findCls / shape WARN）全改走常量；单测 `listenerMethodsLocked` 加两条断言锁住「常量 = tj.i」且「代码不得出现 `"ltj.i"` 字面量」。
- **M6 变异验证**：把常量改回 `"ltj.i"` ⇒ `listenerMethodsLocked` 判红（12 例 1 失败），恢复后绿。
- **三闸重跑**：compileDebugUnitTestKotlin OK；全量单测 `OK (1165 tests)`；assembleDebug 见下方装机记录。
- **教训**：design 文档的 DEX 描述符写法（`Ltj/i;`）不能直接当 Java 类名用；`status classes=true` 的口径只覆盖四类，listener 类失败被掩盖，后续探针应把 listener 也纳入 status 判定。

## R86b 真机第二跑 bug + 修复（00:16~00:19）

类名修好后重跑，arm 从 `ltj.i missing` 前进到 `B6X arm skip reason=g9c_null`——a3 拿到 r0.u 了，但 `g9.c(u,"msg","")` 返回 null。

- **根因**：msgId（本地 8 位，日志 `srcMsgId=19733550`）≠ msgSvrId（服务端 19 位）。a3 的第二参是 msgSvrId，探针广播只拿到 msgId ⇒ 用错 id ⇒ 取不到 xml。
- **修复**：取参改双路 `for (m in arrayOf("a3","Q2"))`——a3(talker,msgSvrId) 优先，失败回退 Q2(talker,msgId)（ForceForward.resolveImgLocalId:1419 真机验证过同 storage 并行入口，都返回含 u=xml 的 r0）。日志带 `via=a3|Q2`。**只读、不改判据、不改下发链**。变量 `via` 曾与下发路径重名导致编译冲突，改名 `xmlVia`。
- **M7 变异**：去掉 Q2 ⇒ `mapKeysComplete` 判红，恢复后绿。全量 OK 1165 tests。

## R86b 真机第三跑：广播传参路证伪 → 改转发当下自动 arm（00:20~00:24）

双路取参 + 带/不带冒号 talker 均 `u=blank`（a3/Q2 都返回 r0 但 r0.u 空串）⇒ **广播手工传参这条路不成立**：手上只有本地 msgId=19733550，没有 19 位 msgSvrId，任何组合都命中不了 ImgInfo2 记录。上一跑的 `g9c_null` 实为 `u=""` 传给 g9.c 的后果。

- **改法**（回设计 §5.3 接入点）：新增 `armFromForward(talker,msgSvrId)`，`FileSender.send` 前用 `forwardCtx` 的真实 `sourceChat`/`msgSvrId` 自动 arm（与 ForwardCdnFieldHook.arm 同处）。默认 enabled=false ⇒ 零动作；广播 `b6step sub=on` 只开 enabled、不传参（缺参分支 WARN 不 arm）。判据/红线/下发链不变。
- **M8 变异**：注释掉 FileSender 接线 ⇒ `wiringPresent` 判红，恢复后绿。全量 OK 1165 tests。
- **真机新操作**：先 `sub=on`（不带 talker/svrid）→ 用户长按坏图走「转发（模块）」发一次 → 自动 arm 用真实 ctx → 拉 `B6X arm try / C2`。

## R86b 真机第四跑：自动 arm 命中真实 xml，但 map 缺 cdnbigimgurl 键 → 加正则回退（00:26~00:30）

自动 arm 用真实 ctx 成功：`B6X arm try a3 uLen=1334`（msgSvrId 对了），但 `no_fileId via=a3`。诊断日志显示 map 前 20 键含 `.msg.img.live.hdfileid` / `.msg.img.$cdnthumbaeskey` / `.msg.img.$hevc_mid_size` 等，**无 `.msg.img.$cdnbigimgurl`**；而同次转发 `StockCdnProbe SCD2 g9c` 用正则对同一 r0.u 打出 `cdnbigimgurl bigLen=194`。

- **根因**：这条坏图的 g9.c map 结构与 ForwardCdnFieldHook 历史样本不同，map 键取不到原图身份；但 xml 原文里 `cdnbigimgurl="..."` 确实存在（bigLen=194=态① fileId 长度，值即裸 fileId，非完整 URL）。
- **修复**：取参改 **map 键优先 → xml 原文正则回退**（`cdnbigimgurl=\"([^\"]*)\"` / `aeskey=` / `md5=`，与 SCD2 g9c 同款），日志带 `idVia=map|regex` + `fidPre/md5Pre`。诊断日志 uPre 去换行（换行会把 keys 段拆到无前缀行，之前误判 map 无键）。只读，不改判据/红线/下发链。
- **M9 变异**：去掉 Regex(RE_CDNBIG) 回退 ⇒ mapKeysComplete 判红，恢复后绿。全量 OK 1165 tests。
- **待验**：正则回退后 fileId 能否填进 task、`B8/m1.e` 是否下发、C2 是否命中原图（exist=true + md5 非 0ef205）。

## R86b 真机第五跑：属性键名诊断 + fileId 三候选链（00:33~00:36）

第四跑正则回退只找 cdnbigimgurl 仍 `no_fileId`。把 uPre 打全后看到源消息 xml：
`<img aeskey="60af7983..." encryver="1" cdnthumbaeskey="..." cdnthumburl="305f020100044b3049..." ...>`
——**无 cdnbigimgurl，有 cdnthumburl=305f0201...（194B）**，而态① stock 转发的 m1e fileId fidPre 正是 `305f0201`（194B），两者逐字同。

- **推断（待 C2 坐实）**：checkExist 该喂的 fileId = 源消息 `cdnthumburl` 值，不是文档假设的 cdnbigimgurl；stock 用它秒传命中服务端原图。
- **修复**：加 `B6X arm attrs` 诊断（img 属性键名:长度，只键名不打值，一次看清所有 CDN 身份键）；fileId 回退链从单一 big 正则扩为 `RE_FILEID_CANDIDATES = big→mid→thumb` 三候选，`idVia=map|regex:big|regex:mid|regex:thumb` 打标。裁决仍看 C2 回包 exist/filemd5，不预设哪个候选是原图。
- **M9 变异**：删 thumb 候选 ⇒ mapKeysComplete 判红，恢复后绿。全量 OK 1165 tests。
- **待验**：attrs 诊断打出真实键名全集；idVia 是否 regex:thumb + fidLen=194/fidPre=305f0201；C2 回包 exist/filemd5。

## R86b 最小步真机裁决数据（00:50，C2 未命中）

归档：`docs/_r86b_b6step_state_final_raw.txt`（221 行）。关键行：
```
00:50:22.897 B6X arm attrs a3 version:3,aeskey:32,encryver:1,cdnthumbaeskey:32,
   cdnthumburl:194,...,cdnmidimgurl:194,...,md5:32,...,originsourcemd5:32   ← img 节点全部键；无 cdnbigimgurl
00:50:22.898 B6X arm xml via=a3 idVia=regex:mid fidLen=194 fidPre=305f0201 aesLen=32 md5Len=32 md5Pre=ca47f83e
00:50:22.899 B6X arm sent fidLen=194 aesLen=32 md5Len=32 existHook=true via=B8 fields=12
00:50:22.931 [GT]ColdPool#7 B6X C2 exist=NA md5Pre=NA md5Len=-1       ← 第一次：空壳（字段未填）
00:50:23.056 [GT]HotPool#4 B6X C2 exist=false md5Pre= md5Len=0        ← 第二次：真实回包，exist=false
```

### 客观事实（不解释因果）
1. **attrs 证实**：源 xml img 节点有 `cdnthumburl:194`、`cdnmidimgurl:194`、`md5:32`(=ca47f83e)、`aeskey:32`、`originsourcemd5:32`，**无 cdnbigimgurl**。
2. **取参**：候选链 big→mid→thumb 中 mid 先命中，`idVia=regex:mid`，fileId=fidPre `305f0201`（194B）——与态① stock 原生转发的 m1e fileId 逐字同；md5=`ca47f83e`（原图身份）。
3. **下发**：`via=B8 fields=12`，自建 Proxy listener 挂上（`existHook=true`）。
4. **回包**：第一次 C2 `exist=NA`（字段未填空壳），第二次 `exist=false md5Len=0`——与 SCD-CDNcb 历史真机形态（先 NA 后 true）一致 ⇒ 第二次是真实回包，**checkExist 未命中原图**。
5. **输入与 stock 相同但结果不同**：fileId/md5/aesKey 都用 stock 的值，stock 态① 清晰且 hit=1，我方 checkExist 却 exist=false。

### 按设计 §5.3 唯一裁决点判读（待 Pro 复核）
- 设计 §5.3：C2 出现 `exist=true` 且 md5 非 0ef205 ⇒ 命中进第二步；否则按 Q4 判据 D 回退、全量复刻 no-go。
- 本轮 `exist=false md5Len=0` ⇒ **未命中**，按判据 D 应回退。
- 设计 Q5 回退原因①已列：「fileId 305f... 可能过期 / 服务端无该原图 ⇒ checkExist 命中但回包仍缩略图」——本轮连 exist 都是 false，比"命中但回包错"更前一步。
