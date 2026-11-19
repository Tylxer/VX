# R72 微X模块（`me.yun.fkwechat`）转发功能 只读解析取证

> 文档状态：`[DONE]` — 6 个子节全部完成
> 角色：R72-L1 只读解析取证子代理（r72-l1-mfwd）
> 纪律：只读（不碰 adb / 不连设备 / 不跑 gradle / 不改 app 源码）；反汇编地址必须实跑脚本输出，不许手写猜。
> 证据分档：`[已证实]`=dex/资源直接可见 ｜ `[推断]`=由已证实外推 ｜ `[需真机]`=有锚点无实测 ｜ `[取证缺口]`=材料不存在/解不出。

---

## 背景证据链（复述，不重复走死路）

- `[已证实]` R55 定案：微X业务 dex 不在壳 `classes.dex`（2829 类全是 androidx），而在 `_mods/x/assets/` 下 7 个 hash 命名密文（头字节 `92 c5 01 00`、熵 8.00），key 疑在剥符号 native .so 里 ⇒ 静态解不出。
- `[已证实]` R71 定案：微X转发按钮自身会闪退（`CalledFromWrongThreadException`，`wx_features_base_thread` 线程），与我方无关；我方 G1 转发机制全通但对端无标记。
- `[已证实]` 用户本轮诉求：先解析微X模块的转发功能。因此本文不止写「静态受阻」，而是给出：(a) 已能读到什么；(b) 每条卡点及证据；(c) 真机侧可执行判据清单。

---

## §1 壳加载链反汇编 `[已证实 / 部分取证缺口]`

> 全部反汇编由 `_cache/_r72_shell_disasm.py`（复用 `_r55_disasm.py` 的 Dex 解析器，DEXDIR 指向 `_mods/x/classes.dex`）**实跑输出**，地址为 dex `code_off`（方法指令区文件偏移）。`code_off` 与方法一一对应，可复核。

### 1.1 Xposed 入口 `com.fkzhang.wechatxposed.XposedInit`

**（a）`initZygote(StartupParam)V` — code=0xd9e88** `[已证实]`
```
0000: iget-object v1, v1, IXposedHookZygoteInit$StartupParam.modulePath : String
0002: iput-object v1, v0, XposedInit.ᵢٴ : String
0004: return-void
```
→ Zygote 阶段把**模块 APK 自身路径**（modulePath）存入静态字段 `XposedInit.ᵢٴ`。该路径随后成为 `handleLoadPackage` 的第 3 个参数（modulePath），是定位 assets/密文的根。

**（b）`handleLoadPackage(LoadPackageParam)V` — code=0xd9e2c** `[已证实]`
```
0000: iget-object v0, v4, LoadPackageParam.packageName : String
0002: invoke-static {v4}, x.ᵢـ.ˈﹶ->ᵢٴ(LoadPackageParam)V          // 初始化配置/上下文
0005: invoke-virtual {v3,v4,v0}, XposedInit->ᵢٴ(LoadPackageParam,String)Z   // 目标包判定 gate
0008: move-result v1
0009: if-eqz v1, +3
000b: return-void                                                // 非目标包直接返回
000c: new-instance v1, Lx/ˏᴵ/ᴵˑ;  ; invoke-direct <init>()V
0011: iget-object v2, v3, XposedInit.ᵢٴ : String                 // 取 modulePath
0013: invoke-virtual {v1,v4,v0,v2}, x.ᵢـ.ﹳי->ᴵˑ(LoadPackageParam,String,String)V // ★真正的 hook 安装入口
0016: invoke-static {v4}, x.ᵢـ.ᵢٴ->ⁱˑ(LoadPackageParam)V
001a: move-exception → x.ʼˏ.ˆʻ->ˈﹶ(Throwable)V
```
→ **hook 入口**是 `x.ᵢـ.ﹳי->ᴵˑ(LoadPackageParam, packageName, modulePath)`（code 0xd9e2c+0x13），业务 dex 加载完成后由它安装各功能 hook（含转发）。

**（c）gate `XposedInit.ᵢٴ(LoadPackageParam,String)Z` — code=0xd9c5c** `[已证实]`
连续 12 次调用 `x.ˋˊ.ᵢٴ->ᵢٴ(J)String` 把 `0xffff113322f7a3fe`、`0xffff113122f7a3fe`、`0xffff113922f7a3fe`、`0xffff112222f7a3fe`、`0xffff112a22f7a3fe`、`0xffff112d22f7a3fe`、`0xffff111622f7a3fe`、`0xffff111922f7a3fe`、`0xffff110122f7a3fe`、`Build.MANUFACTURER.toLowerCase()`、`0xffff110a22f7a3fe`、`0xffff116122f7a3fe` 解成字符串，对 `packageName`/`processName` 做 `String.contains` 判定，再查 `x.ʼˏ.ʿˋ->ᐧ/ٴˎ(ApplicationInfo)`。
→ 这些 long 是**混淆常量**，明文（应为微信包名/进程名/厂商白名单）由 §1.4 的解码器决定。`[取证缺口]`：未解出明文，但**判定结构确定**。

### 1.2 业务 dex 加载器 `Lx/ᵢـ/ˑʼ;.ـˉ(ClassLoader,String,String)Object` — code=0x19baa4 `[已证实]`

