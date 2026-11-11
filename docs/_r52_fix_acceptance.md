# R52 修复 · 装包与真机验收清单（设备恢复连接后照做）

> 生成于 2026-11-11 02:20。**当前状态：代码/编译/单测/独立复核全部通过，APK 已就绪，但装包未执行**
> —— 02:13 手机 USB 从系统层消失（`adb devices` 空、`Get-PnpDevice` 无手机 VID），主会话无法恢复。
> 本文件是「重新插上线后一步不落地跑完」的操作单，⛔ 不含任何未验证的功能结论。

---

## 0. 当前已证事实（可直接引用）

| 项 | 结果 |
|---|---|
| 编译闸门 | `assembleDebug compileDebugUnitTestKotlin` **BUILD SUCCESSFUL in 21s** |
| 单测 | `bash tools/_junit.sh`（项目根）**OK (901 tests)**；`MediaDeleteLogicTest.class` mtime 02:11:27 ⇒ 读到的是新 83 例 |
| 独立 Pro 复核 | **可以装包，P0 = 0**（C1~C12 全 PASS，C13 已补齐，C14 `[需真机]`） |
| APK | `wxgroup/app/build/outputs/apk/debug/app-debug.apk` 3083191B，sha256 `19a8033732487b6587287b44d5c7bb899319faa6cfddd709506bd5dd39c610e9` |
| 源码 hash | `MediaDeleteLogic.kt` `b1893060…`(387行) / `MediaTableGuard.kt` `a1c4dc3e…`(544行) / `MediaDeleteLogicTest.kt` `c27fb77f…`(765行) |
| 设备侧现状 | 装包前为探针版 `lastUpdateTime=2026-11-11 00:59:05` |

## 1. 装包（连接恢复后第一件事）

```bash
ADB=/c/Users/lx/AppData/Local/Android/Sdk/platform-tools/adb.exe
"$ADB" devices -l                       # 必须看到 467cf5bc  device
cd /c/Users/lx/WorkBuddy/2026-09-29-20-23-21
"$ADB" install -r wxgroup/app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell dumpsys package com.lx.wxgroup | grep lastUpdateTime   # 必须由 00:59:05 变为新值
```

**硬判据**：`lastUpdateTime` 变化 + `versionName=0.2.8`。⛔ 只看 `Success` 不算装成功。

## 2. 冷启两次 + 验挂

```bash
cd /c/Users/lx/WorkBuddy/2026-09-29-20-23-21
python tools/dev.py restart      # 冷启一次
python tools/dev.py restart      # 再冷启（装包后常不重载，需两次）
# 微信必须在前台，否则广播不投递
"$ADB" shell am start -n com.tencent.mm/.ui.LauncherUI
python tools/dev.py probe --es op mtg --es sub stat
```

期望 `MTG stat ... enabled=true gen=0 ... pool=ok`；`enabled=false` ⇒ 先 `kvget antirevoke.media_delete`，
若为 false 则 `kvset antirevoke.media_delete=true` 后**再冷启**（`refresh()` 只在 `install()`/`invalidate()` 调）。

## 3. 开关前置（⛔ 缺一白测）

| 键 | 期望 | 说明 |
|---|---|---|
| `antirevoke.media_delete` | `true` | 本轮修复的开关，关了整套 `MediaTableGuard` 空转 |
| `antirevoke.sns` / `antirevoke.sns_block` | 不动 | 与本轮无关，⛔ 别顺手改，保持自变量唯一 |
| `chat.auto_hd_img` | `true` | 挡在 `HDIMG trace` 之前，关了读不到诊断行 |

## 4. 测前清场

```bash
python tools/dev.py probe --es op logclear      # 防 LogX 满 5000 行整文件清空冲掉证据
python tools/dev.py probe --es op mtg --es sub reset   # 只清账本，不动 enabled/cachedRowIds
python tools/dev.py probe --es op label --es sub on --ei sec 180   # 开主库捕获窗口
# 撤前基线：记下 ImgInfo2 max(id)
```

## 5. 请 verity 配合（唯一需要人的一步）

1. 在「**测试群聊**」发**一张新图**（⛔ 不能用 23:55 / 00:13 / 01:03 三张老图，它们的 `ImgInfo2` 行已被真删、不可回滚）；
2. **先别点开**；
3. 3~5 秒后撤回。

撤后立刻抓：

```bash
python tools/dev.py q 'MTG|RRV|VFG|HDIMG' 40
python tools/dev.py probe --es op mtg --es sub stat
```

