# _r28 — SnsInfo.sourceType 广告标记位取证

> 纯静态 dex 取证（**未跑 gradle、未连 adb、未改任何源码**）。
> dex：`_cache/dex/classes*.dex`；反汇编缓存 `_cache/_x2_full.txt`（classes3.dex 全量，263MB）。
> 工具：`dexdump.exe -d`（build-tools 36.0.0）+ awk/grep 过滤。
>
> 涉及的类：
> - `com.tencent.mm.plugin.sns.storage.SnsInfo`，其 DB 实体基类 `Ljj/ca;`（`field_sourceType` 声明在 `jj.ca`）
> - `com.tencent.mm.plugin.sns.storage.AdSnsInfo`，其 DB 实体基类 `Ljj/m;`（`field_sourceType` 声明在 `jj.m`；**AdSnsInfo 不继承 SnsInfo**，靠 `convertToSnsInfo()` 拷贝）
> - `com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo`，其 DB 实体基类 `Ljj/e6;`
> - `com.tencent.mm.plugin.sns.model.l5` = `SnsInfoStorageLogic`；`com.tencent.mm.plugin.sns.model.x` = `AdSnsInfoStorageLogic`

---

## 1. 结论摘要

**`bit7(128)` 不是广告位。「广告」标记位是 `bit5`，即值 `32`。**

证据等级：**① 字面证据** —— `SnsInfo.isAd()` 的方法体字节码把常量 32 直接传给 `isSourceExist()`；
并且 `AdSnsInfo.convertToSnsInfo()` 在把广告转成普通 SnsInfo 时显式 `addSourceFlag(2)` + `addSourceFlag(32)`。
另有第二处独立实现（`ImproveSnsInfo.isAd()`）同样用 32，互为佐证。

> ⚠️ `docs/conclusions/moments.md:196` 的「`bit7(128)`=广告」**是错的，需修正**。
> 128 的真实语义是 **WS Fold（朋友圈折叠动态）**，见 §3.3。若按 128 做黑名单，会把所有被折叠的动态（真机实测的 `130`）全部误杀。

---

## 2. 字面证据

### 2.1 `addSourceFlag` / `removeSourceFlag` / `isSourceExist` 的语义（先钉死位操作定义）

**`SnsInfo.addSourceFlag:(I)V`** —— `_x2_full.txt:2817624-2817638`

```
3dcdf0:                                        |[3dcdf0] com.tencent.mm.plugin.sns.storage.SnsInfo.addSourceFlag:(I)V
3dce00: 1a00 9aa1                              |0000: const-string v0, "addSourceFlag" // string@a19a
3dce04: 1a01 84b8                              |0002: const-string v1, "com.tencent.mm.plugin.sns.storage.SnsInfo" // string@b884
3dce08: 7120 ab58 1000                         |0004: invoke-static {v0, v1}, Lcom/tencent/mm/plugin/sns/ui/monitor/SnsMethodCalculate;.markStartTimeMs:(Ljava/lang/String;Ljava/lang/String;)V
3dce0e: 5232 9965                              |0007: iget v2, v3, Ljj/ca;.field_sourceType:I // field@6599
3dce12: b624                                   |0009: or-int/2addr v4, v2
3dce14: 5934 9965                              |000a: iput v4, v3, Ljj/ca;.field_sourceType:I // field@6599
3dce18: 7120 aa58 1000                         |000c: invoke-static {v0, v1}, Lcom/tencent/mm/plugin/sns/ui/monitor/SnsMethodCalculate;.markEndTimeMs:(Ljava/lang/String;Ljava/lang/String;)V
3dce1e: 0e00                                   |000f: return-void
```
→ `addSourceFlag(int f) { field_sourceType |= f; }`

**`SnsInfo.removeSourceFlag:(I)V`** —— `_x2_full.txt:2820126-2820138`

