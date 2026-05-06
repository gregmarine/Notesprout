import 'base_object.dart';

class LayerModel extends BaseObject {
  final bool isLocked;
  final bool isVisible;
  final double opacity;

  const LayerModel({
    required super.id,
    super.parentId,
    super.subtype,
    required super.createdAt,
    required super.updatedAt,
    super.deletedAt,
    this.isLocked = false,
    this.isVisible = true,
    this.opacity = 1.0,
  }) : super(type: 'layer');

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'parentId': parentId,
      'type': type,
      'subtype': subtype,
      'createdAt': createdAt.millisecondsSinceEpoch,
      'updatedAt': updatedAt.millisecondsSinceEpoch,
      'deletedAt': deletedAt?.millisecondsSinceEpoch,
      'isLocked': isLocked ? 1 : 0,
      'isVisible': isVisible ? 1 : 0,
      'opacity': opacity,
    };
  }

  factory LayerModel.fromMap(Map<String, dynamic> map) {
    return LayerModel(
      id: map['id'] as String,
      parentId: map['parentId'] as String?,
      subtype: map['subtype'] as String?,
      createdAt: DateTime.fromMillisecondsSinceEpoch(map['createdAt'] as int),
      updatedAt: DateTime.fromMillisecondsSinceEpoch(map['updatedAt'] as int),
      deletedAt: map['deletedAt'] != null
          ? DateTime.fromMillisecondsSinceEpoch(map['deletedAt'] as int)
          : null,
      isLocked: (map['isLocked'] as int) == 1,
      isVisible: (map['isVisible'] as int) == 1,
      opacity: (map['opacity'] as num).toDouble(),
    );
  }
}
