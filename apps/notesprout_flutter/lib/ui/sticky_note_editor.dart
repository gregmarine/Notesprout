import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:uuid/uuid.dart';

import 'dart:math' as math;

import '../core/lasso_geometry.dart';
import '../core/undo_manager.dart';
import '../domain/objects.dart';
import '../domain/page_object.dart';
import '../domain/stroke.dart';
import '../platform/pen_bridge.dart';
import 'finger_gestures.dart';
import 'flutter_ink_surface.dart';
import 'overflow_toolbar.dart';
import 'nb_icons.dart';
import 'page_painter.dart';

const _uuid = Uuid();

enum _Mode { pen, eraser, lasso }

/// The sticky-note content editor — a page-less, fully in-memory drawing canvas. It owns NO database:
/// it takes an initial [StickyNoteObject], lets the user draw/erase/lasso strokes, and on close pops
/// the edited object (or null when nothing changed) back to the host, which persists it.
///
/// On BOOX it drives its OWN Onyx overlay via the dedicated `notesprout/onyx_sticky` channel (a
/// second view-type), so the notebook's overlay/channel is never touched while the editor is open
/// (the host suspends the page overlay for the duration). Off-BOOX it uses [FlutterInkSurface].
///
/// Mirrors the notebook's pen/lasso model (see `notebook_screen.dart` + `core/lasso_geometry.dart`)
/// but trimmed to strokes only — native deferred in-editor heading/text/line insertion. Any embedded
/// headings/text/lines/shapes a native-authored note carries ride through untouched (passthrough on
/// [StickyNoteObject]).
class StickyNoteEditorScreen extends StatefulWidget {
  const StickyNoteEditorScreen({super.key, required this.initial});

  final StickyNoteObject initial;

  @override
  State<StickyNoteEditorScreen> createState() => _StickyNoteEditorScreenState();
}

class _StickyNoteEditorScreenState extends State<StickyNoteEditorScreen> {
  static const _viewType = 'notesprout/onyx_sticky_drawing';
  static const _kToolbarH = 56.0;

  final _bridge = PenBridge(channel: 'notesprout/onyx_sticky');
  final _undo = UndoStack();
  final _histVersion = ValueNotifier<int>(0);

  final List<PageObject> _objects = []; // StrokeObject only (editor authors strokes)
  _Mode _mode = _Mode.pen;
  bool _dirty = false; // any stroke mutation → the host should persist on close

  // Selection state (drag-move / delete / copy / paste within the note).
  final List<PageObject> _selection = [];
  final List<StrokeData> _clipboard = [];
  BoundingBox? _selBounds;
  final Map<String, PageObject> _moveBefore = {};
  Offset _dragTotal = Offset.zero;
  bool _movingSel = false;
  bool _pendingDismiss = false;

  // Lasso-tool loop capture (stylus; SDK pen suspended → live Flutter preview, no EPD ink).
  final List<Offset> _lassoPreview = [];
  bool _lassoDrawing = false;
  int _lastLassoMs = 0;

  // Finger gestures (touch only; stylus draws/lassos) — the SAME shared recognizer the notebook uses,
  // wired to just 2-finger=undo / 3-finger=redo (the editor has no pages or toolbar toggle).
  late final FingerGestures _fingers = FingerGestures(
    screenSize: () => MediaQuery.of(context).size,
    onUndo: _doUndo,
    onRedo: _doRedo,
    debugLabel: 'SN_FINGER',
  );

  double _dpr = 1.0;
  bool _surfaceReady = false;
  Size _canvasSize = Size.zero; // logical size of the drawing region (below the toolbar)

  bool get _useOnyx => defaultTargetPlatform == TargetPlatform.android;
  bool get _selectionActive => _mode == _Mode.lasso && _selection.isNotEmpty;