```
3dd030:                                        |[3dd030] com.tencent.mm.plugin.sns.storage.SnsInfo.removeSourceFlag:(I)V
3dd050: 5232 9965                              |0008: iget v2, v3, Ljj/ca;.field_sourceType:I // field@6599
3dd054: 7c44                                   |000a: not-int v4, v4
3dd056: b524                                   |000b: and-int/2addr v4, v2
3dd058: 5934 9965                              |000c: iput v4, v3, Ljj/ca;.field_sourceType:I // field@6599
```
→ `removeSourceFlag(int f) { field_sourceType &= ~f; }`

**`SnsInfo.isSourceExist:(I)Z`** —— `_x2_full.txt:2819744-2819766`

```
3dbc24:                                        |[3dbc24] com.tencent.mm.plugin.sns.storage.SnsInfo.isSourceExist:(I)Z
3dbc42: 5232 9965                              |0007: iget v2, v3, Ljj/ca;.field_sourceType:I // field@6599
3dbc46: b524                                   |0009: and-int/2addr v4, v2
3dbc48: 3d04 0400                              |000a: if-lez v4, 000e // +0004
3dbc4c: 1214                                   |000c: const/4 v4, #int 1 // #1
3dbc50: 1204                                   |000e: const/4 v4, #int 0 // #0
3dbc58: 0f04                                   |0012: return v4
```
→ `isSourceExist(int f) { return (field_sourceType & f) > 0; }`

（三个方法在 `AdSnsInfo` 上各有一份同构覆写，操作 `Ljj/m;.field_sourceType`，逻辑完全一致：`2815804` / `2816904` / `2816848`。）

### 2.2 ★ 决定性证据 A：`SnsInfo.isAd()` = `isSourceExist(32)` —— `_x2_full.txt:2818981-2818996`

```
3db4cc:                                        |[3db4cc] com.tencent.mm.plugin.sns.storage.SnsInfo.isAd:()Z
3db4dc: 1a00 f3f4                              |0000: const-string v0, "isAd" // string@f4f3
3db4e0: 1a01 84b8                              |0002: const-string v1, "com.tencent.mm.plugin.sns.storage.SnsInfo" // string@b884
3db4e4: 7120 ab58 1000                         |0004: invoke-static {v0, v1}, Lcom/tencent/mm/plugin/sns/ui/monitor/SnsMethodCalculate;.markStartTimeMs:(Ljava/lang/String;Ljava/lang/String;)V // method@58ab
3db4ea: 1302 2000                              |0007: const/16 v2, #int 32 // #20
3db4ee: 6e20 e944 2300                         |0009: invoke-virtual {v3, v2}, Lcom/tencent/mm/plugin/sns/storage/SnsInfo;.isSourceExist:(I)Z // method@44e9
3db4f4: 0a02                                   |000c: move-result v2
3db4f6: 7120 aa58 1000                         |000d: invoke-static {v0, v1}, Lcom/tencent/mm/plugin/sns/ui/monitor/SnsMethodCalculate;.markEndTimeMs:(Ljava/lang/String;Ljava/lang/String;)V // method@58aa
3db4fc: 0f02                                   |0010: return v2
```

### 2.3 ★ 决定性证据 B：`AdSnsInfo.convertToSnsInfo()` 显式加 `2` 和 `32`

`AdSnsInfo` → `SnsInfo` 的转换（广告动态进入普通时间线时的落库），`_x2_full.txt:2816032-2816062`：

```
3da700:                                        |[3da700] com.tencent.mm.plugin.sns.storage.AdSnsInfo.convertToSnsInfo:()Lcom/tencent/mm/plugin/sns/storage/SnsInfo;
3da768: 5253 f26c                              |002c: iget v3, v5, Ljj/m;.field_sourceType:I // field@6cf2     <- 先整体拷贝 AdSnsInfo 的 sourceType
3da76c: 5923 9965                              |002e: iput v3, v2, Ljj/ca;.field_sourceType:I // field@6599
3da778: 1223                                   |0034: const/4 v3, #int 2 // #2
3da77a: 6e20 a244 3200                         |0035: invoke-virtual {v2, v3}, Lcom/tencent/mm/plugin/sns/storage/SnsInfo;.addSourceFlag:(I)V // method@44a2
3da780: 1303 2000                              |0038: const/16 v3, #int 32 // #20
3da784: 6e20 a244 3200                         |003a: invoke-virtual {v2, v3}, Lcom/tencent/mm/plugin/sns/storage/SnsInfo;.addSourceFlag:(I)V // method@44a2
```
→ 广告动态转成普通 SnsInfo 后 `sourceType` 必带 `2|32 = 34`。
**这条把「32 = 广告」和「isAd()」完全闭环。**

