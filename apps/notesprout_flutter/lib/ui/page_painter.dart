import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../core/markdown/markdown_parser.dart';
import '../core/markdown/markdown_render.dart';
import '../domain/objects.dart';
import '../domain/page_object.dart';
import '../domain/stroke.dart';

/// Renders a page's committed content objects (the canonical `.soil` model) on every platform.
///
/// Geometry is stored in native/physical px, so we scale the canvas by `1/dpr` and draw in px
/// directly — both positions and widths land at true physical pixels, matching what the native
/// Onyx overlay drew, so the pen-lift handoff stays seamless.
///
/// Z-order matches the native compositor regardless of insertion order: line guides underneath,
/// then headings, then strokes on top. (Text-object bodies land in Phase 2B with the markdown
/// engine; their embedded stroke fallback still draws here.)
class PagePainter extends CustomPainter {
  PagePainter(this.objects, this.dpr, {this.selection = const []});

  final List<PageObject> objects;
  final double dpr;

  /// Bounding boxes (px) to outline as the current lasso selection.
  final List<BoundingBox> selection;

  static const _inkLight = Color(0xFF888888);

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.scale(1 / dpr);

    for (final o in objects) {
      if (o is LineRender) _drawLine(canvas, o);
    }
    for (final o in objects) {
      if (o is HeadingRender) _drawHeading(canvas, o);
    }
    for (final o in objects) {
      if (o is TextRender) _drawText(canvas, o);
    }
    for (final o in objects) {
      if (o is StrokeObject) _drawStroke(canvas, o.data);
    }

    for (final b in selection) {
      _drawSelection(canvas, b);
    }

