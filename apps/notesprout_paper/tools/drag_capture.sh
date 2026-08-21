#!/bin/sh
# Capture what a "sluggish selection drag" looks like from outside (arc 6 / S3): the foreground
# paper screen's last ~120 frames (UI-thread draw vs GPU vs total), the busiest processes, and the
# last paper-related log lines. Run WITHIN ~10 s of the drag (framestats is a ring buffer).
#   tools/drag_capture.sh <serial> [out-dir]
S=${1:?serial}; OUT=${2:-/tmp/drag_capture_$(date +%H%M%S)}; mkdir -p "$OUT"
TOP=$(adb -s "$S" shell dumpsys activity activities | grep -m1 topResumedActivity | sed -E 's/.* u0 ([^/]*)\/.*/\1/')
echo "foreground: $TOP" | tee "$OUT/summary.txt"
adb -s "$S" shell dumpsys gfxinfo "$TOP" framestats > "$OUT/framestats.txt"
adb -s "$S" shell top -b -n 1 2>/dev/null | head -25 > "$OUT/top.txt"
adb -s "$S" logcat -d -t 400 > "$OUT/logcat.txt"
python3 - "$OUT/framestats.txt" <<'PY' | tee -a "$OUT/summary.txt"
import sys
lines=open(sys.argv[1]).read().splitlines()
marks=[k for k,l in enumerate(lines) if l.startswith('---PROFILEDATA---')]
for a,b in zip(marks[0::2],marks[1::2]):
    win=next((lines[k] for k in range(a,-1,-1) if lines[k].startswith('Window:')),'?')
    hdr=lines[a+1].split(','); ix={h:k for k,h in enumerate(hdr)}
    ui=[];gpu=[];tot=[];gaps=[];prev=None
    for l in lines[a+2:b]:
        r=l.split(',')
        try:
            iv=int(r[ix['IntendedVsync']]); ds=int(r[ix['DrawStart']]); sq=int(r[ix['SyncQueued']]); ic=int(r[ix['IssueDrawCommandsStart']]); sw=int(r[ix['SwapBuffers']]); fc=int(r[ix['FrameCompleted']])
        except: continue
        ui.append((sq-ds)/1e6); gpu.append((sw-ic)/1e6); tot.append((fc-iv)/1e6)
        if prev: gaps.append((iv-prev)/1e6)
        prev=iv
    n=len(ui)
    if n: print(f"{win.split('/')[-1][:40]}: frames={n} onDraw avg={sum(ui)/n:.1f} max={max(ui):.1f} | gpu avg={sum(gpu)/n:.1f} max={max(gpu):.1f} | total avg={sum(tot)/n:.1f} max={max(tot):.1f} | frame gap avg={sum(gaps)/max(1,len(gaps)):.0f} max={max(gaps) if gaps else 0:.0f} ms")
PY
grep -E "GPaper|ScratchPad|NotebookActivity|Notesprout|FATAL|ANR" "$OUT/logcat.txt" | tail -25 | tee -a "$OUT/summary.txt"
echo "saved under $OUT"