这是把 7 个 assets 密文变成可 `loadClass` 的核心（参数：父 ClassLoader、要加载的类名 v7、辅助串 v6）：
```
0001: iget-object v8, v5, x.ᵢـ.ˆᴵ.ᐧᵎ : x.ʼˏ.ᵎ
0003: invoke-virtual {v8}, x.ʼˏ.ᵎ->ˆי()Z                 // 配置开关（是否走 in-memory）
0007: sget v0, Build$VERSION.SDK_INT; const/16 v2,#26    // Android 8.0 阈值
0014: new-instance v8, Ljava/io/File;
0016: invoke-virtual {v5}, x.ᵢـ.ˆᴵ->ⁱˑ()String           // 基目录（modulePath 相关）
001c: invoke-virtual {v3}, x.ʼˏ.ᵎ->ᴵˑ()String            // 混淆文件名 → 解码
0020: invoke-static {v3}, x.ʼˏ.ٴﹳ->ˈﹶ(String)String
0024: invoke-direct {v8, v1, v3}, File-><init>(String,String)
0032: const-wide v1, #0xffff30c022f7a3fe                 // ★密文文件"密钥串"混淆常量
0037: invoke-static {v1,v2}, x.ˋˊ.ᵢٴ->ᵢٴ(J)String
003b: invoke-static {v0,v8}, x.ʼˏ.ʾʻ->ᵢٴ(File,String)[B  // ★读文件+取字节
003f: if-eqz v8, +65
0041: invoke-static {v8}, ByteBuffer->wrap([B)ByteBuffer
0045: new-instance v0, Ldalvik/system/InMemoryDexClassLoader;
0049: invoke-direct {v0, v8, v1}, InMemoryDexClassLoader-><init>(ByteBuffer,ClassLoader)V
004c: invoke-virtual {v0, v7}, InMemoryDexClassLoader->loadClass(String)Class  // ★加载业务类
0051: [else] new-instance v0, Ldalvik/system/DexClassLoader; ... loadClass(v7)
0065: new-array 4 Object{classLoader, v7, Boolean(v8), Boolean(v4)}
007c: invoke-virtual {v5,v3,v0}, x.ᵢـ.ˑʼ->ﾞʼ(I,Object[])Object   // 兜底：native CallMethod 通道
```
→ **何时加载**：`handleLoadPackage` 命中目标包后，hook 安装阶段按 SDK 分支加载——Android≥26 用 `InMemoryDexClassLoader`（内存字节，不落盘），<26 用 `DexClassLoader`。`[已证实]`

### 1.3 两个"取字节"helper —— 关键反直觉发现

**`Lx/ʼˏ/ʾʻ;.ᵢٴ(File,String)[B` — code=0x1209b0** `[已证实]`
```
0000: new-instance v0, FileInputStream; <init>(File)
0005: invoke-static {v0,v2}, x.ʼˏ.ʾʻ->ˈﹶ(InputStream,String)[B
```

**`Lx/ʼˏ/ʾʻ;.ˈﹶ(InputStream,String)[B` — code=0x1209f4** `[已证实]`
```
0000: new-instance v0, Ljava/util/zip/ZipInputStream; <init>(InputStream v2)
0005: invoke-virtual {v0}, ZipInputStream->getNextEntry()ZipEntry
000b: ZipEntry->isDirectory()Z → 跳过目录
0011: ZipEntry->getName()String
0015: invoke-virtual {v1,v3}, String->endsWith(String)Z     // v3=密钥串=ZIP entry 后缀
001b: ZipEntry->getSize()J → new-array [B
0023: ZipInputStream->read([BII)I  → 返回该 entry 的字节
```
→ **所谓"解密"在 Java 侧其实是 ZIP 条目抽取**：把文件当 `ZipInputStream`，按 entry 名后缀匹配取出一个 dex 字节流。**Java 侧没有对 assets 做 AES**。这与 §4 的字节证据（assets 头 `92 c5 01 00`、无 `PK` 签名、熵 8.0）一致：**assets 不是裸 ZIP**，必须先经另一层解密/解包成 ZIP 才能走到这里。

### 1.4 另有完整 AES 实现（用于字符串/配置，非 assets dex） `[已证实]`

`Lx/ʼˏ/ᵢٴ;` 类：
- `ˈﹶ(SecretKeySpec,[B,[B)[B` code=0x118aa8：`Cipher.getInstance(解码常量 0xffff1f2e22f7a3fe)` + `IvParameterSpec(iv)` + `Cipher.init(**2=DECRYPT_MODE**, key, iv)` + `doFinal` → 解密
- `ـﾞ(...)` code=0x118af0：同样但 `init(1=ENCRYPT_MODE)` → 加密
- `ⁱˑ(String)` code=0x118c0c：`MessageDigest.getInstance(0xffff1cc322f7a3fe)`（疑 MD5）对 key 串取摘要 → `SecretKeySpec(digest, 0xffff1ccb22f7a3fe)`（疑 "AES"）
- `<clinit>` code=0x118c74：用 `fill-array-data`（classes.dex 文件偏移≈0x118cdc）初始化 16 字节静态 IV 字段 `x.ʼˏ.ᵢٴ.ـﾞ:[B`，另解出 3 个静态字符串 `ᵢٴ/ˈﹶ/ᴵˑ`（0xffff1f74…/1f6f…/1f51…）
- `ᵢٴ(String,String)String` code=0x118b38：`Base64.decode(...,2)` → AES 解密 → `new String(bytes, 0xffff1f2422f7a3fe 解出的charset)`
→ 这是 **字符串级** AES（Base64 包裹），与 assets dex 的 ZIP 抽取是两条独立路径。`[推断]` 它可能用于解密配置URL/开关值；`[取证缺口]` 未证实它作用于 7 个 assets。

