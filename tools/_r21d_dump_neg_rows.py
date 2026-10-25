"""R21d 止血：把 10 条负数 msgId 提示行 **完整 dump** 到本地（只读，不写库）。

## 背景：微信闪退根因（2026-10-25 15:51 真机 crash buffer 定案）
```
FATAL EXCEPTION: main
Process: com.tencent.mm
fr4.b: msgId not in the reasonable scope
    at fr4.a.h → fr4.a.g
    at com.tencent.mm.storage.h9.x1
    at com.tencent.mm.storage.h9.convertFrom
    at com.tencent.mm.storage.j9.F8
    at com.tencent.mm.ui.chatting.component.qb.k0
    at com.tencent.mm.ui.chatting.s2.a
    at com.tencent.mm.ui.chatting.BaseChattingUIFragment.z
    at com.tencent.mm.ui.chatting.ChattingUIFragment.V
```
⇒ 进任何会话 → 读 message 表 → `h9.convertFrom` 校验 msgId 范围 → **我们的负数行触发异常 → 主线程崩溃**。

罪证（`select msgId,type,talker,createTime from message where msgId<0`）：
```
count(*)=10
-10 .. -1   type=10000   talker=多个群/单聊   content="XX 尝试撤回一条消息（已阻止）"
```
这 10 行全是 `RevokeRescue.nextMsgId()` 生成的（负数区间故意避开 max(msgId)+1 撞号），
但微信 `convertFrom` 有 msgId 范围校验 ⇒ **负数过不了**。

## 本脚本做什么
只读。逐列把 10 行 dump 成 JSON，存 `_r21d_neg_rows.json`，供删除后回滚 / 分析可插区间。
⚠️ 探针单列截断 60 字符，所以长列（lvbuffer 等）会看不全 —— 这没关系：
   我们只需要确认「哪些列有值」，全文不在删前必需（删的是提示行，本来就没有 lvbuffer）。

用法：python tools/_r21d_dump_neg_rows.py
"""
import importlib.util
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
spec = importlib.util.spec_from_file_location("h6", os.path.join(HERE, "_r21c_verify_hint6.py"))
h6 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h6)

OUT = os.path.join(ROOT, "_r21d_neg_rows.json")

# message 表里我们关心的列（不含 lvbuffer 这类大 BLOB，探针也看不全）
COLS = ["msgId", "msgSvrId", "type", "status", "isSend", "createTime",
        "talker", "content", "imgPath", "msgsource", "reserved", "flag"]


def parse_row(line):
    """`msgId=-1 | type=10000 | ...` → dict"""
    d = {}
    for k, v in re.findall(r"(\w+)=([^=|]*)", line):
        d[k] = v.strip()
    return d


def main():
    h6.open_window()

    print("=" * 80)
    print("[1] dump 负数提示行（%d 列）" % len(COLS))
    print("=" * 80)
    rows = []
    for c in COLS:
        # 探针每列截断 60 字符；分两段取长列不受影响，但这里只取一段够用
        q = "select msgId,%s from message where msgId<0 order by msgId" % c
        for l in h6.query(q):
            if "LABEL" in l and "msgId=" in l:
                print("  | " + l.rstrip()[:185])
                d = parse_row(l)
                mid = d.get("msgId")
                if mid is None:
                    continue
                # 合并到 rows
                hit = next((r for r in rows if r["msgId"] == mid), None)
                if hit is None:
                    hit = {"msgId": mid}
                    rows.append(hit)
                if c != "msgId" and c in d:
                    hit[c] = d[c]

    print("\n" + "=" * 80)
    print("[2] 汇总（%d 行）" % len(rows))
    print("=" * 80)
    for r in rows:
        print("  msgId=%-4s type=%-6s isSend=%-2s status=%-2s createTime=%s"
              % (r.get("msgId"), r.get("type"), r.get("isSend"),
                 r.get("status"), r.get("createTime")))
        print("      talker=%s" % r.get("talker"))
        print("      content=%s" % r.get("content"))

    print("\n" + "=" * 80)
    print("[3] 真实性三重校验（删前必过）")
    print("=" * 80)
    n_neg = all(int(r["msgId"]) < 0 for r in rows if r.get("msgId", "x").lstrip("-").isdigit())
    n_type = all(r.get("type") == "10000" for r in rows)
    n_text = all("尝试撤回一条消息（已阻止）" in (r.get("content") or "") for r in rows)
    print("  ① msgId 全为负        : %s" % ("✅" if n_neg else "❌ 有非负行，禁止删除"))
    print("  ② type 全为 10000     : %s" % ("✅" if n_type else "❌ 有非 10000 行，禁止删除"))
    print("  ③ content 全含阻止文案 : %s" % ("✅" if n_text else "❌ 有非提示行，禁止删除"))
    safe = n_neg and n_type and n_text and len(rows) > 0

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"rows": rows, "safeToDelete": safe,
                   "criteria": "msgId<0 AND type=10000 AND content contains 尝试撤回一条消息（已阻止）"},
                  f, ensure_ascii=False, indent=2)
    print("\n  快照已写 %s（%d 行）" % (OUT, len(rows)))
    print("  可删除 = %s" % ("✅ 是（三重条件全过）" if safe else "❌ 否（条件未全过，先别删）"))


if __name__ == "__main__":
    main()
