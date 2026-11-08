#!/usr/bin/env python3
"""GeneralsX / Flux Build: triage a device log (generals-stderr.log) in one pass.

A match log runs to ~300k lines, 95% of them [GX-NET] object create/destroy. This prints the parts
that answer the usual questions; nothing is modified.

  logtriage.py LOG                  summary: tags, build, mods, text tables, problems, frame rate
  logtriage.py LOG --tag gxbig      every line of one tag (without brackets)
  logtriage.py LOG --grep REGEX     lines matching a regex, [GX-NET] excluded unless --net
  logtriage.py LOG --tread          [gxtread]: tread meshes, scroll continuity, matrix vs offset
  logtriage.py LOG --diff OTHER     what differs between two logs (tags, build, mods, problems)
"""
import argparse
import collections
import re
import statistics
import sys

TAG = re.compile(r"^\[([A-Za-z0-9_.:-]+)\]")
PROBLEM = re.compile(r"\b(FATAL|ERROR|Error|error:|WARN(ING)?|Exception|exception|assert|ASSERT|"
                     r"SIGSEGV|SIGABRT|signal \d+|abort|crash|MISSING:|FAILED)\b")
FPS = re.compile(r"\[d3d8gles\] perf: ([\d.]+) fps")
BUILD = re.compile(r"^\[GX-BUILD\]|CI build number|INFO: Loading engine|versionName|engine library")
NOISE_TAGS = {"GX-NET"}


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def tag_of(line):
    m = TAG.match(line)
    return m.group(1) if m else None


def analyse(lines):
    tags = collections.Counter()
    problems = collections.Counter()
    fps, build, mods, text = [], [], [], []
    for line in lines:
        t = tag_of(line)
        tags[t or "(untagged)"] += 1
        if t in NOISE_TAGS:
            continue
        m = FPS.search(line)
        if m:
            fps.append(float(m.group(1)))
        if BUILD.search(line):
            build.append(line)
        if t == "gxbig" and ("mod " in line or "community patch" in line):
            mods.append(line)
        if t == "GX-TEXT" and ("table" in line or "primary=" in line):
            text.append(line)
        if PROBLEM.search(line) and t != "d3d8gles-diag":
            # Collapse numbers so repeats of one message count together.
            problems[re.sub(r"0x[0-9a-fA-F]+|\d+", "#", line.strip())[:200]] += 1
    return {"tags": tags, "problems": problems, "fps": fps, "build": build, "mods": mods, "text": text,
            "lines": len(lines)}


