import 'package:flutter_test/flutter_test.dart';
import 'package:notesprout_flutter/core/geometry.dart';
import 'package:notesprout_flutter/domain/stroke.dart';

void main() {
  // A 100×100 square loop.
  const square = [
    StrokePoint(0, 0),
    StrokePoint(100, 0),
    StrokePoint(100, 100),
    StrokePoint(0, 100),
  ];

  test('center inside the loop is selected', () {
    expect(pointInPolygon(50, 50, square), isTrue);
  });

  test('point outside the loop is not selected', () {
    expect(pointInPolygon(150, 50, square), isFalse);
    expect(pointInPolygon(50, -10, square), isFalse);
  });

  test('concave loop excludes the notch', () {
    // An L-shape (concave) polygon.
    const l = [
      StrokePoint(0, 0),
      StrokePoint(100, 0),
      StrokePoint(100, 40),
      StrokePoint(40, 40),
      StrokePoint(40, 100),
      StrokePoint(0, 100),
    ];
    expect(pointInPolygon(20, 20, l), isTrue); // in the arm
    expect(pointInPolygon(70, 70, l), isFalse); // in the notch (outside)
  });
}
