import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';

/// Shared finger (touch-only) gesture recognizer used by BOTH the notebook page and the sticky-note
/// editor, so multi-finger undo/redo — and the notebook's page swipes / toolbar toggle — behave
/// identically everywhere. (Native keeps parity by reusing its drawing views; the Flutter port keeps
/// parity by reusing this one recognizer instead of duplicating the logic per screen.)
///
/// Movement is measured from the CENTROID of the active fingers, with the baseline reset whenever the
/// finger count changes (the native technique). This is what makes multi-finger taps reliable: when a
/// 2nd/3rd finger lands, a single finger's landing jitter is averaged into the centroid and measured
/// from a fresh baseline, so a stationary multi-finger tap is not mis-flagged as "moved". Without it,
/// first-finger tracking flags the tap as moved on BOOX (where a 3-finger touch arrives as an instant
/// cancel), and the tap is dropped. The swipe VECTOR still uses the primary finger, so 1-finger page
/// flips / TOC behave exactly as before (for one finger the centroid IS that finger).
class FingerGestures {
  FingerGestures({
    required this.screenSize,
    this.onFingerDown,
    this.onFingerTapConsume,
    this.onToggleToolbar,
    this.onUndo,
    this.onRedo,
    this.onPageFlip,
    this.onPageInsert,
    this.onTocOpen,
    this.debugLabel,
  });

  /// Live screen size (swipe-distance thresholds) — a getter so it stays current across frames.
  final Size Function() screenSize;

  /// Every finger-down (e.g. to dismiss an open overflow row). Local position.
  final void Function(Offset localPos)? onFingerDown;

  /// A single-finger tap; return true to consume it (e.g. open a sticky note under the point) and
  /// skip the double-tap toolbar toggle.
  final bool Function(Offset localPos)? onFingerTapConsume;

  final VoidCallback? onToggleToolbar; // 1-finger double-tap
  final VoidCallback? onUndo; // 2-finger double-tap
  final VoidCallback? onRedo; // 3-finger double-tap
  final void Function(int dir)? onPageFlip; // 1-finger horizontal swipe (+1 next, −1 prev)
  final void Function(bool after)? onPageInsert; // 2-finger horizontal swipe
  final VoidCallback? onTocOpen; // 1-finger downward swipe

  final String? debugLabel;

  static const double _kTapSlopPx = 18;
  static const double _kDoubleTapSlopPx = 48;
  static const int _kTapMaxMs = 500;
  static const int _kDoubleTapMaxMs = 300;
  static const double _kSwipeFraction = 0.30;

  final Map<int, Offset> _pts = {}; // active pointer → current local position
  int _peak = 0;
  Offset _baseline = Offset.zero; // centroid at the last finger-count change (movement baseline)
  bool _moved = false;

  // Primary-finger tracking — used ONLY for the swipe vector (unchanged from the notebook's model).
  int? _primary;
  Offset _swipeStart = Offset.zero;
  Offset _swipeLast = Offset.zero;
  Duration _downTime = Duration.zero;

  // Double-tap memory per finger count.
  Duration? _t1, _t2, _t3;
  Offset _p1 = Offset.zero, _p2 = Offset.zero, _p3 = Offset.zero;

  Offset _centroid() {
    if (_pts.isEmpty) return Offset.zero;
    var x = 0.0, y = 0.0;
    for (final p in _pts.values) {
      x += p.dx;
      y += p.dy;
    }
    return Offset(x / _pts.length, y / _pts.length);
  }

  void down(PointerDownEvent e) {
    if (e.kind != PointerDeviceKind.touch) return;
    onFingerDown?.call(e.localPosition);
    final firstDown = _pts.isEmpty;
    _pts[e.pointer] = e.localPosition;
    if (_pts.length > _peak) _peak = _pts.length;
    _baseline = _centroid(); // reset the movement baseline on every finger-count change
    if (firstDown) {
      _primary = e.pointer;
      _swipeStart = e.localPosition;
      _swipeLast = e.localPosition;
      _downTime = e.timeStamp;
      _moved = false;
    }
  }