### 2.4 ★ 决定性证据 C：广告落库路径 `AdSnsInfoStorageLogic.x.n()` 写入 `2` + `32`

`com.tencent.mm.plugin.sns.model.x.n:(Lq54/z85;I)AdSnsInfo` —— `_x2_full.txt:445422 / 445541-445543`：

```
399310: 122b                                   |0034: const/4 v11, #int 2 // #2        <- v11 = 2 在本方法头定义
399312: 121c                                   |0035: const/4 v12, #int 1 // #1
...
399494: 6e20 7944 b500                         |00f6: invoke-virtual {v5, v11}, Lcom/tencent/mm/plugin/sns/storage/AdSnsInfo;.addSourceFlag:(I)V // method@4479   <- |= 2
39949a: 1300 2000                              |00f9: const/16 v0, #int 32 // #20
39949e: 6e20 7944 0500                         |00fb: invoke-virtual {v5, v0}, Lcom/tencent/mm/plugin/sns/storage/AdSnsInfo;.addSourceFlag:(I)V // method@4479   <- |= 32
```
同一方法上下文含 `Ljj/m;.field_adxml`、`Ljj/m;.field_recxml`、`com.tencent.mm.plugin.sns.model/x;.w(Lq54/z85; AdSnsInfo)`，
语义确认为「广告动态落库」路径（`x` = AdSnsInfoStorageLogic，见 `3992ba` 的 const-string `"com.tencent.mm.plugin.sns.model.AdSnsInfoStorageLogic"`）。

### 2.5 ★ 佐证 D：新版朋友圈 `ImproveSnsInfo.isAd()` 同样用 32（独立实现，非同一份代码）

`com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo.isAd:()Z` —— `_x2_full.txt:2967141-2967158`：

```
761b28:                                        |[761b28] oh3.p.isAd:()Z
761b38: 1a00 f3f4                              |0000: const-string v0, "isAd" // string@f4f3
761b3c: 1a01 bcbe                              |0002: const-string v1, "com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo" // string@bebc
761b46: 1a02 26f8                              |0007: const-string v2, "isSourceExist" // string@f826
761b50: 6e10 118f 0500                         |000c: invoke-virtual {v5}, Ljj/e6;.getSourceType:()I // method@8f11
761b56: 0a03                                   |000f: move-result v3
761b58: 1304 2000                              |0010: const/16 v4, #int 32 // #20
761b5c: b543                                   |0012: and-int/2addr v3, v4
761b5e: 3d03 0400                              |0013: if-lez v3, 0017 // +0004
```
→ 老版（`SnsInfo`/`jj.ca`）和新版（`ImproveSnsInfo`/`jj.e6`）两套实现**都用 32 判广告**。

### 2.6 佐证 E：`isAd()` 是运行时真在用的判据（不是死代码）

`com.tencent.mm.plugin.sns.model.l5.o(...)`（= SnsInfoStorageLogic.replace）落库后立刻用 `isAd()` 分流，
`_x2_full.txt:1055148-1055152`：

