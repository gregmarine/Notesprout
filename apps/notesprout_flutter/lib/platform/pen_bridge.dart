import 'package:flutter/services.dart';

/// One pen event pushed up from the native Onyx surface at pen-lift.
/// [points] is interleaved [x0,y0,x1,y1,...] in NATIVE (physical px) view coordinates.
class PenEvent {
  PenEvent(this.type, this.points, {this.durationMs = 0});
  final String type; // 'stroke' | 'erase'
  final List<double> points;
  final int durationMs; // pen-contact time; drives the smart-lasso velocity gate
}

/// Dart side of the native pen bridge. Commands go Dart→native; committed strokes / eraser paths
/// come native→Dart as `penEvent` method calls on the SAME channel.
///
/// We use native→Dart `invokeMethod` (not an EventChannel) on purpose: the Dart handler is set
/// synchronously here, while native only emits after the user writes — so there is no
/// registration-ordering race. (An EventChannel subscribed in initState reaches no native handler
/// until the PlatformView is created later, silently dropping every event.)
class PenBridge {
  PenBridge() {
    _method.setMethodCallHandler(_onCall);
  }

  static const _method = MethodChannel('notesprout/onyx');

  /// Set by the host screen to receive committed-stroke / eraser events.
  void Function(PenEvent event)? onEvent;

  Future<dynamic> _onCall(MethodCall call) async {
    if (call.method == 'penEvent') {
      final m = call.arguments as Map;
      onEvent?.call(PenEvent(
        m['type'] as String,
        (m['points'] as List).cast<double>(),
        durationMs: (m['durationMs'] as num?)?.toInt() ?? 0,
      ));
    }
    return null;
  }

  Future<void> setPen() => _invoke('setPen');
  Future<void> setEraser() => _invoke('setEraser');
  Future<void> clear() => _invoke('clear');

  /// Hand the panel off to Flutter's committed layer — call AFTER Dart has painted its frame.
  Future<void> repaintPanel() => _invoke('repaintPanel');

  /// Suspend (false) or resume (true) raw pen input — used while placing/editing a text object so
  /// the stylus doesn't draw. Dart captures the placement tap instead.
  Future<void> setDrawingEnabled(bool enabled) async {
    try {
      await _method.invokeMethod('setDrawingEnabled', enabled);
    } catch (_) {}
  }

  /// Exclude the top [px] (the floating toolbar strip) from the pen region, so the page is
  /// full-screen behind the toolbar yet stylus taps hit the toolbar buttons instead of drawing.
  Future<void> setToolbarInset(double px) async {
    try {
      await _method.invokeMethod('setToolbarInset', px);
    } catch (_) {}
  }

  void dispose() {
    _method.setMethodCallHandler(null);
    onEvent = null;
  }

  Future<void> _invoke(String method) async {
    try {
      await _method.invokeMethod(method);
    } catch (_) {
      // Channel absent (no surface yet) — safe to ignore.
    }
  }
}
