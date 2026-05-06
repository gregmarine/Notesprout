import 'dart:ui' as ui;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:notesprout_core/notesprout_core.dart';
import 'package:path/path.dart' as p;
import 'package:uuid/uuid.dart';

const _soilFile = 'notebook.soil';
const _uuid = Uuid();

class CanvasScreen extends StatefulWidget {
  final String folderPath;
  final String pageId;

  const CanvasScreen({
    super.key,
    required this.folderPath,
    required this.pageId,
  });

  @override
  State<CanvasScreen> createState() => _CanvasScreenState();
}

class _CanvasScreenState extends State<CanvasScreen> {
  SoilDatabase? _db;
  String? _layerId;
  List<StrokeModel> _strokes = [];
  List<StrokePoint> _activePoints = [];
  bool _ready = false;
  ui.Image? _committedImage;
  double _pageWidth = 0;
  double _pageHeight = 0;

  @override
  void initState() {
    super.initState();
    _initCanvas();
  }

  @override
  void dispose() {
    _committedImage?.dispose();
    _db?.close();
    super.dispose();
  }

  Future<void> _initCanvas() async {
    final db = SoilDatabase(p.join(widget.folderPath, _soilFile));
    final meta = await db.getNotebookMeta();
    final layers = await db.getLayers(widget.pageId);
    if (layers.isEmpty) {
      await db.close();
      return;
    }
    final layerId = layers.first.id;
    final strokes = await db.getStrokes(layerId);

    _db = db;
    _pageWidth = meta.pageWidth;
    _pageHeight = meta.pageHeight;

    if (mounted) {
      setState(() {
        _layerId = layerId;
        _strokes = strokes;
        _ready = true;
      });
      await _rebuildCommittedImage();
    }
  }

  Future<void> _rebuildCommittedImage() async {
    if (_pageWidth <= 0 || _pageHeight <= 0) return;

    final recorder = ui.PictureRecorder();
    final canvas = ui.Canvas(recorder);

    for (final stroke in _strokes) {
      _drawPoints(canvas, stroke.points, stroke.color, stroke.width);
    }

    final picture = recorder.endRecording();
    final image = await picture.toImage(_pageWidth.ceil(), _pageHeight.ceil());
    picture.dispose();

    if (mounted) {
      setState(() {
        _committedImage?.dispose();
        _committedImage = image;
      });
    }
  }

  void _drawPoints(
    ui.Canvas canvas,
    List<StrokePoint> points,
    int color,
    double width,
  ) {
    if (points.isEmpty) return;

    final paint = Paint()
      ..color = Color(color)
      ..strokeWidth = width
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;

    final path = Path()..moveTo(points.first.x, points.first.y);
    for (var i = 1; i < points.length; i++) {
      path.lineTo(points[i].x, points[i].y);
    }
    canvas.drawPath(path, paint);
  }

  // ---------------------------------------------------------------------------
  // Pointer handlers (stylus only)
  // ---------------------------------------------------------------------------

  void _onPointerDown(PointerDownEvent event) {
    if (!_ready || event.kind != PointerDeviceKind.stylus) return;
    _activePoints = [
      StrokePoint(
        x: event.localPosition.dx,
        y: event.localPosition.dy,
        pressure: 1.0,
        tilt: 0.0,
        timestamp: DateTime.now().millisecondsSinceEpoch,
      ),
    ];
  }

  void _onPointerMove(PointerMoveEvent event) {
    if (!_ready || event.kind != PointerDeviceKind.stylus) return;
    if (_activePoints.isEmpty) return;
    setState(() {
      _activePoints = [
        ..._activePoints,
        StrokePoint(
          x: event.localPosition.dx,
          y: event.localPosition.dy,
          pressure: 1.0,
          tilt: 0.0,
          timestamp: DateTime.now().millisecondsSinceEpoch,
        ),
      ];
    });
  }

  Future<void> _onPointerUp(PointerUpEvent event) async {
    if (!_ready || event.kind != PointerDeviceKind.stylus) return;
    if (_activePoints.isEmpty || _layerId == null) return;

    final now = DateTime.now();
    final stroke = StrokeModel(
      id: _uuid.v4(),
      parentId: _layerId!,
      createdAt: now,
      updatedAt: now,
      points: List.unmodifiable(_activePoints),
      color: 0xFF000000,
      width: 3.0,
    );

    setState(() {
      _strokes = [..._strokes, stroke];
      _activePoints = [];
    });

    await _db?.addStroke(stroke);
    await _rebuildCommittedImage();
  }

  Future<void> _close() async {
    await _db?.close();
    _db = null;
    if (mounted) Navigator.of(context).pop();
  }

  // ---------------------------------------------------------------------------
  // Build
  // ---------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: Stack(
        children: [
          // Full-screen drawing surface — stylus input only.
          Listener(
            onPointerDown: _onPointerDown,
            onPointerMove: _onPointerMove,
            onPointerUp: _onPointerUp,
            child: Stack(
              children: [
                // Bottom layer: committed strokes rendered from cached image.
                RepaintBoundary(
                  child: CustomPaint(
                    painter: _CommittedStrokesPainter(image: _committedImage),
                    child: const SizedBox.expand(),
                  ),
                ),
                // Top layer: active (in-progress) stroke only.
                RepaintBoundary(
                  child: CustomPaint(
                    painter: _ActiveStrokePainter(points: _activePoints),
                    child: const SizedBox.expand(),
                  ),
                ),
              ],
            ),
          ),

          // Floating toolbar — top left, respects safe area.
          // SafeArea must be the direct Stack child; Positioned cannot be
          // wrapped before reaching the Stack.
          SafeArea(
            child: Align(
              alignment: Alignment.topLeft,
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: _Toolbar(onClose: _close),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Toolbar
// ---------------------------------------------------------------------------

class _Toolbar extends StatelessWidget {
  final VoidCallback onClose;

  const _Toolbar({required this.onClose});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.white,
      elevation: 2,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: const Icon(Icons.close, size: 20),
              tooltip: 'Close',
              onPressed: onClose,
            ),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 8),
              child: Text(
                'Page 1',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Painters
// ---------------------------------------------------------------------------

class _CommittedStrokesPainter extends CustomPainter {
  final ui.Image? image;

  const _CommittedStrokesPainter({required this.image});

  @override
  void paint(Canvas canvas, Size size) {
    if (image == null) return;
    canvas.drawImage(image!, Offset.zero, Paint());
  }

  @override
  bool shouldRepaint(_CommittedStrokesPainter old) => old.image != image;
}

class _ActiveStrokePainter extends CustomPainter {
  final List<StrokePoint> points;

  const _ActiveStrokePainter({required this.points});

  @override
  void paint(Canvas canvas, Size size) {
    if (points.isEmpty) return;

    final paint = Paint()
      ..color = const Color(0xFF000000)
      ..strokeWidth = 3.0
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;

    final path = Path()..moveTo(points.first.x, points.first.y);
    for (var i = 1; i < points.length; i++) {
      path.lineTo(points[i].x, points[i].y);
    }
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(_ActiveStrokePainter old) => old.points != points;
}
