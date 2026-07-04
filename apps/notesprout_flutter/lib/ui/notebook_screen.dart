import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:uuid/uuid.dart';

import '../data/db_worker.dart';
import '../data/soil_database.dart';
import '../domain/stroke.dart';
import '../platform/pen_bridge.dart';
import 'ink_painter.dart';

const _uuid = Uuid();

enum _Tool { pen, eraser }

class _Committed {
  _Committed(this.id, this.data);
  final String id;
  final StrokeData data;
}

/// One open notebook. Pen strokes update the in-memory model + fire a background `.soil` write; the
/// live Onyx overlay shows them while writing, so we do NOT touch the EPD panel per stroke. A single
/// quality refresh (`repaintPanel`) reconciles Flutter's committed layer only at transitions —
/// page flip, erase, clear, back — matching how the native app keeps writing smooth.
class NotebookScreen extends StatefulWidget {
  const NotebookScreen(
      {super.key, required this.worker, required this.notebookId, required this.title});

  final DbWorker worker;
  final String notebookId;
  final String title;

  @override
  State<NotebookScreen> createState() => _NotebookScreenState();
}

class _NotebookScreenState extends State<NotebookScreen> {
  static const _viewType = 'notesprout/onyx_drawing';

  final _bridge = PenBridge();

  List<PageRef> _pages = [];
  int _pageIndex = 0;
  final List<_Committed> _committed = [];
  _Tool _tool = _Tool.pen;
  double _dpr = 1.0;
  bool _ready = false;

  String get _layerId => _pages[_pageIndex].layerId;

  @override
  void initState() {
    super.initState();
    _bridge.onEvent = _onPen;
    _init();
  }

  Future<void> _init() async {
    _pages = await widget.worker.openNotebook(widget.notebookId);
    await _loadPage();
    if (mounted) setState(() => _ready = true);
  }

  @override
  void dispose() {
    _bridge.dispose();
    widget.worker.closeNotebook(widget.notebookId, _pages.length);
    super.dispose();
  }

  Future<void> _loadPage() async {
    final rows = await widget.worker.strokes(widget.notebookId, _layerId);
    _committed
      ..clear()
      ..addAll(rows.map((r) => _Committed(r.id, r.data)));
    if (mounted) setState(() {});
  }

  void _onPen(PenEvent e) {
    if (!_ready) return;
    final pts = <StrokePoint>[];
    for (var i = 0; i + 1 < e.points.length; i += 2) {
      pts.add(StrokePoint(e.points[i], e.points[i + 1]));
    }
    if (pts.isEmpty) return;

    if (e.type == 'stroke') {
      // Overlay already shows the ink — just record + persist in the background. No repaint, no
      // EPD refresh: reconciliation happens at the next transition. The id is pre-assigned here so
      // a same-session erase can delete the persisted row.
      final data = StrokeData(points: pts);
      final strokeId = _uuid.v4();
      _committed.add(_Committed(strokeId, data));
      widget.worker.insertStroke(widget.notebookId, _layerId, strokeId, data);
    } else if (e.type == 'erase') {
      final r2 = (15 * _dpr) * (15 * _dpr);
      final hits = _committed.where((c) => _hit(c.data.points, pts, r2)).toList();
      if (hits.isEmpty) return;
      widget.worker.softDelete(widget.notebookId, [for (final h in hits) if (h.id.isNotEmpty) h.id]);
      _committed.removeWhere(hits.contains);
      setState(() {});
      WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
    }
  }

  bool _hit(List<StrokePoint> stroke, List<StrokePoint> eraser, double r2) {
    for (final s in stroke) {
      for (final e in eraser) {
        final dx = s.x - e.x, dy = s.y - e.y;
        if (dx * dx + dy * dy <= r2) return true;
      }
    }
    return false;
  }

  void _selectTool(_Tool t) {
    setState(() => _tool = t);
    t == _Tool.pen ? _bridge.setPen() : _bridge.setEraser();
  }

  void _clear() {
    if (_committed.isNotEmpty) {
      widget.worker
          .softDelete(widget.notebookId, [for (final c in _committed) if (c.id.isNotEmpty) c.id]);
      _committed.clear();
    }
    setState(() {});
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.clear());
  }

  Future<void> _switchPage(int delta) async {
    final next = _pageIndex + delta;
    if (next < 0 || next >= _pages.length) return;
    _pageIndex = next;
    await _loadPage();
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
  }

  Future<void> _addPage() async {
    final size = MediaQuery.of(context).size;
    final p = await widget.worker.addPage(widget.notebookId, size.width * _dpr, size.height * _dpr);
    _pages.add(p);
    _pageIndex = _pages.length - 1;
    await _loadPage();
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
  }

  @override
  Widget build(BuildContext context) {
    _dpr = MediaQuery.of(context).devicePixelRatio;
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Column(
          children: [
            _toolbar(),
            const Divider(height: 1, thickness: 1, color: Colors.black),
            Expanded(
              child: Stack(
                children: [
                  Positioned.fill(
                    child: CustomPaint(
                      painter: InkPainter(_committed.map((c) => c.data).toList(), _dpr),
                    ),
                  ),
                  Positioned.fill(child: _surface()),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _toolbar() {
    return Container(
      height: 56,
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: [
          _btn('‹ Back', false, () => Navigator.of(context).pop()),
          const SizedBox(width: 12),
          _btn('Pen', _tool == _Tool.pen, () => _selectTool(_Tool.pen)),
          const SizedBox(width: 8),
          _btn('Eraser', _tool == _Tool.eraser, () => _selectTool(_Tool.eraser)),
          const SizedBox(width: 8),
          _btn('Clear', false, _clear),
          const Spacer(),
          _btn('‹', false, () => _switchPage(-1)),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10),
            child: Text(_ready ? '${_pageIndex + 1} / ${_pages.length}' : '…',
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
          ),
          _btn('›', false, () => _switchPage(1)),
          const SizedBox(width: 8),
          _btn('+ Page', false, _addPage),
        ],
      ),
    );
  }

  Widget _btn(String label, bool selected, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
        decoration: BoxDecoration(
          color: selected ? Colors.black : Colors.white,
          border: Border.all(color: Colors.black, width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(label,
            style: TextStyle(
                color: selected ? Colors.white : Colors.black,
                fontSize: 15,
                fontWeight: FontWeight.w600)),
      ),
    );
  }

  Widget _surface() {
    return PlatformViewLink(
      viewType: _viewType,
      surfaceFactory: (context, controller) => AndroidViewSurface(
        controller: controller as AndroidViewController,
        hitTestBehavior: PlatformViewHitTestBehavior.transparent,
        gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
      ),
      onCreatePlatformView: (params) {
        final controller = PlatformViewsService.initExpensiveAndroidView(
          id: params.id,
          viewType: _viewType,
          layoutDirection: TextDirection.ltr,
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        )
          ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
          ..create();
        return controller;
      },
    );
  }
}
