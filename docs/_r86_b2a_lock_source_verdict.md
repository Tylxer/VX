# R86 B2-a 补锁同源取证（2026-11-23 23:08~23:10）

## 0. 目的与手法
- **目的**：补 `docs/_r86_b2a_pro_verdict_v2.md` 判4 留下的缺口 —— 锁定态①/态②是**同一条源图消息**，让 A/B 唯一自变量成立。
- **手法**：开双探针 `op=inp` + `op=stockcdn`，每态间 `logclear`。`INP j2init` hook 挂在**宿主** `modelimage/j2` 构造器（stock 原生转发与模块转发都经过），其 `ori=` 是宿主侧源身份锁行 —— 正是上次态①缺的那一行。
- **本次仅只读取证**，不改源码、不写 hook。

## 1. 态① stock 原生转发 = 清晰（时间戳 23:08:17~18）
来源：`docs/_r86_b2a_lock_state1_raw.txt`（433 行原始日志）

| 探针行 | 取值 |
|---|---|
| `INP j2init ori`【真机坐实·宿主锁行】 | `.../image2/21/6b/216b1f7ccd59b1268349fd0eb3c46801.jpg`（**原图身份，无 `th_`**） |
| `SCD m1e task fileId` | `305f0201...`（194B，非空） |
| `SCD m1e field_fullpath` | `.../image2/ea/cd/eacdf2dd...jpg_` |
| `SCD2 c2c` | `pathTail=4fc.jpg_hevc fidLen=194 enableHit=true` |
| `SCD2 x0cb` | `md5=ca47f83e hit=1 exist=true fidLen=194` |
| `SCD f2C2 field_*` | `field_UploadHitCacheType=1 field_exist_whencheck=true` |

肉眼：**清晰**。

## 2. 态② 模块转发 = 模糊（时间戳 23:09:34~45）
来源：`docs/_r86_b2a_lock_state2_raw.txt`（186 行原始日志）

| 探针行 | 取值 |
|---|---|
| `FORWARD resolveImage`【真机坐实·模块侧】 | `kind=IMAGE md5=216b1f7ccd bucket=21/6b pick=th_216b1f7ccd59b1268349fd0eb3c46801 exists=true` |
| `FORWARD plan` | `strategy=RESEND_FILE reason=原生转发不可用，降级重发文件 picked=1` |
| `FORWARD P3 origin-by-md5` | `miss reason=no-exist` |
| `INP j2init ori`【真机坐实·宿主锁行】 | `.../image2/21/6b/th_216b1f7ccd59b1268349fd0eb3c46801.jpg`（**缩略图身份，`th_` 前缀**） |
| `SCD m1e task fileId` | 空 |
| `SCD m1e field_fullpath` | `.../image2/89/26/89265da3...jpg_` |
| `SCD2 c2c` | `pathTail=57e.jpg_hevc fidLen=0 enableHit=true` |
| `SCD2 x0cb` | `md5=0ef205c1 hit=3 exist=true fidLen=194` |
| `SCD f2C2 field_*` | `field_UploadHitCacheType=3` |

肉眼：**模糊**。

## 3. 锁源结论（仅事实层，不新造因果）
- **【真机坐实】同一源图**：两态 `j2init ori` 的 md5 主体逐字相同 = `216b1f7ccd59b1268349fd0eb3c46801` ⇒ 同一条源图消息确认。v2 判4「两态可能不同源」的担忧**被排除**。
- **【真机坐实】唯一自变量**：喂给上传链路的**源身份** —— 态①原图身份（无 `th_`）、态②缩略图身份（`th_` 前缀）。其余条件（同一消息、同一接收方 filehelper、同一设备/探针）相同。
- **【源码确定 + 真机坐实】分叉点**：态② `FORWARD resolveImage pick=th_` + `P3 origin-by-md5 miss(no-exist)`（原图 `216b` 在磁盘是 36B 占位，P3 判 no-exist）→ 降级 `RESEND_FILE` → 上传任务 `fileId` 空、`x0cb` 回包 `md5=0ef205c1`（缩略图身份）。
- **【真机坐实】旁证**：态②后台 `g9c` 同时出现 `md5Pre=f6ab3ac5`（th_ 缩略图 md5）与 `md5Pre=ca47f83e`（原图身份）⇒ 原图身份在消息里确实存在，只是模块没选它 —— 印证 v2「B6 复刻能拿原图 URL」的前提成立。

## 4. 与 v2 判定的关系
- v2 判4 要求「补 ori/md5 锁行 + 同态开关翻转」：本次 **ori/md5 锁行已完成**；同态开关翻转在「同一源、两态分别跑」下隐含满足。
- 结论升级：v2「A/B 唯一自变量**未验证** → B2 命题待补证」⇒ 本次补成「A/B 唯一自变量**已验证成立**」，差异可归因于源身份选择（原图 vs 缩略图），与 v2 裁定「决定量 = 回包内容身份（ca47 原图 / 0ef205 缩略图）」一致。
- **未产生态③**：B2 命题的终极验证（实现 B6 复刻后仅缩略图仍清晰）仍需写 hook 实现 + 真机重测，本次取证不覆盖。

## 5. 收尾
- 双探针取证完成，待关闭（`op=inp sub=off` + `op=stockcdn sub=off`）。
- 源码未改、未提交、未推送。
