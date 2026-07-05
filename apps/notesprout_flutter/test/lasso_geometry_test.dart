import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:notesprout_flutter/core/lasso_geometry.dart';
import 'package:notesprout_flutter/domain/page_object.dart';
import 'package:notesprout_flutter/domain/stroke.dart';

/// A sampled circular loop of [n] points, radius [r] about ([cx],[cy]), sweeping [sweep] radians.
/// sweep == 2π ⇒ a closed loop (last point coincides with the first).
List<StrokePoint> _arc(double cx, double cy, double r, int n, {double sweep = 2 * math.pi}) {
  return [
    for (var i = 0; i < n; i++)
      StrokePoint(cx + r * math.cos(sweep * i / (n - 1)), cy + r * math.sin(sweep * i / (n - 1)))
  ];
}

StrokeObject _stroke(List<StrokePoint> pts) =>
    StrokeObject('s${pts.hashCode}', BoundingBox.ofPoints(pts), StrokeData(points: pts));

void main() {
  group('winding', () {
    test('a full loop winds ~360°', () {
      expect(lassoWindingDegrees(_arc(0, 0, 100, 48)), closeTo(360, 5));
    });
    test('a straight line is never a smart-lasso candidate (open, closure gate)', () {
      // A collinear line's centroid lies on the line, so winding alone is degenerate (~180°);
      // the closure gate (far-apart endpoints) is what actually rejects lines.
      final line = [for (var i = 0; i <= 20; i++) StrokePoint(i * 5.0, 0)];
      expect(isSmartLassoCandidate(line, 100, 1.0), isFalse);
    });
    test('a half-circle arc stays below the lasso winding gate', () {
      // (centroid sits inside the arc, so it winds >180° — but still < the 270° loop threshold)
      expect(lassoWindingDegrees(_arc(0, 0, 100, 24, sweep: math.pi)),
          lessThan(kSmartLassoMinWindingDegrees));
    });
  });

  group('isSmartLassoCandidate', () {
    test('a fast closed loop qualifies', () {
      // circumference ≈ 628 px over 1000 ms → 0.63 px/ms ≥ 0.5
      expect(isSmartLassoCandidate(_arc(0, 0, 100, 48), 1000, 1.0), isTrue);
    });
    test('too slow fails the velocity gate', () {
      expect(isSmartLassoCandidate(_arc(0, 0, 100, 48), 4000, 1.0), isFalse);
    });
    test('an open arc fails (closure + winding)', () {
      expect(isSmartLassoCandidate(_arc(0, 0, 100, 24, sweep: math.pi), 500, 1.0), isFalse);
    });
    test('too few points fails', () {
      expect(isSmartLassoCandidate(_arc(0, 0, 100, 3), 100, 1.0), isFalse);
    });
  });

  group('isScribbleCandidate', () {
    test('a dense zigzag qualifies', () {
      // Triangle wave in x with a slow y drift — sharp horizontal reversals at every peak/valley
      // (no perpendicular cushion), so consecutive move vectors have a clearly negative dot product.
      final pts = <StrokePoint>[];
      for (var i = 0; i <= 40; i++) {
        final tri = i % 20;
        final x = (tri <= 10 ? tri : 20 - tri) * 10.0; // 0→100→0
        pts.add(StrokePoint(x, i.toDouble()));
      }
      expect(isScribbleCandidate(pts, 1.0), isTrue);
    });
    test('a plain line is not a scribble', () {
      final line = [for (var i = 0; i <= 20; i++) StrokePoint(i * 8.0, 0)];
      expect(isScribbleCandidate(line, 1.0), isFalse);
    });
  });

  group('polygonIntersectsRect', () {
    final loop = _arc(100, 100, 60, 48); // centered at (100,100), r=60
    test('box fully inside the loop overlaps', () {
      expect(polygonIntersectsRect(loop, const BoundingBox(90, 90, 20, 20)), isTrue);
    });
    test('box straddling the loop edge overlaps', () {
      expect(polygonIntersectsRect(loop, const BoundingBox(150, 90, 40, 20)), isTrue);
    });
    test('far-away box does not overlap', () {
      expect(polygonIntersectsRect(loop, const BoundingBox(400, 400, 20, 20)), isFalse);
    });
    test('loop entirely inside a large box overlaps', () {
      expect(polygonIntersectsRect(loop, const BoundingBox(0, 0, 300, 300)), isTrue);
    });
  });

  group('lassoHitTest', () {
    test('selects a stroke whose points fall inside the loop', () {
      final inside = _stroke([for (var i = 0; i < 8; i++) StrokePoint(100 + i.toDouble(), 100)]);
      final outside = _stroke([for (var i = 0; i < 8; i++) StrokePoint(400 + i.toDouble(), 400)]);
      final hit = lassoHitTest(_arc(100, 100, 60, 48), [inside, outside], 1.0);
      expect(hit.ids, [inside.id]);
      expect(hit.bounds, isNotNull);
    });

    test('empty result when nothing is enclosed', () {
      final far = _stroke([for (var i = 0; i < 8; i++) StrokePoint(400 + i.toDouble(), 400)]);
      final hit = lassoHitTest(_arc(100, 100, 60, 48), [far], 1.0);
      expect(hit.ids, isEmpty);
      expect(hit.bounds, isNull);
    });

    test('trivially small loop selects nothing (min-size guard)', () {
      final s = _stroke([StrokePoint(100, 100), StrokePoint(101, 100)]);
      final tiny = _arc(100, 100, 3, 12); // ~6px extent < 10dp
      expect(lassoHitTest(tiny, [s], 1.0).ids, isEmpty);
    });
  });
}
