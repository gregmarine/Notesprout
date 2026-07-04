import 'package:flutter/material.dart';

import '../domain/stroke.dart';

/// Renders committed strokes (the canonical `.soil` model) on every platform.
///
/// Stroke points are stored in native/physical px. We scale the canvas by `1/dpr` and draw in px
/// directly, so both geometry and `strokeWidth` land at true physical pixels — matching what the
/// native Onyx overlay drew during the stroke, so the pen-lift handoff is seamless.
class InkPainter extends CustomPainter {
  InkPainter(this.strokes, this.dpr);

  final List<StrokeData> strokes;
  final double dpr;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.scale(1 / dpr);
    for (final stroke in strokes) {
      final pts = stroke.points;
      if (pts.isEmpty) continue;
      final paint = Paint()
        ..color = Colors.black
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round
        ..strokeWidth = stroke.strokeWidth
        ..isAntiAlias = true;
      if (pts.length == 1) {
        canvas.drawCircle(Offset(pts.first.x, pts.first.y), stroke.strokeWidth / 2,
            paint..style = PaintingStyle.fill);
        continue;
      }
      final path = Path()..moveTo(pts.first.x, pts.first.y);
      for (var i = 1; i < pts.length; i++) {
        path.lineTo(pts[i].x, pts[i].y);
      }
      canvas.drawPath(path, paint);
    }
    canvas.restore();
  }

  // Model is mutated in place and rebuilt only on real changes (setState) — always repaint.
  @override
  bool shouldRepaint(InkPainter old) => true;
}
