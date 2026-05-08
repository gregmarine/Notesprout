import 'base_object.dart';

class PageModel extends BaseObject {
  final int pageNumber;
  final double width;
  final double height;

  const PageModel({
    required super.id,
    super.parentId,
    super.subtype,
    required super.createdAt,
    required super.updatedAt,
    super.deletedAt,
    required this.pageNumber,
    required this.width,
    required this.height,
  }) : super(type: 'page');

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'parentId': parentId,
      'type': type,
      'subtype': subtype,
      'createdAt': createdAt.millisecondsSinceEpoch,
      'updatedAt': updatedAt.millisecondsSinceEpoch,
      'deletedAt': deletedAt?.millisecondsSinceEpoch,
      'pageNumber': pageNumber,
      'width': width,
      'height': height,
    };
  }

  factory PageModel.fromMap(Map<String, dynamic> map) {
    return PageModel(
      id: map['id'] as String,
      parentId: map['parentId'] as String?,
      subtype: map['subtype'] as String?,
      createdAt: DateTime.fromMillisecondsSinceEpoch(map['createdAt'] as int),
      updatedAt: DateTime.fromMillisecondsSinceEpoch(map['updatedAt'] as int),
      deletedAt: map['deletedAt'] != null
          ? DateTime.fromMillisecondsSinceEpoch(map['deletedAt'] as int)
          : null,
      pageNumber: map['pageNumber'] as int,
      width: (map['width'] as num).toDouble(),
      height: (map['height'] as num).toDouble(),
    );
  }
}
