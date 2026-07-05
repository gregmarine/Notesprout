import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:uuid/uuid.dart';

import 'dart:convert';
import 'dart:math' as math;
import 'dart:ui' as ui;

import '../core/lasso_geometry.dart';
import '../core/snap_engine.dart';
import '../core/toc.dart';
import '../core/markdown/markdown_parser.dart';
import '../core/markdown/markdown_render.dart';
import '../core/undo_manager.dart';
import '../data/app_settings.dart';
import '../data/toolbar_config.dart';
import '../data/db_worker.dart';
import '../recognition/handwriting_recognizer.dart';
import '../data/soil_database.dart';
import '../domain/objects.dart';
import '../domain/page_object.dart';
import '../domain/stroke.dart';
import '../platform/pen_bridge.dart';
import 'flutter_ink_surface.dart';
import 'customize_toolbar_dialog.dart';
import 'heading_dialog.dart';
import 'line_dialog.dart';
import 'nb_icons.dart';
import 'overflow_toolbar.dart';
import 'toolbar_registry.dart';
import 'page_painter.dart';
import 'recents_panel.dart';
import 'text_dialog.dart';
import 'toc_panel.dart';

const _uuid = Uuid();

/// The active editing mode. Pen/eraser keep the Onyx overlay live (fast e-ink ink); lasso and text
/// suspend it so Flutter can own pointer input (loop capture / selection drag / tap-to-place).
enum _Mode { pen, eraser, lasso, text }

