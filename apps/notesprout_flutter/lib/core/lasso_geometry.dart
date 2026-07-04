import 'dart:math' as math;

import '../domain/page_object.dart';
import '../domain/stroke.dart';
import 'geometry.dart';

/// Shared lasso + pen-gesture geometry — a 1:1 port of the native
/// `notebook/LassoGeometry.kt` + the smart-lasso/scribble detectors in
/// `notebook/OnyxNotebookView.kt`, using the same constants from
/// `notebook/NotebookConstants.kt`. Keeping selection lasso, lasso tool, smart-lasso, and
/// scribble-erase on one set of helpers means the hit-test sites cannot drift apart (the same
/// reason native centralises it).
///
/// All coordinates are physical px (the wire coordinate space of strokes/objects). Native scales
/// its dp thresholds by `displayMetrics.density`; here that scale is [dpr]. Velocity is raw px/ms,
/// unnormalised — exactly as native computes it.

// ── Smart-lasso constants (dp / raw) ─────────────────────────────────────────
const double kSmartLassoMinVelocity = 0.5; // px/ms
const double kSmartLassoClosureDistanceDp = 50.0;
const double kSmartLassoMinWindingDegrees = 270.0;

// ── Scribble-to-erase constants ──────────────────────────────────────────────
const double kScribbleDensityRatio = 3.0;
const int kScribbleMinDirectionReversals = 2;
const double kScribbleMinDiagonalDp = 40.0;
const double kScribbleBboxPenetrationDp = 14.0;
const double kScribbleStrokeTouchRadiusDp = 8.0;

// ── Lasso selection constants ────────────────────────────────────────────────
const double kLassoMinSizeDp = 10.0; // ignore accidental taps / trivially small paths
const double kLassoOverlayPadDp = 8.0; // dashed overlay inset so it doesn't sit on the ink

// ── Basic path measures ──────────────────────────────────────────────────────

double lassoPathLength(List<StrokePoint> pts) {
  var len = 0.0;
  for (var i = 1; i < pts.length; i++) {
    final dx = pts[i].x - pts[i - 1].x;
    final dy = pts[i].y - pts[i - 1].y;
    len += math.sqrt(dx * dx + dy * dy);
  }
  return len;
}

/// Signed angular sweep (degrees, absolute) the path winds around its own centroid. A loop wins
/// ~360°; letters/open arcs never reach 270°. Deltas are unwrapped to [-π, π] so we measure true
/// incremental rotation, not atan2 wrap jumps.
double lassoWindingDegrees(List<StrokePoint> pts) {
  var cx = 0.0, cy = 0.0;
  for (final p in pts) {
    cx += p.x;
    cy += p.y;
  }
  cx /= pts.length;
  cy /= pts.length;

  var total = 0.0;
  var prev = math.atan2(pts[0].y - cy, pts[0].x - cx);
  for (var i = 1; i < pts.length; i++) {
    final angle = math.atan2(pts[i].y - cy, pts[i].x - cx);
    var delta = angle - prev;
    while (delta > math.pi) {
      delta -= 2 * math.pi;
    }
    while (delta < -math.pi) {
      delta += 2 * math.pi;
    }
    total += delta;
    prev = angle;
  }
  return (total * 180 / math.pi).abs();
}

// ── Gate 1: smart lasso ──────────────────────────────────────────────────────

/// True when [pts] form a smart-lasso candidate — all three native gates: velocity (px/ms),
/// first-to-last closure (dp), and ≥270° winding around the centroid. [durationMs] is the
/// pen-contact time; [dpr] scales the dp closure threshold.
bool isSmartLassoCandidate(List<StrokePoint> pts, int durationMs, double dpr) {
  if (pts.length < 4 || durationMs <= 0) return false;

  final len = lassoPathLength(pts);
  if (len / durationMs < kSmartLassoMinVelocity) return false;

  final closurePx = kSmartLassoClosureDistanceDp * dpr;
  final cdx = pts.last.x - pts.first.x;
  final cdy = pts.last.y - pts.first.y;
  if (math.sqrt(cdx * cdx + cdy * cdy) > closurePx) return false;

  return lassoWindingDegrees(pts) >= kSmartLassoMinWindingDegrees;
}

// ── Gate 2: scribble-to-erase ────────────────────────────────────────────────