```
6def3c: 6e20 a244 f200                         |009c: invoke-virtual {v2, v15}, Lcom/tencent/mm/plugin/sns/storage/SnsInfo;.addSourceFlag:(I)V
6def42: 6e10 ce44 0200                         |009f: invoke-virtual {v2}, Lcom/tencent/mm/plugin/sns/storage/SnsInfo;.isAd:()Z // method@44ce
6def48: 0a05                                   |00a2: move-result v5
6def4a: 3905 0500                              |00a3: if-nez v5, 00a8 // +0005
6def4e: 7120 673c d200                         |00a5: invoke-static {v2, v13}, Lcom/tencent/mm/plugin/sns/model/l5;.a:(Lcom/tencent/mm/plugin/sns/storage/SnsInfo;Lcom/tencent/mm/protocal/protobuf/SnsObject;)V
```

另有大量业务侧直接写 `isSourceExist(32)` 判广告（全 dex 共 17 处 `isSourceExist:(I)Z` 调用，能解析出常量的**全部是 32**），典型：

```
# com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI  (_x2_full.txt:533212)
422572: 1303 2000                              |0071: const/16 v3, #int 32 // #20
422576: 6e20 e944 3400                         |0073: invoke-virtual {v4, v3}, Lcom/tencent/mm/plugin/sns/storage/SnsInfo;.isSourceExist:(I)Z

# SnsCommentDetailUI$46.onViewAttachedToWindow  (_x2_full.txt:2139197)
42b4a0: 1305 2000                              |254a: const/16 v5, #int 32 // #20
42b4a4: 6e20 e944 5c00                         |254c: invoke-virtual {v12, v5}, Lcom/tencent/mm/plugin/sns/storage/SnsInfo;.isSourceExist:(I)Z

# com.tencent.mm.plugin.sns.model.w5.l / w5.m（SnsObject → 上报结构体填充）(_x2_full.txt:439344 等 6 处)
3933f4: 1309 2000                              |0054: const/16 v9, #int 32 // #20
3933f8: 6e20 e944 9000                         |0056: invoke-virtual {v0, v9}, com.tencent.mm.plugin.sns.storage.SnsInfo;.isSourceExist:(I)Z
```

---

## 3. sourceType 完整位映射表

> 数据基础：对 `_x2_full.txt` 做了一次全量扫描，把**所有**（62 个）出现 `field_sourceType` / `isSourceExist` /
> `addSourceFlag` / `removeSourceFlag` 的方法及其字面整型常量汇总（缓存：`_cache/_r28_srctypemap.txt`）。
> 由于 `addSourceFlag`/`isSourceExist`/`removeSourceFlag` 三个方法名**只存在于 classes3.dex**
> （对其余 14 个 dex 做过二进制 grep，`addSourceFlag` 命中数均为 0），这张表对 SnsInfo 是**完备**的。

| 值 | 位 | 含义 | 证据等级 | 依据 |
|---|---|---|---|---|
| `0` | — | 死来源 | ① 字面证据 | `isDeadSource()` = `(sourceType & 270) == 0`（见 §3.1） |
| `2` | bit1 | 时间线可见 | ① 字面证据 | `i2.onGYNetEnd` @0x00a7、`a8.<init>` @0x0115、`x.n` @0x00f6、`AdSnsInfo.convertToSnsInfo` @0x0035 |
| `4` | bit2 | 自己私密 | ① 字面证据 | `a8.<init>` @0x0120：`const/4 v0, #int 4` → `addSourceFlag(v0)` |
| `8` | bit3 | **未查明**（但是「合法来源位」之一） | ③ 无法确认 | 见 §3.2 |
| `16` | bit4 | 未在 SnsInfo 上找到写入点 | ③ 无法确认 | 仅 `l5.h` 中作为游标查询类型选择器出现，未写入 `sourceType` |
| **`32`** | **bit5** | **广告** | **① 字面证据** | §2.2 / §2.3 / §2.4 / §2.5 |
| `64` | bit6 | 未查明 | ③ 无法确认 | 仅在 `l5.o` 方法内出现常量，未与 sourceType 位操作配对 |
| `128` | bit7 | **WS Fold（朋友圈折叠动态）**，**不是广告** | ① 字面证据（写入点）+ ① 语义（`isWsFold`） | §3.3 |
| `256` | bit8 | withTa（拼图/共同好友类动态，Kotlin 新路径） | ① 字面证据 | §3.4 |

