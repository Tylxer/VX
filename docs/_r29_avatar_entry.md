# R29-2 静态 dex 取证：头像「取 Bitmap / 文件路径」入口（第①档补齐）

> **范围**：纯静态只读。`_cache/dex/classes.dex` ~ `classes15.dex`（微信 8.0.48 / 2588 GP）。
> **禁止**：跑 gradle、adb、改源码。
> **证据分档**：① 字面（贴 dexdump/字节码原文）/ ② 强推测 / ③ 未定位到。
> **交叉验证**：本轮的纯 Python 解析均与 `_cache/r28_dd7_full.txt`（classes7.dex dexdump 全文）、
> `_cache/_s9dump/classes5.txt`（classes5.dex dexdump 头部）交叉核对过。

---

## 0 结论摘要（先给答案）

| # | 结论 | 等级 | 可直接用于代码的签名 |
|---|------|------|----------------------|
| 1 | **`AvatarStorage` = `com.tencent.mm.modelavatar.x`**（不是 R28 里写的 `a1`）。`x` 的方法体内用 log tag `MicroMsg.AvatarStorage`、埋点串 `com/tencent/mm/modelavatar/AvatarStorage` + `getAvatarFromFile`/`saveFile`/`updateAvatar`，且 `<clinit>` 直接 `new AvatarStorage$MMTrimMemoryEventListener`。R28 结论①把 AvatarStorage 钉在 `a1` 是**错的**：`a1` 是**服务类** `SubCoreAvatar`（父 `d94/w`）。 | ① | `Class.forName("com.tencent.mm.modelavatar.x")` |
| 2 | **按 username 取「已存在文件绝对路径」**：`x.f(String username, boolean hd, boolean unused) -> String`。文件存在返回绝对路径，否则返回 null。 | ① | `x.f(wxid, false, false)` |
| 3 | **按 username 取「内存缓存 Bitmap」**：`x.i(String username) -> Bitmap`。miss 或已 recycle 返回 null。 | ① | `x.i(wxid)` |
| 4 | **按 username 直接解码 HD 头像 Bitmap**：`x.j(String username) -> Bitmap`（内部 1080 解码）。 | ① | `x.j(wxid)` |
| 5 | **按「文件路径」取 Bitmap（静态底层）**：`x.e(String filePath, String username, boolean hd) -> Bitmap`（= `getAvatarFromFile`，含中心裁剪）。 | ① | `x.e(path, wxid, hd)` |
| 6 | **本地文件路径拼装规则**：`<dataDir>/avatar/<md5[0:2]>/<md5[2:4]>/user_<hd_><md5>.png`，其中 `md5 = MD5(username)`（`ai/j.g` 引用了字符串 `"MD5"`）。**R28 的「文件名=md5、大图带 _hd」只是近似**，真实有 `xx/yy/` 子目录 + `user_` 前缀 + `.png` 后缀。 | ① 规则 / ① MD5 |
| 7 | **单例链路**：`a1.B8()`（`SubCoreAvatar` 服务单例，经 `d94/n0.c(Class)` 服务注册表）→ `a1.z8()` → 返回 `x`（AvatarStorage 实例，懒初始化）。 | ① getter / ② 服务已注册 |
| 8 | **回调/监听**：监听接口 `fp/z` 有 `C(String) -> void`；`x.a(fp/z)`/`x.o(fp/z)` 增删监听；「头像已更新」通知走 `x.a`（`k74/t0` 观察者）。**无「单张头像加载完成」回调**，异步下载走 `m0` 图像加载器（`a1.F8()`），不在 `x` 上。 | ① 签名 / ③ 加载完成回调 |

**一句话给模块**：在微信主进程里
```
a1 = Class.forName("com.tencent.mm.modelavatar.a1")      // SubCoreAvatar 服务
x   = 反射 a1.B8() 再反射 z8()                              // AvatarStorage 实例
bitmap = x.i(wxid)   // 同步内存缓存；null 则试 x.j(wxid)（HD 解码）或 x.f(wxid,false,false) 拼路径后 BitmapFactory.decodeFile
```

