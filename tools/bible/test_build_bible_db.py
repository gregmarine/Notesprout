"""Cross-reference target resolution — the arc-41 fixes (2026-09-14).

Run: python3 -m unittest tools/bible/test_build_bible_db.py
"""
import importlib.util
import os
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "build_bible_db", os.path.join(_HERE, "build_bible_db.py"))
bb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bb)

PE1 = bb.ORDINAL["1PE"]


def resolve(target, disp, cur="1PE"):
    b = bb.Builder(slim=True)
    return b._resolve_target(target, disp, bb.ORDINAL[cur]), b.warnings


class ResolveTargetTest(unittest.TestCase):

    def test_coded_target_is_unchanged(self):
        rng, warnings = resolve("EPH 5:22-33", "Ephesians 5:22–33")
        self.assertEqual((bb.encode(49, 5, 22), bb.encode(49, 5, 33)), rng)
        self.assertEqual([], warnings)

    def test_cross_chapter_range(self):
        rng, _ = resolve("1CH 15:29-16:3", "1 Chronicles 15:29–16:3")
        self.assertEqual((bb.encode(13, 15, 29), bb.encode(13, 16, 3)), rng)

    def test_codeless_target_resolves_from_the_display_book(self):
        # 1 Peter 3's line: the USFM omits the code; the display names the book.
        rng, warnings = resolve("1:1-17", "Song of Solomon 1:1–17")
        self.assertEqual((bb.encode(22, 1, 1), bb.encode(22, 1, 17)), rng)
        self.assertEqual([], warnings)

    def test_codeless_target_never_falls_back_to_the_source_book(self):
        rng, _ = resolve("1:1-17", "Song of Solomon 1:1–17", cur="EPH")
        self.assertEqual(22, rng[0] // bb.BOOK_FACTOR)

    def test_non_canon_display_is_not_a_link(self):
        for disp, target in (("Jasher 79:27", "79:27"), ("1 Enoch 1:9", "1:9"),
                             ("1 Esdras 8:32", "8:32")):
            rng, warnings = resolve(target, disp, cur="JUD")
            self.assertIsNone(rng, disp)
            self.assertTrue(warnings and warnings[0].startswith("xref: non-canon target"), disp)

    def test_one_chapter_book_bare_numbers_are_verses(self):
        rng, _ = resolve("JUD 17-23", "Jude 1:17–23")
        self.assertEqual((bb.encode(65, 1, 17), bb.encode(65, 1, 23)), rng)
        rng, _ = resolve("JUD 18", "Jude 1:18")
        self.assertEqual((bb.encode(65, 1, 18), bb.encode(65, 1, 18)), rng)
        rng, _ = resolve("OBA 1-14", "Obadiah 1:1–14")
        self.assertEqual((bb.encode(31, 1, 1), bb.encode(31, 1, 14)), rng)

    def test_one_chapter_book_without_a_colon_is_the_whole_chapter(self):
        rng, _ = resolve("JUD 1", "Jude 1")
        self.assertEqual((bb.encode(65, 1, 0), bb.encode(65, 1, bb.MAX_VERSE)), rng)

    def test_many_chapter_book_bare_numbers_stay_chapters(self):
        rng, _ = resolve("PSA 38", "Psalm 38")
        self.assertEqual((bb.encode(19, 38, 0), bb.encode(19, 38, bb.MAX_VERSE)), rng)

    def test_unknown_code_warns(self):
        rng, warnings = resolve("XYZ 1:1", "Xyz 1:1")
        self.assertIsNone(rng)
        self.assertEqual(["xref: unknown book code 'XYZ'"], warnings)


class DisplayBookTest(unittest.TestCase):

    def test_names_and_aliases(self):
        self.assertEqual("SNG", bb.display_book("Song of Solomon 1:1–17"))
        self.assertEqual("SNG", bb.display_book("Song of Songs 2:1"))
        self.assertEqual("PSA", bb.display_book("Psalm 23:1"))
        self.assertEqual("PSA", bb.display_book("Psalms 23:1"))
        self.assertEqual("1JN", bb.display_book("1 John 4:8"))
        self.assertEqual("JHN", bb.display_book("John 3:16"))
        self.assertEqual("JUD", bb.display_book("Jude 1:9"))
        self.assertEqual("JDG", bb.display_book("Judges 5:1"))

    def test_non_canon_and_prefixes(self):
        self.assertIsNone(bb.display_book("Jasher 79:27"))
        self.assertIsNone(bb.display_book("1 Enoch 13:1–11"))
        self.assertIsNone(bb.display_book("Johnson 1:1"))


if __name__ == "__main__":
    unittest.main()
