import 'package:flutter/material.dart';

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

  @override
  void initState() {
    super.initState();
    DrawingEngineFactory.create().then((engine) {
      if (mounted) setState(() => _engine = engine);
    });
  }

  @override
  void dispose() {
    _engine?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final topPadding = MediaQuery.of(context).padding.top;
    const toolbarHeight = 48.0;

    return Scaffold(
      body: Stack(
        children: [
          Positioned.fill(
            top: topPadding + toolbarHeight,
            child: ColoredBox(
              color: AppTheme.paperWhite,
              child: _engine == null
                  ? const SizedBox.expand()
                  : _engine!.buildCanvas(),
            ),
          ),
          Positioned(
            top: topPadding,
            left: 0,
            right: 0,
            height: toolbarHeight,
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
