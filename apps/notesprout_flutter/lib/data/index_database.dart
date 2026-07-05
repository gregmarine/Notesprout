import 'dart:convert';

import 'package:sqlite3/sqlite3.dart';
import 'package:uuid/uuid.dart';

import 'recents.dart';

const _uuid = Uuid();

/// One notebook entry as seen by the global index (folder/notebook tree lives here, never on disk).
class NotebookEntry {
  NotebookEntry(this.id, this.name, this.updatedAt, this.pageCount);
  final String id;
  final String name;
  final int updatedAt;
  final int pageCount;
}

/// A row in the browsable library tree — a folder or a notebook. The library shows folders and
/// notebooks side by side (ports the native `ObjectEntity` folder/notebook rows). `pageCount` is
/// meaningful only for notebooks.
class LibraryEntry {
  LibraryEntry({
    required this.id,
    required this.name,
    required this.isFolder,
    required this.parentId,
    required this.updatedAt,
    this.pageCount = 0,
  });
  final String id;
  final String name;
  final bool isFolder;
  final String? parentId;
  final int updatedAt;
  final int pageCount;
}

/// One segment of the breadcrumb trail. Root is `Crumb(null, 'Notebooks')`.
class Crumb {
  const Crumb(this.id, this.name);
  final String? id; // null == root
  final String name;
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

  /// Create a folder index row under [parentId] (null == root). Returns the folder id.
  String createFolder(String name, {String? parentId}) {
    final id = _uuid.v4();
    final now = _now;
    _db.execute(
      'INSERT INTO objects (id, type, name, parentId, createdAt, updatedAt, deletedAt, data) '
      "VALUES (?, 'folder', ?, ?, ?, ?, NULL, '{}')",
      [id, name, parentId, now, now],
    );
    return id;
  }

  /// Direct children (folders + notebooks) of [parentId] (null == root), folders first, then each
  /// group alphabetical. `parentId IS ?` is null-safe, so a null bind matches root rows.
  List<LibraryEntry> children(String? parentId) {
    final rows = _db.select(
      "SELECT id, type, name, parentId, updatedAt, data FROM objects "
      "WHERE deletedAt IS NULL AND type IN ('folder','notebook') AND parentId IS ? "
      "ORDER BY CASE type WHEN 'folder' THEN 0 ELSE 1 END, name COLLATE NOCASE",
      [parentId],
    );
    return rows.map((r) {
      final isFolder = r['type'] == 'folder';
      var pages = 0;
      if (!isFolder) {
        final data = jsonDecode(r['data'] as String) as Map<String, dynamic>;
        pages = (data['pageCount'] as num?)?.toInt() ?? 1;
      }
      return LibraryEntry(
        id: r['id'] as String,
        name: r['name'] as String,
        isFolder: isFolder,
        parentId: r['parentId'] as String?,
        updatedAt: r['updatedAt'] as int,
        pageCount: pages,
      );
    }).toList();
  }

  /// The breadcrumb trail from root down to [folderId] (null == root → just the root crumb).
  /// Walks the `parentId` chain; stops if a link is missing/deleted (stale folder → partial trail).
  List<Crumb> breadcrumb(String? folderId) {
    final trail = <Crumb>[];
    var cur = folderId;
    while (cur != null) {
      final rows = _db.select(
        "SELECT id, name, parentId FROM objects WHERE id = ? AND type = 'folder' AND deletedAt IS NULL",
        [cur],
      );
      if (rows.isEmpty) break;
      final r = rows.first;
      trail.insert(0, Crumb(r['id'] as String, r['name'] as String));
      cur = r['parentId'] as String?;
    }
    trail.insert(0, const Crumb(null, 'Notebooks'));
    return trail;
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

  /// Resolve device-local recent entries against the index (ports native `RecentsManager.resolve`):
  /// drops entries whose notebook is missing/deleted, keeps input order (already newest-first), and
  /// attaches the display name + full folder breadcrumb (`"Notebooks › A › B"`).
  List<ResolvedRecent> resolveRecents(List<RecentEntry> entries) {
    final out = <ResolvedRecent>[];
    for (final e in entries) {
      final rows = _db.select(
        "SELECT name, parentId FROM objects WHERE id = ? AND type = 'notebook' AND deletedAt IS NULL",
        [e.notebookId],
      );
      if (rows.isEmpty) continue; // pruned: notebook gone
      final folderPath =
          breadcrumb(rows.first['parentId'] as String?).map((c) => c.name).join(' › ');
      out.add(ResolvedRecent(e.notebookId, rows.first['name'] as String, folderPath, e.timestamp));
    }
    return out;
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
