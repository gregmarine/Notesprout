import 'package:flutter/widgets.dart';

abstract class DrawingEngine {
  Widget buildCanvas();
  void clear();
  void dispose();
}
