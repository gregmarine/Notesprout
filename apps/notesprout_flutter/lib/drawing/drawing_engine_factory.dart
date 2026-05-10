import 'dart:io';

import 'package:flutter/services.dart';

import 'drawing_engine.dart';
import 'generic_drawing_engine.dart';
import 'onyx_drawing_engine.dart';

class DrawingEngineFactory {
  static const _channel = MethodChannel('com.notesprout/onyx_canvas');

  // Checks manufacturer on the native side (returns "ok" only on BOOX devices)
  // and falls back to GenericDrawingEngine on any other platform or device.
  static Future<DrawingEngine> create() async {
    if (!Platform.isAndroid) return GenericDrawingEngine();
    try {
      final result = await _channel.invokeMethod<String>('ping');
      if (result == 'ok') return OnyxDrawingEngine();
    } catch (_) {}
    return GenericDrawingEngine();
  }
}
