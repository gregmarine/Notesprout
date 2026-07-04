import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:uuid/uuid.dart';

import 'dart:math' as math;

import '../core/lasso_geometry.dart';
import '../core/snap_engine.dart';
import '../core/markdown/markdown_parser.dart';
import '../core/markdown/markdown_render.dart';
import '../core/undo_manager.dart';
import '../data/app_settings.dart';
import '../data/db_worker.dart';
import '../recognition/handwriting_recognizer.dart';
import '../data/soil_database.dart';
import '../domain/objects.dart';
import '../domain/page_object.dart';
import '../domain/stroke.dart';
import '../platform/pen_bridge.dart';
import 'flutter_ink_surface.dart';
import 'line_dialog.dart';
import 'overflow_toolbar.dart';
import 'page_painter.dart';
import 'text_dialog.dart';

const _uuid = Uuid();

/// The active editing mode. Pen/eraser keep the Onyx overlay live (fast e-ink ink); lasso and text
/// suspend it so Flutter can own pointer input (loop capture / selection drag / tap-to-place).
enum _Mode { pen, eraser, lasso, text }

/// One open notebook. Pen strokes update the in-memory model + fire a background `.soil` write; the
/// live Onyx overlay shows them while writing, so we do NOT touch the EPD panel per stroke. A single
/// quality refresh (`repaintPanel`) reconciles Flutter's committed layer only at transitions —
/// page flip, erase, clear, gesture resolve, back — matching how the native app keeps writing smooth.
///
/// Lasso follows the native model (see docs/lasso-and-gestures.md): in pen mode every stroke is
/// classified at pen-lift (smart-lasso → scribble-erase → normal); an explicit Lasso tool lets the
/// user draw a deliberate selection loop. A loop always draws on the fast Onyx overlay; only once a
/// selection is *active* do we suspend the pen and let Flutter handle drag-to-move / tap-to-dismiss.
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
  _Mode _mode = _Mode.pen;

  // ── Selection state ─────────────────────────────────────────────────────────
  final List<PageObject> _selection = [];
  final List<PageObject> _clipboard = [];
  BoundingBox? _selBounds; // padded union of the selection (px); drives overlay + floating toolbar
  bool _smartSession = false; // selection came from a smart-lasso in pen mode → dismiss returns to pen
  bool _recognizing = false; // handwriting recognition in flight (convert tools)

  // Selection drag-move state.
  final Map<String, PageObject> _moveBefore = {};
  Offset _dragTotal = Offset.zero;
  bool _movingSel = false;
  bool _pendingDismiss = false;
  int _lastPanelMs = 0; // throttle for live e-ink feedback during a drag

  // Snap-to-guide — default off; persisted device-local (mirrors native SnapPreferences).
  bool _snapEnabled = AppSettings.instance.snapEnabled;
  BoundingBox? _dragOriginBox; // unpadded selection bbox at drag start (snap anchors)
  List<double> _guidesV = const []; // active vertical snap guides (px) during a drag
  List<double> _guidesH = const [];

  double _dpr = 1.0;
  bool _ready = false;

  String get _layerId => _pages[_pageIndex].layerId;

  /// BOOX Android hosts the Onyx EPD overlay; every other platform (desktop now, iPad/web later)
  /// draws with the pure-Flutter [FlutterInkSurface] and skips all EPD/bridge handoffs.
  bool get _useOnyx => defaultTargetPlatform == TargetPlatform.android;

  bool get _selectionActive => _mode == _Mode.lasso && _selection.isNotEmpty;

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

  void _repaintPanelNextFrame() =>
      WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());

  // ── Pen input ────────────────────────────────────────────────────────────────

  void _onPen(PenEvent e) {
    if (!_ready) return;
    // While a selection is active the pen is suspended (Flutter drives drag/dismiss), so nothing
    // should arrive here. Guard defensively.
    if (_selectionActive) return;

    final pts = <StrokePoint>[];
    for (var i = 0; i + 1 < e.points.length; i += 2) {
      pts.add(StrokePoint(e.points[i], e.points[i + 1]));
    }
    if (pts.isEmpty) return;

    // ── Lasso tool: any stroke is a deliberate selection loop (no gesture gates) ──
    if (_mode == _Mode.lasso) {
      if (e.type == 'stroke') {
        final hit = lassoHitTest(pts, _objects, _dpr);
        if (hit.ids.isNotEmpty) {
          _enterSelection(hit, smart: false);
        }
      }
      _repaintPanelNextFrame(); // clear the transient loop ink from the Onyx overlay
      return;
    }

    if (e.type == 'stroke') {
      // ── Pen mode: classify at pen-lift, native gate order ──────────────────────
      // Gate 1: smart-lasso — a fast closed loop enclosing content becomes a selection.
      if (isSmartLassoCandidate(pts, e.durationMs, _dpr)) {
        final hit = lassoHitTest(pts, _objects, _dpr);
        if (hit.ids.isNotEmpty) {
          _enterSelection(hit, smart: true);
          _repaintPanelNextFrame(); // discard the loop stroke (never persisted)
          return;
        }
      }
      // Gate 2: scribble-to-erase — a dense zigzag crossing content erases it.
      if (isScribbleCandidate(pts, _dpr)) {
        final hitIds = scribbleHitTest(pts, _objects, _dpr).toSet();
        if (hitIds.isNotEmpty) {
          final gone = _objects.where((o) => hitIds.contains(o.id)).toList();
          widget.worker.softDelete(widget.notebookId, [for (final o in gone) o.id]);
          _objects.removeWhere(gone.contains);
          _pushDelete(gone);
          setState(() {});
          _repaintPanelNextFrame(); // discard the scribble stroke + reveal the erase
          return;
        }
      }
      // Gate 3: normal stroke. Overlay already shows the ink — just record + persist. No repaint.
      final data = StrokeData(points: pts);
      final strokeId = _uuid.v4();
      final obj = StrokeObject(strokeId, BoundingBox.ofPoints(pts), data);
      _objects.add(obj);
      widget.worker.insertStroke(widget.notebookId, _layerId, strokeId, data);
      _pushInsert([obj]);
      if (!_useOnyx) setState(() {}); // off-BOOX has no overlay → must repaint to reveal the ink
    } else if (e.type == 'erase') {
      // Eraser tool: remove strokes by point proximity (objects are erased via scribble/lasso).
      final r2 = (15 * _dpr) * (15 * _dpr);
      final hits = _objects
          .whereType<StrokeObject>()
          .where((c) => _hit(c.data.points, pts, r2))
          .toList();
      if (hits.isEmpty) return;
      widget.worker
          .softDelete(widget.notebookId, [for (final h in hits) if (h.id.isNotEmpty) h.id]);
      _objects.removeWhere(hits.contains);
      _pushDelete(hits);
      setState(() {});
      _repaintPanelNextFrame();
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
    _repaintPanelNextFrame();
  }

  void _doRedo() {
    if (!_undo.canRedo) return;
    _undo.redo();
    _histVersion.value++;
    setState(() {});
    _repaintPanelNextFrame();
  }

  // ── Mode switching ───────────────────────────────────────────────────────────

  void _setMode(_Mode m) {
    setState(() {
      _mode = m;
      if (m != _Mode.lasso) {
        _selection.clear();
        _selBounds = null;
        _smartSession = false;
      }
      _movingSel = false;
      _pendingDismiss = false;
      _dragOriginBox = null;
      _guidesV = const [];
      _guidesH = const [];
    });
    switch (m) {
      case _Mode.pen:
        _bridge.setDrawingEnabled(true);
        _bridge.setPen();
      case _Mode.eraser:
        _bridge.setDrawingEnabled(true);
        _bridge.setEraser();
      case _Mode.lasso:
        // Loops draw as fast overlay ink; a selection then suspends the pen (see _enterSelection).
        _bridge.setDrawingEnabled(true);
        _bridge.setPen();
      case _Mode.text:
        _bridge.setDrawingEnabled(false);
    }
    _repaintPanelNextFrame();
  }

  // ── Selection: enter / move / dismiss ────────────────────────────────────────

  /// Unpadded union of [objs] boxes (px) — the true content bounds.
  BoundingBox? _rawBounds(Iterable<PageObject> objs) {
    if (objs.isEmpty) return null;
    final first = objs.first.box;
    var minX = first.x, minY = first.y, maxX = first.x + first.width, maxY = first.y + first.height;
    for (final o in objs) {
      minX = math.min(minX, o.box.x);
      minY = math.min(minY, o.box.y);
      maxX = math.max(maxX, o.box.x + o.box.width);
      maxY = math.max(maxY, o.box.y + o.box.height);
    }
    return BoundingBox(minX, minY, maxX - minX, maxY - minY);
  }

  /// Padded union of the selection — used for the touch target + floating-toolbar anchor.
  BoundingBox? _computeSelBounds() {
    final raw = _rawBounds(_selection);
    if (raw == null) return null;
    final pad = kLassoOverlayPadDp * _dpr;
    return BoundingBox(raw.x - pad, raw.y - pad, raw.width + 2 * pad, raw.height + 2 * pad);
  }

  /// Adopt a lasso hit-test result as the active selection: suspend the pen (so it now drags/
  /// dismisses via Flutter), show the dashed overlay + floating toolbar. [smart] flags a pen-mode
  /// smart-lasso session (dismiss returns to pen; tool-initiated stays in lasso).
  void _enterSelection(LassoHit hit, {required bool smart}) {
    setState(() {
      _mode = _Mode.lasso;
      _smartSession = smart;
      _selection
        ..clear()
        ..addAll(_objects.where((o) => hit.ids.contains(o.id)));
      _selBounds = hit.bounds;
    });
    _bridge.setDrawingEnabled(false);
    _repaintPanelNextFrame();
  }

  /// The selection is gone (deleted / dismissed): restore the pen. Smart-lasso sessions and Delete
  /// return to pen mode; a plain dismiss in the lasso tool stays in lasso (ready to draw again).
  void _endSelection({required bool toPen}) {
    final wasSmart = _smartSession;
    setState(() {
      _selection.clear();
      _selBounds = null;
      _smartSession = false;
      _movingSel = false;
      _pendingDismiss = false;
      _dragOriginBox = null;
      _guidesV = const [];
      _guidesH = const [];
    });
    if (toPen || wasSmart) {
      _setMode(_Mode.pen);
    } else {
      _bridge.setDrawingEnabled(true); // back to loop-drawing in the lasso tool
      _repaintPanelNextFrame();
    }
  }

  void _selDown(PointerDownEvent e) {
    final px = e.localPosition.dx * _dpr, py = e.localPosition.dy * _dpr;
    if (_selBounds != null && _inBox(_selBounds!, px, py)) {
      _movingSel = true;
      _pendingDismiss = false;
      _dragTotal = Offset.zero;
      _dragOriginBox = _rawBounds(_selection);
      _moveBefore
        ..clear()
        ..addEntries(_selection.map((o) => MapEntry(o.id, o)));
    } else {
      _movingSel = false;
      _pendingDismiss = true; // tap outside the selection → dismiss on up
    }
  }

  void _selMove(PointerMoveEvent e) {
    if (!_movingSel) return;
    _dragTotal += Offset(e.delta.dx * _dpr, e.delta.dy * _dpr);

    // Snap-to-guide: adjust the raw delta so the nearest anchor pulls flush to a guide.
    var dx = _dragTotal.dx, dy = _dragTotal.dy;
    if (_snapEnabled && _dragOriginBox != null) {
      final page = _pages[_pageIndex].data;
      final targets = [
        for (final o in _objects)
          if ((o is HeadingRender || o is TextRender) && !_selection.contains(o)) o.box
      ];
      final r = computeSnap(
        box: _dragOriginBox!,
        rawDx: _dragTotal.dx,
        rawDy: _dragTotal.dy,
        pageWidth: page.width,
        pageHeight: page.height,
        marginPx: kSnapMarginDp * _dpr,
        thresholdPx: kSnapThresholdDp * _dpr,
        objectTargets: targets,
      );
      dx = r.dx;
      dy = r.dy;
      _guidesV = [for (final g in r.guides) if (g is VerticalGuide) g.x];
      _guidesH = [for (final g in r.guides) if (g is HorizontalGuide) g.y];
    }

    setState(() {
      for (var i = 0; i < _selection.length; i++) {
        final before = _moveBefore[_selection[i].id];
        if (before == null) continue;
        final moved = _translate(before, dx, dy);
        final oi = _objects.indexWhere((o) => o.id == moved.id);
        if (oi >= 0) _objects[oi] = moved;
        _selection[i] = moved;
      }
      _selBounds = _computeSelBounds();
    });
    _throttledPanelRepaint(); // coarse live feedback on e-ink during the drag
  }

  void _clearGuides() {
    if (_guidesV.isEmpty && _guidesH.isEmpty) return;
    setState(() {
      _guidesV = const [];
      _guidesH = const [];
    });
  }

  void _selUp(PointerUpEvent e) {
    if (_movingSel) {
      _movingSel = false;
      _dragOriginBox = null;
      _clearGuides();
      if (_dragTotal.distance >= 8 * _dpr) {
        final pairs = <(PageObject, PageObject)>[];
        for (final after in _selection) {
          final before = _moveBefore[after.id];
          if (before == null) continue;
          widget.worker.updateObject(widget.notebookId, after.id, after.box, _objDataJson(after));
          pairs.add((before, after));
        }
        if (pairs.isNotEmpty) _pushMove(pairs);
      }
      _repaintPanelNextFrame();
    } else if (_pendingDismiss) {
      _pendingDismiss = false;
      _endSelection(toPen: false); // tap outside → dismiss
    }
  }

  void _throttledPanelRepaint() {
    final now = DateTime.now().millisecondsSinceEpoch;
    if (now - _lastPanelMs >= 120) {
      _lastPanelMs = now;
      _repaintPanelNextFrame();
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

  void _reselect(List<String> ids) {
    _selection
      ..clear()
      ..addAll(_objects.where((o) => ids.contains(o.id)));
    _selBounds = _computeSelBounds();
  }

  // ── Selection actions ────────────────────────────────────────────────────────

  void _deleteSelection() {
    if (_selection.isEmpty) return;
    final gone = List<PageObject>.of(_selection);
    widget.worker.softDelete(widget.notebookId, [for (final o in gone) o.id]);
    _objects.removeWhere(gone.contains);
    _pushDelete(gone);
    _endSelection(toPen: true); // native: Delete exits lasso mode
  }

  void _copySelection() {
    _clipboard
      ..clear()
      ..addAll(_selection);
    setState(() {}); // keep the selection highlighted; enables the main-toolbar Paste
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
      widget.worker.insertObject(
          widget.notebookId, _layerId, id, _typeOf(copy), copy.box, _objDataJson(copy));
      pasted.add(copy);
    }
    _pushInsert(pasted);
    setState(() {
      _mode = _Mode.lasso;
      _smartSession = false;
      _selection
        ..clear()
        ..addAll(pasted);
      _selBounds = _computeSelBounds();
    });
    _bridge.setDrawingEnabled(false); // pasted objects are the active selection
    _repaintPanelNextFrame();
  }

  /// Align & distribute is offered for ≥2 non-stroke objects (headings/text/lines) — native's rule.
  bool get _alignEligible =>
      _selection.length >= 2 && _selection.every((o) => o is! StrokeObject);

  /// Align one edge to the selection bbox and distribute with equal gaps along the other axis
  /// ([vertical] = align-left + stack top→bottom; else align-top + spread left→right). One undo step.
  void _align(bool vertical) {
    if (!_alignEligible) return;
    final box = _rawBounds(_selection);
    if (box == null) return;
    final items = List<PageObject>.of(_selection)
      ..sort((a, b) => vertical
          ? (a.box.y + a.box.height / 2).compareTo(b.box.y + b.box.height / 2)
          : (a.box.x + a.box.width / 2).compareTo(b.box.x + b.box.width / 2));

    final pairs = <(PageObject, PageObject)>[];
    if (vertical) {
      final sumH = items.fold(0.0, (s, o) => s + o.box.height);
      final gap = items.length > 1 ? (box.height - sumH) / (items.length - 1) : 0.0;
      var y = box.y;
      for (final o in items) {
        final moved = _translate(o, box.x - o.box.x, y - o.box.y);
        pairs.add((o, moved));
        y += o.box.height + gap;
      }
    } else {
      final sumW = items.fold(0.0, (s, o) => s + o.box.width);
      final gap = items.length > 1 ? (box.width - sumW) / (items.length - 1) : 0.0;
      var x = box.x;
      for (final o in items) {
        final moved = _translate(o, x - o.box.x, box.y - o.box.y);
        pairs.add((o, moved));
        x += o.box.width + gap;
      }
    }
    for (final (_, a) in pairs) {
      _replaceObject(a);
      widget.worker.updateObject(widget.notebookId, a.id, a.box, _objDataJson(a));
    }
    _pushMove(pairs);
    setState(() => _reselect([for (final (_, a) in pairs) a.id]));
    _repaintPanelNextFrame();
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

  // ── Text placement ───────────────────────────────────────────────────────────

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
    _repaintPanelNextFrame();
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

  // ── Handwriting recognition — convert a pure-stroke selection to Text / Heading ────────────
  bool get _selectionIsPureStrokes =>
      _selection.isNotEmpty && _selection.every((o) => o is StrokeObject);

  BoundingBox _selectionStrokeBounds(List<StrokeObject> strokes) {
    var minX = strokes.first.box.x, minY = strokes.first.box.y;
    var maxX = minX, maxY = minY;
    for (final s in strokes) {
      minX = math.min(minX, s.box.x);
      minY = math.min(minY, s.box.y);
      maxX = math.max(maxX, s.box.x + s.box.width);
      maxY = math.max(maxY, s.box.y + s.box.height);
    }
    return BoundingBox(minX, minY, maxX - minX, maxY - minY);
  }

  Future<String?> _recognizeSelection(List<StrokeObject> strokes) async {
    setState(() => _recognizing = true);
    final text = await Recognition.instance.recognize([for (final s in strokes) s.data]);
    if (!mounted) return null;
    setState(() => _recognizing = false);
    if (text == kFallbackText) {
      _snack(Recognition.instance.isReady
          ? "Couldn't recognize that handwriting"
          : 'Handwriting model still preparing — try again in a moment');
      return null;
    }
    return text;
  }

  Future<void> _convertToText() async {
    if (!_selectionIsPureStrokes || _recognizing) return;
    final strokes = _selection.whereType<StrokeObject>().toList();
    final text = await _recognizeSelection(strokes);
    if (text == null || !mounted) return;
    final b = _selectionStrokeBounds(strokes);
    final box = _measureBox(text, b.x, b.y, _pages[_pageIndex].data.width);
    final data = TextObject(text: text);
    _replaceStrokesWith(strokes, TextRender(_uuid.v4(), box, data), 'text', data.toJson());
  }

  Future<void> _convertToHeading(int level) async {
    if (!_selectionIsPureStrokes || _recognizing) return;
    final strokes = _selection.whereType<StrokeObject>().toList();
    final raw = await _recognizeSelection(strokes);
    if (raw == null || !mounted) return;
    final prefixed = HeadingObject.applyLevel(raw, level)!; // e.g. "## Title"
    final b = _selectionStrokeBounds(strokes);
    final box = _measureBox(prefixed, b.x, b.y, _pages[_pageIndex].data.width);
    final data = HeadingObject(recognizedText: prefixed, level: level);
    _replaceStrokesWith(strokes, HeadingRender(_uuid.v4(), box, data), 'heading', data.toJson());
  }

  /// Atomically swap a pure-stroke selection for the recognized [obj]; one undo step.
  void _replaceStrokesWith(
      List<StrokeObject> strokes, PageObject obj, String type, String dataJson) {
    final ids = [for (final s in strokes) s.id];
    widget.worker.softDelete(widget.notebookId, ids);
    widget.worker.insertObject(widget.notebookId, _layerId, obj.id, type, obj.box, dataJson);
    _objects.removeWhere(strokes.contains);
    _objects.add(obj);
    _record(UndoAction(
      undo: () {
        _objects.remove(obj);
        widget.worker.setDeleted(widget.notebookId, [obj.id], true);
        _objects.addAll(strokes);
        widget.worker.setDeleted(widget.notebookId, ids, false);
        _reselect(ids);
      },
      redo: () {
        _objects.removeWhere(strokes.contains);
        widget.worker.setDeleted(widget.notebookId, ids, true);
        _objects.add(obj);
        widget.worker.setDeleted(widget.notebookId, [obj.id], false);
        _reselect([obj.id]);
      },
    ));
    setState(() {
      _selection
        ..clear()
        ..add(obj);
      _selBounds = _computeSelBounds();
    });
    _repaintPanelNextFrame();
  }

  void _snack(String msg) => ScaffoldMessenger.of(context)
      .showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 2)));

  Future<int?> _chooseHeadingLevel() => showDialog<int>(
        context: context,
        // E-ink: no full-screen dim behind the dialog (the default black54 scrim reads as a shadow
        // over the page). The 1dp border carries the separation. See library_screen dialogs.
        barrierColor: Colors.transparent,
        builder: (ctx) => Dialog(
          backgroundColor: Colors.white,
          elevation: 0,
          shadowColor: Colors.transparent,
          surfaceTintColor: Colors.transparent,
          shape: RoundedRectangleBorder(
            side: const BorderSide(color: Colors.black, width: 1),
            borderRadius: BorderRadius.circular(4),
          ),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              const Text('Heading level',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.black)),
              const SizedBox(height: 12),
              Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                for (final lvl in const [1, 2, 3]) ...[
                  _btn('H$lvl', false, () => Navigator.pop(ctx, lvl)),
                  if (lvl != 3) const SizedBox(width: 8),
                ],
              ]),
            ]),
          ),
        ),
      );

  // ── Lines / clear / page nav ─────────────────────────────────────────────────

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
      widget.worker.insertObject(widget.notebookId, _layerId, id, 'line', pl.box, pl.line.toJson());
    }
    _pushInsert(created);
    setState(() {});
    _repaintPanelNextFrame();
  }

  void _clear() {
    if (_objects.isNotEmpty) {
      final cleared = List<PageObject>.of(_objects);
      widget.worker
          .softDelete(widget.notebookId, [for (final o in cleared) if (o.id.isNotEmpty) o.id]);
      _objects.clear();
      _pushDelete(cleared);
    }
    setState(() {
      _selection.clear();
      _selBounds = null;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.clear());
  }

  Future<void> _switchPage(int delta) async {
    final next = _pageIndex + delta;
    if (next < 0 || next >= _pages.length) return;
    _pageIndex = next;
    _undo.clear(); // undo is page-scoped for now (cross-page undo deferred)
    _histVersion.value++;
    _selection.clear();
    _selBounds = null;
    await _loadPage();
    _repaintPanelNextFrame();
  }

  Future<void> _addPage() async {
    final size = MediaQuery.of(context).size;
    final p = await widget.worker.addPage(widget.notebookId, size.width * _dpr, size.height * _dpr);
    _pages.add(p);
    _pageIndex = _pages.length - 1;
    _undo.clear();
    _histVersion.value++;
    _selection.clear();
    _selBounds = null;
    await _loadPage();
    _repaintPanelNextFrame();
  }

  // ── Build ─────────────────────────────────────────────────────────────────────

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
              child: LayoutBuilder(builder: (context, constraints) {
                return Stack(
                  children: [
                    Positioned.fill(
                      child: CustomPaint(
                        // The painter pads the outline itself, so pass the raw (unpadded) bounds.
                        painter: PagePainter(List.of(_objects), _dpr,
                            selection: () {
                              final raw = _rawBounds(_selection);
                              return raw != null ? [raw] : const <BoundingBox>[];
                            }(),
                            guidesV: _guidesV,
                            guidesH: _guidesH),
                      ),
                    ),
                    Positioned.fill(child: _surface()),
                    // Text mode: a transparent layer above the (suspended) Onyx surface captures the
                    // placement/edit tap.
                    if (_mode == _Mode.text)
                      Positioned.fill(
                        child: GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTapUp: _onPlacementTap,
                        ),
                      ),
                    // Selection active: the pen is suspended, so Flutter owns drag-to-move /
                    // tap-to-dismiss. (Loop-drawing in the lasso tool stays on the Onyx overlay and
                    // arrives via _onPen, so there is NO capture layer when nothing is selected.)
                    if (_selectionActive) ...[
                      Positioned.fill(
                        child: Listener(
                          behavior: HitTestBehavior.opaque,
                          onPointerDown: _selDown,
                          onPointerMove: _selMove,
                          onPointerUp: _selUp,
                        ),
                      ),
                      _floatingToolbar(constraints),
                    ],
                  ],
                );
              }),
            ),
          ],
        ),
      ),
    );
  }

  Widget _toolbar() {
    final tools = <TbButton>[
      TbButton('Pen', selected: _mode == _Mode.pen, onTap: () => _setMode(_Mode.pen)),
      TbButton('Eraser', selected: _mode == _Mode.eraser, onTap: () => _setMode(_Mode.eraser)),
      TbButton('Clear', onTap: _clear),
      TbButton('Text', selected: _mode == _Mode.text, onTap: () => _setMode(_Mode.text)),
      TbButton('Lasso', selected: _mode == _Mode.lasso, onTap: () => _setMode(_Mode.lasso)),
      TbButton('Lines', onTap: _insertLines),
      if (_clipboard.isNotEmpty) TbButton('Paste', onTap: _pasteClipboard),
    ];
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
          // Tool cluster: fills the middle, collapsing extras into a ⋯ overflow rather than scrolling.
          Expanded(child: OverflowToolbar(tools, height: 56)),
          const SizedBox(width: 8),
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

  /// A floating action bar anchored to the active selection (native's floating selection toolbar).
  /// Horizontally scrollable + width-bounded so a crowded selection never overflows the page.
  Widget _floatingToolbar(BoxConstraints constraints) {
    const barH = 46.0;
    final double leftL =
        (_selBounds!.x / _dpr).clamp(4.0, math.max(4.0, constraints.maxWidth - 120)).toDouble();
    final maxBarW = math.max(120.0, constraints.maxWidth - leftL - 4);
    final aboveTop = _selBounds!.y / _dpr - barH - 6;
    final belowTop = (_selBounds!.y + _selBounds!.height) / _dpr + 6;
    final double top = (aboveTop >= 4 ? aboveTop : belowTop)
        .clamp(4.0, math.max(4.0, constraints.maxHeight - barH))
        .toDouble();
    final canConvert = _selectionIsPureStrokes && Recognition.instance.supported && !_recognizing;
    final items = <TbButton>[
      TbButton('Snap', selected: _snapEnabled, onTap: _toggleSnap),
      if (_selectionIsPureStrokes && Recognition.instance.supported) ...[
        TbButton(_recognizing ? '…' : '→Text', enabled: canConvert, onTap: _convertToText),
        TbButton('→H', enabled: canConvert, onTap: () async {
          final lvl = await _chooseHeadingLevel();
          if (lvl != null) await _convertToHeading(lvl);
        }),
      ],
      if (_alignEligible) ...[
        TbButton('Align↓', onTap: () => _align(true)),
        TbButton('Align→', onTap: () => _align(false)),
      ],
      TbButton('Cut', onTap: _cutSelection),
      TbButton('Copy', onTap: _copySelection),
      TbButton('Delete', onTap: _deleteSelection),
    ];
    return Positioned(
      left: leftL,
      top: top,
      child: Container(
        height: barH,
        decoration: BoxDecoration(
          color: Colors.white,
          border: Border.all(color: Colors.black, width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6),
          child: ConstrainedBox(
            constraints: BoxConstraints(maxWidth: maxBarW - 12),
            child: OverflowToolbar(items, height: barH),
          ),
        ),
      ),
    );
  }

  void _toggleSnap() {
    setState(() => _snapEnabled = !_snapEnabled);
    AppSettings.instance.setSnapEnabled(_snapEnabled);
  }

  /// A momentary action button that dims its label to inkLight when [enabled] is false (a disabled
  /// *fill* would be invisible on e-ink; a lighter label reads clearly). Taps are ignored when off.
  Widget _actionBtn(String label, bool enabled, VoidCallback onTap) {
    return GestureDetector(
      onTap: enabled ? onTap : null,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
        decoration: BoxDecoration(
          color: Colors.white,
          border: Border.all(color: enabled ? Colors.black : const Color(0xFF888888), width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(label,
            style: TextStyle(
                color: enabled ? Colors.black : const Color(0xFF888888),
                fontSize: 15,
                fontWeight: FontWeight.w600)),
      ),
    );
  }

  Widget _surface() {
    // Off-BOOX: pure-Flutter ink. A finished stroke routes through the same _onPen path, tagged by
    // the current tool (eraser → 'erase'); lasso/smart-lasso are handled inside _onPen regardless.
    if (!_useOnyx) {
      return FlutterInkSurface(
        dpr: _dpr,
        strokeWidthPx: 3 * _dpr,
        onStroke: (pts, durationMs) => _onPen(
            PenEvent(_mode == _Mode.eraser ? 'erase' : 'stroke', pts, durationMs: durationMs)),
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
