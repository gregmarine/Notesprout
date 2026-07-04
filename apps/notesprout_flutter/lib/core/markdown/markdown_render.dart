import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'markdown_parser.dart';

/// Renders a parsed [Block] list to a Canvas — the Flutter analogue of the native
/// `MarkdownRenderer` (SpannableStringBuilder + StaticLayout). Because Flutter's `TextPainter` has no
/// per-paragraph leading-margin / quote-stripe / replacement-span, blocks are laid out one at a time
/// down a running y-offset: text via `TextPainter`, and block chrome (list glyphs, blockquote bar,
/// horizontal rule) drawn directly. Inline styling (bold/italic/strikethrough/underlined links) rides
/// on the `TextSpan` tree.
///
/// Heading multipliers, list indents, bullet glyphs, quote-bar geometry, and HR sizing mirror the
/// native constants so on-page text objects look the same on both apps.
class MarkdownRender {
  MarkdownRender._();

  /// Lays out [blocks] at [widthPx] and, when [canvas] is non-null, paints them anchored at [origin].
  /// Returns the natural content size (max line width capped at [widthPx], total height) — the same
  /// value used to size a text object's bounding box. [basePx] is the body font size in px
  /// (typically `24 * dpr`); headings scale off it.
  static Size layout(
    Canvas? canvas,
    List<Block> blocks, {
    required double widthPx,
    required double basePx,
    required double dpr,
    double blockGapPx = 0,
    Offset origin = Offset.zero,
    Color color = const Color(0xFF000000),
  }) {
    final indentStep = 16 * dpr;
    var y = 0.0;
    var maxW = 0.0;

    for (var index = 0; index < blocks.length; index++) {
      if (blockGapPx > 0 && index > 0) y += blockGapPx;
      final block = blocks[index];

      // Horizontal rule — a drawn line one text-line tall.
      if (block is HorizontalRule) {
        final h = basePx;
        if (canvas != null) {
          final midY = origin.dy + y + h / 2;
          canvas.drawLine(
            Offset(origin.dx, midY),
            Offset(origin.dx + widthPx, midY),
            Paint()
              ..color = color
              ..strokeWidth = math.max(dpr, 1),
          );
        }
        y += h;
        maxW = math.max(maxW, widthPx);
        continue;
      }

      var indent = 0.0;
      var barWidth = 0.0;
      var prefix = '';
      var style = TextStyle(color: color, fontSize: basePx, height: 1.2);

      switch (block) {
        case Heading b:
          style = style.copyWith(
              fontSize: basePx * _headingMult(b.level), fontWeight: FontWeight.bold);
        case ListItemBlock b:
          indent = (b.depth + 1) * indentStep;
          prefix = b.isTask
              ? (b.checked ? '☑ ' : '☐ ') // ☑ / ☐
              : b.ordered
                  ? '${b.displayNumber}. '
                  : _bullet(b.depth);
        case Blockquote _:
          barWidth = math.max(3 * dpr, 2);
          indent = barWidth + 8 * dpr;
        case Paragraph _:
        case HorizontalRule _:
          break;
      }

      final inlines = switch (block) {
        Heading b => b.inlines,
        Paragraph b => b.inlines,
        ListItemBlock b => b.inlines,
        Blockquote b => b.inlines,
        HorizontalRule() => const <Inline>[],
      };

      final tp = TextPainter(
        text: TextSpan(style: style, children: [
          if (prefix.isNotEmpty) TextSpan(text: prefix, style: style),
          ..._spans(inlines, style),
        ]),
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: math.max(0, widthPx - indent));

      if (canvas != null) {
        tp.paint(canvas, origin + Offset(indent, y));
        if (barWidth > 0) {
          canvas.drawRect(
            Rect.fromLTWH(origin.dx, origin.dy + y, barWidth, tp.height),
            Paint()..color = color,
          );
        }
      }

      final lineWidth = tp.computeLineMetrics().fold<double>(0, (m, l) => math.max(m, l.width));
      maxW = math.max(maxW, indent + lineWidth);
      y += tp.height;
    }

    return Size(math.min(maxW, widthPx), y);
  }

  /// Natural (width, height) of [blocks] at [widthPx] without painting — for bbox sizing.
  static Size measure(List<Block> blocks,
          {required double widthPx, required double basePx, required double dpr}) =>
      layout(null, blocks, widthPx: widthPx, basePx: basePx, dpr: dpr);

  // ── Inline → span tree ─────────────────────────────────────────────────────
  static List<InlineSpan> _spans(List<Inline> inlines, TextStyle style) =>
      [for (final i in inlines) _span(i, style)];

  static InlineSpan _span(Inline i, TextStyle style) => switch (i) {
        InlineText t => TextSpan(text: t.text, style: style),
        Bold b => TextSpan(children: _spans(b.children, style.copyWith(fontWeight: FontWeight.bold))),
        Italic b =>
          TextSpan(children: _spans(b.children, style.copyWith(fontStyle: FontStyle.italic))),
        Strikethrough b =>
          TextSpan(children: _spans(b.children, _withDecoration(style, TextDecoration.lineThrough))),
        Link l => TextSpan(text: l.displayText, style: _withDecoration(style, TextDecoration.underline)),
      };

  static TextStyle _withDecoration(TextStyle s, TextDecoration d) {
    final existing = s.decoration;
    final combined = (existing == null || existing == TextDecoration.none)
        ? d
        : TextDecoration.combine([existing, d]);
    return s.copyWith(decoration: combined);
  }

  static double _headingMult(int level) => switch (level) {
        1 => 2.0,
        2 => 1.75,
        3 => 1.5,
        4 => 1.25,
        5 => 1.1,
        _ => 1.0, // h6 — body size, bold distinguishes it
      };

  static String _bullet(int depth) => switch (depth % 3) {
        0 => '• ', // •
        1 => '◦ ', // ◦
        _ => '▪ ', // ▪
      };
}