---

## 1 `AvatarStorage`（= `x`）方法清单（只列「取头像」相关）

> dexdump 原文见 `_cache/r28_dd7_full.txt:189139`（类头）。类头关键行：

```
  Class descriptor  : 'Lcom/tencent/mm/modelavatar/x;'
  Access flags      : 0x0001 (PUBLIC)
  Superclass        : 'Ljava/lang/Object;'
  Interfaces        -
    #0              : 'Lfp/a0;'                       ← marker 接口（无方法）
  Static fields     -
    #0 name:'c' type:'Ljava/util/Map;'     access:0x0019 (PUBLIC STATIC FINAL)   ← 特殊账号→resId 表
    #1 name:'d' type:'Lcom/tencent/mm/modelavatar/a;' access:0x0009 (PUBLIC STATIC) ← Bitmap LruCache(容量100)
    #2 name:'e' type:'Ldh4/f0;'            access:0x0019 (PUBLIC STATIC FINAL)   ← 文件 IO 抽象(实为 modelavatar/t)
  Instance fields   -
    #0 name:'a' type:'Lk74/t0;'            access:0x0011 (PUBLIC FINAL)          ← 观察者/通知器
    #1 name:'b' type:'Ljava/util/List;'    access:0x0011 (PUBLIC FINAL)          ← fp/z 监听器(WeakReference)
```

### 1.1 静态方法（Direct）

| 混淆名 | 签名 | 语义（据埋点串/字节码） | 等级 |
|--------|------|------------------------|------|
| `e` | `(Ljava/lang/String;Ljava/lang/String;Z)Landroid/graphics/Bitmap;` | **getAvatarFromFile(path, username, hd)**：文件存在则解码（hd 走 940×940），null 则回退 `w.a(path,username)` 读 `.bm`，再中心裁成正方形。 | ① |
| `d` | `(Ljava/lang/StringBuilder;Ljava/lang/String;Z)Ljava/lang/StringBuilder;` | 拼相对路径 `md5[0:2]/md5[2:4]/user_<hd_><md5>.png`（见 §4） | ① |
| `c` | `(Landroid/graphics/Bitmap;)Z` | bitmap 为 null 或已 recycle → true（即「无效」） | ① |
| `b` | `(Landroid/content/Context;Landroid/widget/ImageView;I)V` | decodeResource + 圆角 + setImageBitmap（资源型默认头像） | ① |

`e` 内埋点串原文（钉死「AvatarStorage.getAvatarFromFile」）：

```
2a04e2: 1a0c 29db   |0069: const-string v12, "com/tencent/mm/modelavatar/AvatarStorage" // string@db29
2a04e6: 1a0d d1f5   |006b: const-string v13, "getAvatarFromFile" // string@f5d1
2a04ea: 1a0e ad0c   |006d: const-string v14, "(Ljava/lang/String;Ljava/lang/String;Z)Landroid/graphics/Bitmap;" // string@0cad
2a04ee: 1a0f fac2   |006f: const-string v15, "android/graphics/Bitmap_EXEC_" // string@c2fa
2a04f2: 1a10 dce2   |0071: const-string v16, "createBitmap" // string@e2dc
```

### 1.2 实例方法（Virtual）

