import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'screens/drawing_screen.dart';
import 'theme/app_theme.dart';
import 'theme/app_theme_test_screen.dart';

void main() {
  runApp(
    const ProviderScope(
      child: NoteSproutApp(),
    ),
  );
}

class NoteSproutApp extends StatelessWidget {
  const NoteSproutApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'NoteSprout',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.themeData,
      home: const AppThemeTestScreen(),
      routes: {
        '/draw': (_) => const DrawingScreen(),
      },
    );
  }
}