### 3.1 `isDeadSource()` = `(sourceType & 270) == 0` —— 掩码反证 128 不是「来源」

`_x2_full.txt:2819152-2819168`：

```
3db658:                                        |[3db658] com.tencent.mm.plugin.sns.storage.SnsInfo.isDeadSource:()Z
3db676: 5232 9965                              |0007: iget v2, v3, Ljj/ca;.field_sourceType:I // field@6599
3db67a: d522 0e01                              |0009: and-int/lit16 v2, v2, #int 270 // #010e
3db67e: 3902 0400                              |000b: if-nez v2, 000f // +0004
3db682: 1212                                   |000d: const/4 v2, #int 1 // #1
```
`270 = 256 + 8 + 4 + 2`（bits 1,2,3,8）。
→ **微信自己认为的「来源位」只有 2/4/8/256；`32`（广告）和 `128`（折叠）都不算来源，是附加属性位。**
这也从反面说明 128 不可能是「广告」（若一条动态只有 128，会被判成死来源而被丢弃）。

### 3.2 bit3(8) —— **没查到，标 ③ 无法确认**

- 全部 13 处 `addSourceFlag` 调用（已逐一回溯常量）**没有任何一处传 8**；
  实际出现的常量只有：`2`（4 处）、`4`（1 处）、`32`（2 处）、`128`（1 处）、`256`（1 处）、以及 3 处来自方法参数的变量值。
- 全部 17 处 `isSourceExist` 调用中，能解析出常量的**全部是 32**，没有 8。
- 唯一能确定的是：`8` 被包含在 `isDeadSource` 的活来源掩码 `270` 里，所以它是**某种合法来源**（与 2/4/256 同类），
  不是属性位。
- **没有任何字面证据能说明 8 具体是什么**。真机实测的 `8` / `10(=8+2)` 在本 dex 中找不到写入点，
  推测来自其它模块、旧版本遗留值或其它 DB 表同步 —— **这是假设，不是证据**。

### 3.3 bit7(128) = WS Fold（朋友圈折叠），不是广告

写入点唯一，`com.tencent.mm.plugin.sns.model.l5.o(SnsInfo, SnsObject, String, int)J` @0x0190-0x019a
（`_x2_full.txt:425559`）：

```
3810da: 6e10 a744 0900                         |018d: invoke-virtual {v9}, Lcom/tencent/mm/plugin/sns/storage/SnsInfo;.clearWsFeedTypeFlag:()V
3810e0: 52a3 c448                              |0190: iget v3, v10, Lcom/tencent/mm/protocal/protobuf/SnsObject;.ExtFlag:I // field@48c4
3810e4: 7110 703c 0300                         |0192: invoke-static {v3}, Lcom/tencent/mm/plugin/sns/model/l5;.j:(I)Z // method@3c70
3810ea: 0a03                                   |0195: move-result v3
3810ec: 3803 4b00                              |0196: if-eqz v3, 01e1 // +004b
3810f0: 1303 8000                              |0198: const/16 v3, #int 128 // #80
3810f4: 6e20 a244 3900                         |019a: invoke-virtual {v9, v3}, Lcom/tencent/mm/plugin/sns/storage/SnsInfo;.addSourceFlag:(I)V // method@44a2
3810fa: 7100 393b 0000                         |019d: invoke-static {}, Lcom/tencent/mm/plugin/sns/model/f4;.Ia:()Lcom/tencent/mm/plugin/sns/storage/o2;
381106: 6e30 2b46 7308                         |01a3: invoke-virtual {v3, v7, v8}, Lcom/tencent/mm/plugin/sns/storage/o2;.P0:(J)Lcom/tencent/mm/plugin/sns/storage/n2;
```

守卫函数 `l5.j(I)Z` 的名字与实现 —— `_x2_full.txt:425068`：

