import 'dart:convert';

import 'base_object.dart';

class StrokePoint {
  final double x;
  final double y;
  final double pressure;
  final double tilt;
  final int timestamp;

  const StrokePoint({
    required this.x,
    required this.y,
    required this.pressure,
    required this.tilt,
    required this.timestamp,
  });

  Map<String, dynamic> toMap() {
    return {
      'x': x,
      'y': y,
      'pressure': pressure,
      'tilt': tilt,
      'timestamp': timestamp,
    };
  }

  factory StrokePoint.fromMap(Map<String, dynamic> map) {
    return StrokePoint(
      x: (map['x'] as num).toDouble(),
      y: (map['y'] as num).toDouble(),
      pressure: (map['pressure'] as num).toDouble(),
      tilt: (map['tilt'] as num).toDouble(),
      timestamp: map['timestamp'] as int,
    );
  }
}

class StrokeModel extends BaseObject {
  final List<StrokePoint> points;
  final int color;
  final double width;

  const StrokeModel({
    required super.id,
    super.parentId,
    super.subtype,
    required super.createdAt,
    required super.updatedAt,
    super.deletedAt,
    required this.points,
    required this.color,
    required this.width,
  }) : super(type: 'stroke');

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'parentId': parentId,
      'type': type,
      'subtype': subtype,
      'createdAt': createdAt.millisecondsSinceEpoch,
      'updatedAt': updatedAt.millisecondsSinceEpoch,
      'deletedAt': deletedAt?.millisecondsSinceEpoch,
      'points': jsonEncode(points.map((p) => p.toMap()).toList()),
      'color': color,
      'width': width,
    };
  }

  factory StrokeModel.fromMap(Map<String, dynamic> map) {
    final rawPoints = jsonDecode(map['points'] as String) as List<dynamic>;
    return StrokeModel(
      id: map['id'] as String,
      parentId: map['parentId'] as String?,
      subtype: map['subtype'] as String?,
      createdAt: DateTime.fromMillisecondsSinceEpoch(map['createdAt'] as int),
      updatedAt: DateTime.fromMillisecondsSinceEpoch(map['updatedAt'] as int),
      deletedAt: map['deletedAt'] != null
          ? DateTime.fromMillisecondsSinceEpoch(map['deletedAt'] as int)
          : null,
      points: rawPoints
          .map((p) => StrokePoint.fromMap(p as Map<String, dynamic>))
          .toList(),
      color: map['color'] as int,
      width: (map['width'] as num).toDouble(),
    );
  }
}
