import 'package:flutter/material.dart';

void main() {
  runApp(const NoteSproutApp());
}

class NoteSproutApp extends StatelessWidget {
  const NoteSproutApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        backgroundColor: Colors.white,
        body: Center(
          child: Text(
            'NoteSprout',
            style: TextStyle(fontSize: 24),
          ),
        ),
      ),
    );
  }
}
