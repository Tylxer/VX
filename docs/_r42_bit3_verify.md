# R42 · bit3 写回 —— 真机验收协议（待执行）

> 写代码与真机分离：本协议由主会话执行，子代理不碰 adb。
> 设备 `467cf5bc`，微信 8.0.48/2588 GP，1440×3200，模块 `com.lx.wxgroup` 0.2.8。
> 探针 action ⛔ 是 `com.lx.wxgroup.action.PROBE`（写成 `com.lx.wxgroup.PROBE` 会静默零日志）。
> ⛔ 装包前必须交叉复核最终 diff（子代理 review / 逐条对照 / 单测三选一以上），
> 本轮已做设计复核（`docs/_r42_bit3_design.md`）与实现 diff 逐条对照，补齐开关后还须再核一次。

## 0. 基线（已取，2026-11-07 00:41）

同一好友 `verity`（`wxid_xlrbelf6hv0w22`）两条被拦下的动态：

| snsId (signed) | sourceType | 含义 |
|---|---|---|
| `-3397762123896778223` | **10** | 删前进过主页 ⇒ 有 bit3，主页可见 |
| `-3397763077853081064` | **2** | 删前没进主页 ⇒ 无 bit3，**主页查不到（缺口）** |

当前开关：`antirevoke.sns=true`、`antirevoke.sns_block=false`、`antirevoke.sns_sqlblock_off=false`。
本次新增调试键 `antirevoke.sns_album_fix_off` 默认未设置（即 false ⇒ 补位开启）。

## 1. 复现前提（必须用户配合，不能由 adb 代劳）

**关键点：删除之前绝对不能进该好友主页**，否则那条动态会被相册列表拉取、自动带上 bit3，
缺口就不复现了（这正是 `src=10` 那条与 `src=2` 那条的差别）。

1. 好友号发一条**新**动态（图片或纯文字均可，记住内容）。
2. 本机时间线看到这条；**不要点头像 / 不要进好友主页 / 不要点「朋友圈」入口**。
3. 好友号删除这条动态。
4. 本机时间线刷新（下拉），确认这条仍在（防撤回升效）。
5. 通知我，由我读 SQL 与计数；**此时仍不要进主页**。
6. 我确认读数后，再进好友主页相册看这条是否出现。

## 2. 我这边要跑的命令（固定口径，别临时改）

```bash
ADB='C:\Users\lx\AppData\Local\Android\Sdk\platform-tools\adb.exe'
P="am broadcast -a com.lx.wxgroup.action.PROBE"

# ① 清日志（测前必做，5000 行环形会盖）
"$ADB" -s 467cf5bc shell "$P --es op logclear"

# ② 看这条的 sourceType / localFlag（SQL 只包一层双引号，包单引号会被 SQLX 拒）
"$ADB" -s 467cf5bc shell "$P --es op sql --es q \"select snsId,userName,sourceType,localFlag from SnsInfo where userName='wxid_xlrbelf6hv0w22' order by createTime desc limit 6\" --ei n 20"

# ②b 看三层开关真实状态（⛔ 别靠「我以为开了」，kvset 写完必须 kvget 回读）
"$ADB" -s 467cf5bc shell "$P --es op snsg --es sub stat"
python tools/dev.py q "SNSG" 4
#    期望 SNSG SNS stat blockEnabled=true sqlBlock=true albumFix=true snsDb=true

# ③ 拦截账本（after 读回值、afterOk / afterFail、shape / drop / sqlClear9 计数）
"$ADB" -s 467cf5bc shell "$P --es op snsg --es sub blockstat"
python tools/dev.py q "BLOCK after" 8

# ④ 灰标仍在（时间线那条应带「该动态已被作者删除」）
"$ADB" -s 467cf5bc shell "$P --es op snsdel --es sub stat"
```

读 `BLOCK after` 完整行时别用 `tools/dev.py q`（它截断到 240 字符），用
`python -c "import sys;sys.path.insert(0,'tools');import dev;..."` 直接读落盘日志。

⚠️ **引号分两种场景，别混用**（照错的那条跑会静默拿不到数据）：
- `op=sql` 的 `--es q`：**只包一层双引号**（见上面 ②），把整条 select 再包一层单引号会被
  SQLX 白名单拒，日志里只留一行「拒绝」。SQL **内部**的 `'wxid_...'` 是 SQL 字符串字面量，必须保留。
- 其它带空格 / 特殊字符的 `--es` 值：`adb shell` 会**二次解析**，这类才需要额外包一层单引号。
- 无论哪种，`kvset` 之后一律 `kvget` 回读复核（写进去 ≠ 已生效，还要看 `SNS stat` 的 `albumFix=`）。

## 3. 正向判据（补位开启）

**三条必须同时成立，缺一条不算修好。⛔ 主判据只认「用户能看见的事实」：库值 / 日志行 / 界面。**

1. SQL 里这条新动态的 `sourceType` 从 `2` 变成 **`10`**（不是停在 2，也不是变成 0）；
2. `BLOCK after snsId=.. before=2 after=10`，且事件行带 `keep=10 shape=BLOCKED_BY_REWRITE`；
3. **进好友主页相册后这条动态真的出现**（`uiautomator dump` 或截图可见），且**无重复、无错序**。