```
38062c:                                        |[38062c] com.tencent.mm.plugin.sns.model.l5.j:(I)Z
38063c: 1a00 d7f8                              |0000: const-string v0, "isWsFold" // string@f8d7
380640: 1a01 e4b5                              |0002: const-string v1, "com.tencent.mm.plugin.sns.model.SnsInfoStorageLogic" // string@b5e4
38064a: dd02 0210                              |0007: and-int/lit8 v2, v2, #int 16 // #10
38064e: 3d02 0400                              |0009: if-lez v2, 000d // +0004
```
→ `isWsFold(int extFlag) { return (extFlag & 16) > 0; }`

配套的类名（`_x2_full.txt` 中 const-string 命中）：`SnsWsFoldManager`、`WsFoldData`、
`"analyzeWsFoldData"`、`"wsFoldGroupSize"`、`"MicroMsg.SnsWsFoldManager"`。

**结论：`128` = 该动态属于「被折叠的一组动态（WS Fold）」，触发条件是服务端下发的 `SnsObject.ExtFlag & 16`。**
真机实测的 `130 = 128 + 2` = 「折叠 + 时间线可见」，**是正常动态，绝不能当广告排除**。

### 3.4 bit8(256) = withTa 路径

`nf3.h.a:(JILjava/lang/String;Continuation)`（`_x2_full.txt:1128932`，内含 const-string
`"toSnsInfo"` / `"com.tencent.mm.plugin.sns.model.withta.UtilsKt"`）：

```
73b3fc: 130a 0001                              |010c: const/16 v10, #int 256 // #100
73b400: 6e20 a244 a700                         |010e: invoke-virtual {v7, v10}, Lcom/tencent/mm/plugin/sns/storage/SnsInfo;.addSourceFlag:(I)V // method@44a2
```
常量 256 是字面证据；语义（"withTa" = 拼图/共同好友类动态）来自类名 `withta.UtilsKt`，**语义部分属 ② 强推测**。

### 3.5 时间线取数 SQL（顺带确证）

`com.tencent.mm.plugin.sns.model.l5.h(...)`（`_x2_full.txt:424651`）内字面量：

```
381bbe: 1b02 751d 0100                         |01c7: const-string/jumbo v2, "select *,rowid from SnsInfo  where  (sourceType & 2 != 0 ) " // string@00011d75
381bca: 1b0c 761d 0100                         |01cd: const-string/jumbo v12, "select *,rowid from SnsInfo  where  (sourceType & 2 != 0 )  AND " // string@00011d76
```
与既有结论一致（时间线一律带 `sourceType & 2 != 0`）。

---

## 4. 反证 / 明确「没找到」的部分

以下**明确没找到**，不要当成已确认：

1. **没有找到任何把 `128` 当作「广告」使用的调用点。** 全 dex 中 `128` 只出现在两处与 sourceType 相关的位置：
   - `l5.o` @0x0198 → `addSourceFlag(128)`，守卫是 `isWsFold(ExtFlag & 16)`；
   - `l5.h` @0x0212 → `const/16 v8, #int 128` + `if-ne v1, v8`，是**游标查询类型选择器**（选择 `d2.e4(...)` 查询），
     不是位标记；该方法里另有 `if-ne v1, 16` / `if-ne v1, 2` 等同类比较。
   - 第三处 `lg3.e.m`（`_x2_full.txt:1055148`）是 `l5.o` 的 Kotlin 复制版，同样是 wsFold 路径。
2. **没有找到 `isSourceExist(128)`、`isSourceExist(8)`、`isSourceExist(16)`、`isSourceExist(64)`、`isSourceExist(256)` 中的任何一个**
   （17 处调用全部为 32 或方法参数变量）。
3. **没有找到把 `8` 写入 `sourceType` 的任何调用点**（bit3 含义无法确认，见 §3.2）。
4. **没有找到名为 `isAdvertise` / `isAdSource` / `AdSource` 的方法**（全 dex grep 无命中；`isAd` 只有
   `SnsInfo.isAd` 与 `ImproveSnsInfo.isAd` 两个实现，都用 32）。
