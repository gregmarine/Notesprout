import '../domain/stroke.dart';

/// Snap-to-guide during a lasso drag — a 1:1 port of the native `notebook/SnapEngine.kt` +
/// `SnapGuide.kt`, using `SNAP_MARGIN_DP` / `SNAP_THRESHOLD_DP` from `NotebookConstants.kt`.
/// All coordinates are physical px.

const double kSnapMarginDp = 44.0; // page-edge margin inset; also the object proximity gap
const double kSnapThresholdDp = 20.0; // max anchor→guide distance for a snap to engage

sealed class SnapGuide {
  const SnapGuide();
}

class VerticalGuide extends SnapGuide {
  const VerticalGuide(this.x);
  final double x;
}

class HorizontalGuide extends SnapGuide {
  const HorizontalGuide(this.y);
  final double y;
}

class SnapResult {
  const SnapResult(this.dx, this.dy, this.guides);
  final double dx;
  final double dy;
  final List<SnapGuide> guides;
}

/// Given the selection box in its *original* (pre-drag) position and the raw pointer delta, return
/// the snapped delta plus any active guide lines to draw. Each axis snaps independently: the nearest
/// (anchor → guide) pair within [thresholdPx] wins. Anchors are the box's left/center/right (X) and
/// top/center/bottom (Y). Guides are the page edges/margins/center plus, per non-selected target
/// object, its edges/center and ±[marginPx] proximity lines (strokes are never targets — the caller
/// excludes them from [objectTargets]).
SnapResult computeSnap({
  required BoundingBox box,
  required double rawDx,
  required double rawDy,
  required double pageWidth,
  required double pageHeight,
  required double marginPx,
  required double thresholdPx,
  List<BoundingBox> objectTargets = const [],
}) {
  final movedLeft = box.x + rawDx;
  final movedCenterX = box.x + box.width / 2 + rawDx;
  final movedRight = box.x + box.width + rawDx;
  final movedTop = box.y + rawDy;
  final movedCenterY = box.y + box.height / 2 + rawDy;
  final movedBottom = box.y + box.height + rawDy;

  final vGuides = <double>[0, marginPx, pageWidth / 2, pageWidth - marginPx, pageWidth];
  for (final t in objectTargets) {
    vGuides
      ..add(t.x - marginPx)
      ..add(t.x)
      ..add(t.x + t.width / 2)
      ..add(t.x + t.width)
      ..add(t.x + t.width + marginPx);
  }
  final hGuides = <double>[0, marginPx, pageHeight / 2, pageHeight - marginPx, pageHeight];
  for (final t in objectTargets) {
    hGuides
      ..add(t.y - marginPx)
      ..add(t.y)
      ..add(t.y + t.height / 2)
      ..add(t.y + t.height)
      ..add(t.y + t.height + marginPx);
  }

  double bestXAdj = double.maxFinite;
  double? snapXAt;
  for (final anchor in [movedLeft, movedCenterX, movedRight]) {
    for (final guide in vGuides) {
      final dist = (anchor - guide).abs();
      if (dist < thresholdPx && dist < bestXAdj.abs()) {
        bestXAdj = guide - anchor;
        snapXAt = guide;
      }
    }
  }

  double bestYAdj = double.maxFinite;
  double? snapYAt;
  for (final anchor in [movedTop, movedCenterY, movedBottom]) {
    for (final guide in hGuides) {
      final dist = (anchor - guide).abs();
      if (dist < thresholdPx && dist < bestYAdj.abs()) {
        bestYAdj = guide - anchor;
        snapYAt = guide;
      }
    }
  }

  final dx = snapXAt != null ? rawDx + bestXAdj : rawDx;
  final dy = snapYAt != null ? rawDy + bestYAdj : rawDy;
  final guides = <SnapGuide>[
    if (snapXAt != null) VerticalGuide(snapXAt),
    if (snapYAt != null) HorizontalGuide(snapYAt),
  ];
  return SnapResult(dx, dy, guides);
}
