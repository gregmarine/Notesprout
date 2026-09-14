# Bible DB build tool

`build_bible_db.py` builds the `bsb.bible` SQLite source (Berean Standard
Bible) from the official BSB USFM. It is a copy of Biblesprout's
`data/tools/build_bible_db.py`, adapted with argparse-driven paths and a
`--slim` mode that drops the word (interlinear) layer, which Notesprout's
Bible extension does not use, and builds the full-text index as **FTS4**
(`verse_fts`, `content='verse'`, `tokenize=unicode61`) instead of FTS5 — the
extension opens the file through the platform `android.database.sqlite`,
which carries FTS3/4 everywhere and cannot be assumed to carry FTS5
(B8, 2026-09-13).

**Sources** (both public domain, published by Berean Bible):
- USFM: https://bereanbible.com/bsb_usfm.zip
- Translation tables (word layer only, not used by the slim build):
  https://bereanbible.com/bsb_tables.tsv

Notesprout ships the **slim** build only — `metadata`, `book`, `verse`,
`block` (+ index), `verse_marker`, `redletter`, `footnote`, `xref` (+
indexes), `verse_fts` (FTS4). No `word` / `form` / `morphology` tables.

## Command used

```
python3 tools/bible/build_bible_db.py --slim \
  --out /Users/gregmarine/git/Notesprout/extensions/bible/src/main/assets/bible/bsb.bible
```

(USFM read from the default `~/git/Biblesprout/data/bible/bsb_usfm`; no
`--tables` needed since `--slim` skips the word layer.)

## Result

`extensions/bible/src/main/assets/bible/bsb.bible` — 15,257,600 bytes
(~14.6 MB; 11.6 MB before the FTS4 index). 31,086 verses, 46,360 blocks,
4,854 footnotes, 3,278 cross-references. `PRAGMA integrity_check` returns
`ok`; `metadata.layers` = `display,fts4`.