### 1.5 字符串混淆解码器 `x.ˋˊ` `[已证实结构 / 明文取证缺口]`

- `Lx/ˋˊ/ᵢٴ;.ᵢٴ(J)String` code=0x16bc10：`sget-object v0, x.ˋˊ.ᵢٴ.ᵢٴ : String[]` → `x.ˋˊ.ˈﹶ->ˈﹶ(J, String[])String`
- `Lx/ˋˊ/ˈﹶ;.ˈﹶ(J,String[])String` code=0x16bc60：自定义 rolling-XOR 解码，`x.ˋˊ.ᴵˑ->ᴵˑ(J)J`（splitmix64 式：常量 `0x62a9d9ed799705f5`/`0xcb24d0a5c88c35b3`）+ `->ᵢٴ(J)J`（16 位循环移位）+ `->ˈﹶ(SI)S`（rotate），逐字符 `String[]` 表取值异或
- `x.ˋˊ.ᵢٴ` 的 `<clinit>` code=0x16bc30 装载 **2 张超长字符表**（dex string 池 idx 11560 长度 2968、idx 11561 长度 8191，uniq 98/200，字符集中在 U+FF00 半角假名区）
→ 所有混淆 long（含 gate 的包名、assets 文件名、AES 算法名/charset/key）都靠这张表解出。`[取证缺口]`：解码器算法**已完整反汇编、可复现**，但本轮未逐字还原明文；要做静态解密，第一步就是实现该解码器求出 `0xffff30c022f7a3fe` 等常量明文。

### 1.6 与 native stub 的关系 `[已证实]`

- `Lcom/fkzhang/xposed/hook/WxCoreLoader;.CallMethod(I,Object[])Object` 在壳 dex 中 `code=0x0`（**无方法体**，native/抽象声明）。
- dexdump `_mods/shell.txt:462857` 显示 `x.ᵢـ.ˑʼ.ﾞʼ` 以 `invoke-static {...}, WxCoreLoader.CallMethod` 调用它；加载器 §1.2 的 0x0065–0x007c 兜底分支也走这条。
→ 与 R55「key 疑在剥符号 native .so」吻合：assets 密文的真正解密很可能下沉到 .so（`74fab57f…`=ELF32、`dc7be70f…`=ELF64，§4），Java 侧仅保留 ZIP 抽取 + ClassLoader。

---


## §2 baseline.prof 解析 `[已证实结构 / 业务符号取证缺口]`

### 2.1 二进制结构（实跑提取）

**`_mods/x/assets/dexopt/baseline.prof`**（1102 B）：
```
0000: 70 72 6f 00   "pro\0"      ART offline profile magic
0004: 30 31 30 00   "010\0"      profile 版本
0008: 01            flag
0009: 81 1d 00 00   0x1d81=7553  解压后字节数
000d: 3d 04 00 00   0x043d=1085  压缩数据字节数
0011: 78 01 ...     zlib 压缩流（standard deflate）
```
`zlib.decompress(b[0x11:])` → **7553 B**（解压命令可直接复跑复核）。解压数据**只含一个可打印字符串**：
- 偏移 `0x10`：`"classes.dex"`（11 B）

紧邻字段（解压数据偏移）：`0x00:0x000b`(=11, 即字符串长度)、`0x02:0x0083`(=131)、`0x04:0x00000584`(=1412)、`0x08:0x6565eaba`(校验和)、`0x0c:0x5b6d`(=**23405**，恰等于壳 `classes.dex` 的 `method_ids_size`)、`0x10:"classes.dex"`、`0x1b:`起为一串 u32 整数索引。

**`_mods/x/assets/dexopt/baseline.profm`**（181 B）：magic `prm\0` / 版本 `001\0`，解压 277 B，同样**只含 `"classes.dex"`**，其余为 u16 整数对。

### 2.2 索引→符号映射尝试（实跑） `[推断，不能证明语义]`

把 `.prof` 解压数据 `0x1f` 起的 u32 序列（共 1880 个）当作 `method_id` 索引代入壳 `_mods/x/classes.dex`（`method_ids_size=23405`）：
- 1715/1880 个值落在合法范围，解析出**真实方法名**，例如 `android.animation.Animator->addPauseListener`、`Animator->cancel`、`android.app.Activity->getComponentName`、`android.content.Context->createPackageContext` 等；
- 当作 `class_def` 索引（2829 个类）也 largely in-range，映射到 `androidx.activity.ComponentActivity$…` 等。

→ 索引落点与壳 dex 自洽；但**这些 u32 的准确语义（method index / class index / 热度编码）未能从 ART 格式文档独立确证**，故仅标 `[推断]`。

### 2.3 转发符号检索结果 `[已证实：无命中]`