  @override
  void initState() {
    super.initState();
    // Full-screen immersive, IDENTICAL to the notebook — the drawing surface must be assembled the
    // same way (full-screen Stack, no SafeArea/toolbar offset) or the platform view's geometry differs
    // and multi-finger pointer delivery diverges (3-finger tap mis-flagged as moved). The notebook
    // re-applies its own immersive mode on return (appRouteObserver.didPopNext), so we don't restore.
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    _bridge.onEvent = _onPen;
    // Seed the canvas from the note's stored strokes.
    for (final ls in widget.initial.strokes) {
      final data = ls.toStrokeData();
      if (data.points.isEmpty) continue;
      final id = ls.id.isNotEmpty ? ls.id : _uuid.v4();
      _objects.add(StrokeObject(id, BoundingBox.ofPoints(data.points), data));
    }
  }

  @override
  void dispose() {
    _bridge.dispose();
    _histVersion.dispose();
    super.dispose();
  }

  void _repaintPanelNextFrame() =>
      WidgetsBinding.instance.addPostFrameCallback((_) => _bridge.repaintPanel());

  // ── Pen input (in-memory; same classify order as the notebook, no text mode) ─────────────────
  void _onPen(PenEvent e) {
    if (_selectionActive) return; // pen suspended while a selection is live

    final pts = <StrokePoint>[];
    for (var i = 0; i + 1 < e.points.length; i += 2) {
      pts.add(StrokePoint(e.points[i], e.points[i + 1]));
    }
    if (pts.isEmpty) return;

    if (_mode == _Mode.lasso) {
      if (e.type == 'stroke') {
        final hit = lassoHitTest(pts, _objects, _dpr);
        if (hit.ids.isNotEmpty) _enterSelection(hit);
      }
      _repaintPanelNextFrame();
      return;
    }

    if (e.type == 'stroke') {
      // Gate 1: smart-lasso.
      if (isSmartLassoCandidate(pts, e.durationMs, _dpr)) {
        final hit = lassoHitTest(pts, _objects, _dpr);
        if (hit.ids.isNotEmpty) {
          _enterSelection(hit);
          _repaintPanelNextFrame();
          return;
        }
      }
      // Gate 2: scribble-to-erase.
      if (isScribbleCandidate(pts, _dpr)) {
        final hitIds = scribbleHitTest(pts, _objects, _dpr).toSet();
        if (hitIds.isNotEmpty) {
          final gone = _objects.where((o) => hitIds.contains(o.id)).toList();
          _objects.removeWhere(gone.contains);
          _pushDelete(gone);
          setState(() {});
          _repaintPanelNextFrame();
          return;
        }
      }
      // Gate 3: normal stroke.
      final data = StrokeData(points: pts);
      final obj = StrokeObject(_uuid.v4(), BoundingBox.ofPoints(pts), data);
      _objects.add(obj);
      _pushInsert([obj]);
      if (!_useOnyx) setState(() {});
    } else if (e.type == 'erase') {
      final r2 = (15 * _dpr) * (15 * _dpr);
      final hits = _objects
          .whereType<StrokeObject>()
          .where((c) => _hit(c.data.points, pts, r2))
          .toList();
      if (hits.isEmpty) return;
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

  // ── Undo / redo (in-memory only) ─────────────────────────────────────────────
  void _record(UndoAction a) {
    _undo.push(a);
    _dirty = true;
    _histVersion.value++;
  }

  void _pushInsert(List<PageObject> objs) {
    if (objs.isEmpty) return;
    _record(UndoAction(
      undo: () => _objects.removeWhere(objs.contains),
      redo: () => _objects.addAll(objs),
    ));
  }

  void _pushDelete(List<PageObject> objs) {
    if (objs.isEmpty) return;
    _record(UndoAction(
      undo: () => _objects.addAll(objs),
      redo: () => _objects.removeWhere(objs.contains),
    ));
  }

  void _pushMove(List<(PageObject, PageObject)> pairs) {
    _record(UndoAction(
      undo: () {
        for (final (b, _) in pairs) {
          _replaceObject(b);
        }
        _reselect([for (final (b, _) in pairs) b.id]);
      },
      redo: () {
        for (final (_, a) in pairs) {
          _replaceObject(a);
        }
        _reselect([for (final (_, a) in pairs) a.id]);
      },
    ));
  }

  void _replaceObject(PageObject obj) {
    final i = _objects.indexWhere((o) => o.id == obj.id);
    if (i >= 0) _objects[i] = obj;
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

  // ── Mode / selection ─────────────────────────────────────────────────────────
  void _setMode(_Mode m) {
    setState(() {
      _mode = m;
      if (m != _Mode.lasso) {
        _selection.clear();
        _selBounds = null;
      }
      _movingSel = false;
      _pendingDismiss = false;
      _lassoDrawing = false;
      _lassoPreview.clear();
    });
    switch (m) {
      case _Mode.pen:
        _bridge.setDrawingEnabled(true);
        _bridge.setPen();
      case _Mode.eraser:
        _bridge.setDrawingEnabled(true);
        _bridge.setEraser();
      case _Mode.lasso:
        // Suspend the SDK pen so Flutter captures the loop (live preview, no EPD ink) — native parity.
        _bridge.setDrawingEnabled(false);
    }
    _applyExclusion();
    _repaintPanelNextFrame();
  }

  /// Exclude the floating toolbar strip from the pen region so the surface is full-screen (identical
  /// geometry to the notebook) yet stylus taps on the top bar hit the buttons instead of drawing.
  void _applyExclusion() {
    if (!mounted) return;
    final size = MediaQuery.of(context).size;
    _bridge.setToolbarExclusion(0, 0, size.width * _dpr, _kToolbarH * _dpr);
  }

  // ── Lasso-tool loop capture (stylus; SDK pen suspended) ──────────────────────
  static bool _isStylus(PointerDeviceKind k) =>
      k == PointerDeviceKind.stylus || k == PointerDeviceKind.invertedStylus;

  void _lassoDown(PointerDownEvent e) {
    _lassoDrawing = true;
    _lassoPreview
      ..clear()
      ..add(e.localPosition);
    setState(() {});
  }

  void _lassoMove(PointerMoveEvent e) {
    if (!_lassoDrawing) return;
    _lassoPreview.add(e.localPosition);
    final now = DateTime.now().millisecondsSinceEpoch;
    if (now - _lastLassoMs >= 50) {
      _lastLassoMs = now;
      setState(() {});
    }
  }

  void _lassoUp(PointerUpEvent e) {
    if (!_lassoDrawing) return;
    _lassoDrawing = false;
    final pts = [for (final p in _lassoPreview) StrokePoint(p.dx * _dpr, p.dy * _dpr)];
    setState(() => _lassoPreview.clear());
    if (pts.length < 3) return;
    final hit = lassoHitTest(pts, _objects, _dpr);
    if (hit.ids.isNotEmpty) _enterSelection(hit);
  }

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

  BoundingBox? _computeSelBounds() {
    final raw = _rawBounds(_selection);
    if (raw == null) return null;
    final pad = kLassoOverlayPadDp * _dpr;
    return BoundingBox(raw.x - pad, raw.y - pad, raw.width + 2 * pad, raw.height + 2 * pad);
  }

  void _enterSelection(LassoHit hit) {
    setState(() {
      _mode = _Mode.lasso;
      _selection
        ..clear()
        ..addAll(_objects.where((o) => hit.ids.contains(o.id)));
      _selBounds = hit.bounds;
    });
    _bridge.setDrawingEnabled(false);
    _repaintPanelNextFrame();
  }

  void _endSelection() {
    setState(() {
      _selection.clear();
      _selBounds = null;
      _movingSel = false;
      _pendingDismiss = false;
    });
    _bridge.setDrawingEnabled(true);
    _repaintPanelNextFrame();
  }

  void _reselect(List<String> ids) {
    _selection
      ..clear()
      ..addAll(_objects.where((o) => ids.contains(o.id)));
    _selBounds = _computeSelBounds();
  }

  PageObject _translate(PageObject o, double dx, double dy) {
    final b = BoundingBox(o.box.x + dx, o.box.y + dy, o.box.width, o.box.height);
    final s = o as StrokeObject;
    return StrokeObject(
        s.id,
        b,
        StrokeData(
          color: s.data.color,
          strokeWidth: s.data.strokeWidth,
          points: [
            for (final p in s.data.points)
              StrokePoint(p.x + dx, p.y + dy, pressure: p.pressure, tilt: p.tilt)
          ],
        ));
  }

  bool _inBox(BoundingBox b, double px, double py) =>
      px >= b.x && px <= b.x + b.width && py >= b.y && py <= b.y + b.height;

  void _selDown(PointerDownEvent e) {
    final px = e.localPosition.dx * _dpr, py = e.localPosition.dy * _dpr;
    if (_selBounds != null && _inBox(_selBounds!, px, py)) {
      _movingSel = true;
      _pendingDismiss = false;
      _dragTotal = Offset.zero;
      _moveBefore
        ..clear()
        ..addEntries(_selection.map((o) => MapEntry(o.id, o)));
    } else {
      _movingSel = false;
      _pendingDismiss = true;
    }
  }

  void _selMove(PointerMoveEvent e) {
    if (!_movingSel) return;
    _dragTotal += Offset(e.delta.dx * _dpr, e.delta.dy * _dpr);
    setState(() {
      for (var i = 0; i < _selection.length; i++) {
        final before = _moveBefore[_selection[i].id];
        if (before == null) continue;
        final moved = _translate(before, _dragTotal.dx, _dragTotal.dy);
        final oi = _objects.indexWhere((o) => o.id == moved.id);
        if (oi >= 0) _objects[oi] = moved;
        _selection[i] = moved;
      }
      _selBounds = _computeSelBounds();
    });
  }

  void _selUp(PointerUpEvent e) {
    if (_movingSel) {
      _movingSel = false;
      if (_dragTotal.distance >= 8 * _dpr) {
        final pairs = <(PageObject, PageObject)>[];
        for (final after in _selection) {
          final before = _moveBefore[after.id];
          if (before != null) pairs.add((before, after));
        }
        if (pairs.isNotEmpty) _pushMove(pairs);
      }
      _repaintPanelNextFrame();
    } else if (_pendingDismiss) {
      _pendingDismiss = false;
      _endSelection();
    }
  }

  void _deleteSelection() {
    if (_selection.isEmpty) return;
    final gone = List<PageObject>.of(_selection);
    _objects.removeWhere(gone.contains);
    _pushDelete(gone);
    _endSelection();
  }

  void _copySelection() {
    _clipboard
      ..clear()
      ..addAll([for (final o in _selection) (o as StrokeObject).data]);
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
    for (final d in _clipboard) {
      final pts = [
        for (final p in d.points) StrokePoint(p.x + off, p.y + off, pressure: p.pressure, tilt: p.tilt)
      ];
      final data = StrokeData(color: d.color, strokeWidth: d.strokeWidth, points: pts);
      final obj = StrokeObject(_uuid.v4(), BoundingBox.ofPoints(pts), data);
      _objects.add(obj);
      pasted.add(obj);
    }
    _pushInsert(pasted);
    setState(() {
      _mode = _Mode.lasso;
      _selection
        ..clear()
        ..addAll(pasted);
      _selBounds = _computeSelBounds();
    });
    _bridge.setDrawingEnabled(false);
    _repaintPanelNextFrame();
  }

  // ── Done / build output ──────────────────────────────────────────────────────
  StickyNoteObject _buildObject() {
    final size = _canvasSize == Size.zero ? MediaQuery.of(context).size : _canvasSize;
    return widget.initial.copyWith(
      strokes: [
        for (final o in _objects.whereType<StrokeObject>())
          LiveStroke(
            id: o.id,
            points: [for (final p in o.data.points) Vec2(p.x, p.y)],
            color: o.data.color,
            strokeWidth: o.data.strokeWidth,
          )
      ],
      contentWidth: size.width * _dpr,
      contentHeight: size.height * _dpr,
    );
  }

  void _done() {
    if (!mounted) return;
    Navigator.of(context).pop(_dirty ? _buildObject() : null);
  }

  // ── Build ──────────────────────────────────────────────────────────────────
  @override
  Widget build(BuildContext context) {
    _dpr = MediaQuery.of(context).devicePixelRatio;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _done();
      },
      child: Scaffold(
        backgroundColor: Colors.white,
        // Full-screen Stack with a FLOATING toolbar over the canvas — assembled exactly like the
        // notebook (see notebook_screen.dart build). The drawing surface + finger layer fill the whole
        // screen so multi-finger pointer delivery matches the notebook; the pen region excludes the
        // toolbar strip (setToolbarExclusion) so stylus taps hit the buttons instead of drawing.
        body: Stack(
          children: [
            Positioned.fill(
              child: LayoutBuilder(builder: (context, constraints) {
                _canvasSize = Size(constraints.maxWidth, constraints.maxHeight);
                return Stack(
                  children: [
                    Positioned.fill(
                      child: CustomPaint(
                        painter: PagePainter(List.of(_objects), _dpr, selection: () {
                          final raw = _rawBounds(_selection);
                          return raw != null ? [raw] : const <BoundingBox>[];
                        }()),
                      ),
                    ),
                    Positioned.fill(child: _surface()),
                    // Finger-gesture layer (touch only; translucent so the stylus reaches the
                    // surface below). 2-finger double-tap = undo, 3-finger double-tap = redo.
                    Positioned.fill(
                      child: Listener(
                        behavior: HitTestBehavior.translucent,
                        onPointerDown: _fingers.down,
                        onPointerMove: _fingers.move,
                        onPointerUp: _fingers.end,
                        onPointerCancel: _fingers.end,
                      ),
                    ),
                    // Lasso tool, no selection: capture the stylus loop in Flutter (SDK pen off).
                    if (_mode == _Mode.lasso && _selection.isEmpty) ...[
                      if (_lassoPreview.length > 1)
                        Positioned.fill(
                          child: IgnorePointer(
                            child: CustomPaint(
                                painter: _LassoPreviewPainter(List.of(_lassoPreview))),
                          ),
                        ),
                      Positioned.fill(
                        child: Listener(
                          behavior: HitTestBehavior.translucent,
                          onPointerDown: (e) {
                            if (_isStylus(e.kind)) _lassoDown(e);
                          },
                          onPointerMove: (e) {
                            if (_isStylus(e.kind)) _lassoMove(e);
                          },
                          onPointerUp: (e) {
                            if (_isStylus(e.kind)) _lassoUp(e);
                          },
                        ),
                      ),
                    ],
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
            // Floating tool bar over the top of the canvas (the pen region excludes this strip).
            Positioned(top: 0, left: 0, right: 0, child: _toolbar()),
          ],
        ),
      ),
    );
  }

  /// The editor's fixed top toolbar: Done (save + close) · pen/eraser/lasso · undo/redo.
  Widget _toolbar() {
    return ValueListenableBuilder<int>(
      valueListenable: _histVersion,
      builder: (context, _, _) => Container(
        height: _kToolbarH,
        decoration: const BoxDecoration(
          color: Colors.white,
          border: Border(bottom: BorderSide(color: Colors.black, width: 1)),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 6),
        child: Row(
          children: [
            tbIconButton(TbButton('Done', icon: NbIcons.close, onTap: _done)),
            tbDivider(),
            tbIconButton(
                TbButton('Pen', icon: NbIcons.pen, selected: _mode == _Mode.pen, onTap: () => _setMode(_Mode.pen))),
            const SizedBox(width: 3),
            tbIconButton(TbButton('Eraser',
                icon: NbIcons.eraser, selected: _mode == _Mode.eraser, onTap: () => _setMode(_Mode.eraser))),
            const SizedBox(width: 3),
            tbIconButton(TbButton('Lasso',
                icon: NbIcons.lasso, selected: _mode == _Mode.lasso, onTap: () => _setMode(_Mode.lasso))),
            tbDivider(),
            tbIconButton(TbButton('Undo', icon: NbIcons.undo, enabled: _undo.canUndo, onTap: _doUndo)),
            const SizedBox(width: 3),
            tbIconButton(TbButton('Redo', icon: NbIcons.redo, enabled: _undo.canRedo, onTap: _doRedo)),
            if (_clipboard.isNotEmpty) ...[
              tbDivider(),
              tbIconButton(TbButton('Paste', icon: NbIcons.pastePage, onTap: _pasteClipboard)),
            ],
            const Spacer(),
            const Text('Sticky Note',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: Color(0xFF888888))),
          ],
        ),
      ),
    );
  }

  /// A floating action bar anchored to the active selection: Copy · Cut · Delete.
  Widget _floatingToolbar(BoxConstraints constraints) {
    const barH = 46.0;
    const chrome = 14.0;
    final aboveTop = _selBounds!.y / _dpr - barH - 6;
    final belowTop = (_selBounds!.y + _selBounds!.height) / _dpr + 6;
    final top = (aboveTop >= 4 ? aboveTop : belowTop)
        .clamp(4.0, math.max(4.0, constraints.maxHeight - barH))
        .toDouble();
    final raw = _rawBounds(_selection) ?? _selBounds!;
    final selCenter = (raw.x + raw.width / 2) / _dpr;
    final items = <TbButton>[
      TbButton('Copy', icon: NbIcons.lassoCopy, onTap: _copySelection),
      TbButton('Cut', icon: NbIcons.lassoCut, onTap: _cutSelection),
      TbButton('Delete', icon: NbIcons.lassoDelete, onTap: _deleteSelection),
    ];
    final maxContentW = math.max(60.0, constraints.maxWidth - 8 - chrome);
    final w = math.min(toolbarContentWidth(items), maxContentW) + chrome;
    final left =
        (selCenter - w / 2).clamp(4.0, math.max(4.0, constraints.maxWidth - w - 4)).toDouble();
    return Positioned(
      left: left,
      top: top,
      child: Container(
        height: barH,
        decoration: BoxDecoration(
          color: Colors.white,
          border: Border.all(color: Colors.black, width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 6),
        child: ConstrainedBox(
          constraints: BoxConstraints(maxWidth: maxContentW),
          child: OverflowToolbar(items, height: barH),
        ),
      ),
    );
  }

  Widget _surface() {
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
            if (!_surfaceReady) {
              _surfaceReady = true;
              _setMode(_mode); // enable the overlay + set the current tool
            }
          })
          ..create();
        return controller;
      },
    );
  }
}

/// Live preview of the in-flight lasso loop (logical space; the SDK pen is suspended in lasso mode).
/// Dashed to match native's `lassoPaint`, so it reads as a selection loop, not real ink.
class _LassoPreviewPainter extends CustomPainter {
  _LassoPreviewPainter(this.points);
  final List<Offset> points;

  @override
  void paint(Canvas canvas, Size size) {
    if (points.length < 2) return;
    final paint = Paint()
      ..color = Colors.black
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..isAntiAlias = true;
    final full = Path()..moveTo(points.first.dx, points.first.dy);
    for (var i = 1; i < points.length; i++) {
      full.lineTo(points[i].dx, points[i].dy);
    }
    const dash = 10.0, gap = 6.0;
    for (final metric in full.computeMetrics()) {
      var d = 0.0;
      while (d < metric.length) {
        final end = math.min(d + dash, metric.length);
        canvas.drawPath(metric.extractPath(d, end), paint);
        d += dash + gap;
      }
    }
  }

  @override
  bool shouldRepaint(_LassoPreviewPainter old) => true;
}
