# Proofread dictionary tooling

`patch_dictionary.py` builds the app's bundled spell-check dictionary
(`apps/notesprout_android/app/src/main/assets/proofread/en_82765.dict`) by repairing the
upstream SymSpell English frequency list with VarCon, the SCOWL project's US/UK/CA/AU
spelling-variant table.

## Why

The upstream list (SymSpell `frequency_dictionary_en_82_765.txt`) merged regional spelling
pairs inconsistently: `color`/`colour` are both present at the same frequency, but a long
tail of standard American forms is missing outright — `favorite`, `theater`, `neighbor`,
`analyze`, `labor`, `jewelry`, `airplane`, even `mom` — while the British form is present
(and a smaller tail the other way). A missing form is flagged as a misspelling with the
other region's spelling as the top suggestion, which is how "favorite → favourite" showed
up on a Manta. The patch completes the merge symmetrically: **both spellings of every
standard pair are accepted**, neither is ever flagged.

## How it works

For every VarCon cluster of equivalent spellings at SCOWL level ≤ 50, any standard form
(bare `A`/`B`/`Z`/`C`/`D` tag — variant/archaic/improper suffixes excluded) missing from
the dictionary is added at the maximum frequency present in its cluster — the upstream
identical-frequency convention. The level gate matters: obscure clusters (level 55–95)
can collide with common words and inherit huge frequencies (`que / quae` would add "quae"
at "que"'s frequency), polluting suggestion ranking. Output is gzipped with `mtime=0`, so
identical inputs reproduce the asset byte-for-byte.

## Regenerating

```sh
curl -sLO https://raw.githubusercontent.com/en-wl/wordlist/master/varcon/varcon.txt
python3 patch_dictionary.py \
  --dict ../../apps/notesprout_android/app/src/main/assets/proofread/en_82765.dict \
  --varcon varcon.txt \
  --out ../../apps/notesprout_android/app/src/main/assets/proofread/en_82765.dict
```

`--dict` also accepts the plain upstream `.txt` to rebuild from scratch. The script is
idempotent — re-running it over an already-patched dictionary adds nothing.

Shipped asset built 2026-08-13 from varcon.txt
sha256 `75af63da46ec12d7eb14b9f1ba8d3898d484dd6872755b73c921b215875a3629`
(82,834 → 83,627 terms, 793 variants added).

Licenses: VarCon is permissively licensed (http://wordlist.aspell.net/varcon-readme/);
attribution lives in `NOTICE.txt` beside the asset. The asset ships gzip content under an
opaque `.dict` extension on purpose — **never name an asset `.gz`** (AAPT decompresses it
and strips the extension).
