## S9 发送接口「不依赖 UI 发文本消息」—— ✅ 端到端闭环（2026-10-18）

> 服务于 **6.5 群发**（唯一路径）与 **6.6 强制转发降级重发**。
> 实现落点 `core/TextSender.kt`；只读/真发探针 `probe/SendProbe.kt`（`op=send`）。

### ⚠️ 前提：微信 8.0.48 的**方法名已混淆**
`com.tencent.mm` 下 50710 个单字符方法名；`sendText` / `sendMsg` / `SendMessageTask` / `MsgRetry`
全 dex 零命中。所以**不能按语义名找发送入口**，只能靠「保留的日志 tag / CGI 路径 / 类结构」反推。
（这推翻了旧的「只混淆类名/字段名、方法名保留」认知。）

### ✅ 最小发送序列（唯一推荐，全部 PUBLIC，反射最稳）
```
hl0/r1.b(String talker) : hl0/q1      // PUBLIC STATIC FINAL 工厂
q1.e(String content) : q1             // 设正文（链式，返回 this）
q1.b() : void                         // executeByPPC 跨进程异步发送
```
- **工厂返回的 `q1` 已预设** `a=toUser`（=talker）、`d=type=1`（文本），**无需再调 `h(talker)` / `i(type)`**。
- `hl0/q1` 字段全 PUBLIC：`a=toUser(String)`、`c=content(String)`、`d=type(int)`、`h=状态位(int)`；
  方法：`e(String):q1`（设 content）、`h(String):q1`（设 talker）、`i(int):q1`（设 type）、
  `b():void`（发送）、`a():hl0/l1`（构造入队对象 SendMsgCgiFactory）。
- 底层兑现 `hl0/q0` = **NetSceneSendMsg**（`super=com.tencent.mm.modelbase.k1`，
  CGI `/cgi-bin/micromsg-bin/newsendmsg`）。

### ✅ 其它发送门面（备查，未用于实现）
| 类/方法 | 签名 | 说明 |
|---|---|---|
| `qf4/o4.W8` | `(String toUser, String content, int type, int flag, long msgSourceId):void` | 发送总门面，tag `MicroMsg.SendMsgMgr`，日志原文 `send msg args error, toUser[%s] content[%s]` |
| `qf4/o4.H8` | `(String content, String toUser):void` | **参数顺序与 W8 相反**；toUser 支持逗号分隔多目标 |
| `qf4/o4.V8` | `(String toUser, String content, int type, int flag):void` | W8 简化版 |
| `mo2/w0.a()` | `():mo2/l0`（PUBLIC STATIC） | 服务定位器，实现类即 `qf4/o4` |

### ✅ 验证证据（四层，缺一不可）
1. **只读反射探针** `op=send sub=probe`：上述类/方法/字段运行时签名与静态逆向 **100% 一致**；
2. **探针真发** `op=send sub=test --es val filehelper`：`message` 表落库
   `type=1 isSend=1 content=[模块自检] S9 发送链路测试`；
3. **正式代码** `op=send sub=txt --es val filehelper`（调 `TextSender.send`）：
   日志 `TextSender.send -> true`，落库 `content=... (TextSender)`；
4. **交叉验证**：子代理五点复核通过。

### 接入点（只改「最后一公里」，状态机/UI 未动）
- `core/TextSender.kt` —— 共用发送器（`prepare(lpparam)` 存 loader；`send(wxid,content)` 走三步；`ready()` 判链路是否可用）。
- `MassSendPage.MassSendRunner.send` —— `ready()`→ OK/FAIL；未 prepare → `NO_CHANNEL`
  （不重试、不计连续失败、不触发「连续 5 条中止」闸门）。
- `ForceForward.resendText` —— `TextSender.send(target, snap.text)`，原「恒 false」降级已摘除。

### 查消息库的姿势（复用 `op=label sub=sql`）
```
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op label --es sub sql \
  --es sql "select msgId,type,isSend,createTime,content from message where talker='filehelper' order by createTime desc limit 3"
```
- ⚠️ **SQL 字符串字面量必须用单引号**：用双引号会被设备 `sh` 拆断
  （`talker="filehelper"` → `talker=filehelper`，被当列名报 `no such column: filehelper`）。
- `message` 表列：`msgId`(INTEGER PK)/`msgSvrId`/`type`/`status`/`isSend`/`isShowTimer`/`createTime`/
  `talker`/`content`/`imgPath`/`reserved`/`lvbuffer`(BLOB)/`transContent`/`transBrandWording`/
  `talkerId`/`bizClientMsgId`/`bizChatId`/`bizChatUserId`/`msgSeq`/`flag`。

### 边界 / 铁律
- ⚠️ `q1.b()` 返回 void 且异步（executeByPPC）：**不抛异常 ≠ 服务端一定送达**，重试/对账由上层状态机负责。
- ⚠️ 实测在**后台线程**调 `q1.b()` 可正常落库（与「真免打扰 setter 必须主线程发 CGI」不同 ——
  本链路是跨进程异步，不依赖主线程）。
- 反射探针入口：`op=send sub=probe|test|txt [--es val <wxid>]`。

---

