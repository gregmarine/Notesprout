#!/usr/bin/env python3
"""Patch the bundled SymSpell English frequency dictionary with missing spelling variants.

The upstream list (SymSpell frequency_dictionary_en_82_765.txt) merged US/UK spelling
pairs inconsistently: many pairs carry both forms at the same frequency (color/colour,
center/centre), but a long tail of standard American forms is simply absent (favorite,
theater, neighbor, analyze, ...) while the British form is present — and vice versa in
places. A missing form gets flagged as a misspelling, with the other region's spelling
as the top suggestion.

This script completes the merge symmetrically using VarCon (the SCOWL project's
US/UK/CA/AU variant table): for every VarCon cluster of equivalent spellings, if any
standard form is already in the dictionary, the missing standard forms are added at the
maximum frequency present in the cluster — the same identical-frequency convention the
upstream list already uses for the pairs it did merge.

Usage:
    python3 patch_dictionary.py --dict en_82765.dict --varcon varcon.txt --out en_82765.dict

--dict accepts the gzipped asset (.dict) or the plain upstream .txt; --out is always
written gzipped (the asset ships gzip-compressed under an opaque extension — never .gz,
AAPT would decompress it). Output is `term frequency` lines, frequency-descending.
"""

import argparse
import gzip
import re
import sys

# A word is eligible only under a bare standard tag: A American, B British (-ise),
# Z British (-ize / Oxford), C Canadian, D Australian. Suffixed tags (Av, BV, A-, Ax)
# mark variant / seldom-used / archaic / improper spellings — never added.
BARE_STANDARD = {"A", "B", "Z", "C", "D"}
WORD_RE = re.compile(r"^[a-z][a-z']*$")

# VarCon headers carry the SCOWL obscurity level (10 = core English ... 95 = barely a word).
# Obscure clusters are dangerous, not just useless: their spellings can collide with common
# dictionary words and inherit huge frequencies (level-95 "que / quae" would add "quae" at
# que's frequency; "eric / aeric" likewise). 50 is SCOWL's own standard-dictionary boundary.
MAX_LEVEL = 50
LEVEL_RE = re.compile(r"\(level (\d+)\)")


def read_dictionary(path):
    opener = gzip.open if is_gzip(path) else open
    freqs = {}
    with opener(path, "rt", encoding="utf-8") as f:
        for raw in f:
            line = raw.lstrip("﻿").strip()
            cut = line.find(" ")
            if cut <= 0:
                continue
            try:
                freqs[line[:cut]] = int(line[cut + 1:].strip())
            except ValueError:
                continue
    return freqs


def is_gzip(path):
    with open(path, "rb") as f:
        return f.read(2) == b"\x1f\x8b"


def varcon_clusters(path):
    """Yield sets of equivalent standard spellings, one per VarCon data line."""
    # VarCon ships Latin-1 (accented notes in comments); non-ASCII words fail WORD_RE anyway
    level = None
    with open(path, "rt", encoding="latin-1") as f:
        for raw in f:
            line = raw.strip()
            if line.startswith("#"):
                m = LEVEL_RE.search(line)
                if m:
                    level = int(m.group(1))
                continue
            if not line:
                continue
            if level is None or level > MAX_LEVEL:
                continue
            line = line.split("#")[0].split("|")[0].strip()  # drop comments, sense markers
            cluster = set()
            for part in line.split("/"):
                if ":" not in part:
                    continue
                tags, _, word = part.rpartition(":")
                word = word.strip()
                if not WORD_RE.match(word):
                    continue
                if any(t in BARE_STANDARD for t in tags.split()):
                    cluster.add(word)
            if len(cluster) > 1:
                yield cluster


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dict", required=True, help="existing dictionary (.dict gz or plain .txt)")
    ap.add_argument("--varcon", required=True, help="VarCon varcon.txt")
    ap.add_argument("--out", required=True, help="output path (written gzipped)")
    args = ap.parse_args()

    freqs = read_dictionary(args.dict)
    print(f"dictionary: {len(freqs)} terms")

    added = {}
    for cluster in varcon_clusters(args.varcon):
        present = [w for w in cluster if w in freqs]
        if not present:
            continue
        top = max(freqs[w] for w in present)
        for w in cluster:
            if w not in freqs and added.get(w, 0) < top:
                added[w] = top

    freqs.update(added)
    print(f"added: {len(added)} variants")
    for w in sorted(added)[:20]:
        print(f"  + {w} {added[w]}")
    if len(added) > 20:
        print(f"  ... and {len(added) - 20} more")

    ordered = sorted(freqs.items(), key=lambda kv: (-kv[1], kv[0]))
    # mtime=0 keeps the output byte-reproducible run to run
    with gzip.GzipFile(args.out, "wb", mtime=0) as f:
        f.write("".join(f"{w} {n}\n" for w, n in ordered).encode("utf-8"))
    print(f"wrote {args.out}: {len(ordered)} terms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