| 混淆名 | 签名 | 语义 | 等级 |
|--------|------|------|------|
| `f` | `(Ljava/lang/String;ZZ)Ljava/lang/String;` | **getAvatarPath(username, hd, unused)**：拼 `<dataDir>/avatar/`+`d(...)`，`o6.v` 判存在，存在返回绝对路径否则 null。 | ① |
| `g` | `(Ljava/lang/String;Z)Ljava/lang/String;` | 拼 `wcf://avatar/`+`d(...)`，**总是返回** VFS URI（不判存在）。 | ① |
| `i` | `(Ljava/lang/String;)Landroid/graphics/Bitmap;` | **getAvatarFromCache(username)**：查 `d`（LruCache `a.a`→`qe0/f`，key=`"avatar_cache"`），null/recycled 返回 null。 | ① |
| `j` | `(Ljava/lang/String;)Landroid/graphics/Bitmap;` | `g(username,true)` 后 `x.Z(path,1080,1080,0)` 直接解码 HD 头像。 | ① |
| `k` | `(Ljava/lang/String;)Landroid/graphics/Bitmap;` | 特殊账号（官方号/服务号等，见 `c` 表）→ decodeResource 默认图；否则 null。 | ① |
| `h` | `(Landroid/content/Context;)Landroid/graphics/Bitmap;` | `"I_AM_NO_SDCARD_USER_NAME"` 无 SD 卡默认头像。 | ① |
| `m` | `(Ljava/lang/String;Z)Z` | 头像文件（`.png` 或 `.bm`）是否存在。 | ① |
| `n` | `(Ljava/lang/String;Z)Z` | 删除头像文件（`o6.h` + `w.b`），日志 `Removed avatar: %s, hd: %b, path: %s`。 | ① |
| `p` | `(Ljava/lang/String;[B)Landroid/graphics/Bitmap;` | **saveFile(username, bytes)**：156×156 解码→中心裁→写 `.png`，日志 `Saved avatar: %s`。 | ① |
| `q` | `(Ljava/lang/String;Landroid/graphics/Bitmap;)V` | 写缓存 + 通知 `k74/t0` 观察者（`a.d()`+`a.c()`）。 | ① |
| `r` | `(Ljava/lang/String;Landroid/graphics/Bitmap;)Z` | updateAvatar 156×156（转调 `s`）。 | ① |
| `s` | `(Ljava/lang/String;Landroid/graphics/Bitmap;III)Z` | **updateAvatar(username, bm, w, h, quality)**：裁剪/缩放后写文件+缓存，日志 `kevin updateAvatar fail %s`。 | ① |
| `t` | `(Ljava/lang/String;[B)Z` | save bytes 后写缓存。 | ① |
| `a` | `(Lfp/z;)V` | 注册 `fp/z` 监听器（存为 WeakReference 进 `b`）。 | ① |
| `o` | `(Lfp/z;)V` | 注销 `fp/z` 监听器。 | ① |
| `l` | `(Lfp/z;)Ljava/lang/ref/WeakReference;` | 按 equals 查找监听器 WeakReference。 | ① |

`i` 内缓存键原文（钉死「avatar_cache」LruCache）：

```
2a0740: 5400 8812   |000a: iget-object v0, Lcom/tencent/mm/modelavatar/x;.d:Lcom/tencent/mm/modelavatar/a; // field@12f9
2a0744: 1a02 20c5   |000c: const-string v2, "avatar_cache" // string@c520
...
2a0750: 6e20 01d4 6000 |0012: invoke-virtual {v0}, Lqe0/f;.k:(Ljava/lang/Object;)Ljava/lang/Object;  ← LruCache.get
```

> 注：`f` 第 3 个 boolean 参数在方法开头即被 `a8.F0(username)` 的 `move-result` 覆盖（`2a10d6: move-result v5`），本版中**未被实际使用**，反射调用时随便传 false 即可。

---

## 2 单例 / 初始化链路（怎么拿到实例）

### 2.1 两个类，别混淆

| 混淆名 | 原名（据证据） | 父类 | 角色 |
|--------|---------------|------|------|
| `com.tencent.mm.modelavatar.a1` | **SubCoreAvatar** | `d94/w`（服务基类） | 头像子系统**服务**，单例入口 |
| `com.tencent.mm.modelavatar.x` | **AvatarStorage** | `java/lang/Object`，实现 `fp/a0` | 头像**存储实现**（get/save/update） |

