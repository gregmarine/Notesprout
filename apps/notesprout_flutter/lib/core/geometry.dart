import '../domain/stroke.dart';

/// Ray-casting point-in-polygon test. [poly] is the lasso loop (px); returns true when (x,y) is
/// inside. Used to select objects whose center falls within a drawn lasso — matching the native
/// app's center-point containment rule.
bool pointInPolygon(double x, double y, List<StrokePoint> poly) {
  var inside = false;
  for (var i = 0, j = poly.length - 1; i < poly.length; j = i++) {
    final xi = poly[i].x, yi = poly[i].y, xj = poly[j].x, yj = poly[j].y;
    if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) inside = !inside;
  }
  return inside;
}