  void move(PointerMoveEvent e) {
    if (e.kind != PointerDeviceKind.touch || !_pts.containsKey(e.pointer)) return;
    _pts[e.pointer] = e.localPosition;
    if (e.pointer == _primary) _swipeLast = e.localPosition;
    if (!_moved && (_centroid() - _baseline).distance > _kTapSlopPx) _moved = true;
  }

  void end(PointerEvent e) {
    if (e.kind != PointerDeviceKind.touch) return;
    if (_pts.remove(e.pointer) == null) return;
    if (_pts.isNotEmpty) {
      _baseline = _centroid(); // a partial lift changes the centroid — rebaseline, don't call it moved
      return;
    }
    // All fingers up.
    final peak = _peak;
    final moved = _moved;
    final durMs = (e.timeStamp - _downTime).inMilliseconds;
    final endPos = _swipeLast;
    final dx = _swipeLast.dx - _swipeStart.dx;
    final dy = _swipeLast.dy - _swipeStart.dy;
    _peak = 0;
    _moved = false;
    _primary = null;

    if (kDebugMode) {
      debugPrint('${debugLabel ?? "FG"} peak=$peak moved=$moved dur=${durMs}ms '
          'cancel=${e is PointerCancelEvent}');
    }

    // BOOX cancels a stationary 3-finger touch with no UP — treat it as a completed tap (native).
    if (e is PointerCancelEvent) {
      if (peak == 3 && !moved && durMs <= _kTapMaxMs) {
        _handleTap(3, endPos, e.timeStamp);
      } else {
        _resetTaps();
      }
      return;
    }

    final size = screenSize();
    // Horizontal-dominant swipe past the min fraction → page flip (1 finger) / insert (2 fingers).
    if (dx.abs() > dy.abs() && dx.abs() / size.width >= _kSwipeFraction) {
      _resetTaps();
      if (peak == 1) {
        onPageFlip?.call(dx < 0 ? 1 : -1);
      } else if (peak >= 2) {
        onPageInsert?.call(dx < 0);
      }
      return;
    }
    // 1-finger downward swipe → Table of Contents.
    if (peak == 1 && dy > 0 && dy.abs() > dx.abs() && dy.abs() / size.height >= _kSwipeFraction) {
      _resetTaps();
      onTocOpen?.call();
      return;
    }
    if (!moved && durMs <= _kTapMaxMs) {
      _handleTap(peak, endPos, e.timeStamp);
    } else {
      _resetTaps();
    }
  }

  void _handleTap(int count, Offset pos, Duration now) {
    if (count < 1 || count > 3) {
      _resetTaps();
      return;
    }
    // A single-finger tap the host wants to consume (e.g. open a sticky note under the point).
    if (count == 1 && (onFingerTapConsume?.call(pos) ?? false)) {
      _resetTaps();
      return;
    }
    final (Duration? lastTime, Offset lastPos) = switch (count) {
      1 => (_t1, _p1),
      2 => (_t2, _p2),
      _ => (_t3, _p3),
    };
    final isDouble = lastTime != null &&
        (now - lastTime).inMilliseconds <= _kDoubleTapMaxMs &&
        (pos - lastPos).distance <= _kDoubleTapSlopPx;
    _resetTaps();
    if (isDouble) {
      switch (count) {
        case 1:
          onToggleToolbar?.call();
        case 2:
          onUndo?.call();
        default:
          onRedo?.call();
      }
    } else {
      switch (count) {
        case 1:
          _t1 = now;
          _p1 = pos;
        case 2:
          _t2 = now;
          _p2 = pos;
        default:
          _t3 = now;
          _p3 = pos;
      }
    }
  }

  void _resetTaps() => _t1 = _t2 = _t3 = null;
}