/// True when [pts] satisfy both scribble heuristics: density (pathLength / bbox-diagonal) after a
/// minimum-diagonal guard, and ≥2 significant direction reversals on the noise-filtered path.
bool isScribbleCandidate(List<StrokePoint> pts, double dpr) {
  if (pts.length < 4) return false;

  var len = 0.0;
  var minX = pts[0].x, minY = pts[0].y, maxX = pts[0].x, maxY = pts[0].y;
  for (var i = 1; i < pts.length; i++) {
    final dx = pts[i].x - pts[i - 1].x;
    final dy = pts[i].y - pts[i - 1].y;
    len += math.sqrt(dx * dx + dy * dy);
    if (pts[i].x < minX) {
      minX = pts[i].x;
    } else if (pts[i].x > maxX) {
      maxX = pts[i].x;
    }
    if (pts[i].y < minY) {
      minY = pts[i].y;
    } else if (pts[i].y > maxY) {
      maxY = pts[i].y;
    }
  }
  final dw = maxX - minX, dh = maxY - minY;
  final diagonal = math.sqrt(dw * dw + dh * dh);
  if (diagonal < kScribbleMinDiagonalDp * dpr) return false;
  if (len / diagonal < kScribbleDensityRatio) return false;

  // Noise-filter: keep points > 2px apart to reduce stylus jitter.
  final filtered = <StrokePoint>[pts[0]];
  for (final p in pts) {
    final last = filtered.last;
    final dx = p.x - last.x, dy = p.y - last.y;
    if (dx * dx + dy * dy >= 4.0) filtered.add(p);
  }
  if (filtered.length < 3) return false;

  var reversals = 0;
  for (var i = 2; i < filtered.length; i++) {
    final ax = filtered[i - 1].x - filtered[i - 2].x;
    final ay = filtered[i - 1].y - filtered[i - 2].y;
    final bx = filtered[i].x - filtered[i - 1].x;
    final by = filtered[i].y - filtered[i - 1].y;
    if (ax * bx + ay * by < 0) reversals++;
  }
  return reversals >= kScribbleMinDirectionReversals;
}

// ── Hit-testing ──────────────────────────────────────────────────────────────

/// Result of a lasso hit-test: the ids of enclosed/overlapped objects and the padded union of
/// their bounding boxes (for the dashed selection overlay). [ids] empty ⇒ nothing caught.
class LassoHit {
  const LassoHit(this.ids, this.bounds);
  final List<String> ids;
  final BoundingBox? bounds; // null when ids is empty
}

/// Does the closed polygon [loop] overlap axis-aligned box [b] at all? Mirrors native's filled
/// `Region ∩ box` "touch" semantics (select if the loop crosses any part of the object, not just
/// its center): true if any box corner is inside the loop, any loop vertex is inside the box, or
/// any loop edge crosses any box edge.
bool polygonIntersectsRect(List<StrokePoint> loop, BoundingBox b) {
  final l = b.x, t = b.y, r = b.x + b.width, bot = b.y + b.height;
  // Box corner inside the loop?
  if (pointInPolygon(l, t, loop) ||
      pointInPolygon(r, t, loop) ||
      pointInPolygon(r, bot, loop) ||
      pointInPolygon(l, bot, loop)) {
    return true;
  }
  // Loop vertex inside the box?
  for (final p in loop) {
    if (p.x >= l && p.x <= r && p.y >= t && p.y <= bot) return true;
  }
  // Edge crossing (loop treated as closed: last→first included).
  final corners = [
    [l, t],
    [r, t],
    [r, bot],
    [l, bot],
  ];
  for (var i = 0; i < loop.length; i++) {
    final a = loop[i];
    final c = loop[(i + 1) % loop.length];
    for (var k = 0; k < 4; k++) {
      final e0 = corners[k];
      final e1 = corners[(k + 1) % 4];
      if (_segmentsIntersect(a.x, a.y, c.x, c.y, e0[0], e0[1], e1[0], e1[1])) return true;
    }
  }
  return false;
}

bool _segmentsIntersect(
    double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
  final d1 = _cross(cx, cy, dx, dy, ax, ay);
  final d2 = _cross(cx, cy, dx, dy, bx, by);
  final d3 = _cross(ax, ay, bx, by, cx, cy);
  final d4 = _cross(ax, ay, bx, by, dx, dy);
  if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
      ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
    return true;
  }
  return false;
}

double _cross(double ox, double oy, double ax, double ay, double bx, double by) =>
    (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);

