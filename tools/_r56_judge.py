"""R56「只防@全体成员」判据真机验证脚本。

跑法：python tools/_r56_judge.py [--n 3]

做两件事：
1. 读 `op=quiet sub=at sub2=status`，打印 `allOnly=` 当前值（判本次装的包是否含新开关）；
2. 对一组固定样本逐个跑 `sub2=judge --es val <文本>`，把模块返回的
   `allOnly=true -> ... | allOnly=false -> ...` 原样贴出来。

为什么不用「真的有人去群里 @全体成员」来验证：那样一次只能验一个样本，
且需要群主/管理员配合；judge 子命令把判据单独暴露出来，一次能验完整的真假阳性矩阵。
真机 E2E（静音是否真的发生）另用 `status` 里的 matched/muted 计数在真消息前后对比，
见 docs/_r56_mute_at_all_only.md 的真机验证节。
"""
import argparse
import subprocess
import sys

sys.path.insert(0, __file__.rsplit("\\", 1)[0].rsplit("/", 1)[0])
import dev  # noqa: E402

# (样本说明, 通知正文)。期望值写在 -- 后面，便于人眼比对。
CASES = [
    ("at-all 中文", "老王: @所有人 明天九点开会", "true"),
    ("at-all 紧贴中文", "@所有人请查看群公告", "true"),
    ("at-all 全体成员", "老王: @全体成员 下午有活动", "true"),
    ("at-all 英文", "Bob: @all standup at 9", "true"),
    ("at-me 别名 V", "老王: @V 这个你处理下", "false"),
    ("at-me 别名后接字母", "老王: @Vip 会员到期", "false"),
    ("普通消息", "老王: 收到", "false"),
]


def run_probe(args):
    out = subprocess.run(
        [sys.executable, "tools/dev.py", "probe", "quiet", "at"] + args,
        capture_output=True, text=True, encoding="utf-8", errors="ignore",
        cwd=dev.ROOT if hasattr(dev, "ROOT") else None,
    )
    return (out.stdout or "") + (out.stderr or "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=3)
    a = ap.parse_args()

    print("=== status ===")
    txt = run_probe(["status", "--grep", "registered=", "--n", str(a.n), "--wait", "4"])
    for line in txt.splitlines():
        if "registered=" in line:
            print(line.strip())

    print("\n=== judge ===")
    ok = fail = 0
    for label, text, expect in CASES:
        txt = run_probe(["judge", "--val", text, "--grep", "judge", "--n", "8", "--wait", "4"])
        parsed = None
        for line in txt.splitlines():
            if "judge" in line and "allOnly=" in line:
                parsed = line.strip()
        if parsed is None:
            print(f"[?] {label:16} 未拿到输出（文本={text!r}）")
            fail += 1
            continue
        got = "true" if "allOnly=true -> true" in parsed else (
            "false" if "allOnly=true -> false" in parsed else "?")
        mark = "OK " if got == expect else "FAIL"
        if got == expect:
            ok += 1
        else:
            fail += 1
        print(f"[{mark}] {label:16} 期望 allOnly=true -> {expect:5} 实际 {got:5} | {parsed}")

    print(f"\n合计 OK={ok} FAIL={fail}")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
