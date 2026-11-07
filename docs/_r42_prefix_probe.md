# R42 前缀取証报告（prefixprobe）

> 任务：只读取证"把 `[已删除]` 写进 `SnsInfo.content`"的可行性。
> 铁律：只读 SELECT/PRAGMA、不整库 pull、不装包/commit/改屏幕、分步落盘。

进度占位（完成即追加）：

---

## [DONE-1] 样本抽取

用 `instr(content, char(91,24050,21024,38500,93))>0` 抽取（**LIKE 对 BLOB 列无效，必须用 instr**——本代理实测 `LIKE char(37,...)` 返回 0 行，`instr` 返回 13002）。

带前缀样本（4 条，取前 3 条做解析）：

| # | snsId | length(content) | f5 正文 |
|---|---|---|---|
| r1 | -4081611818289262075 | 1064 | `[已删除]\n等康复后好好锻炼，不瞎吃垃圾食品，饮料，每天换药太痛了。` |
| r3 | -4123009097890590129 | 1021 | `[已删除]\n新年快乐！[烟花][烟花][烟花]` |
| r4 | -4140048690341342639 | 1484 | `[已删除]\n一起帮忙来投票[抱拳]` |

对照（无前缀）样本（2 条）：

| # | snsId | length | f5 正文 |
|---|---|---|---|
| c1 | -4080918751735041453 | 1003 | `看到的都，福来` |
| c2 | -4080931164641881443 | 1294 | `📦香港澳门快件发德邦\n🤩安全快速服务全\n🚚产品齐全 \| 直营品质\n🫡高效通关 \| 全境派送\n👇下单速戳mas.deppon.com/s/CTIUV2` |

**结论**：content 是 SnsObject（TimelineObject）的序列化 protobuf。f5 是正文字段。带前缀样本的 f5 一律以 `[已删除]\n` 开头；对照样本 f5 是纯正文。

---

## [DONE-2] protobuf 逐字段解析结果

对 r1（1064 字节）完整 wire-format 递归解析。**顶层字段号 → 类型 → 含义**：

| 字段号 | wire 类型 | 值 | 含义（推断） |
|---|---|---|---|
| 1 | 2(len) | 20 字节 ASCII `14365132255420289541` | objectId（=snsId 的字符串，20 位） |
| 2 | 2(len) | `wxid_r49pks9ebjqp11` | username（作者） |
| 3 | 0(varint) | 0 | （flags，0） |
| 4 | 0(varint) | 1712457210 | createTime（unix 秒） |
| **5** | **2(len)** | **96 字节：`[已删除]\n等康复后好好锻炼…`** | **ContentDesc（正文）** |
| 6 | 2(len) | 38 字节嵌套（全 0/空，疑似 ContentObject/版本结构） | 内容对象/权限位 |
| 7 | 2(len) | 14 字节嵌套（全空） | 附加结构 |
| 8 | 2(len) | 686 字节嵌套 | 媒体列表（含图片 url、缩略图、点赞等） |
| 9~28 | 混合 | 大多空/0 | 位置、可见性、评论、转发等其余字段 |

**正文在字段 5（f5，wire type 2 length-delimited string）**。`[已删除]` 前缀位于 **f5 值内部的偏移 0**（即 f5 值的第一字节就是 `5B`=`[`）。

f5 的精确字节：`5B E5B7B2 E588A0 E999A4 5D 0A` + 原文。
- `5B`=`[`，`E5B7B2`=已，`E588A0`=删，`E999A4`=除，`5D`=`]`，`0A`=换行符 `\n`。

r3、r4 的 f5 前 12 字节与 r1 完全一致（`prefix_match=True`），正文不同。

---

## [DONE-3] 前缀插入的精确定位与长度变化

- **插入位置**：f5（正文）字段值的最开头。插入字节 = `5B E5B7B2 E588A0 E999A4 5D 0A`，共 **12 字节**。
- **换行符**：有。是**单个 `\n`（0x0A）**，不是 `\r\n`（0D 0A）。该 `0A` 位于 f5 的 length-delimited 值内部，不是 protobuf 的 field tag（field tag 的 `0A` 是 field1/wire2；此处由 f5 的 length 字段交叉确认：r1 f5 len=96 = 12 前缀 + 84 原文，r4 f5 len=41 = 12 + 29 原文，r3 f5 len=51 = 12 + 39 原文，三处吻合）。
- **外层长度字段变化**：
  - f5 的长度 varint：r1 原文 84(0x54) → 96(0x60)，**+12**；r4 原文 29(0x1D) → 41(0x29)，+12。三者均为单字节 varint（<128），**长度字段字节数不变**（仍是 1 字节），仅值 +12。
  - **没有嵌套长度**：f5 是叶子 string，不是子消息，故不存在"内层长度"。
  - 整个 content BLOB 长度 = 原长 +12（SQLite 存 BLOB 自带字节长，无前缀）。
  - 其余字段（f6/f7/f8 及之后）**字节不动**，只是整体后移 12 字节。

