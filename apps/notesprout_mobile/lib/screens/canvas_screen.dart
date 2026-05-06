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

  @override
  void initState() {
    super.initState();
    _initCanvas();
  }

  @override
  void dispose() {
    _db?.close();
    super.dispose();
  }

  Future<void> _initCanvas() async {
    final db = SoilDatabase(p.join(widget.folderPath, _soilFile));
    final layers = await db.getLayers(widget.pageId);
    if (layers.isEmpty) {
      await db.close();
      return;
    }
    final layerId = layers.first.id;
    final strokes = await db.getStrokes(layerId);

    _db = db;
    if (mounted) {
      setState(() {
        _layerId = layerId;
        _strokes = strokes;
        _ready = true;
      });
    }
  }

  // ---------------------------------------------------------------------------
  // Gesture handlers
  // ---------------------------------------------------------------------------

  void _onPanStart(DragStartDetails details) {
    if (!_ready) return;
    _activePoints = [
      StrokePoint(
        x: details.localPosition.dx,
        y: details.localPosition.dy,
        pressure: 1.0,
        tilt: 0.0,
        timestamp: DateTime.now().millisecondsSinceEpoch,
      ),
    ];
  }

  void _onPanUpdate(DragUpdateDetails details) {
    if (!_ready || _activePoints.isEmpty) return;
    setState(() {
      _activePoints = [
        ..._activePoints,
        StrokePoint(
          x: details.localPosition.dx,
          y: details.localPosition.dy,
          pressure: 1.0,
          tilt: 0.0,
          timestamp: DateTime.now().millisecondsSinceEpoch,
        ),
      ];
    });
  }

  Future<void> _onPanEnd(DragEndDetails _) async {
    if (!_ready || _activePoints.isEmpty || _layerId == null) return;

    final now = DateTime.now();
    final stroke = StrokeModel(
      id: _uuid.v4(),
      parentId: _layerId,
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
          // Full-screen drawing surface.
          GestureDetector(
            onPanStart: _onPanStart,
            onPanUpdate: _onPanUpdate,
            onPanEnd: _onPanEnd,
            child: CustomPaint(
              painter: _StrokePainter(
                strokes: _strokes,
                activePoints: _activePoints,
              ),
              child: const SizedBox.expand(),
            ),
          ),

          // Floating toolbar — top left, respects safe area.
          SafeArea(
            child: Positioned(
              top: 8,
              left: 8,
              child: _Toolbar(onClose: _close),
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
// Painter
// ---------------------------------------------------------------------------

class _StrokePainter extends CustomPainter {
  final List<StrokeModel> strokes;
  final List<StrokePoint> activePoints;

  const _StrokePainter({required this.strokes, required this.activePoints});

  @override
  void paint(Canvas canvas, Size size) {
    for (final stroke in strokes) {
      _drawPoints(canvas, stroke.points, stroke.color, stroke.width);
    }
    if (activePoints.isNotEmpty) {
      _drawPoints(canvas, activePoints, 0xFF000000, 3.0);
    }
  }

  void _drawPoints(
    Canvas canvas,
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

  @override
  bool shouldRepaint(_StrokePainter old) =>
      old.strokes != strokes || old.activePoints != activePoints;
}
