import 'dart:convert';

/// Wire-compatible ports of the Kotlin serialization models so `.soil` files created by the
/// native app open unchanged (and vice-versa). Field presence matches the kotlinx.serialization
/// output: null pressure/tilt/ts are omitted; BoundingBox always writes all four fields.

/// `{x, y, width, height}` — the `boundingBox` column of every `notebook` row.
class BoundingBox {
  const BoundingBox(this.x, this.y, this.width, this.height);

  final double x;
  final double y;
  final double width;
  final double height;

  Map<String, dynamic> toMap() => {'x': x, 'y': y, 'width': width, 'height': height};
  String toJson() => jsonEncode(toMap());

  static BoundingBox fromJson(String json) {
    final m = jsonDecode(json) as Map<String, dynamic>;
    return BoundingBox(
      (m['x'] as num?)?.toDouble() ?? 0,
      (m['y'] as num?)?.toDouble() ?? 0,
      (m['width'] as num?)?.toDouble() ?? 0,
      (m['height'] as num?)?.toDouble() ?? 0,
    );
  }

  /// AABB over [points]; used to fill a stroke row's `boundingBox` at insert time.
  static BoundingBox ofPoints(List<StrokePoint> points) {
    var minX = points.first.x, minY = points.first.y;
    var maxX = minX, maxY = minY;
    for (final p in points) {
      if (p.x < minX) minX = p.x;
      if (p.x > maxX) maxX = p.x;
      if (p.y < minY) minY = p.y;
      if (p.y > maxY) maxY = p.y;
    }
    return BoundingBox(minX, minY, maxX - minX, maxY - minY);
  }
}

/// A single input sample. pressure/tilt/ts are optional and omitted from JSON when null.
class StrokePoint {
  const StrokePoint(this.x, this.y, {this.pressure, this.tilt});

  final double x;
  final double y;
  final double? pressure;
  final double? tilt;

  Map<String, dynamic> toMap() => {
        'x': x,
        'y': y,
        if (pressure != null) 'pressure': pressure,
        if (tilt != null) 'tilt': tilt,
      };

  static StrokePoint fromMap(Map<String, dynamic> m) => StrokePoint(
        (m['x'] as num).toDouble(),
        (m['y'] as num).toDouble(),
        pressure: (m['pressure'] as num?)?.toDouble(),
        tilt: (m['tilt'] as num?)?.toDouble(),
      );
}

/// The `data` payload of a `type="stroke"` row: `{color, strokeWidth, points:[{x,y}...]}`.
class StrokeData {
  const StrokeData({
    this.color = '#000000',
    this.strokeWidth = 3.0,
    required this.points,
  });

  final String color;
  final double strokeWidth;
  final List<StrokePoint> points;

  Map<String, dynamic> toMap() => {
        'color': color,
        'strokeWidth': strokeWidth,
        'points': points.map((p) => p.toMap()).toList(),
      };

  String toJson() => jsonEncode(toMap());

  static StrokeData fromJson(String json) {
    final m = jsonDecode(json) as Map<String, dynamic>;
    return StrokeData(
      color: (m['color'] as String?) ?? '#000000',
      strokeWidth: (m['strokeWidth'] as num?)?.toDouble() ?? 3.0,
      points: ((m['points'] as List?) ?? const [])
          .map((e) => StrokePoint.fromMap(e as Map<String, dynamic>))
          .toList(),
    );
  }
}

/// The `data` payload of a `type="page"` row: `{width, height, template, snapshot?}`.
class PageData {
  const PageData({
    this.width = 0,
    this.height = 0,
    this.template = '',
    this.snapshot,
  });

  final double width;
  final double height;
  final String template;
  final String? snapshot;

  Map<String, dynamic> toMap() => {
        'width': width,
        'height': height,
        'template': template,
        if (snapshot != null) 'snapshot': snapshot,
      };

  String toJson() => jsonEncode(toMap());

  static PageData fromJson(String json) {
    final m = jsonDecode(json) as Map<String, dynamic>;
    return PageData(
      width: (m['width'] as num?)?.toDouble() ?? 0,
      height: (m['height'] as num?)?.toDouble() ?? 0,
      template: (m['template'] as String?) ?? '',
      snapshot: m['snapshot'] as String?,
    );
  }
}