/// Which mode the shared heading-type popover (H1/H2/H3) is in — mirrors native's `headingSubmenuMode`:
/// [create] converts a pure-stroke selection to a heading at the picked level; [change] re-levels an
/// existing single heading (its current level shown highlighted). Null → popover hidden.
enum _HeadingMenu { create, change }

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
  static const _kToolbarH = 56.0; // floating-toolbar height; also the pen exclude strip

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
  _HeadingMenu? _headingMenu; // shared H1/H2/H3 popover, anchored under the selection toolbar

  // Selection drag-move state.
  final Map<String, PageObject> _moveBefore = {};
  Offset _dragTotal = Offset.zero;
  bool _movingSel = false;
  bool _pendingDismiss = false;
  bool _selDownStylus = false; // the active selection touch is a stylus → a tap can open the editor
  int _lastPanelMs = 0; // throttle for live e-ink feedback during a drag

  // Snap-to-guide — default off; persisted device-local (mirrors native SnapPreferences).
  bool _snapEnabled = AppSettings.instance.snapEnabled;
  BoundingBox? _dragOriginBox; // unpadded selection bbox at drag start (snap anchors)
  List<double> _guidesV = const []; // active vertical snap guides (px) during a drag
  List<double> _guidesH = const [];

  double _dpr = 1.0;
  bool _ready = false;

  // Page template (read-only): decoded bitmap cache keyed by template-row id + the current page's.
  final Map<String, ui.Image?> _templateCache = {};
  ui.Image? _template;

  // Finger-swipe gesture state (touch pointers only; stylus draws via Onyx). 1 finger flips pages,
  // 2 fingers inserts a page.
  int? _swipeFinger;
  Offset _swipeStart = Offset.zero;
  Offset _swipeLast = Offset.zero;
  int _activeTouches = 0;
  int _peakTouches = 0;
  bool _pageBusy = false; // guards page flip/insert against re-entrancy mid-gesture

  // Double-tap gesture state (touch only). A stationary, short tap that is NOT a swipe arms a
  // per-finger-count first-tap memory; a matching second tap within the double-tap window fires:
  // 1 finger = toggle toolbar · 2 fingers = undo · 3 fingers = redo (native's multi-finger taps).
  Duration _gestureDownTime = Duration.zero; // first pointer-down of the current gesture
  bool _gestureMoved = false; // latched once the primary finger travels past the tap slop
  Duration? _tap1Time, _tap2Time, _tap3Time;
  Offset _tap1Pos = Offset.zero, _tap2Pos = Offset.zero, _tap3Pos = Offset.zero;
  bool _toolbarHidden = false; // 1-finger double-tap hides the floating toolbar; pen reclaims strip
  double _toolbarExtent = _kToolbarH; // live toolbar cross-axis extent (grows when overflow opens)
  bool _overflowOpen = false; // secondary overflow row visible; dismissed on any page interaction
  ToolbarConfig _tbConfig = AppSettings.instance.toolbarConfig; // customized toolbar (device-local)
  Offset? _floatPos; // live top-left of the floating bar (null → center on first layout)
  final GlobalKey _floatKey = GlobalKey(); // measures the float bar's rect for the pen exclusion
  Rect? _floatRect; // last measured float-bar rect (logical) — exclusion + page hit-test source

  static const double _kTapSlopPx = 18; // logical px; movement past this → not a tap
  static const double _kDoubleTapSlopPx = 48; // second tap must land within this of the first
  static const int _kTapMaxMs = 500; // a tap must lift within this (long-press timeout)
  static const int _kDoubleTapMaxMs = 300; // second tap must follow within this window

  String get _layerId => _pages[_pageIndex].layerId;

  /// BOOX Android hosts the Onyx EPD overlay; every other platform (desktop now, iPad/web later)
  /// draws with the pure-Flutter [FlutterInkSurface] and skips all EPD/bridge handoffs.
  bool get _useOnyx => defaultTargetPlatform == TargetPlatform.android;

  bool get _selectionActive => _mode == _Mode.lasso && _selection.isNotEmpty;

  @override
  void initState() {
    super.initState();
    // Full-screen immersive page, matching native NotebookActivity (hide system bars; swipe reveals).
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    _bridge.onEvent = _onPen;
    if (_tbConfig.floatX >= 0) _floatPos = Offset(_tbConfig.floatX, _tbConfig.floatY);
    AppSettings.instance.recordRecentOpen(widget.notebookId); // device-local recents (fire-and-forget)
    _init();
  }

  Future<void> _init() async {
    _pages = await widget.worker.openNotebook(widget.notebookId);
    await _loadPage();
    if (mounted) setState(() => _ready = true);
  }

  @override
  void dispose() {
    // Note: system bars are restored by the library (via appRouteObserver.didPopNext), NOT here —
    // doing it in dispose would clobber an incoming notebook's immersive mode during a Recents switch.
    _bridge.dispose();
    _histVersion.dispose();
    widget.worker.closeNotebook(widget.notebookId, _pages.length);
    AppSettings.instance.recordRecentClose(widget.notebookId); // refresh recents timestamp
    super.dispose();
  }

  Future<void> _loadPage() async {
    final rows = await widget.worker.objects(widget.notebookId, _layerId);
    _objects
      ..clear()
      ..addAll(rows);
    if (mounted) setState(() {});
    await _loadTemplate();
  }

  /// Load the current page's template bitmap (read-only). Decoded images are cached by template id
  /// so flipping between pages that share a template doesn't re-decode.
  Future<void> _loadTemplate() async {
    final tid = _pages[_pageIndex].data.template;
    if (tid.isEmpty) {
      if (mounted) setState(() => _template = null);
      return;
    }
    if (_templateCache.containsKey(tid)) {
      if (mounted) setState(() => _template = _templateCache[tid]);
      return;
    }
    ui.Image? img;
    final b64 = await widget.worker.templateImage(widget.notebookId, tid);
    if (b64 != null) {
      try {
        final codec = await ui.instantiateImageCodec(base64Decode(b64));
        img = (await codec.getNextFrame()).image;
      } catch (_) {
        img = null;
      }
    }
    _templateCache[tid] = img;
    if (mounted) setState(() => _template = img);
  }

  void _repaintPanelNextFrame() =>
      WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());

  // ── Pen input ────────────────────────────────────────────────────────────────

  void _onPen(PenEvent e) {
    if (!_ready) return;
    // Any stylus stroke on the page dismisses an open overflow row (it draws below the toolbar).
    _dismissOverflow();
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
      _headingMenu = null;
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
      _headingMenu = null;
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
    if (_headingMenu != null) setState(() => _headingMenu = null); // any touch closes the popover
    _selDownStylus =
        e.kind == PointerDeviceKind.stylus || e.kind == PointerDeviceKind.invertedStylus;
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
      } else if (_selDownStylus) {
        // A stylus tap (no drag) on a single selected heading/text opens its editor.
        _maybeEditOnTap(e.localPosition);
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
  Future<void> _onPlacementTap(Offset localPosition) async {
    final px = localPosition.dx * _dpr;
    final py = localPosition.dy * _dpr;
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

  /// The single selected heading, or null — drives the change-level popover button (native's
  /// `btnHeadingMenu`, shown only when the selection is exactly one heading).
  HeadingRender? get _selectedHeading =>
      _selection.length == 1 && _selection.first is HeadingRender
          ? _selection.first as HeadingRender
          : null;

  /// Re-level an existing heading (port of native `changeHeadingLevel`): re-prefix its recognizedText
  /// via `applyLevel` (null stays null → a stroke-only heading keeps its box), re-measure for text
  /// headings, apply as one undo step, and keep it selected.
  void _changeHeadingLevel(HeadingRender heading, int newLevel) {
    if (heading.data.level == newLevel) return;
    final newText = HeadingObject.applyLevel(heading.data.recognizedText, newLevel); // null stays null
    final box = newText == null
        ? heading.box
        : _measureBox(newText, heading.box.x, heading.box.y, _pages[_pageIndex].data.width);
    final after =
        HeadingRender(heading.id, box, HeadingObject(recognizedText: newText, level: newLevel));
    _replaceObject(after);
    widget.worker.updateObject(widget.notebookId, heading.id, box, after.data.toJson());
    _pushUpdate(heading, after);
    setState(() {
      _reselect([heading.id]);
      _headingMenu = null;
    });
    _repaintPanelNextFrame();
  }

  /// A level picked from the shared H1/H2/H3 popover: create a heading from strokes, or re-level the
  /// selected heading, per [_headingMenu] mode.
  Future<void> _pickHeadingLevel(int level) async {
    final mode = _headingMenu;
    if (mode == _HeadingMenu.change) {
      final h = _selectedHeading;
      setState(() => _headingMenu = null);
      if (h != null) _changeHeadingLevel(h, level);
    } else {
      setState(() => _headingMenu = null);
      await _convertToHeading(level);
    }
  }

  // ── Tap-to-edit a selected heading / text object ─────────────────────────────
  /// A stylus tap inside a single selected heading/text object opens its editor (native's tap-on-
  /// selected-object edit). Multi-object selections and taps outside the object's box are ignored.
  void _maybeEditOnTap(Offset localPosition) {
    if (_selection.length != 1) return;
    final o = _selection.first;
    final px = localPosition.dx * _dpr, py = localPosition.dy * _dpr;
    if (!_inBox(o.box, px, py)) return;
    if (o is HeadingRender) {
      _editHeading(o);
    } else if (o is TextRender) {
      _editText(o);
    }
  }

  /// Edit a heading's text via the simple dialog. The field shows only the words (no `#` prefix); the
  /// level stays controlled by the H1/H2/H3 popover, and the prefix is re-applied canonically on save
  /// so the stored text and level always agree (port of native `updateHeadingText`).
  Future<void> _editHeading(HeadingRender h) async {
    final result =
        await showHeadingDialog(context, initial: HeadingObject.stripHeadingPrefix(h.data.recognizedText ?? ''));
    if (result == null || !mounted) return;
    final words = HeadingObject.stripHeadingPrefix(result.trim()); // tolerate a typed prefix
    if (words.isEmpty) return; // native: blank → no change
    final prefixed = HeadingObject.applyLevel(words, h.data.level)!;
    final box = _measureBox(prefixed, h.box.x, h.box.y, _pages[_pageIndex].data.width);
    final after = HeadingRender(h.id, box, HeadingObject(recognizedText: prefixed, level: h.data.level));
    _replaceObject(after);
    widget.worker.updateObject(widget.notebookId, h.id, box, after.data.toJson());
    _pushUpdate(h, after);
    setState(() => _reselect([h.id]));
    _repaintPanelNextFrame();
  }

  /// Edit a text object via the same markdown dialog used to create one. Empty result deletes it.
  Future<void> _editText(TextRender t) async {
    final result = await showTextDialog(context, initial: t.data.text);
    if (result == null || !mounted) return; // cancelled
    if (result.isEmpty) {
      widget.worker.softDelete(widget.notebookId, [t.id]);
      _objects.remove(t);
      _pushDelete([t]);
      _endSelection(toPen: false);
    } else {
      final box = _measureBox(result, t.box.x, t.box.y, _pages[_pageIndex].data.width);
      final after = TextRender(t.id, box, TextObject(text: result, strokes: t.data.strokes));
      _replaceObject(after);
      widget.worker.updateObject(widget.notebookId, t.id, box, after.data.toJson());
      _pushUpdate(t, after);
      setState(() => _reselect([t.id]));
    }
    _repaintPanelNextFrame();
  }

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
    if (_pageBusy) return;
    final next = _pageIndex + delta;
    if (next < 0 || next >= _pages.length) return;
    _pageBusy = true;
    _pageIndex = next;
    _undo.clear(); // undo is page-scoped for now (cross-page undo deferred)
    _histVersion.value++;
    _selection.clear();
    _selBounds = null;
    await _loadPage();
    _repaintPanelNextFrame();
    _pageBusy = false;
  }

  // ── Finger swipes: 1-finger flip / 2-finger insert (touch only; stylus draws via Onyx) ──────
  void _onSwipeDown(PointerDownEvent e) {
    if (e.kind != PointerDeviceKind.touch) return;
    // A finger touch on the page (below the toolbar) dismisses an open overflow row. Touches within
    // the toolbar band fall through to its buttons and must NOT dismiss.
    if (_overflowOpen && _pointOnPage(e.localPosition)) _dismissOverflow();
    _activeTouches++;
    if (_activeTouches > _peakTouches) _peakTouches = _activeTouches;
    if (_swipeFinger == null) {
      _swipeFinger = e.pointer;
      _swipeStart = e.localPosition;
      _swipeLast = e.localPosition;
      _gestureDownTime = e.timeStamp;
      _gestureMoved = false;
    }
  }

  void _onSwipeMove(PointerMoveEvent e) {
    if (e.kind != PointerDeviceKind.touch || e.pointer != _swipeFinger) return;
    _swipeLast = e.localPosition;
    if (!_gestureMoved && (e.localPosition - _swipeStart).distance > _kTapSlopPx) {
      _gestureMoved = true; // travelled too far to be a tap
    }
  }

  void _onSwipeEnd(PointerEvent e) {
    if (e.kind != PointerDeviceKind.touch) return;
    if (_activeTouches > 0) _activeTouches--;
    if (_activeTouches > 0) return; // wait until all fingers lift
    final peak = _peakTouches;
    final moved = _gestureMoved;
    final durMs = (e.timeStamp - _gestureDownTime).inMilliseconds;
    final endPos = _swipeLast;
    final dx = _swipeLast.dx - _swipeStart.dx;
    final dy = _swipeLast.dy - _swipeStart.dy;
    _swipeFinger = null;
    _peakTouches = 0;
    _activeTouches = 0;
    _gestureMoved = false;

    // BOOX intercepts 3-finger touches and cancels the gesture (no UP) — treat a stationary,
    // short 3-finger cancel as a completed tap so double-tap redo still works (matches native).
    if (e is PointerCancelEvent) {
      if (peak == 3 && !moved && durMs <= _kTapMaxMs) {
        _handleTap(3, endPos, e.timeStamp);
      } else {
        _resetTaps();
      }
      return;
    }

    final size = MediaQuery.of(context).size;
    // Horizontal-dominant swipe past the min-distance fraction → flip / insert.
    if (dx.abs() > dy.abs() && dx.abs() / size.width >= 0.30) {
      _resetTaps();
      if (peak == 1) {
        _switchPage(dx < 0 ? 1 : -1); // left → next page, right → prev
      } else if (peak >= 2) {
        _insertPageBySwipe(after: dx < 0); // left → insert after, right → insert before
      }
      return;
    }
    // 1-finger swipe DOWN (vertical-dominant, past the min-distance fraction) → Table of Contents.
    if (peak == 1 && dy > 0 && dy.abs() > dx.abs() && dy.abs() / size.height >= 0.30) {
      _resetTaps();
      _openToc();
      return;
    }

    // Otherwise: a stationary short touch is a tap → feed the double-tap detector.
    if (!moved && durMs <= _kTapMaxMs) {
      _handleTap(peak, endPos, e.timeStamp);
    } else {
      _resetTaps();
    }
  }

  /// A completed tap with [count] fingers. A second tap of the same count within the double-tap
  /// window (and near the first) fires the action; otherwise this becomes the new first tap.
  void _handleTap(int count, Offset pos, Duration now) {
    if (count < 1 || count > 3) {
      _resetTaps();
      return;
    }
    final (Duration? lastTime, Offset lastPos) = switch (count) {
      1 => (_tap1Time, _tap1Pos),
      2 => (_tap2Time, _tap2Pos),
      _ => (_tap3Time, _tap3Pos),
    };
    final isDouble = lastTime != null &&
        (now - lastTime).inMilliseconds <= _kDoubleTapMaxMs &&
        (pos - lastPos).distance <= _kDoubleTapSlopPx;
    _resetTaps();
    if (isDouble) {
      switch (count) {
        case 1:
          _toggleToolbar();
        case 2:
          _doUndo();
        default:
          _doRedo();
      }
    } else {
      switch (count) {
        case 1:
          _tap1Time = now;
          _tap1Pos = pos;
        case 2:
          _tap2Time = now;
          _tap2Pos = pos;
        default:
          _tap3Time = now;
          _tap3Pos = pos;
      }
    }
  }

  void _resetTaps() {
    _tap1Time = _tap2Time = _tap3Time = null;
  }

  ToolbarPlacement get _placement => _tbConfig.placement;
  bool get _isFloat => _placement == ToolbarPlacement.float;
  bool get _tbVertical => _isFloat
      ? _tbConfig.floatAxis == ToolbarAxis.vertical
      : (_placement == ToolbarPlacement.left || _placement == ToolbarPlacement.right);
  bool get _tbMini => _tbConfig.miniEnabled && _isFloat;

  /// 1-finger double-tap: hide/show the toolbar. When hidden the pen reclaims the whole screen;
  /// when shown the bar's rect is excluded again so stylus taps hit the buttons.
  void _toggleToolbar() {
    setState(() => _toolbarHidden = !_toolbarHidden);
    _applyToolbarExclusion();
  }

  /// Reported by [StackedToolbar] whenever its cross-axis extent changes (overflow open/closed).
  void _onToolbarHeight(double ext) {
    if (ext == _toolbarExtent) return;
    _toolbarExtent = ext;
    _applyToolbarExclusion();
  }

  /// Exclude the toolbar's rectangle from the pen region per placement (cleared when hidden).
  void _applyToolbarExclusion() {
    if (_toolbarHidden || !mounted) {
      _bridge.setToolbarExclusion(0, 0, 0, 0);
      return;
    }
    if (_isFloat) {
      // The float bar's rect is measured after layout (its size hugs content); see _measureFloat.
      final r = _floatRect;
      if (r == null) {
        _bridge.setToolbarExclusion(0, 0, 0, 0);
      } else {
        _bridge.setToolbarExclusion(
            r.left * _dpr, r.top * _dpr, r.right * _dpr, r.bottom * _dpr);
      }
      return;
    }
    final size = MediaQuery.of(context).size;
    final ext = _toolbarExtent * _dpr;
    final wPx = size.width * _dpr, hPx = size.height * _dpr;
    final (double l, double t, double r, double b) = switch (_placement) {
      ToolbarPlacement.top => (0, 0, wPx, ext),
      ToolbarPlacement.bottom => (0, hPx - ext, wPx, hPx),
      ToolbarPlacement.left => (0, 0, ext, hPx),
      ToolbarPlacement.right => (wPx - ext, 0, wPx, hPx),
      ToolbarPlacement.float => (0, 0, 0, 0),
    };
    _bridge.setToolbarExclusion(l, t, r, b);
  }

  /// True if [p] (logical, screen coords) lands on the page — outside the toolbar band.
  bool _pointOnPage(Offset p) {
    final size = MediaQuery.of(context).size;
    final ext = _toolbarExtent;
    return switch (_placement) {
      ToolbarPlacement.top => p.dy >= ext,
      ToolbarPlacement.bottom => p.dy <= size.height - ext,
      ToolbarPlacement.left => p.dx >= ext,
      ToolbarPlacement.right => p.dx <= size.width - ext,
      ToolbarPlacement.float => !(_floatRect?.contains(p) ?? false),
    };
  }

  Future<void> _insertPageBySwipe({required bool after}) async {
    if (_pageBusy || !_ready) return;
    _pageBusy = true;
    final ref = _pages[_pageIndex];
    final size = MediaQuery.of(context).size;
    final w = ref.data.width > 0 ? ref.data.width : size.width * _dpr;
    final h = ref.data.height > 0 ? ref.data.height : size.height * _dpr;
    final created = await widget.worker.insertPage(widget.notebookId, ref.pageId, !after, w, h);
    _pages = await widget.worker.openNotebook(widget.notebookId);
    final i = _pages.indexWhere((p) => p.pageId == created.pageId);
    _pageIndex = i < 0 ? _pageIndex : i;
    _undo.clear();
    _histVersion.value++;
    _selection.clear();
    _selBounds = null;
    await _loadPage();
    _repaintPanelNextFrame();
    _pageBusy = false;
    if (mounted) _snack(after ? 'Inserted page after' : 'Inserted page before');
  }

  // ── Build ─────────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    _dpr = MediaQuery.of(context).devicePixelRatio;
    return Scaffold(
      backgroundColor: Colors.white,
      // The page is full-screen; the toolbar floats over it (matching native), and the pen region
      // excludes the toolbar strip (setToolbarInset) so stylus taps hit the buttons.
      body: Stack(
        children: [
          Positioned.fill(
            child: LayoutBuilder(builder: (context, constraints) {
              return Stack(
                children: [
                  Positioned.fill(
                    child: CustomPaint(
                        // The painter pads the outline itself, so pass the raw (unpadded) bounds.
                        painter: PagePainter(List.of(_objects), _dpr,
                            template: _template,
                            selection: () {
                              final raw = _rawBounds(_selection);
                              return raw != null ? [raw] : const <BoundingBox>[];
                            }(),
                            guidesV: _guidesV,
                            guidesH: _guidesH),
                      ),
                    ),
                    Positioned.fill(child: _surface()),
                    // Finger-swipe layer: translucent + touch-filtered, so the stylus still reaches
                    // Onyx and the surface below. Sits under the text/selection overlays, which take
                    // priority when present. 1-finger = flip page, 2-finger = insert page.
                    Positioned.fill(
                      child: Listener(
                        behavior: HitTestBehavior.translucent,
                        onPointerDown: _onSwipeDown,
                        onPointerMove: _onSwipeMove,
                        onPointerUp: _onSwipeEnd,
                        onPointerCancel: _onSwipeEnd,
                      ),
                    ),
                    // Text mode: a STYLUS-ONLY layer above the (suspended) Onyx surface captures the
                    // placement/edit tap. It's translucent and restricted to stylus so finger touches
                    // fall through to the gesture layer below (page swipes / double-tap toolbar etc.).
                    if (_mode == _Mode.text)
                      Positioned.fill(
                        child: GestureDetector(
                          behavior: HitTestBehavior.translucent,
                          supportedDevices: const {
                            PointerDeviceKind.stylus,
                            PointerDeviceKind.invertedStylus,
                          },
                          onTapUp: (d) => _onPlacementTap(d.localPosition),
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
            // The toolbar floats over the full-screen page (the pen region excludes this strip).
            // 1-finger double-tap hides it; the pen then reclaims the full screen.
            if (!_toolbarHidden) _positionedToolbar(),
          ],
        ),
      );
  }

  void _comingSoon(String label) => _snack('$label — coming soon');

  /// A [TbButton] for a registry [key], wiring its behaviour + selected/enabled state. Features not
  /// yet ported toast "coming soon".
  TbButton _buttonForKey(String key) {
    final spec = ToolbarRegistry.spec(key)!;
    final icon = spec.icon;
    final label = spec.label;
    final m = _mode;
    switch (key) {
      case 'close':
        return TbButton(label, icon: icon, onTap: () => Navigator.of(context).pop());
      case 'pen':
        return TbButton(label, icon: icon, selected: m == _Mode.pen, onTap: () => _setMode(_Mode.pen));
      case 'eraser':
        return TbButton(label,
            icon: icon, selected: m == _Mode.eraser, onTap: () => _setMode(_Mode.eraser));
      case 'eraseAll':
        return TbButton(label, icon: icon, onTap: _clear);
      case 'insertText':
        return TbButton(label,
            icon: icon, selected: m == _Mode.text, onTap: () => _setMode(_Mode.text));
      case 'insertLines':
        return TbButton(label, icon: icon, onTap: _insertLines);
      case 'lasso':
        return TbButton(label,
            icon: icon, selected: m == _Mode.lasso, onTap: () => _setMode(_Mode.lasso));
      case 'undo':
        return TbButton(label, icon: icon, enabled: _undo.canUndo, onTap: _doUndo);
      case 'redo':
        return TbButton(label, icon: icon, enabled: _undo.canRedo, onTap: _doRedo);
      case 'insertPageBefore':
        return TbButton(label, icon: icon, onTap: () => _insertPageBySwipe(after: false));
      case 'insertPageAfter':
        return TbButton(label, icon: icon, onTap: () => _insertPageBySwipe(after: true));
      case 'toc':
        return TbButton(label, icon: icon, onTap: _openToc);
      case 'recents':
        return TbButton(label, icon: icon, onTap: _openRecents);
      case 'toolbarSettings':
        return TbButton(label, icon: icon, onTap: _openCustomizeToolbar);
      default:
        return TbButton(label, icon: icon, onTap: () => _comingSoon(label));
    }
  }

  /// Build the middle button run from resolved [keys], inserting an auto-divider between buttons
  /// whose registry group differs.
  List<TbButton> _middleItems(List<String> keys) {
    final out = <TbButton>[];
    String? prevGroup;
    for (final k in keys) {
      final spec = ToolbarRegistry.spec(k);
      if (spec == null) continue;
      if (prevGroup != null && spec.group != prevGroup) out.add(const TbButton.divider());
      out.add(_buttonForKey(k));
      prevGroup = spec.group;
    }
    return out;
  }

  /// Build the ToC tree off the worker and show the panel; navigate to the picked page. The pen is
  /// suspended while the panel is up so a stylus can't draw on the canvas behind it.
  Future<void> _openToc() async {
    if (!_ready) return;
    final headings = await widget.worker.headings(widget.notebookId);
    if (!mounted) return;
    final nodes = buildTocTree(headings, _pages);
    await _bridge.setDrawingEnabled(false);
    if (!mounted) {
      await _bridge.setDrawingEnabled(true);
      return;
    }
    final pageId = await showTocPanel(context, nodes, _pageIndex);
    await _bridge.setDrawingEnabled(true);
    if (pageId == null || !mounted) return;
    final idx = _pages.indexWhere((p) => p.pageId == pageId);
    if (idx >= 0 && idx != _pageIndex) await _switchPage(idx - _pageIndex);
  }

  /// Recent Notebooks switcher: resolve the device-local list (excluding the open notebook), let the
  /// user pick one, and switch to it via `pushReplacement` (this screen's dispose closes the current
  /// notebook + refreshes its recents timestamp; the new screen records its own open).
  Future<void> _openRecents() async {
    if (!_ready) return;
    final entries =
        AppSettings.instance.recents.where((e) => e.notebookId != widget.notebookId).toList();
    final resolved = await widget.worker.resolveRecents(entries);
    if (!mounted) return;
    // Prune any entries the index couldn't resolve (notebook deleted/missing).
    final live = resolved.map((r) => r.notebookId).toSet();
    final missing = entries.map((e) => e.notebookId).where((id) => !live.contains(id));
    if (missing.isNotEmpty) AppSettings.instance.pruneRecents(missing);

    await _bridge.setDrawingEnabled(false);
    if (!mounted) {
      await _bridge.setDrawingEnabled(true);
      return;
    }
    final picked = await showRecentsPanel(context, resolved);
    await _bridge.setDrawingEnabled(true);
    if (picked == null || !mounted) return;
    Navigator.of(context).pushReplacement(MaterialPageRoute(
      builder: (_) => NotebookScreen(
        worker: widget.worker,
        notebookId: picked.notebookId,
        title: picked.name,
      ),
    ));
  }

  Future<void> _openCustomizeToolbar() async {
    final updated = await showCustomizeToolbarDialog(context, _tbConfig);
    if (updated == null || !mounted) return;
    setState(() {
      _tbConfig = updated;
      _floatPos = updated.floatX >= 0 ? Offset(updated.floatX, updated.floatY) : null;
      _floatRect = null; // force a re-measure/centre for the (possibly new) float bar
    });
    await AppSettings.instance.setToolbarConfig(updated);
    _applyToolbarExclusion(); // placement may have changed → re-exclude the correct rect
  }

  /// The customizable notebook toolbar, resolved from [_tbConfig] (order − hidden; Close pinned at
  /// the lead, Customize gear at the trail). Rebuilds on history changes so Undo/Redo enablement
  /// stays live (stroke commits deliberately skip setState).
  Widget _toolbar() {
    final keys = ToolbarRegistry.resolveVisible(
      order: _tbConfig.order,
      hidden: _tbConfig.hidden,
      miniSet: _tbConfig.miniSet,
      mini: _tbMini,
    );
    final leadKey = keys.first; // close (guaranteed by resolveVisible)
    final trailKey = keys.last; // gear
    final middle = _middleItems(keys.sublist(1, keys.length - 1));
    // Object clipboard (populated by a selection Copy) — contextual, not part of the customized set.
    if (_clipboard.isNotEmpty) {
      middle
        ..add(const TbButton.divider())
        ..add(TbButton('Paste Selection', icon: NbIcons.pastePage, onTap: _pasteClipboard));
    }
    final float = _isFloat;
    const side = BorderSide(color: Colors.black, width: 1);
    final border = switch (_placement) {
      ToolbarPlacement.top => const Border(bottom: side),
      ToolbarPlacement.bottom => const Border(top: side),
      ToolbarPlacement.left => const Border(right: side),
      ToolbarPlacement.right => const Border(left: side),
      ToolbarPlacement.float => const Border(), // outer float container carries the border
    };
    // The overflow line sits on the page-facing side of the bar (before the main line for BOTTOM/RIGHT).
    final secondaryBefore =
        _placement == ToolbarPlacement.bottom || _placement == ToolbarPlacement.right;
    return ValueListenableBuilder<int>(
      valueListenable: _histVersion,
      builder: (context, _, _) => StackedToolbar(
        leading: [_buttonForKey(leadKey)],
        items: middle,
        trailing: [_buttonForKey(trailKey)],
        trailingLabel: float ? null : (_ready ? '${_pageIndex + 1}/${_pages.length}' : '…'),
        axis: _tbVertical ? Axis.vertical : Axis.horizontal,
        mainBorder: border,
        secondaryBefore: secondaryBefore,
        spread: !float, // a floating bar hugs its content
        expanded: _overflowOpen,
        onExpandedChanged: (v) => setState(() => _overflowOpen = v),
        rowHeight: _kToolbarH,
        spacing: 3,
        onHeight: _onToolbarHeight,
      ),
    );
  }

  /// Position the toolbar at its anchored edge (full-screen page behind it), or as a draggable
  /// floating bar.
  Widget _positionedToolbar() => switch (_placement) {
        ToolbarPlacement.top => Positioned(top: 0, left: 0, right: 0, child: _toolbar()),
        ToolbarPlacement.bottom => Positioned(bottom: 0, left: 0, right: 0, child: _toolbar()),
        ToolbarPlacement.left => Positioned(top: 0, bottom: 0, left: 0, child: _toolbar()),
        ToolbarPlacement.right => Positioned(top: 0, bottom: 0, right: 0, child: _toolbar()),
        ToolbarPlacement.float => _floatPositioned(),
      };

  // ── Floating toolbar (draggable, position-persisted) ────────────────────────────────────────
  Widget _floatPositioned() {
    final p = _floatPos;
    return Positioned(left: p?.dx ?? 0, top: p?.dy ?? 0, child: _floatBar());
  }

  Widget _floatBar() {
    final axis = _tbVertical ? Axis.vertical : Axis.horizontal;
    final size = MediaQuery.of(context).size;
    // Bound the bar to 85% of the matching screen dimension so a long full bar overflows internally.
    final maxMain = (axis == Axis.horizontal ? size.width : size.height) * 0.85;
    WidgetsBinding.instance.addPostFrameCallback((_) => _measureFloat());
    // ClipRRect clips the white content (grip divider / toolbar bg) to the rounded rect; the border
    // is drawn on top via foregroundDecoration so no square corner pokes out.
    return Container(
      key: _floatKey,
      foregroundDecoration: BoxDecoration(
        border: Border.all(color: Colors.black, width: 1),
        borderRadius: BorderRadius.circular(6),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(6),
        child: ColoredBox(
          color: Colors.white,
          child: Flex(
            direction: axis,
            mainAxisSize: MainAxisSize.min,
            children: [
              _floatGrip(axis),
              ConstrainedBox(
                constraints: BoxConstraints(
                  maxWidth: axis == Axis.horizontal ? maxMain : double.infinity,
                  maxHeight: axis == Axis.vertical ? maxMain : double.infinity,
                ),
                child: _toolbar(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _floatGrip(Axis axis) {
    const side = BorderSide(color: Colors.black, width: 1);
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onPanUpdate: _onFloatDrag,
      onPanEnd: (_) => _persistFloatPos(),
      child: Container(
        width: axis == Axis.horizontal ? 30 : _kToolbarH,
        height: axis == Axis.horizontal ? _kToolbarH : 30,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          border: axis == Axis.horizontal
              ? const Border(right: side)
              : const Border(bottom: side),
        ),
        child: const Icon(Icons.drag_indicator, size: 22, color: Color(0xFF888888)),
      ),
    );
  }

  void _onFloatDrag(DragUpdateDetails d) {
    final size = MediaQuery.of(context).size;
    final w = _floatRect?.width ?? 200, h = _floatRect?.height ?? _kToolbarH;
    final cur = _floatPos ?? Offset((size.width - w) / 2, (size.height - h) / 2);
    final np = cur + d.delta;
    setState(() => _floatPos = Offset(
          np.dx.clamp(0.0, math.max(0.0, size.width - w)),
          np.dy.clamp(0.0, math.max(0.0, size.height - h)),
        ));
  }

  void _persistFloatPos() {
    final p = _floatPos;
    if (p == null) return;
    _tbConfig = _tbConfig.copyWith(floatX: p.dx, floatY: p.dy);
    AppSettings.instance.setToolbarConfig(_tbConfig);
  }

  /// Measure the floating bar's rect (logical) after layout → centre it on first show, then keep the
  /// pen exclusion in sync with its live position/size.
  void _measureFloat() {
    if (!mounted || !_isFloat) return;
    final box = _floatKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize) return;
    final rect = box.localToGlobal(Offset.zero) & box.size;
    if (_floatPos == null) {
      final size = MediaQuery.of(context).size;
      setState(() => _floatPos =
          Offset((size.width - rect.width) / 2, (size.height - rect.height) / 2));
      return; // re-measures next frame at the centred position
    }
    if (_floatRect != rect) {
      _floatRect = rect;
      _applyToolbarExclusion();
    }
  }

  /// Collapse the secondary overflow row (called on any page interaction — finger tap below the
  /// toolbar, or a committed stylus stroke).
  void _dismissOverflow() {
    if (_overflowOpen) setState(() => _overflowOpen = false);
  }

  /// A floating action bar anchored to the active selection (native's floating selection toolbar) —
  /// icon buttons in native order/visibility, width-bounded so a crowded selection overflows into the
  /// "⋯" menu rather than off-page. Below/above it, the shared H1/H2/H3 popover when open.
  Widget _floatingToolbar(BoxConstraints constraints) {
    const barH = 46.0;
    const chrome = 14.0; // container horizontal padding (6×2) + 1dp border each side
    const rowInset = 7.0; // border(1) + padding(6): row's left edge inside the bar
    // Keep clear of the floating main toolbar strip at the top (unless it's hidden).
    // Only the TOP bar overlaps the selection toolbar's anchor zone; clear it then.
    final minTop = (_toolbarHidden || _placement != ToolbarPlacement.top) ? 4.0 : _toolbarExtent + 4;
    final aboveTop = _selBounds!.y / _dpr - barH - 6;
    final belowTop = (_selBounds!.y + _selBounds!.height) / _dpr + 6;
    final double top = (aboveTop >= minTop ? aboveTop : belowTop)
        .clamp(minTop, math.max(minTop, constraints.maxHeight - barH))
        .toDouble();
    // Centre on the RAW content bounds — the same box PagePainter draws — not the (possibly lasso-
    // shaped, off-centre) padded _selBounds.
    final raw = _rawBounds(_selection) ?? _selBounds!;
    final selCenter = (raw.x + raw.width / 2) / _dpr;
    // Widest a centred bar can be: page minus 4px side margins.
    final maxContentW = math.max(60.0, constraints.maxWidth - 8 - chrome);
    // Left edge for a bar of [items] centred on [centerX], clamped to the page. Width comes from the
    // TRUE rendered content (toolbarContentWidth), not OverflowToolbar's divider-inflated estimate.
    double leftFor(List<TbButton> items, double centerX) {
      final w = math.min(toolbarContentWidth(items), maxContentW) + chrome;
      return (centerX - w / 2).clamp(4.0, math.max(4.0, constraints.maxWidth - w - 4)).toDouble();
    }

    final canConvert = _selectionIsPureStrokes && Recognition.instance.supported && !_recognizing;
    final heading = _selectedHeading;
    final items = <TbButton>[
      TbButton('Copy', icon: NbIcons.lassoCopy, onTap: _copySelection),
      TbButton('Cut', icon: NbIcons.lassoCut, onTap: _cutSelection),
      TbButton('Delete', icon: NbIcons.lassoDelete, onTap: _deleteSelection),
      // Pure-stroke selection → convert to heading / text (native btnMakeHeading + btnConvertText).
      if (_selectionIsPureStrokes && Recognition.instance.supported) ...[
        const TbButton.divider(),
        TbButton('Make Heading',
            icon: NbIcons.heading,
            enabled: canConvert,
            selected: _headingMenu == _HeadingMenu.create,
            onTap: () => setState(() =>
                _headingMenu = _headingMenu == _HeadingMenu.create ? null : _HeadingMenu.create)),
        const TbButton.divider(),
        TbButton('Convert to Text',
            icon: NbIcons.convertText, enabled: canConvert, onTap: _convertToText),
      ],
      // Single existing heading → change level (native btnHeadingMenu).
      if (heading != null) ...[
        const TbButton.divider(),
        TbButton('Change heading level',
            icon: NbIcons.heading,
            selected: _headingMenu == _HeadingMenu.change,
            onTap: () => setState(() =>
                _headingMenu = _headingMenu == _HeadingMenu.change ? null : _HeadingMenu.change)),
      ],
      // ≥2 non-stroke objects → align & distribute (native btnAlignLeft + btnAlignTop).
      if (_alignEligible) ...[
        const TbButton.divider(),
        TbButton('Align left', icon: NbIcons.alignLeft, onTap: () => _align(true)),
        TbButton('Align top', icon: NbIcons.alignTop, onTap: () => _align(false)),
      ],
      const TbButton.divider(),
      // Native shows the toggle *target* icon (off-icon when snap is on), no highlight.
      TbButton(_snapEnabled ? 'Snap off' : 'Snap on',
          icon: _snapEnabled ? NbIcons.snapOff : NbIcons.snapOn, onTap: _toggleSnap),
    ];

    final barLeft = leftFor(items, selCenter);
    final bar = Positioned(left: barLeft, top: top, child: _selBar(barH, maxContentW, items));

    // The shared H1/H2/H3 popover — stacked just past the bar, centred under the heading button that
    // launched it, flipped above if it would clip the page.
    Widget? popover;
    final showPopover = (_headingMenu == _HeadingMenu.create && _selectionIsPureStrokes) ||
        (_headingMenu == _HeadingMenu.change && heading != null);
    if (showPopover) {
      final curLevel = _headingMenu == _HeadingMenu.change ? heading!.data.level : null;
      final pItems = <TbButton>[
        for (final lvl in const [1, 2, 3])
          TbButton('H$lvl',
              icon: lvl == 1
                  ? NbIcons.h1
                  : lvl == 2
                      ? NbIcons.h2
                      : NbIcons.h3,
              selected: curLevel == lvl,
              onTap: () => _pickHeadingLevel(lvl)),
      ];
      final belowPop = top + barH + 6;
      final abovePop = top - barH - 6;
      final pTop = (belowPop + barH <= constraints.maxHeight - 4) ? belowPop : math.max(4.0, abovePop);
      // Centre the popover on the launching heading button (its glyph is NbIcons.heading).
      final hi = items.indexWhere((it) => it.icon == NbIcons.heading);
      final headingCenterX = barLeft + rowInset + toolbarButtonCenter(items, hi);
      final popLeft = leftFor(pItems, headingCenterX);
      popover = Positioned(left: popLeft, top: pTop, child: _selBar(barH, maxContentW, pItems));
    }

    return Positioned.fill(child: Stack(children: [bar, ?popover]));
  }

  /// The bordered white pill shared by the selection toolbar and the heading popover; [maxContentW]
  /// bounds the inner row so it overflows into the "⋯" menu rather than off-page.
  Widget _selBar(double barH, double maxContentW, List<TbButton> items) => Container(
        height: barH,
        decoration: BoxDecoration(
          color: Colors.white,
          border: Border.all(color: Colors.black, width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6),
          child: ConstrainedBox(
            constraints: BoxConstraints(maxWidth: maxContentW),
            child: OverflowToolbar(items, height: barH),
          ),
        ),
      );

  void _toggleSnap() {
    setState(() => _snapEnabled = !_snapEnabled);
    AppSettings.instance.setSnapEnabled(_snapEnabled);
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
          ..addOnPlatformViewCreatedListener((id) {
            params.onPlatformViewCreated(id);
            // Surface + channel now exist → exclude the current toolbar rect from the pen region.
            _applyToolbarExclusion();
          })
          ..create();
        return controller;
      },
    );
  }
}
