import 'package:flutter/services.dart';

import 'drawing_engine.dart';

class OnyxDrawingEngine implements DrawingEngine {
  static const _channel = MethodChannel('com.notesprout/onyx');

  @override
  void initialize(dynamic surfaceView) {
    _channel.invokeMethod('initialize');
  }

  @override
  void setDrawingMode() {
    _channel.invokeMethod('setDrawingMode');
  }

  @override
  void setEraserMode() {
    _channel.invokeMethod('setEraserMode');
  }

  @override
  void dispose() {
    _channel.invokeMethod('dispose');
  }

  @override
  bool get isAvailable => true;
}