证据（①）：
- `a1` 的 helper `z0.onTimerExpired()` 用 log tag `MicroMsg.SubCoreAvatar`（`_r28_strref.py 'SubCoreAvatar'` 唯一命中）。
- `x` 的多个方法用 log tag `MicroMsg.AvatarStorage` 与埋点串 `com/tencent/mm/modelavatar/AvatarStorage`。

### 2.2 拿到 `x` 实例的两步

**第 1 步：服务单例** `a1.B8()`（PUBLIC STATIC，dexdump 原文）：

```
#1 : (in Lcom/tencent/mm/modelavatar/a1;)
  name : 'B8'
  type : '()Lcom/tencent/mm/modelavatar/a1;'
  access : 0x0009 (PUBLIC STATIC)
29ab48: 1c00 c10a   |0000: const-class v0, Lcom/tencent/mm/modelavatar/a1; // type@0ac1
29ab4c: 7110 3a96 0000 |0002: invoke-static {v0}, Ld94/n0;.c:(Ljava/lang/Class;)Ld94/m; // method@963a
29ab54: 1f00 c10a   |0006: check-cast v0, Lcom/tencent/mm/modelavatar/a1; // type@0ac1
29ab58: 1100        |0008: return-object v0
```

`d94/n0.c(Class) -> d94/m` = 微信服务注册表 `getService(Class)`（`MMKernel.service()` 模式，②）。即 `a1.B8()` ≈ `SubCoreAvatar.get()`。

**第 2 步：取存储实例** `a1.z8()`（PUBLIC STATIC，dexdump 原文节选）：

```
#5 : (in Lcom/tencent/mm/modelavatar/a1;)
  name : 'z8'
  type : '()Lcom/tencent/mm/modelavatar/x;'
  access : 0x0009 (PUBLIC STATIC)
29ac8e: 7100 b11c 0000 |0007: invoke-static {}, Lcom/tencent/mm/modelavatar/a1;.B8:()Lcom/tencent/mm/modelavatar/a1;
29ac94: 5400 8412   |000b: iget-object v0, v0, Lcom/tencent/mm/modelavatar/a1;.e:Lcom/tencent/mm/modelavatar/x; // field@1284
29aca6: 2201 e40a   |0013: new-instance v1, Lcom/tencent/mm/modelavatar/x; // type@0ae4
29acaa: 7010 241d 0100 |0015: invoke-direct {v1}, Lcom/tencent/mm/modelavatar/x;.<init>:()V
29acb0: 5b01 8412   |0018: iput-object v1, v0, Lcom/tencent/mm/modelavatar/a1;.e:Lcom/tencent/mm/modelavatar/x; // field@1284
```

即：`x` 作为 `a1` 的字段 `e`，由 `z8()` **懒初始化**并返回。

**其它可一并反射的 getter**：`a1.F8() -> m0`（图像加载器，异步下载）、`a1.H8() -> q0`（AvatarService，classes4）、`a1.x8() -> q`。

### 2.3 是否已初始化（在微信主进程、模块 hook 上下文）

- ② 依据 1：`a1` 是 `d94/w` 服务，由微信启动流程统一注册；`onAccountInitialized(Context)`（dexdump 见 `r28_a1.txt`）在账号初始化后被调。模块 hook 跑在微信主进程、账号已登录时，`a1.B8()` 已可用。
- ② 依据 2：`z8()` 是懒初始化，**首次调用即创建 `x`**，无需模块手动初始化。
- ⚠️ 注意：`x.<clinit>` 里 `MMTrimMemoryEventListener.alive()` 与 LruCache `d` 的初始化发生在 `x` 类加载时（静态块）；`x.<init>` 里用 `k74/t0.a(observer, null)` 注册观察者，**未依赖 Looper**（传 null）。取 Bitmap/路径本身是同步 IO + decode，**不要求调用线程是主线程**。

---

## 3 可否同步取 Bitmap（含失败场景）

