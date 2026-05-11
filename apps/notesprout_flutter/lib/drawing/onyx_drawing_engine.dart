import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'drawing_engine.dart';

class OnyxDrawingEngine implements DrawingEngine {
  static const _channel = MethodChannel('com.notesprout/onyx_canvas');
  int _toolbarHeightPx = 0;

  @override
  Widget buildCanvas() =>
      _OnyxCanvasView(channel: _channel, toolbarHeight: _toolbarHeightPx);

  @override
  void setToolbarHeight(double height) {
    _toolbarHeightPx = height.toInt();
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

class _OnyxCanvasView extends StatefulWidget {
  final MethodChannel channel;
  final int toolbarHeight;
  const _OnyxCanvasView({required this.channel, required this.toolbarHeight});

  @override
  State<_OnyxCanvasView> createState() => _OnyxCanvasViewState();
}

class _OnyxCanvasViewState extends State<_OnyxCanvasView> {
  void _onPlatformViewCreated(int id) {
    widget.channel.invokeMethod<void>('initialize');
    // In immersive fullscreen mode the canvas View sits at screen (0,0), so
    // the only thing native needs is the toolbar height to set the exclusion
    // rect on setLimitRect. No localToGlobal calculation required.
    widget.channel.invokeMethod<void>('setCanvasOffset', {
      'x': 0,
      'y': widget.toolbarHeight,
    });
  }

  @override
  Widget build(BuildContext context) {
    return AndroidView(
      viewType: 'com.notesprout/onyx_canvas_view',
      layoutDirection: TextDirection.ltr,
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: _onPlatformViewCreated,
    );
  }
}
