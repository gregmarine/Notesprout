import 'package:sqlite3/sqlite3.dart';
import 'package:uuid/uuid.dart';

import '../domain/stroke.dart';

const _uuid = Uuid();

/// A page and its single content layer, resolved together for stroke I/O.
class PageRef {
  PageRef(this.pageId, this.layerId, this.data, this.order);
  final String pageId;
  final String layerId;
  final PageData data;
  final int order;
}

/// One committed stroke read from / written to the `.soil`.
class StrokeRow {
  StrokeRow(this.id, this.data);
  final String id;
  final StrokeData data;
}

/// A single notebook's `.soil` file — the universal `notebook` table. Schema/DDL is byte-identical
/// to the native app's `NotebookFactory.createBlankNotebook`, so files interoperate both ways.
class SoilDatabase {
  SoilDatabase._(this._db);

  final Database _db;

  static const _insert =
      'INSERT INTO notebook (id, parentId, boundingBox, "order", createdAt, updatedAt, deletedAt, type, data) '
      'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)';

  /// Open (or create) the `.soil` at [path], applying the WAL pragmas and ensuring the schema.
  static SoilDatabase open(String path) {
    final db = sqlite3.open(path);
    db.select('PRAGMA journal_mode = WAL');
    db.select('PRAGMA wal_autocheckpoint = 100');
    db.select('PRAGMA auto_vacuum = INCREMENTAL');
    db.execute('''
      CREATE TABLE IF NOT EXISTS notebook (
        id          TEXT    NOT NULL PRIMARY KEY,
        parentId    TEXT    NOT NULL,
        boundingBox TEXT    NOT NULL,
        "order"     INTEGER NOT NULL DEFAULT 0,
        createdAt   INTEGER NOT NULL,
        updatedAt   INTEGER NOT NULL,
        deletedAt   INTEGER,
        type        TEXT    NOT NULL,
        data        TEXT    NOT NULL
      )
    ''');
    db.execute(
        'CREATE INDEX IF NOT EXISTS idx_notebook_parent_order ON notebook(parentId, "order", deletedAt)');
    db.execute(
        'CREATE TABLE IF NOT EXISTS undo_redo_state (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)');
    db.execute(
        'CREATE TABLE IF NOT EXISTS notebook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)');
    return SoilDatabase._(db);
  }

  int get _now => DateTime.now().millisecondsSinceEpoch;

  /// Seed a fresh notebook: root `notebook` row → one `page` → one content `layer`.
  /// Returns the new page id.
  String bootstrapBlank({required String title, required double width, required double height}) {
    final now = _now;
    final bbox = BoundingBox(0, 0, width, height).toJson();
    final notebookRowId = _uuid.v4();
    final pageId = _uuid.v4();
    final layerId = _uuid.v4();

    // Root notebook-metadata row (parentId = "", boundingBox = "{}").
    _db.execute(_insert, [
      notebookRowId, '', '{}', 0, now, now, null, 'notebook',
      '{"title":${_str(title)},"cover":"","last_opened_page":${_str(pageId)}}',
    ]);
    _db.execute(_insert, [
      pageId, notebookRowId, bbox, 0, now, now, null, 'page',
      PageData(width: width, height: height, template: '').toJson(),
    ]);
    _db.execute(_insert, [
      layerId, pageId, bbox, 0, now, now, null, 'layer',
      '{"label":"Content","isLocked":false,"isVisible":true}',
    ]);
    return pageId;
  }

  /// Append a new blank page (+ its content layer) after the last one. Returns the new page.
  PageRef addPage({required double width, required double height}) {
    final now = _now;
    final notebookRowId =
        _db.select("SELECT id FROM notebook WHERE type = 'notebook' LIMIT 1").first['id'] as String;
    final bbox = BoundingBox(0, 0, width, height).toJson();
    final pageId = _uuid.v4();
    final layerId = _uuid.v4();
    final order = _nextPageOrder();
    _db.execute(_insert, [
      pageId, notebookRowId, bbox, order, now, now, null, 'page',
      PageData(width: width, height: height, template: '').toJson(),
    ]);
    _db.execute(_insert, [
      layerId, pageId, bbox, 0, now, now, null, 'layer',
      '{"label":"Content","isLocked":false,"isVisible":true}',
    ]);
    return PageRef(pageId, layerId, PageData(width: width, height: height), order);
  }

  int _nextPageOrder() {
    final r = _db.select(
        'SELECT COALESCE(MAX("order"), -1) + 1 AS next FROM notebook WHERE type = \'page\'');
    return r.first['next'] as int;
  }

  /// All non-deleted pages (each with its content layer), in canonical order.
  List<PageRef> pages() {
    final rows = _db.select(
        "SELECT id, data, \"order\" FROM notebook WHERE type = 'page' AND deletedAt IS NULL ORDER BY \"order\" ASC");
    return rows.map((r) {
      final pageId = r['id'] as String;
      return PageRef(
        pageId,
        _layerForPage(pageId),
        PageData.fromJson(r['data'] as String),
        r['order'] as int,
      );
    }).toList();
  }

  String _layerForPage(String pageId) {
    final rows = _db.select(
        "SELECT id FROM notebook WHERE type = 'layer' AND parentId = ? AND deletedAt IS NULL LIMIT 1",
        [pageId]);
    return rows.isEmpty ? '' : rows.first['id'] as String;
  }

  /// All non-deleted strokes on [layerId], in draw order.
  List<StrokeRow> strokesForLayer(String layerId) {
    final rows = _db.select(
        "SELECT id, data FROM notebook WHERE type = 'stroke' AND parentId = ? AND deletedAt IS NULL ORDER BY \"order\" ASC",
        [layerId]);
    return rows
        .map((r) => StrokeRow(r['id'] as String, StrokeData.fromJson(r['data'] as String)))
        .toList();
  }

  /// Persist one committed stroke under [layerId]. [id] lets the caller pre-assign the row id
  /// (so its in-memory model can reference/erase the stroke before any reload). Returns the id.
  String insertStroke(String layerId, StrokeData stroke, {String? id}) {
    id ??= _uuid.v4();
    final now = _now;
    final bbox = BoundingBox.ofPoints(stroke.points).toJson();
    final order = _nextOrder(layerId);
    _db.execute(_insert, [id, layerId, bbox, order, now, now, null, 'stroke', stroke.toJson()]);
    return id;
  }

  /// Soft-delete strokes by id (eraser). `updatedAt = deletedAt` so staleness detection works.
  void softDeleteStrokes(Iterable<String> ids) {
    final now = _now;
    final stmt = _db.prepare('UPDATE notebook SET deletedAt = ?, updatedAt = ? WHERE id = ?');
    for (final id in ids) {
      stmt.execute([now, now, id]);
    }
    stmt.close();
  }

  int _nextOrder(String parentId) {
    final r = _db.select(
        'SELECT COALESCE(MAX("order"), -1) + 1 AS next FROM notebook WHERE parentId = ?', [parentId]);
    return r.first['next'] as int;
  }

  /// Checkpoint + vacuum, then close — leaves no `-wal`/`-shm` sidecars.
  void close() {
    _db.select('PRAGMA incremental_vacuum');
    _db.select('PRAGMA wal_checkpoint(TRUNCATE)');
    _db.close();
  }

  static String _str(String s) => '"${s.replaceAll(r'\', r'\\').replaceAll('"', r'\"')}"';
}