对全部可解析出的类名/方法名做关键字过滤（`forward|share|zhuan|转发|msg|chat`）：**0 命中**。
- 原因：profile 只引用 **`classes.dex`（外壳 dex）** 的方法 —— 即 androidx + 壳类（`x.…` / `com.fkzhang…`），**不含加密业务 dex 的任何类**（业务 dex 根本不在 `assets/dexopt/` 的 profile 覆盖范围）。

### 2.4 判定

`[已证实]` baseline.prof/profm 是**构建期针对外壳 `classes.dex` 的启动/编译热度画像**，二进制结构可完整解析、 zlib 可解压、_dex location 仅 `classes.dex_`。
`[取证缺口]` 该文件**不含业务 dex（因而也不含转发功能）的类/方法符号**，无法从中提取 forward/转发 相关符号名。这是材料本身的边界，不是解析失败——所有可读字节都已解析。

---


## §3 资源与字符串扫描 `[已证实：仅壳身份/AndroidX，转发文案在加密dex内]`

### 3.1 `resources.arsc`（外壳 `_mods/x/resources.arsc`，1,025,904 B） `[已证实]`

- 资源包 id `0x7f`，全局 string pool **6852** 条；含中文字符串 256 条。
- **模块自身 UI 文案**（简中 s[2173..2181]，另有繁中 s[6822..6851] 对应项）：
  `强制停止微信程序`、`微X模块`/`微X模塊`、`显示图标`/`隐藏图标`、`模块已更新，需要重启！`、`模块没启动！请（重新）勾选然后重启！`、`设置：微信->我->设置->微信X模块`、捐赠声明。
  → 这些属于 `MainActivity` 设置页，**只是模块开关壳，与转发业务无关**。
- **AndroidX 系统分享组件文案**：`Share with`/`Share with %s`（s[961]/[962]）、`分享對象`/`分享对象`（s[6584]/[6661]）、`与%s分享`/`與「%s」分享`（s[6659]/[6835]）、`使用「%s」分享`（s[6580]）。反查资源 id：
  - `0x7f110018` = `分享對象`
  - `0x7f110019` = `與「%s」分享`
  → 这是 **AndroidX `ShareCompat`/`sharetarget` 库的标准文案**（系统分享面板），**不是微X转发按钮的自定义文案**。

### 3.2 壳 `classes.dex` 字符串池（11,562 条） `[已证实]`

按 `转发|发送给|多选|选择联系人|分享|forward|Share|合并` 过滤，**6 条命中且全部无关**：
- s[1954/1955]=`SharedPreferences`、s[3163]=`XSharedPreferences`、s[8474]=`getSharedPreferences`
- s[8291]=`getNextClusterForwardId`、s[10488]=`setNextClusterForwardId`（Android 键盘导航 cluster，**非消息转发**）

→ 壳 dex 中**没有任何微X转发业务字符串**。

### 3.3 `xposed_init`（35 B） `[已证实]`

内容 `com.fkzhang.wechatxposed.XposedInit`——与 §1.1 的 Xposed 入口一致。

### 3.4 能否反推转发功能结构

`[取证缺口]` 不能。理由：
- 壳 resources.arsc 提供的只是**模块身份 + 系统分享组件**文案；
- 转发按钮的真实文案（"转发"/"发送给朋友"/"选择联系人"/"多选"）与功能类、菜单 id、布局均位于**加密业务 dex/加密资源**中（§1.2 的 InMemoryDexClassLoader 加载路径，§4 的密文 assets）；
- 现有材料只能证明「微X有独立的分享/转发 UI 资源入口（AndroidX ShareCompat 链路）」这一**壳级事实**，无法枚举其转发菜单结构或目标 Activity。

---


## §4 七个密文 assets 结构分析 `[已证实结构 / 不可静态解，卡点明确]`

### 4.1 逐文件实测表（大小 / 头16字节 / 熵，实跑）

| 文件（截前8） | 大小B | 头16字节 | 熵 | 判定 |
|---|---|---|---|---|
| `36a76b97…` | 530707 | `92 c5 01 00 22 a7 f3 df b2 f3 62 4e b1 11 5d c4` | 8.000 | 密文容器 |
| `5dbc98dc…` | 258499 | `92 c5 01 00 44 ac 78 8b 01 f0 13 87 03 1c 72 64` | 7.999 | 密文容器 |
| `d61f837c…` | 353907 | `92 c5 01 00 5f 41 a4 66 c6 47 86 10 85 7e e8 01` | 7.999 | 密文容器 |
| `d2caec3b…` | 323411 | `92 c5 01 00 c0 e4 b1 db 11 fd 2a b3 65 c7 de c9` | 7.999 | 密文容器 |
| `74fab57f…` | 276456 | `7f 45 4c 46 01 01 01 00 …` | 6.725 | ELF32 ARM (e_type=3 DYN, machine=0x28) |
| `dc7be70f…` | 461560 | `7f 45 4c 46 02 01 01 00 …` | 6.039 | ELF64 AArch64 (machine=0xb7) |
| `69691c7b…` | 41471 | `92 c5 01 00 d4 80 81 2b ba 85 32 4c 2f 95 ba ea` | 7.995 | 密文容器 |
| （附）`c1d9f5f8…` | 48191 | `92 c5 01 00 a4 42 b8 87 …` | 7.997 | 密文容器 |
| （附）`44c29edb…` | 4831 | `92 c5 01 00 7f 5e 1b dd …` | 7.962 | 密文容器（小） |
| （附）`534a9729…` | 1159 | `30 82 04 83 30 82 03 6b …` | 7.170 | DER/ASN.1（签名/证书，非dex） |