**期望**（正向）：`MTG id-lookup md5=<md5> rows=1 ids=[<id>] cost=?ms` → `MTG delete table=ImgInfo2 where=id=? reason=id-hit` → `MTG stat ... blocked=1`。
`rows=0` 且带 `MTG id-lookup MISS` ⇒ 反查没查到行（P1 第 2 条：`thumbImgPath` 列形态），**不是**功能失败；
按 D3 停手取证，⛔ 不要加第三条判据。

## 6. 主判据（⛔ 唯一）

隔 3~5 分钟后点开那张图，**能否看到完整大图**。

- `MTG blocked=1` / `seen` / `matched` / `rows` / `HDIMG img!=null` **全部只作定位，不作结论**；
- 辅助：`HDIMG trace ... fld[h/i/j/o]` 与 `img=`。基线对照：
  - 修复前（01:06 实测）：`h=0 i=0 j=0 o=0`、`img=null`
  - 正常图：`h/i/j=875524`、`img=SERVERID://…`

### 结果三分（互斥）

| 结果 | 判定 | 下一步 |
|---|---|---|
| `h/i/j≠0` + `img=SERVERID://…` + **看到完整大图** | ✅ 修复成立 | 做第 7 步反向对照后收工 |
| `ImgInfo2` 行在（`n: 1→1`）但 `h/i/j` 仍 0、`img=null` | ⛔ 命中**甲-7** | 按 **D3 停手**：撤前/撤后行快照对比 `status`/`iscomplete`/`bigImgPath`/`msgSvrId`，查撤回瞬间有无 `ImgInfo2` 的 **UPDATE**（本轮只拦 delete）。⛔ 不许加第三条判据、不许改渲染层 |
| 行不在（`n: 1→0`） | ⛔ 拦截没生效 | 查 `MTG delete ... reason=`：`reason` 非 `id-hit` ⇒ 判据未命中；完全无 `MTG delete` 行 ⇒ 开关/钩子问题 |

## 7. 反向对照（自变量唯一）

只翻 `antirevoke.media_delete=false`，其它开关不动 → 冷启 → 请 verity 再发一张新图撤回 → 点开。
期望：`MTG` 零拦截、图片打不开。⛔ 这一步失败说明「打不开」与开关无关，前面的正向结论要重审。

## 8. 回滚（⛔ 顺序硬约束）

两文件均 untracked（`git status` = `??`），`git revert` 会删文件、R51 原判据不在 git 历史。

```bash
cd wxgroup
git apply ../_cache/r52_revert_guard.patch   # ① 必须先前：Guard 回 R52 探针版（412 行 / 21324B 基线）
git apply ../_cache/r52_revert.patch         # ② 后：MediaDeleteLogic 回 R51 判据
```

反过来先删 `protectedRowIds` 参数 ⇒ Guard 侧 8 参调用**编译失败**。

⛔ 严禁 `git checkout .` / `git checkout -- <file>` / `git reset --hard` / `git clean -fd`：
`EntryReceiver.kt` 混有 R49 阶段二的 11 行 `SnsWriteProbe`，`MediaTableGuard.kt`、`MediaDeleteLogic.kt`、
`SnsWriteProbe.kt` 及两个测试文件都是 untracked，整文件回滚会丢基线。

## 9. 已知 `[需真机]` 未验项（复核 P1，四条）

1. **甲-7**：拦住 delete、`ImgInfo2` 行保留后，微信是否仍把 `h/i/j` 填 0（→ 第 6 步结果 ②）。
2. **`thumbImgPath` 列真实存储形态**（TEXT/BLOB/NULL/`wcf://`）—— 决定 `instr(thumbImgPath, 裸md5)` 是否命中；
   不命中会打 `MTG id-lookup MISS`。
3. **反查竞态窗口 ~10–25ms**：删除在 `[D]#worker`、反查在 `[GT]ColdPool`；`cost=?ms` 用于证明赶得上。
4. **`execSQL` void 跳过写法**（`param.result=null`）：非主路径，决定性证据已证撤回走 `delete()`。

## 10. 判据纪律（贴在最后，别忘）

- 对着**用户能看见的事实**写，别对着内部计数器；
- 互斥判据别写成并列；
- 变异验证先确认变异有语义（等价变异 0 条红 = 假验证）；
- 「日志没有」≠「没发生」：LogX 满 5000 行是 `writeText` 整文件清空重来，先 `q 'log rotated'`；
- 判断源码有没有被改看 **sha256**，不看 mtime（变异 restore 会重写文件）；
- `bash tools/_junit.sh` 在编译闸门跑完前读的是旧 class，会给出 46 例的假判据。
