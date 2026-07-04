import 'dart:convert';

import 'package:sqlite3/sqlite3.dart';
import 'package:uuid/uuid.dart';

const _uuid = Uuid();

/// One notebook entry as seen by the global index (folder/notebook tree lives here, never on disk).
class NotebookEntry {
  NotebookEntry(this.id, this.name, this.updatedAt, this.pageCount);
  final String id;
  final String name;
  final int updatedAt;
  final int pageCount;
}

/// The global index (`notesprout.db`) — the universal `objects` table. Owns the notebook/folder
/// tree; plaintext, always open. Ports the used slice of `NotesproutDatabase` + `IndexRepository`.
class IndexDatabase {
  IndexDatabase._(this._db);

  final Database _db;

  static IndexDatabase open(String path) {
    final db = sqlite3.open(path);
    db.select('PRAGMA journal_mode = WAL');
    db.select('PRAGMA wal_autocheckpoint = 100');
    db.execute('''
      CREATE TABLE IF NOT EXISTS objects (
        id         TEXT    PRIMARY KEY NOT NULL,
        type       TEXT    NOT NULL,
        name       TEXT    NOT NULL,
        parentId   TEXT,
        createdAt  INTEGER NOT NULL,
        updatedAt  INTEGER NOT NULL,
        deletedAt  INTEGER,
        data       TEXT    NOT NULL DEFAULT '{}'
      )
    ''');
    db.execute(
        'CREATE INDEX IF NOT EXISTS idx_objects_parent_type_deleted ON objects(parentId, type, deletedAt)');
    return IndexDatabase._(db);
  }

  int get _now => DateTime.now().millisecondsSinceEpoch;

  /// Create a notebook index row. The returned id is the `.soil` filename uuid.
  String createNotebook(String name, {String? parentId}) {
    final id = _uuid.v4();
    final now = _now;
    _db.execute(
      'INSERT INTO objects (id, type, name, parentId, createdAt, updatedAt, deletedAt, data) '
      'VALUES (?, ?, ?, ?, ?, ?, NULL, ?)',
      [id, 'notebook', name, parentId, now, now, jsonEncode({'pageCount': 1, 'encrypted': false})],
    );
    return id;
  }

  /// All live notebooks, most-recently-updated first.
  List<NotebookEntry> notebooks() {
    final rows = _db.select(
        "SELECT id, name, updatedAt, data FROM objects WHERE type = 'notebook' AND deletedAt IS NULL ORDER BY updatedAt DESC");
    return rows.map((r) {
      final data = jsonDecode(r['data'] as String) as Map<String, dynamic>;
      return NotebookEntry(
        r['id'] as String,
        r['name'] as String,
        r['updatedAt'] as int,
        (data['pageCount'] as num?)?.toInt() ?? 1,
      );
    }).toList();
  }

  /// Bump updatedAt (call after edits so the notebook sorts to the top).
  void touchNotebook(String id, {int? pageCount}) {
    final now = _now;
    if (pageCount != null) {
      _db.execute(
        "UPDATE objects SET updatedAt = ?, data = json_set(data, '\$.pageCount', ?) WHERE id = ?",
        [now, pageCount, id],
      );
    } else {
      _db.execute('UPDATE objects SET updatedAt = ? WHERE id = ?', [now, id]);
    }
  }

  void softDeleteNotebook(String id) {
    final now = _now;
    _db.execute('UPDATE objects SET deletedAt = ?, updatedAt = ? WHERE id = ?', [now, now, id]);
  }

  void close() {
    _db.select('PRAGMA wal_checkpoint(TRUNCATE)');
    _db.close();
  }
}