| 调用 | 同步？ | 可能返回 null 的场景 |
|------|--------|----------------------|
| `x.i(wxid)` | 是（纯内存） | 缓存 miss；Bitmap 被回收（`isRecycled`）；username 空；LRU 被 `MMTrimMemoryEventListener` trim 清掉。 |
| `x.j(wxid)` | 是（磁盘 decode） | 文件不存在；HD 未下载；解码失败；`a8.F0`（username 空）。 |
| `x.f(wxid, false, false)` | 是（磁盘 stat） | 文件不存在（未下载/被删）。 |
| `x.e(path, wxid, hd)` | 是（磁盘 decode） | 文件不存在；解码失败后 `w.a` 回退也失败。 |

失败根因（① 依据）：
- `i` 里 `qe0/f.k(key)`（LruCache.get）miss → null；`if-eqz` 返回 null（`2a076a`）。
- `j` 里 `x.Z(path,1080,1080,0)` 是直接 decode，文件缺失即 null。
- `f` 里 `o6.v(realPath)`（exists）false → 返回 null（`2a1128`）。
- trim：`x.<clinit>` 注册的 `AvatarStorage$MMTrimMemoryEventListener`（`IListener`）会在内存紧张时清 LruCache（② 清缓存动作，类存在是①）。

**结论**：同步取「已在本地缓存的头像」完全可行；取「从未下载/被 trim」的头像会 null，需降级（§6）或走异步 `m0` 图像加载器。

---

## 4 退路：本地文件路径拼装（MD5 规则 ① 已证实）

### 4.1 拼装规则（`x.d` 字节码原文，①）

```
2a1178: |[2a1178] com.tencent.mm.modelavatar.x.d:(Ljava/lang/StringBuilder;Ljava/lang/String;Z)Ljava/lang/StringBuilder;
2a1188: 6e10 a9ae 0400 |0000: invoke-virtual {v4}, Ljava/lang/String;.getBytes:()[B      ← username.getBytes()
2a1190: 7110 1b03 0400 |0004: invoke-static {v4}, Lai/j;.g:([B)Ljava/lang/String;         ← MD5 hex（ai/j.g 引用 "MD5"）
2a119c: 6e30 c2ae 0401 |000a: invoke-virtual {v4, v0, v1}, String.substring:(II)         ← md5.substring(0,2)
2a11ae: 6e20 e5ae 0300 |0013: append '/'  (char 47)
2a11b6: 6e30 c2ae 1402 |0017: substring(2,4)
2a11c4: 6e20 e5ae 0300 |001e: append '/'
2a11ca: 1b00 6c3e 0100 |0021: const-string/jumbo "user_"
2a11d6: 3805 0800     |0027: if-eqz v5 → 跳过 hd 前缀
2a11da: 1b05 4100 0100 |0029: const-string/jumbo "hd_"
2a11e6: 6e20 edae 4300 |002f: append 完整 md5
2a11ec: 1a04 0719     |0032: const-string ".png"
```

**规则**：`相对路径 = <md5[0:2]>/<md5[2:4]>/user_<hd_><md5>.png`。

### 4.2 三种前缀（①）

| 前缀 | 出处 | 说明 |
|------|------|------|
| `<dataDir>/avatar/` | `x.f`：`k1.u().e`（dataDir）+ `"avatar/"`（`2a1100`） | 绝对文件路径（`f` 判存在） |
| `wcf://avatar/` | `x.g`：`"wcf://avatar/"`（`2a114e`） | VFS URI（`g` 不判存在） |
| （无，历史） | R28 里 `/tencent/MicroMsg/avatar/` | 该串在本版另有他用，**不是头像文件落盘目录** |

### 4.3 `.bm`（SmallBM 原始像素）变体（①）

`x.e` 解码 PNG 失败后回退 `w.a(path, username)`；`w.a` 实际读 `<path>.bm`（`2a02d8` 附近 append `".bm"`），按文件头 flag 重建 96×96 或 120×120 的 ARGB 位图（埋点串 `com/tencent/mm/modelavatar/AvatarStorage$SmallBM` + `get`）。所以磁盘上同一头像可能同时有 `.png` 与 `.png.bm` 两个文件。

