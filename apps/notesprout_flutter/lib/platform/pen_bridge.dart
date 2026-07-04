import 'package:flutter/services.dart';

/// One pen event pushed up from the native Onyx surface at pen-lift.
/// [points] is interleaved [x0,y0,x1,y1,...] in NATIVE (physical px) view coordinates.
class PenEvent {
  PenEvent(this.type, this.points);
  final String type; // 'stroke' | 'erase'
  final List<double> points;
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
      ));
    }
    return null;
  }

  Future<void> setPen() => _invoke('setPen');
  Future<void> setEraser() => _invoke('setEraser');
  Future<void> clear() => _invoke('clear');

  /// Hand the panel off to Flutter's committed layer — call AFTER Dart has painted its frame.
  Future<void> repaintPanel() => _invoke('repaintPanel');

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
