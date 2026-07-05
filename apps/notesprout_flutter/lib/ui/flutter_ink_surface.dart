import 'package:flutter/material.dart';

/// A pure-Flutter drawing surface for platforms without the Onyx EPD overlay (desktop, and later
/// iPad/web). It captures pointer input, shows the in-progress stroke live, and on pointer-up hands
/// the completed stroke up as interleaved physical-px points `[x0,y0,x1,y1,…]` — the same wire shape
/// the native Onyx bridge emits, so the host screen's commit logic is identical on every platform.
///
/// It draws only the *in-flight* stroke; committed content is rendered behind it by `PagePainter`.
class FlutterInkSurface extends StatefulWidget {
  const FlutterInkSurface({
    super.key,
    required this.dpr,
    required this.onStroke,
    this.strokeWidthPx = 3.0,
  });

  /// Device pixel ratio — points are reported in physical px (`logical * dpr`) to match the model.
  final double dpr;

  /// Called on pointer-up with the finished stroke as interleaved physical-px coordinates and the
  /// pointer-contact duration in ms (feeds the smart-lasso velocity gate).
  final void Function(List<double> pointsPx, int durationMs) onStroke;

  /// Width of the live preview stroke, in physical px.
  final double strokeWidthPx;

  @override
  State<FlutterInkSurface> createState() => _FlutterInkSurfaceState();
}

class _FlutterInkSurfaceState extends State<FlutterInkSurface> {
  final List<Offset> _current = []; // logical-space points of the in-flight stroke
  bool _drawing = false;
  int _downMs = 0;

  void _down(PointerDownEvent e) {
    _drawing = true;
    _downMs = DateTime.now().millisecondsSinceEpoch;
    setState(() => _current
      ..clear()
      ..add(e.localPosition));
  }

  void _move(PointerMoveEvent e) {
    if (!_drawing) return;
    setState(() => _current.add(e.localPosition));
  }

  void _up(PointerUpEvent e) {
    if (!_drawing) return;
    _drawing = false;
    if (_current.length >= 2 || _current.length == 1) {
      final pts = <double>[];
      for (final p in _current) {
        pts..add(p.dx * widget.dpr)..add(p.dy * widget.dpr);
      }
      widget.onStroke(pts, DateTime.now().millisecondsSinceEpoch - _downMs);
    }
    setState(() => _current.clear());
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: _down,
      onPointerMove: _move,
      onPointerUp: _up,
      onPointerCancel: (_) => setState(() {
        _drawing = false;
        _current.clear();
      }),
      child: CustomPaint(
        size: Size.infinite,
        painter: _LivePainter(_current, widget.strokeWidthPx / widget.dpr),
      ),
    );
  }
}

class _LivePainter extends CustomPainter {
  _LivePainter(this.points, this.widthLogical);
  final List<Offset> points; // logical space
  final double widthLogical;

  @override
  void paint(Canvas canvas, Size size) {
    if (points.isEmpty) return;
    final paint = Paint()
      ..color = Colors.black
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..strokeWidth = widthLogical
      ..isAntiAlias = true;
    if (points.length == 1) {
      canvas.drawCircle(points.first, widthLogical / 2, paint..style = PaintingStyle.fill);
      return;
    }
    final path = Path()..moveTo(points.first.dx, points.first.dy);
    for (var i = 1; i < points.length; i++) {
      path.lineTo(points[i].dx, points[i].dy);
    }
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(_LivePainter old) => true;
}
