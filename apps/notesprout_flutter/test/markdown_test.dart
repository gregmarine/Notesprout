import 'dart:ui' as ui;

import 'package:flutter_test/flutter_test.dart';
import 'package:notesprout_flutter/core/markdown/markdown_parser.dart';
import 'package:notesprout_flutter/core/markdown/markdown_render.dart';

/// Faithful-port checks against the native MarkdownParser behaviour, plus a render smoke test.
void main() {
  group('block parsing', () {
    test('headings by hash count', () {
      final b = MarkdownParser.parse('### Deep');
      expect(b.single, isA<Heading>());
      expect((b.single as Heading).level, 3);
    });

    test('horizontal rule variants; too-short is not a rule', () {
      expect(MarkdownParser.parse('---').single, isA<HorizontalRule>());
      expect(MarkdownParser.parse('***').single, isA<HorizontalRule>());
      expect(MarkdownParser.parse('___').single, isA<HorizontalRule>());
      expect(MarkdownParser.parse('--').single, isA<Paragraph>());
    });

    test('ordered list auto-renumbers regardless of source numbers', () {
      final b = MarkdownParser.parse('1. a\n1. b\n5. c').cast<ListItemBlock>();
      expect(b.map((e) => e.displayNumber), [1, 2, 3]);
      expect(b.every((e) => e.ordered), isTrue);
    });

    test('blank line resets ordered counter', () {
      final b = MarkdownParser.parse('1. a\n\n1. b').whereType<ListItemBlock>().toList();
      expect(b.map((e) => e.displayNumber), [1, 1]);
    });

    test('task items detected before unordered; checked state', () {
      final b = MarkdownParser.parse('- [x] done\n- [ ] todo\n- plain').cast<ListItemBlock>();
      expect(b[0].isTask && b[0].checked, isTrue);
      expect(b[1].isTask && !b[1].checked, isTrue);
      expect(b[2].isTask, isFalse);
    });

    test('nesting depth from 2-space indent', () {
      final b = MarkdownParser.parse('- a\n  - b\n    - c').cast<ListItemBlock>();
      expect(b.map((e) => e.depth), [0, 1, 2]);
    });

    test('blockquote joins consecutive lines', () {
      final b = MarkdownParser.parse('> one\n> two');
      expect(b.single, isA<Blockquote>());
    });

    test('paragraph collects wrapped lines until a block starts', () {
      final b = MarkdownParser.parse('hello\nworld\n# Stop');
      expect(b.length, 2);
      expect(b[0], isA<Paragraph>());
      expect(b[1], isA<Heading>());
    });
  });

  group('inline parsing', () {
    test('bold / italic / strike / link', () {
      final i = MarkdownParser.parseInlines('a **b** _c_ ~~d~~ [t](http://x)');
      expect(i.whereType<Bold>().length, 1);
      expect(i.whereType<Italic>().length, 1);
      expect(i.whereType<Strikethrough>().length, 1);
      final link = i.whereType<Link>().single;
      expect(link.displayText, 't');
      expect(link.url, 'http://x');
    });

    test('unterminated marker is literal text', () {
      final i = MarkdownParser.parseInlines('a **b');
      expect(i.length, 1);
      expect((i.single as InlineText).text, 'a **b');
    });

    test('nested bold inside italic', () {
      final i = MarkdownParser.parseInlines('_a **b** c_');
      final italic = i.single as Italic;
      expect(italic.children.whereType<Bold>().length, 1);
    });
  });

  group('render', () {
    test('mixed markdown produces ink and non-zero height', () async {
      const md = '# Title\n\nSome **bold** and _italic_ text.\n\n- one\n- two\n\n> quote\n\n---';
      final blocks = MarkdownParser.parse(md);

      final size = MarkdownRender.measure(blocks, widthPx: 600, basePx: 24, dpr: 1);
      expect(size.height, greaterThan(0));
      expect(size.width, greaterThan(0));

      final recorder = ui.PictureRecorder();
      final canvas = ui.Canvas(recorder);
      canvas.drawRect(const ui.Rect.fromLTWH(0, 0, 600, 600),
          ui.Paint()..color = const ui.Color(0xFFFFFFFF));
      MarkdownRender.layout(canvas, blocks, widthPx: 600, basePx: 24, dpr: 1);
      final img = await recorder.endRecording().toImage(600, 600);
      final bytes = (await img.toByteData(format: ui.ImageByteFormat.rawRgba))!;
      var dark = 0;
      for (var i = 0; i < bytes.lengthInBytes; i += 4) {
        if (bytes.getUint8(i) < 128) dark++;
      }
      expect(dark, greaterThan(0), reason: 'markdown produced no ink');
    });
  });
}
