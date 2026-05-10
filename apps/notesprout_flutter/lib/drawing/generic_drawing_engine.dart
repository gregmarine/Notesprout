import 'dart:ui' as ui;

import 'package:flutter/widgets.dart';

import 'drawing_engine.dart';

class GenericDrawingEngine implements DrawingEngine {
  GenericDrawingEngine({this.allowTouch = true});

  final bool allowTouch;
  final _key = GlobalKey<_GenericCanvasState>();

  @override
  Widget buildCanvas() => _GenericCanvas(key: _key, allowTouch: allowTouch);

  @override
  void clear() => _key.currentState?.clear();

  @override
  void dispose() {}
}

class _GenericCanvas extends StatefulWidget {
  const _GenericCanvas({super.key, required this.allowTouch});

  final bool allowTouch;

  @override
  State<_GenericCanvas> createState() => _GenericCanvasState();
}

class _GenericCanvasState extends State<_GenericCanvas> {
  final _committed = <List<Offset>>[];
  final _active = <Offset>[];
  final _committedNotifier = ValueNotifier<ui.Image?>(null);
  final _activeNotifier = ValueNotifier<List<Offset>>([]);
  Size _canvasSize = Size.zero;

  bool _accept(PointerEvent e) =>
      widget.allowTouch || e.kind != ui.PointerDeviceKind.touch;

  @override
  void dispose() {
    _committedNotifier.value?.dispose();
    _committedNotifier.dispose();
    _activeNotifier.dispose();
    super.dispose();
  }

  void _onPointerDown(PointerDownEvent e) {
    if (!_accept(e)) return;
    _active.clear();
    _active.add(e.localPosition);
    _activeNotifier.value = List.from(_active);
  }

  void _onPointerMove(PointerMoveEvent e) {
    if (!_accept(e)) return;
    _active.add(e.localPosition);
    _activeNotifier.value = List.from(_active);
  }

  void _onPointerUp(PointerUpEvent e) {
    if (!_accept(e)) return;
    if (_active.isNotEmpty) {
      _committed.add(List.from(_active));
      _active.clear();
      _activeNotifier.value = [];
      _renderCommitted();
    }
  }

  Future<void> _renderCommitted() async {
    if (_canvasSize.isEmpty) return;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    final paint = Paint()
      ..color = const Color(0xFF000000)
      ..strokeWidth = 2.5
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;

    for (final stroke in _committed) {
      if (stroke.length < 2) continue;
      final path = Path();
      path.moveTo(stroke[0].dx, stroke[0].dy);
      for (int i = 1; i < stroke.length; i++) {
        path.lineTo(stroke[i].dx, stroke[i].dy);
      }
      canvas.drawPath(path, paint);
    }

    final picture = recorder.endRecording();
    final image = await picture.toImage(
      _canvasSize.width.toInt(),
      _canvasSize.height.toInt(),
    );

    if (!mounted) {
      image.dispose();
      return;
    }
    final old = _committedNotifier.value;
    _committedNotifier.value = image;
    old?.dispose();
  }

  void clear() {
    _committed.clear();
    _active.clear();
    _activeNotifier.value = [];
    final old = _committedNotifier.value;
    _committedNotifier.value = null;
    old?.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (ctx, constraints) {
        _canvasSize = constraints.biggest;
        return Listener(
          onPointerDown: _onPointerDown,
          onPointerMove: _onPointerMove,
          onPointerUp: _onPointerUp,
          behavior: HitTestBehavior.opaque,
          child: CustomPaint(
            painter: _CommittedPainter(_committedNotifier),
            foregroundPainter: _ActivePainter(_activeNotifier),
            child: const SizedBox.expand(),
          ),
        );
      },
    );
  }
}

class _CommittedPainter extends CustomPainter {
  _CommittedPainter(ValueNotifier<ui.Image?> notifier)
      : _notifier = notifier,
        super(repaint: notifier);

  final ValueNotifier<ui.Image?> _notifier;

  @override
  void paint(Canvas canvas, Size size) {
    final image = _notifier.value;
    if (image == null) return;
    canvas.drawImage(image, Offset.zero, Paint());
  }

  @override
  bool shouldRepaint(_CommittedPainter old) => old._notifier != _notifier;
}

class _ActivePainter extends CustomPainter {
  _ActivePainter(ValueNotifier<List<Offset>> notifier)
      : _notifier = notifier,
        super(repaint: notifier);

  final ValueNotifier<List<Offset>> _notifier;

  static final _strokePaint = Paint()
    ..color = const Color(0xFF000000)
    ..strokeWidth = 2.5
    ..strokeCap = StrokeCap.round
    ..strokeJoin = StrokeJoin.round
    ..style = PaintingStyle.stroke;

  @override
  void paint(Canvas canvas, Size size) {
    final points = _notifier.value;
    if (points.length < 2) return;
    final path = Path();
    path.moveTo(points[0].dx, points[0].dy);
    for (int i = 1; i < points.length; i++) {
      path.lineTo(points[i].dx, points[i].dy);
    }
    canvas.drawPath(path, _strokePaint);
  }

  @override
  bool shouldRepaint(_ActivePainter old) => old._notifier != _notifier;
}
