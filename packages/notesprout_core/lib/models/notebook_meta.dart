class NotebookMeta {
  final String id;
  final String name;
  final DateTime createdAt;
  final DateTime updatedAt;
  final int syncVersion;
  final double pageWidth;
  final double pageHeight;

  const NotebookMeta({
    required this.id,
    required this.name,
    required this.createdAt,
    required this.updatedAt,
    this.syncVersion = 0,
    required this.pageWidth,
    required this.pageHeight,
  });

  NotebookMeta copyWith({
    String? name,
    DateTime? updatedAt,
    int? syncVersion,
    double? pageWidth,
    double? pageHeight,
  }) {
    return NotebookMeta(
      id: id,
      name: name ?? this.name,
      createdAt: createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      syncVersion: syncVersion ?? this.syncVersion,
      pageWidth: pageWidth ?? this.pageWidth,
      pageHeight: pageHeight ?? this.pageHeight,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'createdAt': createdAt.millisecondsSinceEpoch,
      'updatedAt': updatedAt.millisecondsSinceEpoch,
      'syncVersion': syncVersion,
      'pageWidth': pageWidth,
      'pageHeight': pageHeight,
    };
  }

  factory NotebookMeta.fromMap(Map<String, dynamic> map) {
    return NotebookMeta(
      id: map['id'] as String,
      name: map['name'] as String,
      createdAt: DateTime.fromMillisecondsSinceEpoch(map['createdAt'] as int),
      updatedAt: DateTime.fromMillisecondsSinceEpoch(map['updatedAt'] as int),
      syncVersion: map['syncVersion'] as int,
      pageWidth: (map['pageWidth'] as num).toDouble(),
      pageHeight: (map['pageHeight'] as num).toDouble(),
    );
  }
}
