# R42 写前缀最小验证（真机，2026-11-07 21:25）

包：`com.lx.wxgroup` 21:22:48（含 `SnsPrefixProbe`，dex 校验命中 `classes4/5.dex`）。
微信：8.0.48 / 2588 GP。设备 `467cf5bc`。

## 一、样本

| 项 | 值 |
|---|---|
| snsId | `-3397130994131660261` |
| sourceType | `10`（被 `keep = old \| 10` 拦下的「作者已删」动态） |
| 作者 | `verity` |
| 原始 content 长度 | 975 字节 |
| 原始正文 | `4`（单字符） |
| 写入前 hasPrefix | false |

选它的理由：它本身就是真实场景（作者已删、被我们拦下、时间线仍可见），不是人为构造的样本。

## 二、四步读数

### 1. 只读 probe（写前）

```
PREFIX probe id=-3397130994131660261 len=975 desc=[4] hasPrefix=false
```

三关全过：`snsDbHandle()` 拿到 wcdb 句柄、`TimeLineObject.parseFrom` 解析成功、
`ContentDesc` 字段可读。

### 2. 写入

```
PREFIX write id=-3397130994131660261 before=4 after=[已删除] 4 rows=1 backup=ok(975B)
```

- `rows=1`：只改了一行。
- `backup=ok(975B)`：原始 blob 已备份到 `filesDir/sns_prefix_backup/<snsId>.bin`。
- 只 `update` 了 `content` 一列（`ContentValues{content=…}`），其它列未动。

### 3. 回读

```
PREFIX probe id=-3397130994131660261 len=987 desc=[[已删除] 4] hasPrefix=true
```

长度 975 → **987**，正好 +12 字节：`[`(1) + 已(3) + 删(3) + 除(3) + `]`(1) + `\n`(1) = 12。
与取证报告的 12 字节一致。

### 4. UI 渲染（冷启后）

时间线第 4 屏：

```
  555  1小时前
  658  verity                    ← 作者
  784  [已删除]&#10;4            ← 前缀 + 换行 + 原文
  974  图片第1张共1张            ← 图片还在
```

**图片未丢** ⇒ `toByteArray()` 把 `ContentObj`（field 8，含媒体列表）完整写回，
序列化路径没有丢字段。

### 5. 好友主页相册（灰标从未覆盖到的页面）

`SnsUserUI`（`ListView` + `HeaderViewListAdapter` → `com.tencent.mm.plugin.sns.ui.zl`）：

```
1414 | [已删除]&#10;4，包含一张图片
1414 | 今天,[已删除]&#10;4，包含一张图片
```

**主页也显示前缀**。这一页的 `onBindViewHolder` 钩子从来不触发（`binds` 零增量），
灰标方案在这里完全失效；写前缀靠数据本身，天然覆盖。

### 6. 下拉刷新后复查

刷新两次后滚到第 2 屏：

```
  249  1小时前
  352  verity
  478  [已删除]&#10;4
  668  图片第1张共1张
```

**前缀保持**，微信没有把它覆写回原文。

## 三、结论

| 验证项 | 结果 |
|---|---|
| 微信能正常渲染带前缀的 content | ✅ |
| 序列化不丢字段（图片仍在） | ✅ |
| 时间线可见 | ✅ |
| 好友主页相册可见（灰标做不到） | ✅ |
| 冷启后仍存在 | ✅ |
| 下拉刷新后不被覆写 | ✅ |
| 改动范围 = 单行单列，可还原 | ✅ |

**写前缀方案成立**。它把「每次页面重构都要反推 snsId」降为「删除那一刻写一次」，
并且顺手解决了主页相册无标识的问题。

## 四、尚未验证（本轮没做的）

1. **服务端同步覆盖**：作者已删，服务端无此条，理论上不会覆盖；但没等到一次完整同步周期。
2. **自动写前缀的接入点**：本轮是手动探针，正式方案要接进删除钩子（见下）。
3. **开关语义**：用户要求「取消开关」，具体形态待确认。
4. **批量历史动态**：13002 条是微X 历史上写的，我们不会回溯。

## 五、还原方式

```
am broadcast -a com.lx.wxgroup.action.PROBE --es op snsprefix --es sub restore --el id -3397130994131660261 -p com.tencent.mm
```

备份文件在 `filesDir/sns_prefix_backup/-3397130994131660261.bin`（975B，原始版本）。
`sub=stat` 可列出全部备份。

## 六、实现侧记下的两个坑（来自 `prefixwriter`）

1. **不能把 db 强转成 AOSP `SQLiteDatabase`**：朋友圈库是 wcdb 的
   `com.tencent.wcdb.database.SQLiteDatabase`，super 是 `SQLiteClosable`，与 AOSP 无继承，
   强转恒 null。必须反射 `rawQuery`/`update`。
2. **`snsDbHandle()` 早已存在**（`SnsGuard.kt:860`），本轮只补了宿主 `ClassLoader`。

## 七、下一步（待用户确认后实施）

正式方案：在 `SnsGuard` 的 `updateWithOnConflict` before 钩子里，**把 content 也塞进
同一个 `ContentValues`**，让微信这一次 update 同时完成「保位 + 加前缀」。这样：
- 只有一次写，不额外开事务、不与微信的写竞争；
- 幂等靠「读到的 ContentDesc 已以 `[已删除]` 开头就跳过」；
- 无需再依赖 `sns_deleted` 表与渲染期钩子。

同时按用户指令：删掉灰标整套（`SnsDeletedTag*` 约 1100 行 + 测试）、移除开关。