/// Run the closed lasso [loop] against [objects] (same rules as native `runLassoHitTest`):
/// AABB pre-filter, then — strokes: hit if ANY stroke point is inside the loop; atomic objects
/// (heading/text/line): hit if the loop overlaps the bbox at all. Returns hit ids + padded union
/// bounds. Enforces the [kLassoMinSizeDp] minimum-size guard. [dpr] scales dp thresholds.
LassoHit lassoHitTest(List<StrokePoint> loop, List<PageObject> objects, double dpr) {
  if (loop.length < 3) return const LassoHit([], null);

  var minX = loop[0].x, minY = loop[0].y, maxX = loop[0].x, maxY = loop[0].y;
  for (final p in loop) {
    if (p.x < minX) minX = p.x;
    if (p.x > maxX) maxX = p.x;
    if (p.y < minY) minY = p.y;
    if (p.y > maxY) maxY = p.y;
  }
  final loopBox = BoundingBox(minX, minY, maxX - minX, maxY - minY);
  final minPx = kLassoMinSizeDp * dpr;
  if (loopBox.width < minPx && loopBox.height < minPx) return const LassoHit([], null);

  final ids = <String>[];
  double? uMinX, uMinY, uMaxX, uMaxY;
  void union(BoundingBox b) {
    uMinX = uMinX == null ? b.x : math.min(uMinX!, b.x);
    uMinY = uMinY == null ? b.y : math.min(uMinY!, b.y);
    uMaxX = uMaxX == null ? b.x + b.width : math.max(uMaxX!, b.x + b.width);
    uMaxY = uMaxY == null ? b.y + b.height : math.max(uMaxY!, b.y + b.height);
  }

  for (final o in objects) {
    if (!_aabbOverlap(loopBox, o.box)) continue;
    var hit = false;
    if (o is StrokeObject) {
      for (final p in o.data.points) {
        if (pointInPolygon(p.x, p.y, loop)) {
          hit = true;
          break;
        }
      }
    } else {
      hit = polygonIntersectsRect(loop, o.box);
    }
    if (hit) {
      ids.add(o.id);
      union(o.box);
    }
  }

  if (ids.isEmpty) return const LassoHit([], null);
  final pad = kLassoOverlayPadDp * dpr;
  final bounds = BoundingBox(
    uMinX! - pad,
    uMinY! - pad,
    (uMaxX! - uMinX!) + 2 * pad,
    (uMaxY! - uMinY!) + 2 * pad,
  );
  return LassoHit(ids, bounds);
}

bool _aabbOverlap(BoundingBox a, BoundingBox b) =>
    a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y;

// ── Scribble-erase hit-test ──────────────────────────────────────────────────

/// Which objects a confirmed scribble [gesture] erases (native `scribbleHitTest`): strokes are hit
/// when any stroke point falls within [kScribbleStrokeTouchRadiusDp] of any scribble point; atomic
/// objects (heading/text/line) are hit when the scribble's total travel INSIDE the bbox reaches
/// [kScribbleBboxPenetrationDp] — so a corner-graze doesn't trigger an accidental erase.
List<String> scribbleHitTest(List<StrokePoint> gesture, List<PageObject> objects, double dpr) {
  final r2 = (kScribbleStrokeTouchRadiusDp * dpr) * (kScribbleStrokeTouchRadiusDp * dpr);
  final penThresh = kScribbleBboxPenetrationDp * dpr;
  final hits = <String>[];
  for (final o in objects) {
    var hit = false;
    if (o is StrokeObject) {
      for (final sp in o.data.points) {
        for (final gp in gesture) {
          final dx = sp.x - gp.x, dy = sp.y - gp.y;
          if (dx * dx + dy * dy <= r2) {
            hit = true;
            break;
          }
        }
        if (hit) break;
      }
    } else {
      var penetration = 0.0;
      for (var i = 1; i < gesture.length; i++) {
        final a = gesture[i - 1], b = gesture[i];
        if (_pointInBox(a, o.box) || _pointInBox(b, o.box)) {
          final dx = b.x - a.x, dy = b.y - a.y;
          penetration += math.sqrt(dx * dx + dy * dy);
        }
      }
      hit = penetration >= penThresh;
    }
    if (hit) hits.add(o.id);
  }
  return hits;
}

bool _pointInBox(StrokePoint p, BoundingBox b) =>
    p.x >= b.x && p.x <= b.x + b.width && p.y >= b.y && p.y <= b.y + b.height;