> 任务书称「7 个密文」；实测有 **8 个** `92 c5 01 00` 头的文件（含 `c1d9f5f8`、`44c29edb` 两个未计入的小文件）+ 2 个 ELF + 1 个 DER。这是需要订正的材料清点结果。

### 4.2 是否标准加密容器 / 有无 magic+长度字段

- `[已证实]` 8 个密文文件**共享固定 4 字节前缀 `92 c5 01 00`**（BE 值 `0x92c50100`，LE 值 `0x0001c592`=116114）；**该值不随文件变化，不是长度字段**（各文件长度从 4831 到 530707 不等）。紧随其后的 12 字节在文件间完全不同且高熵，符合「magic + IV/nonce + 密文」容器布局，`[推断]` 4 字节头可能是自定义版本号/magic。
- `[已证实]` **全文件无** `PK\x03\x04`/EOCD（非 ZIP）、无 `dex\n`（非明文 dex）、无 OpenSSL/AES 可识别常量。熵≈8.0 表明是密文或强混淆数据。
- `[已证实]` §1.3 已证明 Java 侧 `ZipInputStream` 抽取是**解密之后的环节**：密文需先变成 ZIP，才有 entry 可读。

### 4.3 解密 stub / key 线索定位

1. `[已证实]` **native 解密存在**：两个 ELF 都是 `libwxcoreloader.so`，导出 `JNI_OnLoad` 与 `CallMethod`（`@0x12c4a`/`@0x179f6` 处字样），恰好对应壳 dex 里 `WxCoreLoader.CallMethod` 的空方法体（§1.6）。→ **assets 的解密键/算法下沉在这两个 `.so` 中**，且符号已剥（仅剩 libc++/STL 名字与 `JNI_OnLoad`）。
2. `[已证实]` **Java 侧另有完整 AES-CBC**（§1.4，`x.ʼˏ.ᵢٴ`）：固定 16B IV（dex 偏移≈`0x118cdc` 的 array-data）、key=MD5(混淆串)、Cipher 算法名由 `0xffff1f2e22f7a3fe` 解码，密文以 Base64 包裹。这是**字符串/配置**加解密，不是 7 个 assets dex 的解密器（assets 是裸二进制非 Base64）。
3. `[已证实]` **密钥串的定位方式**：加载器用 `const-wide 0xffff30c022f7a3fe` → `x.ˋˊ.ᵢٴ(解码器)` 得到字符串，作为 `ZipInputStream` entry 后缀（§1.2/§1.3）；文件名也经 `0xffff1ccf…/1f51…` 等常量解码。所有明文都依赖 §1.5 的 rolling-XOR 解码器 + 两张字符表。
4. `[取证缺口]` 未搜到 assets 文件名 hash 字面量或 `92 c5 01 00` 常量出现在壳 dex 中（文件名由 long 常量动态解码，不以明文存储）。

### 4.4 判定：`不可静态解` + 具体卡点

`[已证实]` **仅凭现有静态材料无法还原 7 个密文中的任何一个为 dex**。三个独立卡点：
- **卡点A（key/算法在 native）**：真正解 `92 c5 01 00` 容器的 `CallMethod` 由 `libwxcoreloader.so`（ELF，符号剥除）实现，无源码、无 JNI 函数体可见；
- **卡点B（Java 字符串混淆未还原）**：即使走 Java AES 路径，算法名/charset/key 串/文件名都被 `x.ˋˊ` 解码器用两张 8191/2968 字符表 + splitmix64 式滚动异或隐藏，本轮未逐字还原；
- **卡点C（无中间态）**：材料里没有「已解密的 ZIP 临时文件」或 dex 缓存，InMemoryDexClassLoader 不落盘。

`[推断]` 静态解的可行路径（留给后续，非本轮）：先实现 §1.5 解码器求出全部 `0xffff…f7a3fe` 常量明文 → 得到 assets 文件名 + AES 参数 → 反编译两个 `libwxcoreloader.so`（需 IDA/ghidra，超出只读文本取证范围）取 `CallMethod` 的解密算法与 key → 解容器 → ZipInputStream 抽取 → 得到业务 dex → 才能真正反编译转发类。

---


## §5 转发功能推断汇总（对照我方 G1 链）

> 我方 G1 链（`docs/_r36_forward.md` §1.1 / `_r71_g1_native_forward_verdict.md` §3，①级）：
> `hl0/r1.b(talker)` → `hl0/q1`（builder）→ `q1.e(content)` → `new hl0/m{forwardMsgId=b, talker=f}` → `q1.f(m)` 挂 ForwardInfo → 直写 `q1.h=4`(文本)/`13`(图片) → `q1.b()` → NetScene `hl0/q0.<init>` → `l1.a()` 入队。
> 差别仅两处：挂 `hl0/m`(ForwardInfo) + 设 `q1.h` 子类型。

### 5.1 已知 `[已证实]`（微X侧，全部来自壳 dex/资源原文）

