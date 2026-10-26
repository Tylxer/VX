# R28 静态 dex 取证：微信 8.0.48 头像加载接口

> **范围**：纯静态只读。`_cache/dex/classes.dex` ~ `classes15.dex`（微信 8.0.48 / 2588 GP）。
> **禁止**：跑 gradle、adb、改源码 —— 本轮一次都没跑。
> **目标**：定位「给一个 wxid → 拿到头像（Bitmap / Drawable / 本地文件路径）」的可调用接口，
> 替换 `GroupRowView.avatar()` 现在的「昵称首字 + 稳定色块」占位实现。
>
> **证据分档**（全文遵守）：
> - **① 字面证据** —— 贴字节码 / dexdump 原文，可信。
> - **② 强推测** —— 有调用链但缺最后一步落锤。
> - **③ 未定位到** —— 宁可写③，不编①。

---

## 0. 结论摘要（先给答案）

| # | 结论 | 等级 |
|---|------|------|
| 1 | 微信头像的**核心存储类**是 `com.tencent.mm.modelavatar.AvatarStorage`（混淆后为 `com.tencent.mm.modelavatar.a1`，父 `d94.w`）。证据：`com/tencent/mm/modelavatar/AvatarStorage$MMTrimMemoryEventListener` 与 `AvatarStorage$SmallBM` 两个**非混淆内部类**把它钉死在 classes7.dex。 | ① |
| 2 | 头像**本地缓存目录** = `<data>/MicroMsg/avatar/`（字面串 `/tencent/MicroMsg/avatar/`），文件名为 **username 的 MD5 十六进制**，大图带 `_hd` 后缀。 | ① 目录 / ② 文件名规则 |
| 3 | 「只吃 username 就返回 Bitmap」的**底层方法存在**，但**第①档证据待补**（见 §3）。 | 待定 |
| 4 | 业务侧统一入口是 `com.tencent.mm.pluginsdk.ui.AvatarDrawable`（`AvatarDrawable$Factory`）与 `com.tencent.mm.plugin.avatar.AvatarImageLoaderFeatureService`。 | ① 类存在 / ② 作为入口 |
| 5 | `attachAvatar(ImageView, username)` 这种**单一标准入口在本版不存在**：`attachAvatar` 只定义在 kinda（Flutter/Kotlin 桥）的 `KContactService` 里，`attachAvatarDrawable` 只定义在 `ShakeMgrImpl`（摇一摇）里。**都不是会话列表用的那个。** | ①（否定结论） |

---

## 1. 取证步骤与中间产物

自建工具（只读，全部落在 `tools/`）：

| 工具 | 作用 |
|------|------|
| `tools/_r28_astr.py` | 全 dex 字符串池扫描（关键字） |
| `tools/_r28_cls.py` | 全 dex **class_defs** 列类（不是 type_ids 引用） |
| `tools/_r28_meth.py` | 按方法名反查**定义类** + 完整签名 + static 与否 |
| `tools/_r28_strref.py` | 字符串常量 → 哪些类/方法引用了它（const-string 反查） |

中间产物：`_cache/r28_avatar_str.txt`、`_cache/r28_c_modelavatar.txt`、`_cache/r28_m1.txt`、`_cache/r28_dd7_hdr.txt`（dexdump 权威输出）。

### 1.1 踩坑记录（写进工具注释了，这里留档）

**坑 1：`type_ids` 命中 ≠ 类定义在这个 dex。**
`grep 'Lcom/.../x;'` 在每个 dex 都能命中（引用），必须走 `class_defs`。
→ `_r28_cls.py` / `_r28_meth.py` 都只走 `class_defs`。

**坑 2：class_data 的 `encoded_method` 里 `code_off` 只在非 abstract/native 时存在。**
本批 dex 里 `com.tencent.mm.dynamicbackground.model.DynamicBackgroundNative`
（含多个 native `draw(J)V` 重载）会让 uleb 解析**错位**，症状：
同一个 `(J)V` 方法连续出现 8 次、`code_off` 出现 `0x111` 这种**未对齐的垃圾值**，
进而把垃圾区当指令扫 → 单 dex 扫描 80 秒、`const-string` 假命中 6373 万条。
→ `_r28_strref.py` 加了 `_code_ok()` 校验（4 字节对齐 + 头部在文件内 + insns_size 有界）。
→ **所有纯 Python 解析结果必须与 `dexdump` 交叉验证后才作数。**

---

## 2. 字符串层证据（`_cache/r28_avatar_str.txt`）

### 2.1 目录 / 路径（① 字面证据）

```
classes.dex    /tencent/MicroMsg/avatar/
classes7.dex   avatar/
classes7.dex   //avatar
classes7.dex   hdAvatarPath:
classes7.dex   getAvatarFromFile
classes7.dex   USERINFO_LAST_LOGIN_AVATAR_PATH_STRING
classes7.dex   last_avatar_dir
classes7.dex   Saved avatar: %s
classes7.dex   Removed avatar: %s, hd: %b, path: %s
classes7.dex   Failed to decode avatar: %s
classes7.dex   Cannot write avatar file: %s
classes10.dex  getAvatarPath -> path:
classes10.dex  getAvatarPath returns null. path:
classes10.dex  temp.avatar.hd
classes10.dex  ${data}/MicroMsg/last_avatar_dir
classes10.dex  getAvatarFromLocal
classes14.dex  avatar_
classes4.dex   temp.avatar
classes4.dex   status_avatar_
classes4.dex   last_avatar_path
```

### 2.2 关键日志 tag（用来反查类，① 字面证据）

```
MicroMsg.AvatarStorage           classes.dex / classes7.dex
MicroMsg.SubCoreAvatar           classes7.dex
MicroMsg.AvatarService           classes4.dex / classes7.dex
MicroMsg.AvatarLogic             classes4.dex
MicroMsg.AvatarDrawable          classes3.dex / classes4.dex
MicroMsg.AvatarRoundDrawable     classes3.dex
MicroMsg.AnyProcessAvatarAttacher classes4.dex / classes9.dex
MicroMsg.AvatarImageLoaderFeatureService  classes4.dex
MicroMsg.ChattingAvatarImageView classes4.dex / classes2.dex
```

### 2.3 非混淆的类型串（① 字面证据，可直接拿去反射/class.forName）

```
com/tencent/mm/modelavatar/AvatarStorage
com/tencent/mm/modelavatar/AvatarStorage$SmallBM
com/tencent/mm/modelavatar/AvatarStorage$MMTrimMemoryEventListener
com/tencent/mm/pluginsdk/ui/AvatarDrawable$Factory$1
com/tencent/mm/pluginsdk/ui/AvatarDrawable$Factory$2
com/tencent/mm/pluginsdk/ui/AvatarDrawable$Factory$3
com/tencent/mm/ui/chatting/view/AvatarImageView
com/tencent/mm/ui/chatting/view/ChattingAvatarImageView
com/tencent/mm/plugin/patmsg/ui/AvatarPatImageView
com/tencent/mm/plugin/patmsg/ui/AvatarPatTipImageView
com/tencent/mm/kara/feature/feature/comm/AvatarFeatureGroup
```

---
