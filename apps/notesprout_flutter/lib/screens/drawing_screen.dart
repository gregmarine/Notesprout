import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../drawing/drawing_engine.dart';
import '../drawing/drawing_engine_factory.dart';
import '../theme/app_theme.dart';

class DrawingScreen extends StatefulWidget {
  const DrawingScreen({super.key});

  @override
  State<DrawingScreen> createState() => _DrawingScreenState();
}

class _DrawingScreenState extends State<DrawingScreen> {
  DrawingEngine? _engine;
  static const _toolbarHeight = 48.0;

  @override
  void initState() {
    super.initState();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    DrawingEngineFactory.create().then((engine) {
      if (!mounted) return;
      engine.setToolbarHeight(_toolbarHeight);
      setState(() => _engine = engine);
    });
  }

  @override
  void dispose() {
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    _engine?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // Canvas is truly fullscreen — in immersive mode, Flutter (0,0) is
          // the physical screen origin, so Onyx touch coordinates align exactly.
          Positioned.fill(
            child: ColoredBox(
              color: AppTheme.paperWhite,
              child: _engine == null
                  ? const SizedBox.expand()
                  : _engine!.buildCanvas(),
            ),
          ),
          // Toolbar floats over the canvas. Onyx setLimitRect excludes this
          // region so pen strokes are never registered here.
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            height: _toolbarHeight,
            child: _DrawingToolbar(onClear: () => _engine?.clear()),
          ),
        ],
      ),
    );
  }
}

class _DrawingToolbar extends StatelessWidget {
  const _DrawingToolbar({required this.onClear});

  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 48,
      decoration: const BoxDecoration(
        color: AppTheme.paperWhite,
        border: Border(
          bottom: BorderSide(color: AppTheme.inkBlack, width: 1),
        ),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: [
          OutlinedButton(
            onPressed: onClear,
            child: const Text('Clear'),
          ),
          const Expanded(
            child: Center(
              child: Text(
                'Canvas',
                style: TextStyle(
                  color: AppTheme.inkBlack,
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ),
          const SizedBox(width: 80),
        ],
      ),
    );
  }
}