1. 微X是 Xposed 模块，入口 `com.fkzhang.wechatxposed.XposedInit`，命中目标包后由 `x.ᵢـ.ﹳי->ᴵˑ(lpparam, packageName, modulePath)` 安装功能 hook（§1.1）。
2. 业务功能**不在壳 dex**，而在 7+1 个 `92 c5 01 00` 密文容器中，运行时经 `libwxcoreloader.so::CallMethod` 解密 → ZIP entry 抽取 → `InMemoryDexClassLoader.loadClass` 加载（§1.2/§1.4/§4）。
3. 壳资源含 AndroidX `ShareCompat` 文案（`分享对象`/`与%s分享`，资源 id `0x7f110018/0x7f110019`）：`[推断]` 微X转发 UI 复用 AndroidX 系统分享组件风格的选人面板——**但这只是壳级线索，不能证明业务实现**。
4. 微X转发按钮自身在真机闪退（`CalledFromWrongThreadException`，`wx_features_base_thread` 线程，R71 §6.1），崩溃栈零我方帧——这是微X自身 bug，与我方机制无关。

### 5.2 推断 `[推断]`（由已证实外推，需真机/解密验证）

1. `[推断]` 微X转发走的是**与 G1 相同的微信原生发送链**：因为 R71 观察到微X转发时我方菜单注入被改变（`FF classified CHAT_MSG but no box found`，微X在长按菜单注入了「微X模块」项，R71 §6.2），说明**微X确实 hook 了消息长按菜单**——这与 G1 的入口（`ChattingRecyclerView` 长按 → adapter.getItem → h9，§1.2 G2）是同一 UI 锚点。
2. `[推断]` 微X若实现「带转发标记」，必然也走 `q1.f(m)` 挂 ForwardInfo + 设 `q1.h=4/13`——因为这是微信 host 链产生「转发」标记的唯一 dex 层机制（R71 §4 已证我方绕这一圈仍无标记，根因是 `m.a=-1`/`q0` 第5参未用，属 host 链约束）。**微X能否带标记，存疑**。
3. `[推断]` 微X的 `wx_features_base_thread` 自定义线程名暗示它在**后台线程构造/操作转发 UI**，这与 R71 闪退栈（view 在 `wx_features_base_thread` 创建、main 线程 requestLayout）一致 ⇒ 微X转发流程可能整体跑在自管线程上。

### 5.3 取证缺口 `[取证缺口]`（材料不存在/解不出）

> ⚠️ 部分已由 R72b 真机侧观察填补，见 §7 真机结论。以下保留静态侧口径。

1. **微X转发到底调了哪些宿主方法**：静态侧未知（业务 dex 密文）；`[已证实·真机]` 微X走 `hl0/q0.<init>`（type=522），但**未触发** `l1.a`/`q1.f`/`q1.a|b`/`m.<init>` hook 点（§7）。
2. **微X是否成功带「转发」标记**：`[已证实·真机]` **无标记**——对端显示普通消息（§7），与我方 G1 一致。
3. **微X ForwardInfo 的 `m.a`(forwardFromImgId) 如何取值**：未知（真机未触发 q1.f/m 构造 hook，无法读到 m.a）；微X转发 `forwardMsgId=0` 已证（§7），文本链标记缺失根因同 G1。
4. **7+1 个密文 dex 各自的业务职责分工**：未知（哪个含转发类、哪个含 UI、哪个含 hook 注册）——文件名是 hash，无语义。

### 5.4 一张对照表

| 环节 | 我方 G1（已证实） | 微X（本轮结论） |
|---|---|---|
| 入口 | 长按消息 → adapter.getItem → h9 | 长按菜单注入「微X模块」项 `[推断]`（R71 §6.2 实证菜单被改） |
| ForwardInfo | `new hl0.m{b=msgId,f=talker}` → `q1.f(m)` | `[真机未触发]` 探针 q1.f/m.<init> 零日志（§7）——微X可能不挂 ForwardInfo 或走不同构造路径 |
| 子类型 | `q1.h=4`(文本)/`13`(图片) | `[真机未触发]` q1.a|b 零日志（§7） |
| 发送 | `hl0/q0.<init>` → `l1.a()` 入队 | `[已证实·真机]` `hl0/q0.<init>` type=522 命中，`forwardMsgId=0`，线程 `wx_forward_send_thread_*`；但 `l1.a()` hook 零日志 ⇒ 入队方式与我方不同 or 更晚 |
| 对端标记 | 无（host链约束，`m.a=-1`） | `[已证实·真机]` **无标记**（普通消息，用户肉眼确认） |
| 加载机制 | AAPT 资源/反射直调 | 加密dex → native解密 → InMemoryDexClassLoader `[已证实]` |

---

## §6 真机侧判据清单（交主会话持设备执行）

> 目标：**不解密微X、不跑我方转发**，只用只读探针侧观察「微X转发那一刻对宿主做了什么」。实现为 `probe/MicroxFwdProbe.kt`（tag=`MFP`，非 R71 的 FNP；R71 FNP 已回退）。复用 R71 已验证的 hook 签名（R71 §3：`hl0/q0.<init>` @classes7 `0x711560`、`q0.getType()=522` @`0x710f5c`、`hl0/l1.a()` @`0x710360`）。
> ⚠️ 本节已按 R72b-L2 复核（`docs/_r72b_probe_review.md`）更新：补 H5a `q1.a()` 主路径、加前置校验 SOP、明确 newMsgId 非硬判据、H6 降级为未实现。