### 4.4 MD5 是 ① 还是 ②

**①**：`x.d` 调用 `ai/j.g(byte[]) -> String`，而 `ai/j.g` 方法体引用字符串常量 `"MD5"`（`_r28_strref.py --dex=classes5 'MD5'` 命中 `Lai/j;->g/h/c/d`）。`ai/j` 是 `PUBLIC ABSTRACT` 全静态工具类，`g([B)->String` 为 hex、`h([B)->[B` 为原始 digest。R28 的「MD5 十六进制」从 ② 升级为 ①。

> 模块自拼路径时注意：必须**完全复刻** `xx/yy/user_<hd_>md5.png` 结构（含子目录与 `user_`），否则拼出的路径与微信实际写入位置不符。这也是「直接用 `x.f`/`x.g` 拿路径」优于「自己拼」的原因。

---

## 5 给模块的推荐调用方式（伪代码，不落 src）

```
// 前置：微信主进程、账号已登录（模块 hook 上下文即满足）
Class<?> a1c = Class.forName("com.tencent.mm.modelavatar.a1");   // SubCoreAvatar 服务
Object a1   = a1c.getMethod("B8").invoke(null);                   // 服务单例
Object x    = a1c.getMethod("z8").invoke(a1);                     // AvatarStorage 实例（懒初始化）

Bitmap bm = (Bitmap) x.getClass().getMethod("i", String.class).invoke(x, wxid);   // 1) 内存缓存
if (bm == null) {
    bm = (Bitmap) x.getClass().getMethod("j", String.class).invoke(x, wxid);      // 2) HD 磁盘解码(1080)
}
if (bm == null) {
    String p = (String) x.getClass().getMethod("f", String.class, boolean.class, boolean.class)
                           .invoke(x, wxid, false, false);                        // 3) 拼绝对路径(存在才返回)
    if (p != null) bm = BitmapFactory.decodeFile(p);
}
// bm 仍为 null → 走 §6 降级（首字色块）
```

要点：
- 全部**同步**，`i` 走内存、`j`/`f` 走磁盘，都不要求主线程。
- 反射时 `f` 的签名是 `(String, boolean, boolean)`，第 3 参被本版丢弃，传 false。
- `x.e(path, wxid, hd)` 是 static，拿到路径后可当「getAvatarFromFile」用（额外做中心裁剪）。
- 若想复用微信的异步下载/裁剪，走 `a1.F8()`（`m0` 图像加载器）与 `a1.H8()`（`q0` AvatarService）——本轮未深入，等级 ②，见 §7。

---

## 6 降级策略（失败时保留「首字色块」占位）

保持现有 `GroupRowView.avatar()` 的占位实现作为兜底，仅在 `bm != null` 时替换：
1. `x.i(wxid)` → `x.j(wxid)` → `x.f(...)`+decodeFile 依次尝试。
2. 全程 null / 解码异常（`OutOfMemoryError`、`FileNotFound`）→ 回退「昵称首字 + 稳定色块」。
3. 建议不主动 `recycle()` 拿到的 Bitmap：`x.i` 返回的是微信 LruCache 里的**共享引用**，recycle 会污染微信缓存（`x.i` 内部对 `isRecycled` 的判空就是防这个）。
4. 特殊账号（官方号/服务号等）微信用资源图（`x.k`），这些 wxid 拼文件路径也拿不到，直接走占位或 `x.k`。

---

## 7 已排除范围

