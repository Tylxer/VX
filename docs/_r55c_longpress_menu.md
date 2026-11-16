# R55c — 分组行「长按菜单」真机实测（2026-11-16 19:07 / 20:06）

> 用户本轮拍板「长按暂不动」，这份只做**事实留存**，供下一轮对齐用。零代码改动。

## 1. 怎么拿到的

真手长按（`adb input` 会被微信识别成点击，拿不到菜单 —— 这条 R55 §11.3 已定案），
菜单弹出后立刻 `uiautomator dump` + `screencap`：

```bash
export MSYS_NO_PATHCONV=1
adb shell uiautomator dump /sdcard/_r55menu.xml && adb pull /sdcard/_r55menu.xml .
adb shell screencap -p /sdcard/_r55menu.png && adb pull /sdcard/_r55menu.png .
```

被长按的行：微X 分组行 `pet片材`（`wx__c_19d94cc78fb`），行底色变灰表示处于按下态。

## 2. 菜单的视图结构（dump 原文解析）

```
android.widget.FrameLayout
android.widget.FrameLayout
└── android.widget.ListView                 ← 全部项都是这个 ListView 的子项
    ├── TextView  解散聊天分组
    ├── TextView  归拢设置
    ├── TextView  标为未读
    ├── TextView  取消置顶
    ├── TextView  不显示该聊天
    ├── TextView  删除该聊天
    ├── TextView  加入分组        ← 本模块 ConvMenuInject 注入
    └── TextView  标记已读        ← 本模块 ConvMenuInject 注入
```

- **8 项全是裸 `TextView`**：无 `resource-id`、无图标、无分割线；白底卡片、字号统一、左对齐。
- 顺序固定：微X 两项在**最前面**，宿主四项居中，我方两项在**最后**。

## 3. 归属拆分（与 R55 §Q3 的结论一致，这里补上真机侧证据）

| # | 文案 | 归属 | 依据 |
|---|---|---|---|
| 0 | 解散聊天分组 | **微X** | 宿主 `resources.arsc` 里 `解散聊天分组` / `归拢设置` / `归拢` 全 0 命中（R55 §Q3） |
| 1 | 归拢设置 | **微X** | 同上 |
| 2 | 标为未读 | 宿主 | 宿主原生菜单项 |
| 3 | 取消置顶 | 宿主 | 同上 |
| 4 | 不显示该聊天 | 宿主 | 同上 |
| 5 | 删除该聊天 | 宿主 | 同上 |
| 6 | 加入分组 | **本模块** | `ConvMenuInject`，`kind=footer` 追加在末尾 |
| 7 | 标记已读 | **本模块** | 同上 |

## 4. 关键定案：宿主那条 PopupWindow 是**微信自己的**，微X 是往里插项

我方注入日志（`ConvMenuInject.kt:433-439` 现成打点，`python tools/dev.py q CMI 30`）：

```
19:07:57.439 CMI target wxid=wx__c_19d94cc78fb pos=14 name=pet片材
19:07:57.514 CMI popup show content=m4
19:07:57.562 CMI popup injected wxid=wx__c_19d94cc78fb name=pet片材 joined=0
             kind=footer box=va4.j3 isListView=true
             adapter=android.widget.HeaderViewListAdapter parent=va4.m4 sampleH=202
19:07:57.570 CMI relayout need=404 box=525x1212 child=6 join.attached=false ...
```

`child=6` = 我方追加前容器里已有 6 项 ⇒ 正好是「微X 2 + 宿主 4」。

**容器类 `va4.j3` / 父容器 `va4.m4` 是微信自己的类**（不是微X 的）：

```python
import zipfile, re
z = zipfile.ZipFile(r"_cache/mm.apk")
for name in z.namelist():
    if re.match(r"classes\d*\.dex$", name):
        d = z.read(name)
        print(name, {t: d.count(t.encode()) for t in ("Lva4/j3;", "Lva4/m4;")})
# → classes4.dex {'Lva4/j3;': 1, 'Lva4/m4;': 1}   （各 1 次 = 类定义）
```

⇒ **[已证实] R55 §Q3 的候选 ②（微X 自绘一个仿原生 PopupWindow）排除，取候选 ①：
微X 往宿主这条菜单里插项，且插在最前面（prepend）；本模块用 `addFooterView` 追加在末尾。**

## 5. 顺手发现的两个可见差异（不在用户本轮要求内，留档）

1. **图标**：微X 分组行左侧是**成员头像 2×2 拼图**（`美妆`/`改性` 都能看出四宫格拼贴），
   我方 `123` 行是**通用灰色人像占位**。这是「长得像不像」里最显眼的一处，比标题栏更直观。
2. **未读数**：截图中标题栏是 `微信(1195)`，分组行右侧显示的是**时间**（19:53 / 16:47 / 15:59…），
   与微X 分组行一致 —— 说明我方 `conversationTime` 的写法已经对上了，这一点不用再改。

## 6. 下一轮若要对齐长按，要做的两件事

1. 把 `GroupRowMenu` 的「删除分组」改成「解散聊天分组」，并把两项**插到最前面**（而不是末尾）——
   现在 `ConvRowMenu` 走 `addFooterView`，要前插得换注入方式（或注入后用反射把子项重排）。
2. 「归拢设置」点下去干什么仍**未知**（本次只拿到菜单，没点）。⚠️ 不要在真实分组上试「解散聊天分组」，
   点之前先 `op=gcs stat` + 备一份 `rconversation` 里我方行。