### 6.1 实际 hook 点（`MicroxFwdProbe.kt` 已实现，按证据强度排序）

| # | hook 点 | 签名/位置（我方已核实 ①） | 观察目标 | 实现日志 |
|---|---|---|---|---|
| H3 | `hl0/q0.<init>(String,String,int,int,long)` | classes7 `0x711560`（R71 §3），`hookAllConstructors`+5参过滤 | 第5参 forwardMsgId 是否≠0；`getType()=522`(文本)/`110`(图片) | `MFP q0 ctor forwardMsgId=X newMsgId=Y type=Z` |
| H4 | `hl0/l1.a()` | classes7 `0x710360`（R71 §3） | 真入微信原生发送队列的**硬判据** | `MFP l1.a queued=true` |
| H1 | `hl0/q1.f(hl0.m)` | ForwardInfo setter（R36 §1.1） | 微X是否挂 `hl0/m`；打印 `m.a~f` 六字段 | `MFP q1.f m[a=-1 b=... f=...]` |
| H5a | `hl0/q1.a()` before | **主路径**（R36 §6:205 `q1.a()→l1.a()`） | 读私有字段 `h` 子类型 + 入队路径 | `MFP q1.a subtype=4 via=a` |
| H5 | `hl0/q1.b()` before | 兜底路径（R36 §6:206，仅 a() 拿不到 l1 时用） | 同上 | `MFP q1.b subtype=4 via=b` |
| H2 | `hl0/m.<init>`（取参数最多重载） | `m` 六字段（R36 §1.1） | 佐证 m 构造（**可能零日志**，见判据②） | `MFP m ctor a=... b=...` |
| H6 | 长按菜单注入点 | — | **降级未实现**：注入点在加密业务 dex，宿主无稳定锚点；盲 hook Menu.add 刷屏且无法归因 | status 行标 `OPTIONAL-未实现` |

### 6.2 判据（什么日志证明什么）

- **判据① 微X走原生链**：出现 `MFP q0 ctor ... type=522` + `MFP l1.a queued=true` ⇒ 微X与我方 G1 走**同一条** `q0→l1.a` 链。`type=522`（来自 getType，R71 §3 已钉死）与 `queued=true` 是**硬证据**；`newMsgId=Y` **仅参考、非硬判据**（`readLongField` 回退扫描不校验字段名，可能取到 createTime/timestamp，见复核 §3.1）。若确认探针已开且 hook OK 却完全没有 MFP 日志 ⇒ 才可能说明微X走自管线程/自定义发送。
- **判据② ForwardInfo 内容**：**只认 H1 `q1.f` 打印的 m 六字段**（`m.a`=forwardFromImgId）。`a≠-1` ⇒ 微X有我方缺失的 CDN imgId 路径，是「带标记」关键差异；`a=-1` ⇒ 同 G1 无标记根因。**H2 零日志不得解读为「微X没构造 m」**（maxParams 启发式会漏无参构造，复核 §2）；「无 q1.f 日志」只能说明「没挂 ForwardInfo」，不能反推 a=-1。
- **判据③ 子类型**：H5a/H5 读到 `via=a|b subtype=4`(文本)/`13`(图片) ⇒ 微X用微信原生转发语义，且 `via=` 直接告诉走主/兜底哪条入队路径；读到别值 ⇒ 自定义流程。**两路径都 hook 了**（F1 修复），任一出现即可。
- **判据④ 菜单锚点**：⚠️ **探针侧未实现**（H6 降级）。归因靠 **时间窗 + `th=` 线程名**，非菜单锚点。若微X动作日志成串出现在 `wx_features_base_thread*` 可辅证；若部分跑 main 线程则 `th=` 单条失效，此时**时间窗是主归因**（见 6.3 归因协议）。

### 6.3 前置条件、归因协议与风险

**硬前置校验（F2，解释任何「无 MFP 日志」之前必做）**：探针默认关、需手动 `op=mfwd sub=on`。发 `python tools/dev.py probe mfwd`（无 sub）回显，确认 `enabled=true` 且 `H3-q0.ctor` / `H4-l1.a` 为 `OK`；否则空日志无效，**不得**据此判「微X没走原生链」（那可能只是探针没开或 hook 没挂上）。

**归因协议（F4，因 H6 未实现）**：严格按「确认 on → **只点一次**微X转发 → **立刻 off** → 只看这段时间窗内的 MFP 日志」。窗口内**不得**触发任何其它消息发送，否则 `q0 ctor`/`l1.a` 会照常触发产生**同形日志**，无法与微X动作区分。

**其它前置/风险**：
- 微X转发按钮当前闪退（R71 §6.1 `CalledFromWrongThreadException`）。要观察需先规避——`[需真机]` 由主会话判断（如切主线程触发），超出只读探针范围。
- 只读纪律：探针仅 before/after 打印，**不写宿主、不主动调 `q1.a()`/`q1.b()`/`l1.a()` 替宿主发消息**；唯一主动调用是 H3 的纯 getter `getType()`（R71 已用，无副作用）。
- 线程安全：回调可能在 `wx_features_base_thread`，全部 runCatching + `LogX`（内部同步落盘），无 UI 操作。