- **否定**：`AvatarStorage` ≠ `a1`。`a1` 是 `SubCoreAvatar` 服务，不直接提供 `getAvatar*`（其方法仅 `<init>/B8/F8/H8/x8/z8/onAccountInitialized/onAccountReleased`，dexdump `r28_a1.txt` 全量）。
- **未定位到（③）**：「单张头像加载完成」回调。`x` 上只有 `fp/z`（`C(String)`）监听 + `k74/t0` 观察者「头像已更新」广播 + `MMTrimMemoryEventListener` 内存事件；异步下载回调在 `m0` 图像加载器（`a1.F8()`），本轮未展开。
- **未深入（②）**：`m0`（ImageLoader）与 `q0`（AvatarService，classes4）的异步下载/加载入口；`d94/n0.c(Class)` 服务注册表内部实现。
- 下一轮建议：若要异步加载 + 圆角/裁剪，直接查 `q0`（`Lcom/tencent/mm/modelavatar/q0;`，classes4.dex）的公开方法；并在 `_s9dump/classes4.txt` 里抓 `q0` 的 `getAvatar`/`loadAvatar` 类方法。

## 8 实现记录（R29-2b）

### 8.1 改了哪几处

| 文件 | 改动 |
|------|------|
| `feature/GroupRowView.kt` | `Model` 加 `lastSenderWxid: String = ""`；`avatar()` 由「单个首字 TextView」改成「`FrameLayout`（圆形裁剪 `clipToOutline`）叠 `ImageView`（真头像，`CENTER_CROP`）+ 首字 `TextView` 兜底」，首字/中性色块逻辑**原样保留**（`colorOf` 一字没动）。 |
| `feature/ConvFilter.kt` | `buildModel` 在「第一个命中」分支里把 `snaps[i].wxid` 带出为 `senderWxid`，传 `lastSenderWxid`；`modelOf()` 兜底构造用默认值，未动。 |
| `core/AvatarLoader.kt`（新建） | 反射 `a1.B8()`→`a1.z8()` 拿 `x`（懒解析 + 缓存，失败只 warn 一次）；模块侧 `LruCache<String,Bitmap>`（按字节计 8MB）；单线程后台池；同文件放纯判据 `AvatarLoadLogic`。 |
| `core/AvatarLoadLogicTest.kt`（新建） | 纯逻辑 JVM 单测，**11 条**。 |

### 8.2 异步时序

```
bind(imageView, wxid)
  ├─ wxid 空白 → 直接返回（首字兜底）
  ├─ imageView.tag = wxid                       ← 复用校验锚
  ├─ getCached: 模块 LruCache → 微信 x.i(wxid)   ← 主线程内存查表
  │    └─ 命中 → setImageBitmap + onLoaded(隐藏首字) → 结束
  └─ miss → io.execute { x.j(wxid) }             ← 后台磁盘 1080 解码（主线程严禁）
       └─ UiKit.runOnMain {
            afterDisk(tagMatches=view.tag==wxid, bitmapOk=bm!=null&&!recycled)
            ├─ SET_IMAGE → cache.put + setImageBitmap + onLoaded
            └─ FALLBACK  → 保持首字兜底（静默）
          }
```

### 8.3 降级路径（全部静默，不刷日志）

1. 反射失败 / 类找不到 → `dead=true`，`LogX.w` **只 warn 一次**，之后所有行退回首字。
2. `x.i` miss / `x.j` null / decode 抛异常 → 静默保持首字。
3. 行复用错位（`view.tag != wxid`）→ `FALLBACK`，**不设图**，防止头像串到别人头上。
4. ⛔ 全程不 `recycle()` 微信返回的 Bitmap（共享 LruCache 引用）。

### 8.4 单测（11 条，零 Android 依赖）

`AvatarLoadLogicTest`：优先级状态机（内存命中→SET_IMAGE / miss→LOAD_DISK_ASYNC / 磁盘空→FALLBACK / 磁盘命中→SET_IMAGE）、tag 复用校验（复用行必 FALLBACK、同/异 wxid、null tag、空串不混淆）、`afterDisk` 四象限确定性。

---

[DONE] 0 结论摘要
[DONE] 1 AvatarStorage 方法清单
[DONE] 2 单例 / 初始化链路
[DONE] 3 可否同步取 Bitmap
[DONE] 4 退路：本地文件路径拼装
[DONE] 5 给模块的推荐调用方式
[DONE] 6 降级策略
[DONE] 7 已排除范围
[DONE] 8 实现记录
[DONE]