---

## [DONE-4] 13002 条的格式一致性统计

| 检查 | SQL | 结果 |
|---|---|---|
| 含 `[已删除]` 总数 | `instr(content, char(91,24050,21024,38500,93))>0` | 13002 |
| 含 `[已删除]\n`（前缀+换行） | `instr(content, char(91,24050,21024,38500,93,10))>0` | **13002** |
| `[已删除]` 但紧跟非换行 | 含 `[已删除]` 且不含 `[已删除]\n` | **0** |
| 首字节 | `GROUP BY hex(substr(content,1,1))` | 全部 262562 条首字节 = `0A`（f1 tag） |
| 前缀在 content 内的偏移 | `min/max/avg(instr(content,char(91,24050,21024,38500,93)))` | min=37, max=246, avg=98.08 |

**结论**：13002 条格式**完全统一**——`[已删除]` 后必跟 `\n`（0 条例外）。前缀偏移随 f2（username）长度变化（37~246），证明它固定贴在 f5 值开头（f1~f4 长度可变导致绝对偏移不同），而非固定字节位置。

### attrBuf 的 2 条"已删除"特例（A.4）
- 两条 snsId：`-3727125053249342975`（attrBuf 420 字节）、`-3576854013617630608`（659 字节）。
- 定位：`已删除` 出现在 attrBuf 内**评论（comment）子结构的 content 字段**，形式是 **`<评论文本> [已删除]`（前有空格、后缀式）**，与 content 的 `[已删除]\n`（前缀式、无空格、带换行）**不同**。
  - ab1 评论：`[咩牛]是满 [已删除]`；ab2 评论：`不是我[捂脸] [已删除]`。
- 结论：attrBuf 里的"已删除"是**评论级删除标记**（WeChat 删除评论时把原评论文本 + ` [已删除]` 后缀写进评论 content），与本任务"朋友圈正文 content 前缀"是**两个不同机制**。数量仅 2 条，可忽略。

---

## [DONE-5] dex 侧：protobuf 类 + 序列化方法 + 正文字段名

**content BLOB 的类 = `com.tencent.mm.protocal.protobuf.TimeLineObject`**（classes3.dex），extends `com.tencent.mm.protobuf.a`（classes4.dex）。
- 关键证据：`SnsInfo.getTimeLine()` 返回 `Lcom/tencent/mm/protocal/protobuf/TimeLineObject;`；且 field_id 表中 `TimeLineObject.Id : Ljava/lang/String;`（field#18676）与样本 f1 的 20 字节字符串吻合。**注意**：`SnsObject` 是姊妹类（`Id : J` 是 long，网络层用），**不是** content 的类——content 是 `TimeLineObject`。

**序列化方法**（基类 `com.tencent.mm.protobuf.a`，classes4.dex）：
- `toByteArray()[B` — 序列化
- `parseFrom([B)Lcom/tencent/mm/protobuf/a;` — 反序列化（返回值是 `a`，需 cast 成 TimeLineObject；TimeLineObject 另提供 `create()LTimeLineObject;` 工厂）
- 其余：`computeSize()I` / `writeFields(Lkv4/a;)V` / `op(I[Ljava/lang/Object;)I`（字段号反射分发）

**正文字段名 = `ContentDesc`**（`TimeLineObject.ContentDesc : Ljava/lang/String;`，field#18673）。setter `setContentDesc(String)` 已确认（`iput-object TimeLineObject->ContentDesc`）。无 getter，字段是 public，直接 `tlo.ContentDesc` 读。

**字段号 ↔ 字段名完整映射**（`op()` 分发顺序 + field_id 表 + 样本 wire 解析三源互证）：

| 字段号 | 字段名 | 类型 |
|---|---|---|
| 1 | Id | String（20 位 id） |
| 2 | UserName | String |
| 3 | Privated | int |
| 4 | CreateTime | int |
| **5** | **ContentDesc** | **String（正文）** |
| 6 | Location | Lq54/eh3 |
| 7 | AppInfo | Lq54/u6 |
| 8 | ContentObj | Lq54/i40 |

---

## [DONE-6] dex 侧：按 snsId 更新 content 的入口

