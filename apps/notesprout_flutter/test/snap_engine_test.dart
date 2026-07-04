import 'package:flutter_test/flutter_test.dart';
import 'package:notesprout_flutter/core/snap_engine.dart';
import 'package:notesprout_flutter/domain/stroke.dart';

void main() {
  const pageW = 1000.0, pageH = 1400.0, margin = 44.0, thresh = 20.0;
  const box = BoundingBox(100, 100, 50, 50); // center (125,125)

  SnapResult snap(double dx, double dy, {List<BoundingBox> targets = const []}) => computeSnap(
        box: box,
        rawDx: dx,
        rawDy: dy,
        pageWidth: pageW,
        pageHeight: pageH,
        marginPx: margin,
        thresholdPx: thresh,
        objectTargets: targets,
      );

  test('no guide within threshold → raw delta, no guides', () {
    final r = snap(200, 5);
    expect(r.dx, 200);
    expect(r.dy, 5);
    expect(r.guides, isEmpty);
  });

  test('left edge pulls the left anchor flush to x=0', () {
    // movedLeft = 100 - 95 = 5 (nearest of all anchors: 5px from guide 0) → adjust by -5.
    final r = snap(-95, 0);
    expect(r.dx, closeTo(-100, 1e-9)); // movedLeft lands on 0
    expect(r.guides.whereType<VerticalGuide>().single.x, 0);
    expect(r.guides.whereType<HorizontalGuide>(), isEmpty);
  });

  test('page center snaps the center anchor', () {
    // movedCenterX = 125 + 375 = 500 = pageW/2.
    final r = snap(375, 0);
    expect(r.dx, 375);
    expect(r.guides.whereType<VerticalGuide>().single.x, 500);
  });

  test('object left edge is a snap target', () {
    const target = BoundingBox(400, 100, 100, 50);
    // movedLeft = 100 + 300 = 400 = target.left.
    final r = snap(300, 0, targets: [target]);
    expect(r.guides.whereType<VerticalGuide>().single.x, 400);
    expect(r.dx, 300);
  });

  test('X and Y snap independently', () {
    // X snaps left→0 (movedLeft 5); Y snaps top→margin 44 (movedTop 40, 4px away — nearest anchor).
    final r = snap(-95, -60);
    expect(r.dx, closeTo(-100, 1e-9));
    expect(r.dy, closeTo(-56, 1e-9)); // movedTop lands on 44
    expect(r.guides.whereType<VerticalGuide>().single.x, 0);
    expect(r.guides.whereType<HorizontalGuide>().single.y, 44);
  });
}