旁证（内部计数器，只用于定位，⛔ 不单独当判据）：`afterOk` 递增、`afterFail` 不涨；
shape 段 `rw:` 增长、`nb:` 与 `un:` 恒 0；
`sqlClear9 +N / sqlBlock9 +N`（方案 B 仍在拦）、`blkFail=0`；
`delSns` / `delRow` 为 0（全程零 DELETE）、时间线仍在、灰标仍挂。

## 4. 反向对照（自变量唯一，必做）

只关新增的补位层，**保留 A（摘键）与 B（`&-9`→`-1`）**：

```bash
"$ADB" -s 467cf5bc shell "$P --es op kvset --es key antirevoke.sns_album_fix_off --es val true"
"$ADB" -s 467cf5bc shell "$P --es op kvget --es key antirevoke.sns_album_fix_off"   # 回读复核
# 再看 ②b：SNSG SNS stat ... albumFix=false，blockEnabled / sqlBlock 必须仍是 true
```

然后**再发一条新动态、同样删前不进主页、删除** ⇒ 期望：

1. SQL 里这条停在 **`2`**（不补位）；
2. `BLOCK after ... before=2 after=2`，时间线仍在（说明 A/B 没被一起关掉）；
3. **好友主页相册里这条不出现**。

旁证：shape 段 `rm:` 增长、`rw:` 不涨（降级为只摘键）；`sqlBlock9` 仍增长（B 层没被关）。

与 §3 的两组读数互斥 ⇒ 证明「主页出现」确实由补位带来。
测完务必还原：`--es val false`（或删键）并 `kvget` 回读。

## 5. 已知风险（验证轮要顺带看）

- ⭐ **重做「方案 B 反向消融」之前必须先停写回**（`docs/_r42_bit3_design.md` §4① / §5.1）：
  只关 `antirevoke.sns_sqlblock_off=true` 时，微信的 `& -9` 把 `10` 削成 `2`，
  但紧随的 `want=0`（或降位写 2）仍被甲拦下、又会被写回成 `10` ⇒ 界面上「主页没消失」，
  从而误判成「B 层不必要 / 写回自愈了缺口」。
  ⇒ 重做 `docs/_r42_bit_b_report.md` §8 的消融判据链
  （`sqlClear9↑ + sqlBlock9 不变 + blkOff↑ + src 10→2`）时，
  **先把 `antirevoke.sns_album_fix_off` 置 true**，自变量才唯一。本轮 §4 正是这个形态。
- ~~`old|10` 对带高位的 `old` 是否落在相册 64 值枚举里，未验证~~ → **2026-11-07 00:53 已关闭**：
  从落盘日志抠出完整枚举（`op=snsg` 那条 `sql-clear9` 的 `sourceType in (...)`，64 个值），
  再用 `select sourceType,count(*) from SnsInfo group by sourceType` 取真机全库 262,374 行的
  **17 种真实旧值**逐一验算：`2→10`、`8→10`、`10→10`、`130→138`、`136→138`、`138→138`、
  `22→30`、`6→14`、`86→94`、`70→78`、`12→14` 全部 **IN 枚举**；
  `0/128/20/84/4/68` 因 `old & 10 == 0` 走降级不写回（本来就两处不可见，不会复活）。
  理论上 0..1023 里有 672 个组合的 `old|10` 不在枚举里，但**这些组合库里一行都没有**，
  不构成现实风险；若将来出现新的 sourceType 取值，需重新验这条闭包。
- `old & 10 == 0`（0/4/32/128/20/84/68…）时不补位、只摘键 ⇒ 这些类型维持旧行为，不会「复活」。
  ⚠️ 与独立设计复核（`docs/_r42_bit3_design.md` §1.4 只排除 `B==0`）的**分歧以实现方为准**：
  只排除 0 会让 `4(私密)/128(折叠)` 这类两处都不可见的行被补成可见，属于误复活。
- 「所有被拦下的动态都进相册」是拍板时接受的代价；若主页出现重复/错序，按 §6 处理。

## 6. 回滚

- **止血（秒级，优先）**：`antirevoke.sns_album_fix_off=true` ⇒ 回到旧行为「只摘键」，A/B 两层不动。
  主开关 `antirevoke.sns=false` 是兜底（一次停掉三层，不适合做单变量对照）。
- ⚠️ **数据不能只靠开关回滚**（`docs/_r42_bit3_design.md` §5.1）：B 层把 `& -9` 改成恒等，
  已经写回成 `10` 的行会长期留在相册枚举里。要让它自然消退，顺序必须是
  **先停写回 → 再让微信自己的 `& -9` 执行 → 等微信对账收缩**；
  反过来（先关 B、写回还开着）等于清了又补，白做。模块不提供带外人工修库。
- **代码回滚**：撤 `SnsGuard.applyBlock` 的写回分支 + `Config.albumFixEnabled` 三参接线
  （`refresh()` 里的 `albumFixEnabled = ...`） + `SnsGuard.ALBUM_FIX_OFF_KEY` 私有常量
  + `SnsBitLogic.keepAliveSource` 与对应测试；`SnsBlockProbe` 只动过日志文案与四个正交计数器，
  可保留（不引入旧口径误报）。
  ⚠️ **别去 `Keys.kt` / `Prefs.ALL` 找它**：该 key 是 `SnsGuard.kt:80` 的 `private const val`，
  **刻意未进 `Keys.kt`、未登记 `PrefSpec`**（纯调试键，不进设置页/备份；独立复核 §5.3 判定此取舍正确）。
  撤销时只需删这一处常量 + 三参接线，不动 `Keys.kt`。