**模型层** `com.tencent.mm.plugin.sns.storage.SnsInfo`（classes3.dex，extends `Ljj/ca`）：
- `field_content : [B`（content 列的实体字段，声明在 `Ljj/ca`）
- `getTimeLine()Lcom/tencent/mm/protocal/protobuf/TimeLineObject;` — content BLOB → TimeLineObject（含 md5 缓存）
- `setContent(Ljava/lang/String;)Z` — 内部 `Lul0/v.w(String)` 建 TimeLineObject → `toByteArray()` → 写 `field_content`（**注意：这会重建整条，丢媒体/位置等其它字段，不适合我们的"前缀"场景**）
- `setAttrBuf([B)V` / `getPostBuf()[B` / `getSnsId()Ljava/lang/String;` / `getUserName()`

**存储层** `SnsInfoStorage` = 混淆名 `com.tencent.mm.plugin.sns.storage.d2`（extends `k74/l0` 泛型存储基类）：
- `update(JLk74/f0;)Z` — **按 long snsId 更新一行**（第二个参数是记录实体，SnsInfo 即 k74/f0 子类）★写回落点
- `replace(Lk74/f0;)Z` — insert-or-replace 一行
- `q1(J)Lcom/tencent/mm/plugin/sns/storage/SnsInfo;` — 按 snsId 取 SnsInfo
- `delete(J)Z`
- 泛型基类 `k74/l0` 另有 `insert(Lk74/f0;)Z` / `update(JLk74/f0;Z)Z`（带 notify）/ `get(JLk74/f0;)Z`

**逻辑层** `SnsInfoStorageLogic` = 混淆名 `com.tencent.mm.plugin.sns.model.l5`：
- `o(Lcom/tencent/mm/plugin/sns/storage/SnsInfo;Lcom/tencent/mm/protocal/protobuf/SnsObject;Ljava/lang/String;I)J` = **`replace`**（语义名，见 docs/_r27_m3_timeline_source.md 自证）——落库兜底入口

**写回两种可行路径**：
1. 对象级（推荐）：`si = storage.q1(snsId)` → `tlo = si.getTimeLine()` → `tlo.ContentDesc = "[已删除]\n" + tlo.ContentDesc` → `si.field_content = tlo.toByteArray()` → `storage.update(snsId, si)`。
2. 字节级（微X 疑似）：读 `field_content` 字节 → 定位 f5 值开头插入 12 字节 → f5 length varint +12 → 写回。

---

## [DONE-7] 交叉验证（A 的字段号 ↔ B 的字段名）

逐条对应，全部吻合：

| A（wire 解析） | B（dex 字段声明） | 结论 |
|---|---|---|
| f1 = 20 字节 ASCII `14365132255420289541` | `TimeLineObject.Id : String` | ✅ 完全一致 |
| f2 = `wxid_r49pks9ebjqp11` | `TimeLineObject.UserName : String` | ✅ |
| f3 = varint 0 | `TimeLineObject.Privated : int` | ✅ |
| f4 = varint 1712457210（时间戳） | `TimeLineObject.CreateTime : int` | ✅ |
| **f5 = 正文 `[已删除]\n…`** | **`TimeLineObject.ContentDesc : String`** | ✅ 关键命中 |
| f6 = 38 字节嵌套（全 0） | `TimeLineObject.Location : Lq54/eh3` | ✅ 空 Location |
| f7 = 14 字节嵌套（全空） | `TimeLineObject.AppInfo : Lq54/u6` | ✅ 空 AppInfo |
| f8 = 686 字节嵌套（图片 url 等） | `TimeLineObject.ContentObj : Lq54/i40` | ✅ 媒体列表 |

**没有对不上的字段**。字段号、类型、顺序三源（样本 hex、`op()` 分发顺序、field_id 表）完全自洽。

**对象级序列化是否与字节级等价**：`setContentDesc` 只改 `ContentDesc` 字段，其余字段（含 `unknownTagHandler` 保留的未知字段）经 `toByteArray()` 原样写出。只要序列化器确定性（字段顺序固定、未知字段保留），对象级改 f5 与字节级插前缀会产生**字节一致**的结果。故"微X 是字节级插入还是对象级序列化"从产物上**无法区分**，但两条路都可行。

---

## [DONE-8] 七个必答问题 + 风险清单

### Q1 这个 protobuf 的 schema（正文相关字段）
`TimeLineObject`（`com.tencent.mm.protocal.protobuf.TimeLineObject`）：
- field 1 `Id` string（20 位 id）
- field 2 `UserName` string
- field 3 `Privated` int
- field 4 `CreateTime` int
- **field 5 `ContentDesc` string = 正文**
- field 6 `Location` message
- field 7 `AppInfo` message
- field 8 `ContentObj` message（媒体）

### Q2 前缀的精确字节规则
- 加 **12 字节**：`5B E5 B7 B2 E5 88 A0 E9 99 A4 5D 0A`（= `[已删除]` + `\n`）。
- 加在 **field 5（ContentDesc）值的最开头**（偏移 0）。
- 唯一要改的长度字段：**field 5 的 length varint**，值 +12。r1：84(0x54)→96(0x60)；r4：29→41；r3：39→51。三者都 <128，length varint 仍是 1 字节，无级联变化。
- 无嵌套长度、无其它字段改动。整个 content BLOB 长度 +12。

