abstract class BaseObject {
  final String id;
  final String? parentId;
  final String type;
  final String? subtype;
  final DateTime createdAt;
  final DateTime updatedAt;
  final DateTime? deletedAt;

  const BaseObject({
    required this.id,
    this.parentId,
    required this.type,
    this.subtype,
    required this.createdAt,
    required this.updatedAt,
    this.deletedAt,
  });
}
