"""Long frames of an app's main thread in a text atrace (adb shell atrace ... > trace.txt).

Usage: python tools/perf/long_frames.py <trace.txt> <pid> [min_ms]

For every Choreographer#doFrame (and RenderThread DrawFrame) longer than min_ms (default 12), prints
its nested sections that took 2 ms or more, so the work behind a hitch can be named.
"""
import re
import sys
from collections import defaultdict

path, pid = sys.argv[1], sys.argv[2]
min_ms = float(sys.argv[3]) if len(sys.argv) > 3 else 12.0
line_re = re.compile(r'^\s*(.+?)-(\d+)\s+\(\s*(\d+|-+)\)\s+\[\d+\]\s+\S+\s+([\d.]+): tracing_mark_write: (.*)$')

stacks = defaultdict(list)  # tid -> [(name, start, children)]
done = defaultdict(list)    # tid -> top-level finished [(name, start, end, children)]
frames = defaultdict(list)  # tid -> frame sections
names = {}
for raw in open(path, encoding='utf-8', errors='replace'):
    m = line_re.match(raw)
    if not m:
        continue
    task, tid, tgid, ts, mark = m.groups()
    ts = float(ts) * 1000.0
    if mark.startswith('B|'):
        parts = mark.split('|', 2)
        if parts[1] != pid:
            continue
        names[tid] = task
        stacks[tid].append([parts[2] if len(parts) > 2 else '?', ts, []])
    elif mark.startswith('E|') or mark == 'E':
        if mark.startswith('E|') and mark.split('|')[1] != pid:
            continue
        st = stacks.get(tid)
        if not st:
            continue
        name, start, children = st.pop()
        node = (name, start, ts, children)
        (st[-1][2] if st else done[tid]).append(node)
        # Frames are kept wherever they close (a section begun before the trace never ends).
        if 'doFrame' in name or 'DrawFrame' in name:
            frames[tid].append(node)


def walk(node, depth, out):
    name, start, end, children = node
    dur = end - start
    if dur >= 1.0 and depth <= 30:
        out.append('  ' * depth + f'{dur:6.1f} ms  {name[:110]}')
        for c in children:
            walk(c, depth + 1, out)


for tid, nodes in frames.items():
    task = names.get(tid, tid)
    if tid != pid and 'RenderThread' not in task:
        continue
    for n in nodes:
        name, start, end, _ = n
        if ('doFrame' in name or 'DrawFrame' in name) and end - start >= min_ms:
            out = []
            walk(n, 0, out)
            print(f'--- {task} @ {start / 1000:.3f}s')
            print('\n'.join(out))
