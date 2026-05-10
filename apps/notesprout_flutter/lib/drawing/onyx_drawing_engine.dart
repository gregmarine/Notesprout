import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'drawing_engine.dart';

class OnyxDrawingEngine implements DrawingEngine {
  static const _channel = MethodChannel('com.notesprout/onyx_canvas');

  @override
  Widget buildCanvas() {
    return AndroidView(
      viewType: 'com.notesprout/onyx_canvas_view',
      layoutDirection: TextDirection.ltr,
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: _onCreated,
    );
  }

  void _onCreated(int id) {
    _channel.invokeMethod<void>('initialize');
  }

  @override
  void clear() {
    _channel.invokeMethod<void>('clear');
  }

  @override
  void dispose() {
    _channel.invokeMethod<void>('dispose');
  }
}