def summary(path, a):
    print("== %s: %d lines" % (path, a["lines"]))
    print("\n-- lines per tag (top 15)")
    for t, n in a["tags"].most_common(15):
        print("  %8d  %s%s" % (n, t, "   (noise, hidden below)" if t in NOISE_TAGS else ""))
    print("\n-- build / engine")
    for l in dict.fromkeys(a["build"]):
        print("  " + l[:200])
    print("\n-- mods and community patch ([gxbig])")
    for l in dict.fromkeys(a["mods"]):
        print("  " + l[:220])
    print("\n-- string tables ([GX-TEXT])")
    for l in dict.fromkeys(a["text"]):
        print("  " + l[:220])
    if a["fps"]:
        f = sorted(a["fps"])
        print("\n-- frame rate ([d3d8gles] perf, %d samples): min %.1f  p10 %.1f  median %.1f  max %.1f"
              % (len(f), f[0], f[len(f) // 10], statistics.median(f), f[-1]))
    print("\n-- problems (warnings/errors/MISSING, numbers collapsed; top 25)")
    if not a["problems"]:
        print("  none")
    for p, n in a["problems"].most_common(25):
        print("  %6dx  %s" % (n, p))


def tread(lines):
    ov = re.compile(r"\[gxtread\] override #(\d+) mesh (\S+): offset u=([\d.]+)")
    dr = re.compile(r"\[gxtread\] gles draw #(\d+): .*?upload=(\w+).*?_31=([\d.-]+)")
    sc = re.compile(r"\[gxtread\] scroll #(\d+) (\S+): .*offset0=([\d.]+)")
    pending, pairs, scroll = None, [], collections.defaultdict(list)
    for line in lines:
        m = ov.search(line)
        if m:
            pending = (m.group(2), float(m.group(3)))
            continue
        m = dr.search(line)
        if m and pending:
            pairs.append(pending + (m.group(2), float(m.group(3))))
            pending = None
            continue
        m = sc.search(line)
        if m and int(m.group(1)) <= 40:
            scroll[m.group(2)].append(float(m.group(3)))
    tanks = sorted({l.split("model ")[1].split(":")[0] for l in lines if "[gxtread] tank " in l and "model " in l})
    print("tank models seen: %s" % (", ".join(tanks) or "none"))
    print("override/draw pairs: %d, matrix != offset: %d, nonzero offsets: %d"
          % (len(pairs), sum(abs(p[1] - p[3]) > 1e-3 for p in pairs), sum(p[1] > 0 for p in pairs)))
    print("sorted-path tread meshes: %d" % sum("[gxtread] SORTED" in l for l in lines))
    for name, offs in scroll.items():
        # Tanks of one type share the name, so their updates interleave; the most common step is the
        # per-update scroll (0.9667 = -1/30 of the texture).
        steps = collections.Counter(round((b - a) % 1.0, 4) for a, b in zip(offs, offs[1:]))
        print("scroll %s: %d updates, most common steps %s" % (name, len(offs), steps.most_common(3)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log")
    ap.add_argument("--tag")
    ap.add_argument("--grep")
    ap.add_argument("--net", action="store_true", help="include [GX-NET] lines in --grep")
    ap.add_argument("--tread", action="store_true")
    ap.add_argument("--diff")
    args = ap.parse_args()
    lines = read(args.log)

    if args.tag:
        for l in lines:
            if tag_of(l) == args.tag:
                print(l)
        return
    if args.grep:
        rx = re.compile(args.grep)
        for i, l in enumerate(lines, 1):
            if rx.search(l) and (args.net or tag_of(l) not in NOISE_TAGS):
                print("%d:%s" % (i, l))
        return
    if args.tread:
        tread(lines)
        return
    a = analyse(lines)
    if not args.diff:
        summary(args.log, a)
        return
    b = analyse(read(args.diff))
    print("== %s (A) vs %s (B)" % (args.log, args.diff))
    print("\n-- tag counts that differ by more than 20%")
    for t in sorted(set(a["tags"]) | set(b["tags"]), key=lambda t: -(a["tags"][t] + b["tags"][t])):
        x, y = a["tags"][t], b["tags"][t]
        if max(x, y) >= 20 and abs(x - y) > 0.2 * max(x, y):
            print("  %-22s A %8d   B %8d" % (t, x, y))
    for key, title in (("build", "build / engine"), ("mods", "mods and community patch")):
        only_a = [l for l in dict.fromkeys(a[key]) if l not in set(b[key])]
        only_b = [l for l in dict.fromkeys(b[key]) if l not in set(a[key])]
        if only_a or only_b:
            print("\n-- %s" % title)
            for l in only_a:
                print("  A only: " + l[:200])
            for l in only_b:
                print("  B only: " + l[:200])
    new = [(p, n) for p, n in b["problems"].most_common() if p not in a["problems"]]
    gone = [(p, n) for p, n in a["problems"].most_common() if p not in b["problems"]]
    print("\n-- problems only in B (top 15)")
    for p, n in new[:15]:
        print("  %6dx  %s" % (n, p))
    print("\n-- problems only in A (top 15)")
    for p, n in gone[:15]:
        print("  %6dx  %s" % (n, p))
    if a["fps"] and b["fps"]:
        print("\n-- median fps: A %.1f   B %.1f" % (statistics.median(a["fps"]), statistics.median(b["fps"])))


if __name__ == "__main__":
    sys.exit(main())