### Q3 有没有换行
有。`[已删除]` 后紧跟 **单个 `\n`（0x0A）**，非 `\r\n`。该 0A 在 f5 值内部，非 protobuf tag。

### Q4 13002 条是否格式完全统一
完全统一。`[已删除]\n` 出现 13002 次（= 全部），`[已删除]` 但后非换行 = 0 条。前缀偏移随 username 长度变化（37~246），证明固定贴 f5 值开头。

### Q5 微信侧可直接调用的序列化器签名
```java
// 反序列化（基类 com.tencent.mm.protobuf.a，classes4.dex）
byte[] b = snsInfo.field_content;
TimeLineObject tlo = (TimeLineObject) new TimeLineObject().parseFrom(b);
// 改字段
tlo.ContentDesc = "[已删除]\n" + tlo.ContentDesc;   // 或 tlo.setContentDesc(...)
// 序列化
byte[] nb = tlo.toByteArray();                      // com.tencent.mm.protobuf.a.toByteArray()[B
```
可行：**构造对象 → 改字段 → toByteArray → 写回**。

### Q6 按 snsId 写回 content 的入口方法签名
```java
// SnsInfoStorage = com.tencent.mm.plugin.sns.storage.d2 (extends k74/l0)
public boolean d2.update(long snsId, Lk74/f0; record)   // 按 snsId 更新，record 传 SnsInfo
public boolean d2.replace(Lk74/f0; record)              // insert-or-replace
public SnsInfo d2.q1(long snsId)                        // 按 snsId 取
// 高层入口 SnsInfoStorageLogic = com.tencent.mm.plugin.sns.model.l5
public long l5.o(SnsInfo, SnsObject, String, int)       // = replace
```

### Q7 风险清单（写错会怎样）
1. **f5 length 不更新 / 更新错** → protobuf 解析错位，正文可能被截断、串到后续字段，渲染出乱码或空白。这是**最高危**的一步。
2. **前缀字节拼错（如漏 0A 或用了 `【】` 全角）** → 与微信原版 13002 条格式不一致，虽仍能显示，但和其它"已删除"动态视觉不统一，可能被识别为异常。
3. **误用 `SnsInfo.setContent(String)` 重建** → 丢 media/位置/AppInfo 等字段，动态变成纯文本，图片消失。**不可逆**（原字段已覆盖）。
4. **直接 UPDATE 覆盖整行其它列** → 若用 `UPDATE SnsInfo SET content=...` 手写 SQL 时列不全，可能清掉 attrBuf/postBuf/时间戳等。要用 `update(snsId, si)` 走实体映射，避免手拼 SQL。
5. **被服务端/其它客户端逻辑覆盖**：`SnsInfoStorageLogic.replace`（l5.o）在落库时会做 sourceType 兜底等额外处理；若服务端下一轮同步认为该动态有效，可能重新下发未加前缀的 content 覆盖本地。**"一旦错就不可逆"的是 #3（setContent 重建丢字段）和 #4（手写 SQL 误清列）**，因为原数据已无备份。
6. **缓存污染**：`getTimeLine()` 有 `cacheTimeLine` 缓存（按 content+attrBuf 的 md5），写回后需清缓存/重读，否则 UI 仍显示旧 content。

---

## [DONE-9] 给复核方的 3 个「最可能被推翻的点」

1. **"微X 是字节级插入"这个推断**。我只能证明产物字节等价（对象级与字节级不可区分）。若复核能找到微X 的代码并证明它用的是 `setContentDesc` 对象级路径而非字节拼接，我的"字节级 vs 对象级等价"结论虽不受影响，但"疑似字节级"表述会被推翻为"对象级"。
2. **`SnsInfoStorage` 的确切混淆名 `d2` 与 `update(J,k74/f0)` 语义**。我是从继承关系（extends k74/l0）+ 既有文档（d2.S7=updateFilterTimeLine）交叉推出的，未真机调用验证过 `d2.update(snsId, si)` 的第二个参数是否真的接受 SnsInfo 实体、是否会触发 observer 通知。真机 hook 验证前，`replace`/`update` 的精确副作用存疑。
3. **f5 是否真的永远没有嵌套长度**。我只抽了 3 条带前缀 + 2 条对照，若存在"正文超长导致 length varint 从 1 字节变 2 字节"（>127 字节正文）或"正文含子消息"的极端样本，我的"长度字段字节数不变、无级联"结论可能不覆盖全部 13002 条（虽然统计显示前缀格式统一，但未逐一验证每条正文长度 <128）。

---

[ALL-DONE]
