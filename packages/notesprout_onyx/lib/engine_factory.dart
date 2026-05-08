import 'dart:io';

import 'drawing_engine.dart';
import 'flutter_drawing_engine.dart';
import 'onyx_drawing_engine.dart';

class DrawingEngineFactory {
  static DrawingEngine create() {
    if (Platform.isAndroid) {
      return OnyxDrawingEngine();
    }
    return FlutterDrawingEngine();
  }
}
