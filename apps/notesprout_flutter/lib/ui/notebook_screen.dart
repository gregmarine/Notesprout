import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:uuid/uuid.dart';

import 'dart:math' as math;

import '../core/geometry.dart';
import '../core/markdown/markdown_parser.dart';
import '../core/markdown/markdown_render.dart';
import '../core/undo_manager.dart';
import '../data/db_worker.dart';
import '../data/soil_database.dart';
import '../domain/objects.dart';
import '../domain/page_object.dart';
import '../domain/stroke.dart';
import '../platform/pen_bridge.dart';
import 'flutter_ink_surface.dart';
import 'line_dialog.dart';
import 'page_painter.dart';
import 'text_dialog.dart';

const _uuid = Uuid();

enum _Tool { pen, eraser }

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
  final _undo = UndoStack();

  // Bumped whenever undo/redo availability changes, so the ↶/↷ buttons refresh WITHOUT a full
  // canvas setState — critical because the stroke-commit path intentionally never calls setState.
  final _histVersion = ValueNotifier<int>(0);

  List<PageRef> _pages = [];
  int _pageIndex = 0;
  final List<PageObject> _objects = [];
  _Tool _tool = _Tool.pen;
  bool _textMode = false; // tap-to-place / tap-to-edit text objects (pen input suspended)
  bool _lassoMode = false; // stylus draws a selection loop; finger drags the selection
  final List<PageObject> _selection = [];
  final List<PageObject> _clipboard = [];
  // Finger-drag move state (lasso mode): snapshot of pre-drag objects + cumulative px delta.
  final Map<String, PageObject> _moveBefore = {};
  Offset _dragTotal = Offset.zero;
  bool _moving = false;
  double _dpr = 1.0;
  bool _ready = false;

  String get _layerId => _pages[_pageIndex].layerId;

  /// BOOX Android hosts the Onyx EPD overlay; every other platform (desktop now, iPad/web later)
  /// draws with the pure-Flutter [FlutterInkSurface] and skips all EPD/bridge handoffs.
  bool get _useOnyx => defaultTargetPlatform == TargetPlatform.android;

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
    _histVersion.dispose();
    widget.worker.closeNotebook(widget.notebookId, _pages.length);
    super.dispose();
  }

  Future<void> _loadPage() async {
    final rows = await widget.worker.objects(widget.notebookId, _layerId);
    _objects
      ..clear()
      ..addAll(rows);
    if (mounted) setState(() {});
  }

  void _onPen(PenEvent e) {
    if (!_ready) return;
    final pts = <StrokePoint>[];
    for (var i = 0; i + 1 < e.points.length; i += 2) {
      pts.add(StrokePoint(e.points[i], e.points[i + 1]));
    }
    if (pts.isEmpty) return;

    if (_lassoMode) {
      // The pen loop is a selection gesture, never committed ink. Compute the selection and hand
      // the panel back to Flutter so the transient loop ink clears.
      if (e.type == 'stroke') _applyLasso(pts);
      WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
      return;
    }

    if (e.type == 'stroke') {
      // Overlay already shows the ink — just record + persist in the background. No repaint, no
      // EPD refresh: reconciliation happens at the next transition. The id is pre-assigned here so
      // a same-session erase can delete the persisted row.
      final data = StrokeData(points: pts);
      final strokeId = _uuid.v4();
      final obj = StrokeObject(strokeId, BoundingBox.ofPoints(pts), data);
      _objects.add(obj);
      widget.worker.insertStroke(widget.notebookId, _layerId, strokeId, data);
      _pushInsert([obj]);
      // On BOOX the Onyx overlay already shows the ink; elsewhere we must repaint to reveal it.
      if (!_useOnyx) setState(() {});
    } else if (e.type == 'erase') {
      // The eraser tool only removes strokes; objects (headings/text/lines) are erased via the
      // lasso eraser (2D). Hit-test committed strokes by point proximity.
      final r2 = (15 * _dpr) * (15 * _dpr);
      final hits = _objects
          .whereType<StrokeObject>()
          .where((c) => _hit(c.data.points, pts, r2))
          .toList();
      if (hits.isEmpty) return;
      widget.worker.softDelete(widget.notebookId, [for (final h in hits) if (h.id.isNotEmpty) h.id]);
      _objects.removeWhere(hits.contains);
      _pushDelete(hits);
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

  // ── Undo / redo ────────────────────────────────────────────────────────────
  // Each mutation records an inverse. Soft-delete is reversible (deletedAt toggles), so an insert's
  // undo just marks the rows deleted and its redo un-marks them — the row is never physically gone.

  void _pushInsert(List<PageObject> objs) {
    if (objs.isEmpty) return;
    final ids = [for (final o in objs) o.id];
    _record(UndoAction(
      undo: () {
        _objects.removeWhere(objs.contains);
        widget.worker.setDeleted(widget.notebookId, ids, true);
      },
      redo: () {
        _objects.addAll(objs);
        widget.worker.setDeleted(widget.notebookId, ids, false);
      },
    ));
  }

  void _pushDelete(List<PageObject> objs) {
    if (objs.isEmpty) return;
    final ids = [for (final o in objs) o.id];
    _record(UndoAction(
      undo: () {
        _objects.addAll(objs);
        widget.worker.setDeleted(widget.notebookId, ids, false);
      },
      redo: () {
        _objects.removeWhere(objs.contains);
        widget.worker.setDeleted(widget.notebookId, ids, true);
      },
    ));
  }

  void _pushUpdate(PageObject before, PageObject after) {
    _record(UndoAction(
      undo: () {
        _replaceObject(before);
        widget.worker.updateObject(widget.notebookId, before.id, before.box, _objDataJson(before));
      },
      redo: () {
        _replaceObject(after);
        widget.worker.updateObject(widget.notebookId, after.id, after.box, _objDataJson(after));
      },
    ));
  }

  void _replaceObject(PageObject obj) {
    final i = _objects.indexWhere((o) => o.id == obj.id);
    if (i >= 0) _objects[i] = obj;
  }

  String _objDataJson(PageObject o) => switch (o) {
        StrokeObject s => s.data.toJson(),
        HeadingRender h => h.data.toJson(),
        TextRender t => t.data.toJson(),
        LineRender l => l.data.toJson(),
      };

  /// Record an already-applied action and refresh the ↶/↷ buttons.
  void _record(UndoAction action) {
    _undo.push(action);
    _histVersion.value++;
  }

  void _doUndo() {
    if (!_undo.canUndo) return;
    _undo.undo();
    _histVersion.value++;
    setState(() {});
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
  }

  void _doRedo() {
    if (!_undo.canRedo) return;
    _undo.redo();
    _histVersion.value++;
    setState(() {});
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
  }

  // ── Lasso: select / move / delete / copy-paste ─────────────────────────────

  void _toggleLassoMode() {
    if (!_ready) return;
    setState(() {
      _lassoMode = !_lassoMode;
      _textMode = false;
      if (!_lassoMode) _selection.clear();
    });
    if (_lassoMode) {
      _bridge.setDrawingEnabled(true);
      _bridge.setPen(); // render on so the loop is visible while drawn
    } else {
      _tool == _Tool.pen ? _bridge.setPen() : _bridge.setEraser();
      WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
    }
  }

  /// The pen loop selects every object whose center falls inside the polygon.
  void _applyLasso(List<StrokePoint> loop) {
    if (loop.length < 3) return;
    final sel = <PageObject>[];
    for (final o in _objects) {
      final cx = o.box.x + o.box.width / 2;
      final cy = o.box.y + o.box.height / 2;
      if (pointInPolygon(cx, cy, loop)) sel.add(o);
    }
    setState(() => _selection
      ..clear()
      ..addAll(sel));
  }

  // Finger-drag move (touch pointers only, so the stylus stays free to draw new loops).
  void _onLassoPointerDown(PointerDownEvent e) {
    if (e.kind != PointerDeviceKind.touch) return;
    _dragTotal = Offset.zero;
    _moving = false;
    _moveBefore
      ..clear()
      ..addEntries(_selection.map((o) => MapEntry(o.id, o)));
  }

  void _onLassoPointerMove(PointerMoveEvent e) {
    if (e.kind != PointerDeviceKind.touch || _selection.isEmpty) return;
    _dragTotal += Offset(e.delta.dx * _dpr, e.delta.dy * _dpr);
    if (!_moving && _dragTotal.distance < 8 * _dpr) return; // ignore jitter → keep tap-to-clear
    _moving = true;
    setState(() {
      for (var i = 0; i < _selection.length; i++) {
        final before = _moveBefore[_selection[i].id];
        if (before == null) continue;
        final moved = _translate(before, _dragTotal.dx, _dragTotal.dy);
        final oi = _objects.indexWhere((o) => o.id == moved.id);
        if (oi >= 0) _objects[oi] = moved;
        _selection[i] = moved;
      }
    });
  }

  void _onLassoPointerUp(PointerUpEvent e) {
    if (e.kind != PointerDeviceKind.touch) return;
    if (_moving) {
      final pairs = <(PageObject, PageObject)>[];
      for (final after in _selection) {
        final before = _moveBefore[after.id];
        if (before == null) continue;
        widget.worker.updateObject(widget.notebookId, after.id, after.box, _objDataJson(after));
        pairs.add((before, after));
      }
      if (pairs.isNotEmpty) _pushMove(pairs);
      _moving = false;
      WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
    } else if (_selection.isNotEmpty) {
      setState(() => _selection.clear()); // tap outside → deselect
      WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
    }
  }

  void _pushMove(List<(PageObject, PageObject)> pairs) {
    _record(UndoAction(
      undo: () {
        for (final (b, _) in pairs) {
          _replaceObject(b);
          widget.worker.updateObject(widget.notebookId, b.id, b.box, _objDataJson(b));
        }
        _reselect([for (final (b, _) in pairs) b.id]);
      },
      redo: () {
        for (final (_, a) in pairs) {
          _replaceObject(a);
          widget.worker.updateObject(widget.notebookId, a.id, a.box, _objDataJson(a));
        }
        _reselect([for (final (_, a) in pairs) a.id]);
      },
    ));
  }

  void _reselect(List<String> ids) => _selection
    ..clear()
    ..addAll(_objects.where((o) => ids.contains(o.id)));

  void _deleteSelection() {
    if (_selection.isEmpty) return;
    final gone = List<PageObject>.of(_selection);
    widget.worker.softDelete(widget.notebookId, [for (final o in gone) o.id]);
    _objects.removeWhere(gone.contains);
    _pushDelete(gone);
    setState(() => _selection.clear());
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
  }

  void _copySelection() {
    _clipboard
      ..clear()
      ..addAll(_selection);
    setState(() {});
  }

  void _cutSelection() {
    _copySelection();
    _deleteSelection();
  }

  void _pasteClipboard() {
    if (_clipboard.isEmpty) return;
    final off = 40 * _dpr;
    final pasted = <PageObject>[];
    for (final o in _clipboard) {
      final id = _uuid.v4();
      final copy = _withId(_translate(o, off, off), id);
      _objects.add(copy);
      widget.worker
          .insertObject(widget.notebookId, _layerId, id, _typeOf(copy), copy.box, _objDataJson(copy));
      pasted.add(copy);
    }
    _pushInsert(pasted);
    setState(() => _selection
      ..clear()
      ..addAll(pasted));
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
  }

  String _typeOf(PageObject o) => switch (o) {
        StrokeObject _ => 'stroke',
        HeadingRender _ => 'heading',
        TextRender _ => 'text',
        LineRender _ => 'line',
      };

  PageObject _withId(PageObject o, String id) => switch (o) {
        StrokeObject s => StrokeObject(id, s.box, s.data),
        HeadingRender h => HeadingRender(id, h.box, h.data),
        TextRender t => TextRender(id, t.box, t.data),
        LineRender l => LineRender(id, l.box, l.data),
      };

  /// A translated copy of [o] (px). Strokes shift their points too; other objects just shift the box.
  PageObject _translate(PageObject o, double dx, double dy) {
    final b = BoundingBox(o.box.x + dx, o.box.y + dy, o.box.width, o.box.height);
    return switch (o) {
      StrokeObject s => StrokeObject(
          s.id,
          b,
          StrokeData(
            color: s.data.color,
            strokeWidth: s.data.strokeWidth,
            points: [
              for (final p in s.data.points)
                StrokePoint(p.x + dx, p.y + dy, pressure: p.pressure, tilt: p.tilt)
            ],
          )),
      HeadingRender h => HeadingRender(h.id, b, h.data),
      TextRender t => TextRender(t.id, b, t.data),
      LineRender l => LineRender(l.id, b, l.data),
    };
  }

  void _selectTool(_Tool t) {
    final wasSuspended = _textMode; // text mode had pen input paused
    setState(() {
      _tool = t;
      _textMode = false;
      _lassoMode = false;
      _selection.clear();
    });
    if (wasSuspended) _bridge.setDrawingEnabled(true);
    t == _Tool.pen ? _bridge.setPen() : _bridge.setEraser();
  }

  void _toggleTextMode() {
    if (!_ready) return;
    setState(() {
      _textMode = !_textMode;
      _lassoMode = false;
      _selection.clear();
    });
    if (_textMode) {
      _bridge.setDrawingEnabled(false); // stylus stops drawing; taps place/edit text
    } else {
      _bridge.setDrawingEnabled(true);
      _tool == _Tool.pen ? _bridge.setPen() : _bridge.setEraser();
    }
  }

  /// A tap while in text mode: edit the text object under the point, or place a new one there.
  Future<void> _onPlacementTap(TapUpDetails d) async {
    final px = d.localPosition.dx * _dpr;
    final py = d.localPosition.dy * _dpr;
    TextRender? hit;
    for (final o in _objects.reversed) {
      if (o is TextRender && _inBox(o.box, px, py)) {
        hit = o;
        break;
      }
    }

    final result = await showTextDialog(context, initial: hit?.data.text ?? '');
    if (result == null || !mounted) return; // cancelled

    final pageW = _pages[_pageIndex].data.width;

    if (hit != null) {
      if (result.isEmpty) {
        widget.worker.softDelete(widget.notebookId, [hit.id]);
        _objects.remove(hit);
        _pushDelete([hit]);
      } else {
        final box = _measureBox(result, hit.box.x, hit.box.y, pageW);
        final after = TextRender(hit.id, box, TextObject(text: result));
        _objects[_objects.indexOf(hit)] = after;
        widget.worker.updateObject(widget.notebookId, hit.id, box, after.data.toJson());
        _pushUpdate(hit, after);
      }
    } else if (result.isNotEmpty) {
      final box = _measureBox(result, px, py, pageW);
      final id = _uuid.v4();
      final data = TextObject(text: result);
      final obj = TextRender(id, box, data);
      _objects.add(obj);
      widget.worker.insertObject(widget.notebookId, _layerId, id, 'text', box, data.toJson());
      _pushInsert([obj]);
    } else {
      return; // new + empty → nothing
    }
    setState(() {});
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
  }

  /// Natural bounding box for [markdown] anchored at (x,y) px, wrapping within the page's right edge.
  BoundingBox _measureBox(String markdown, double x, double y, double pageW) {
    final avail = math.max(1.0, pageW - x);
    final size = MarkdownRender.measure(MarkdownParser.parse(markdown),
        widthPx: avail, basePx: 24 * _dpr, dpr: _dpr);
    return BoundingBox(x, y, size.width, size.height);
  }

  bool _inBox(BoundingBox b, double px, double py) =>
      px >= b.x && px <= b.x + b.width && py >= b.y && py <= b.y + b.height;

  Future<void> _insertLines() async {
    if (!_ready) return;
    final page = _pages[_pageIndex].data;
    final lines = await showLineDialog(context, page.width, page.height, _dpr);
    if (lines == null || lines.isEmpty) return;
    final created = <PageObject>[];
    for (final pl in lines) {
      final id = _uuid.v4();
      final obj = LineRender(id, pl.box, pl.line);
      _objects.add(obj);
      created.add(obj);
      widget.worker
          .insertObject(widget.notebookId, _layerId, id, 'line', pl.box, pl.line.toJson());
    }
    _pushInsert(created);
    setState(() {});
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
  }

  void _clear() {
    if (_objects.isNotEmpty) {
      final cleared = List<PageObject>.of(_objects);
      widget.worker.softDelete(widget.notebookId, [for (final o in cleared) if (o.id.isNotEmpty) o.id]);
      _objects.clear();
      _pushDelete(cleared);
    }
    setState(() {});
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.clear());
  }

  Future<void> _switchPage(int delta) async {
    final next = _pageIndex + delta;
    if (next < 0 || next >= _pages.length) return;
    _pageIndex = next;
    _undo.clear(); // undo is page-scoped for now (cross-page undo deferred)
    await _loadPage();
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());
  }

  Future<void> _addPage() async {
    final size = MediaQuery.of(context).size;
    final p = await widget.worker.addPage(widget.notebookId, size.width * _dpr, size.height * _dpr);
    _pages.add(p);
    _pageIndex = _pages.length - 1;
    _undo.clear();
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
            if (_lassoMode) ...[
              _lassoBar(),
              const Divider(height: 1, thickness: 1, color: Colors.black),
            ],
            Expanded(
              child: Stack(
                children: [
                  Positioned.fill(
                    child: CustomPaint(
                      painter: PagePainter(List.of(_objects), _dpr,
                          selection: [for (final o in _selection) o.box]),
                    ),
                  ),
                  Positioned.fill(child: _surface()),
                  // In text mode, a transparent layer above the Onyx surface captures the
                  // placement/edit tap (pen input is suspended natively so the stylus won't draw).
                  if (_textMode)
                    Positioned.fill(
                      child: GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTapUp: _onPlacementTap,
                      ),
                    ),
                  // In lasso mode on BOOX, a translucent layer handles FINGER drags (move) / taps
                  // (deselect) while the stylus passes through to Onyx to draw new selection loops.
                  // On desktop the single pointer both draws loops and would conflict, so drag-move
                  // is Onyx-only for now (desktop still selects + Cut/Copy/Paste/Delete).
                  if (_lassoMode && _useOnyx)
                    Positioned.fill(
                      child: Listener(
                        behavior: HitTestBehavior.translucent,
                        onPointerDown: _onLassoPointerDown,
                        onPointerMove: _onLassoPointerMove,
                        onPointerUp: _onLassoPointerUp,
                      ),
                    ),
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
          ValueListenableBuilder<int>(
            valueListenable: _histVersion,
            builder: (context, version, child) => Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _actionBtn('↶', _undo.canUndo, _doUndo),
                const SizedBox(width: 8),
                _actionBtn('↷', _undo.canRedo, _doRedo),
              ],
            ),
          ),
          const SizedBox(width: 12),
          _btn('Pen', _tool == _Tool.pen, () => _selectTool(_Tool.pen)),
          const SizedBox(width: 8),
          _btn('Eraser', _tool == _Tool.eraser, () => _selectTool(_Tool.eraser)),
          const SizedBox(width: 8),
          _btn('Clear', false, _clear),
          const SizedBox(width: 8),
          _btn('Text', _textMode, _toggleTextMode),
          const SizedBox(width: 8),
          _btn('Lasso', _lassoMode, _toggleLassoMode),
          const SizedBox(width: 8),
          _btn('Lines', false, _insertLines),
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

  Widget _lassoBar() {
    final has = _selection.isNotEmpty;
    return Container(
      height: 48,
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: [
          Text(
            has
                ? '${_selection.length} selected · drag to move'
                : 'Draw a loop with the pen to select',
            style: const TextStyle(fontSize: 13, color: Color(0xFF888888)),
          ),
          const Spacer(),
          _actionBtn('Cut', has, _cutSelection),
          const SizedBox(width: 8),
          _actionBtn('Copy', has, _copySelection),
          const SizedBox(width: 8),
          _actionBtn('Paste', _clipboard.isNotEmpty, _pasteClipboard),
          const SizedBox(width: 8),
          _actionBtn('Delete', has, _deleteSelection),
        ],
      ),
    );
  }

  /// A momentary action button that dims its label to inkLight when [enabled] is false (a disabled
  /// *fill* would be invisible on e-ink; a lighter label reads clearly). Taps are ignored when off.
  Widget _actionBtn(String label, bool enabled, VoidCallback onTap) {
    return GestureDetector(
      onTap: enabled ? onTap : null,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
        decoration: BoxDecoration(
          color: Colors.white,
          border: Border.all(color: enabled ? Colors.black : const Color(0xFF888888), width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(label,
            style: TextStyle(
                color: enabled ? Colors.black : const Color(0xFF888888),
                fontSize: 16,
                fontWeight: FontWeight.w600)),
      ),
    );
  }

  Widget _surface() {
    // Off-BOOX: pure-Flutter ink. A finished stroke routes through the same _onPen path, tagged by
    // the current tool (eraser → 'erase'); lasso mode is handled inside _onPen regardless of tag.
    if (!_useOnyx) {
      return FlutterInkSurface(
        dpr: _dpr,
        strokeWidthPx: 3 * _dpr,
        onStroke: (pts) =>
            _onPen(PenEvent(_tool == _Tool.eraser ? 'erase' : 'stroke', pts)),
      );
    }
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
