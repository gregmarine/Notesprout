import 'package:flutter/widgets.dart';

abstract class DrawingEngine {
  Widget buildCanvas();
  void clear();
  void dispose();
  // Tells the engine how tall the floating toolbar is so it can restrict
  // hardware pen input to the area below it. No-op for engines that handle
  // hit-testing via Flutter's normal widget tree (e.g. GenericDrawingEngine).
  void setToolbarHeight(double height) {}
}