5. **没有在 `jj.ca`（SnsInfo 的 DB 实体）上找到 `field_adxml` / `field_recxml` 列** —— 这两个字段只属于
   `jj.m`（AdSnsInfo 的 DB 实体）。所以**不能**用「adxml 非空」作为 SnsInfo 表侧的直接判据。
6. **未验证真机数据**：本轮为纯静态取证，未连 adb，因此「真机实测 `130` 到底对应哪些动态」没有实测佐证；
   「130 = 折叠动态」是由字节码语义推得（① 字面证据 + 语义推断），不是实测。

---

## 5. 给实现的建议

### 5.1 推荐判据（直接用）

导出朋友圈排除广告，用 **`sourceType & 32 != 0`**：

```sql
-- 时间线取数，同时排除广告
SELECT * FROM SnsInfo
WHERE (sourceType & 2 != 0)      -- 时间线可见（原判据保留）
  AND (sourceType & 32 == 0);    -- 排除广告 ★ 新增
```

若代码侧已有 SnsInfo 对象，直接 `snsInfo.isAd()`（反射调用 `com.tencent.mm.plugin.sns.storage.SnsInfo.isAd()`），
等价于 `(sourceType & 32) > 0`，且能自动跟随微信后续改版。

### 5.2 不要做的事

- **不要**用 `sourceType & 128` 排除广告 —— 那是 WS Fold（折叠动态），真机实测的 `130` 就是它，
  按 128 过滤会把「折叠分组里的动态」整组误杀（包括正常好友动态）。
- **不要**用 `field_adxml` 非空判广告 —— SnsInfo 表（`jj.ca`）没有这一列（见 §4.5）。

### 5.3 兜底 / 补充判据（可选，证据等级较低）

1. **双表口径**：广告另有独立的 `AdSnsInfo` 表（存储类 `w0` = AdSnsInfoStorage，`jj.m` 实体）。
   若导出走的是 SnsInfo 表，用 §5.1 即可；若还并了 AdSnsInfo 表，那条表里的行**默认全是广告**，
   应整表排除或按需单独导出。
   （依据：`x.n()` = AdSnsInfoStorageLogic 的落库方法，`w0.P0(snsId)` 按 snsId 取 AdSnsInfo —— ① 字面证据）
2. **`SnsInfo.getAdXml()` 非空**：`SnsInfo` 上有 `getAdXml():ADXml`（method@44b4），从 timeLine 解析。
   可作为二次校验，但**解析成本高于位判断，且未验证其在所有广告形态下非空**，只建议作为日志/校验用，不建议作为主判据。
3. **`isDeadSource()`**（`(sourceType & 270) == 0`）：可用作「脏数据过滤」（这些行本来就不该展示），
   但**不能**用来判广告。

### 5.4 需要修正的既有文档

- `docs/conclusions/moments.md:196` 的
  「位定义：`bit1(2)`=时间线可见 / `bit2(4)`=自己私密 / `bit7(128)`=广告 / `0`=死来源」
  → 应改为
  「`bit1(2)`=时间线可见 / `bit2(4)`=自己私密 / **`bit5(32)`=广告** / `bit7(128)`=WS Fold 折叠 / `bit8(256)`=withTa / `0`=死来源（`isDeadSource` = `(sourceType & 270)==0`）」

### 5.5 遗留待确认（交给真机侧）

- `bit3(8)` 的含义 —— 静态查不到，建议真机上抓一条 `sourceType=8/10` 的记录，看它的 `ExtFlag`、`ObjectDesc`、
  是否命中 `isWsFold` 之外的新逻辑。
- 真机上是否存在 `sourceType` 含 `32` 的行（预期 `34`）。若实测一条都没有，说明广告可能只落在 `AdSnsInfo` 表、
  不进 SnsInfo 表，此时 §5.3.1 才是主路径。

---

[DONE]