### 6.4 最小观察方案（给主会话的推荐路径）

1. 装 R72b 探针（`MicroxFwdProbe.kt` 已含 H3/H4/H1/H5a/H5/H2，default off）。广播 `op=mfwd sub=on` 开。
2. **先发 `op=mfwd`（无 sub）确认 enabled=true + H3/H4 OK**——这是解释空日志的硬前置。
3. 用户点一次微X转发（若闪退，看闪退前是否抓到 H3/H4/H5a 日志），随即 `sub=off`。只看窗口内日志。
4. 判定顺序：判据①（H3 type + H4 queued）→ 判据③（H5a/H5 via+subtype）→ 判据②（H1 m.a）。判据④无实现，仅时间窗 + th= 辅证。
5. 结果回填本文档 §5.3 的取证缺口。

---

## §7 真机侧观察结论（R72b，2026-11-19 22:42）

> 工具：`probe/MicroxFwdProbe.kt`（tag=MFP，op=mfwd），延迟挂载修复后 6/6 hook 全 OK（`MFP STATUS enabled=true installed=true mounted=true ok=6 fail=0`）。
> 纪律确认：全程只读（不改 param、不主动调业务方法）、默认关、窗口内只点一次微X转发（F4 归因协议）。

### 7.1 有效样本时间线
```
22:39:53  MFP mounted ok=6 fail=0 / PROBE ON   ← 探针全程 on
22:42:28.316  wx_forward_send_thread_1a1f4424d59  MFP q0 ctor in forwardMsgId=0 nonzero=false
22:42:28.407  wx_forward_send_thread_1a1f4424d59  MFP q0 ctor forwardMsgId=0 newMsgId=19724805 type=522
22:42:28.789  [D]#worker  AR seen message keys=msgSvrId,status,msgId   ← 消息落库更新
22:42:56  MFP PROBE OFF
```
（首轮 22:18 的尝试因「点输入法收起→微X自身闪退→微信冷启→探针内存变量清零」成为无效样本——转发发生时探针已 off，零日志不能判「微X没走原生链」。F2 前置校验纪律的必要性由此实证。）

### 7.2 判据结论
- **判据①（走原生链）**：✅ **命中**。微X转发产出 `MFP q0 ctor ... type=522`（NetSceneSendMsg 文本），新 msgId=19724805，且线程名 `wx_forward_send_thread_*` 是微X自定义线程 ⇒ 微X**走微信原生 `hl0/q0` 发送链**，与我方 G1 同链，归因坐实（F4 的 `th=` 锚点有效）。L4 担心的「H3 假阳性（挂到 j9 DAO）」被否证——H3 真产出 type=522，挂的是 NetScene `hl0/q0`。
- **判据③（子类型/入队路径）**：⚠️ **零日志**。`q1.a`/`q1.b` 的 subtype hook 全未触发，但探针 6/6 OK、同窗口 q0 有数据 ⇒ 不是假阴性，是**微X没走 `q1.a/q1.b` 这两个方法**。说明微X走的是与 G1 builder 不同的入队路径（自建 NetScene 或不同封装）。
- **判据②（ForwardInfo imgId）**：⚠️ **零日志**。`q1.f(m)`/`m.<init>` 未触发 ⇒ 微X文本链未挂我方观测的 `hl0/m` ForwardInfo 结构（或其构造走别的重载）。
- **判据④（菜单锚点）**：探针侧未实现，归因靠时间窗 + `th=`（已生效，见判据①）。

### 7.3 决定性对照：`forwardMsgId=0`
微X的 `q0.<init>` 第 5 参 `forwardMsgId=0`，与我方 R71 G1 完全一致。结合 R29 附②（该参数在 q0 构造器体内未被引用）⇒ **文本转发的源 msgId 不参与落库**，这是「对端无转发标记」的 host 链根因，微X也未突破。

### 7.4 对端结果（用户肉眼确认）
**普通消息，无「转发」标记** —— 与我方 G1 定案一致（R71 §4）。至此微X本身也**没有**我方缺失的标记能力，「参考微X补标记」的路线彻底证伪：微X文本转发同样是"绕原生链发消息、对端≈普通消息"。

### 7.5 最终结论
1. **静态**：微X业务 dex 加密解不出（8 密文容器 + native CallMethod + 字符串混淆）。
2. **动态**：微X文本转发走原生 `q0` 链但 `forwardMsgId=0`、不触发 q1.f/q1.a|b/m ⇒ 与我方 G1 同端不同实现，**对端均无标记**。
3. **收口**：G1「参考微X补转发标记」方向无价值，`chat.forward_native` 保持默认关；MFP 探针为一次性取证工具，建议按 R71 处置（回退代码、仅留结论）或保留为只读工具（待用户定）。

---

`[DONE]` 全部 6 子节 + §7 真机结论完成。一句话结论：微X壳加载链已完整反汇编证实但业务 dex 静态解不出；真机只读探针实证微X文本转发走原生 q0(type=522, forwardMsgId=0, 微X线程) 但对端无标记，与 G1 同根因——「参考微X补标记」路线证伪，G1 保持默认关。