    canvas.restore();
  }

  void _drawSelection(Canvas canvas, BoundingBox b) {
    final pad = 8 * dpr;
    final rect = Rect.fromLTWH(b.x - pad, b.y - pad, b.width + 2 * pad, b.height + 2 * pad);
    final paint = Paint()
      ..color = Colors.black
      ..style = PaintingStyle.stroke
      ..strokeWidth = math.max(dpr, 1);
    // Dashed rectangle outline.
    for (final side in [
      [rect.topLeft, rect.topRight],
      [rect.topRight, rect.bottomRight],
      [rect.bottomRight, rect.bottomLeft],
      [rect.bottomLeft, rect.topLeft],
    ]) {
      _dash(canvas, side[0], side[1], paint, 10 * dpr, 6 * dpr);
    }
  }

  void _dash(Canvas canvas, Offset a, Offset b, Paint paint, double dash, double gap) {
    final total = (b - a).distance;
    if (total == 0) return;
    final dir = (b - a) / total;
    var d = 0.0;
    while (d < total) {
      final s = a + dir * d;
      final e = a + dir * math.min(d + dash, total);
      canvas.drawLine(s, e, paint);
      d += dash + gap;
    }
  }

  // ── Strokes ────────────────────────────────────────────────────────────────
  void _drawStroke(Canvas canvas, StrokeData s) {
    final pts = s.points;
    if (pts.isEmpty) return;
    final paint = Paint()
      ..color = Colors.black
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..strokeWidth = s.strokeWidth
      ..isAntiAlias = true;
    if (pts.length == 1) {
      canvas.drawCircle(Offset(pts.first.x, pts.first.y), s.strokeWidth / 2,
          paint..style = PaintingStyle.fill);
      return;
    }
    final path = Path()..moveTo(pts.first.x, pts.first.y);
    for (var i = 1; i < pts.length; i++) {
      path.lineTo(pts[i].x, pts[i].y);
    }
    canvas.drawPath(path, paint);
  }

  // ── Headings ─────────────────────────────────────────────────────────────
  void _drawHeading(Canvas canvas, HeadingRender h) {
    final rt = h.data.recognizedText;
    if (rt != null) {
      // Recognized → scaled bold canvas text anchored at the box top-left. (Full markdown styling
      // for multi-block text arrives in 2B; a heading is a single line, so this is faithful.)
      final scale = switch (h.data.level) { 2 => 1.75, 3 => 1.5, _ => 2.0 };
      final tp = TextPainter(
        text: TextSpan(
          text: HeadingObject.stripHeadingPrefix(rt),
          style: TextStyle(
            color: Colors.black,
            fontSize: 24 * scale * dpr,
            fontWeight: FontWeight.bold,
            height: 1.0,
          ),
        ),
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: h.box.width > 0 ? h.box.width : double.infinity);
      tp.paint(canvas, Offset(h.box.x, h.box.y));
    } else {
      for (final s in h.data.strokes) {
        _drawStroke(canvas, s.toStrokeData());
      }
    }
  }

  // ── Text objects (markdown body, or embedded-stroke fallback) ─────────────
  void _drawText(Canvas canvas, TextRender t) {
    final txt = t.data.text;
    if (txt.isNotEmpty) {
      // Markdown source → parsed + rendered at the object's top-left, wrapping at its box width.
      MarkdownRender.layout(
        canvas,
        MarkdownParser.parse(txt),
        widthPx: t.box.width > 0 ? t.box.width : double.infinity,
        basePx: 24 * dpr, // native text objects render at 24sp
        dpr: dpr,
        origin: Offset(t.box.x, t.box.y),
      );
    } else {
      // Unrecognized (blank text + embedded strokes) — the strokes are the visual.
      for (final s in (t.data.strokes ?? const <LiveStroke>[])) {
        _drawStroke(canvas, s.toStrokeData());
      }
    }
  }

  // ── Lines (page guides) ───────────────────────────────────────────────────
  void _drawLine(Canvas canvas, LineRender l) {
    final o = l.data;
    final sw = o.strokeWidthDp * dpr;
    final b = l.box;
    final double x1, y1, x2, y2;
    if (o.orientation == LineOrientation.horizontal) {
      final y = b.y + b.height / 2;
      x1 = b.x;
      x2 = b.x + b.width;
      y1 = y;
      y2 = y;
    } else {
      final x = b.x + b.width / 2;
      x1 = x;
      x2 = x;
      y1 = b.y;
      y2 = b.y + b.height;
    }
    final paint = Paint()
      ..color = _inkLight
      ..style = PaintingStyle.stroke
      ..strokeWidth = sw
      ..strokeCap = StrokeCap.round
      ..isAntiAlias = true;

    switch (o.style) {
      case LineStyle.solid:
        canvas.drawLine(Offset(x1, y1), Offset(x2, y2), paint);
      case LineStyle.dashed:
        _drawDashed(canvas, Offset(x1, y1), Offset(x2, y2), paint, 12 * dpr, 8 * dpr);
      case LineStyle.dotted:
        final spacing = o.dotSpacingDp > 0 ? o.dotSpacingDp * dpr : sw * 4;
        _drawDotted(canvas, Offset(x1, y1), Offset(x2, y2), sw / 2, spacing);
    }
  }

  void _drawDashed(Canvas canvas, Offset a, Offset b, Paint paint, double dash, double gap) {
    final total = (b - a).distance;
    if (total == 0) return;
    final dir = (b - a) / total;
    var d = 0.0;
    while (d < total) {
      final start = a + dir * d;
      final end = a + dir * (d + dash).clamp(0, total).toDouble();
      canvas.drawLine(start, end, paint);
      d += dash + gap;
    }
  }

  void _drawDotted(Canvas canvas, Offset a, Offset b, double radius, double spacing) {
    final total = (b - a).distance;
    if (total == 0 || spacing <= 0) return;
    final dir = (b - a) / total;
    final paint = Paint()
      ..color = _inkLight
      ..style = PaintingStyle.fill
      ..isAntiAlias = true;
    for (var d = 0.0; d <= total; d += spacing) {
      canvas.drawCircle(a + dir * d, radius, paint);
    }
  }

  @override
  bool shouldRepaint(PagePainter old) => true;
}
