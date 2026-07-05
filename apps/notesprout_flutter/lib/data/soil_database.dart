import 'dart:convert';

import 'package:sqlite3/sqlite3.dart';
import 'package:uuid/uuid.dart';

import '../domain/objects.dart';
import '../domain/page_object.dart';
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

/// A recognized heading resolved for the Table of Contents: its id, owning [layerId] (→ page), the
/// bounding-box top/left (document-order sort keys), level, and prefix-stripped title.
class HeadingRow {
  HeadingRow(this.id, this.layerId, this.top, this.left, this.level, this.title);
  final String id;
  final String layerId;
  final double top;
  final double left;
  final int level;
  final String title; // recognizedText with the "## " prefix stripped; "" if unrecognized
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

  /// Insert a new blank page relative to [refPageId] ([before] ? before : after it), shifting the
  /// order of the following pages up by one. Returns the new page.
  PageRef insertPage(String refPageId, bool before,
      {required double width, required double height}) {
    final now = _now;
    final notebookRowId =
        _db.select("SELECT id FROM notebook WHERE type = 'notebook' LIMIT 1").first['id'] as String;
    final refRows =
        _db.select('SELECT "order" FROM notebook WHERE id = ? AND type = \'page\'', [refPageId]);
    final refOrder = refRows.isEmpty ? _nextPageOrder() - 1 : refRows.first['order'] as int;
    final at = before ? refOrder : refOrder + 1;
    _db.execute('UPDATE notebook SET "order" = "order" + 1 WHERE type = \'page\' AND "order" >= ?',
        [at]);
    final bbox = BoundingBox(0, 0, width, height).toJson();
    final pageId = _uuid.v4();
    final layerId = _uuid.v4();
    _db.execute(_insert, [
      pageId, notebookRowId, bbox, at, now, now, null, 'page',
      PageData(width: width, height: height, template: '').toJson(),
    ]);
    _db.execute(_insert, [
      layerId, pageId, bbox, 0, now, now, null, 'layer',
      '{"label":"Content","isLocked":false,"isVisible":true}',
    ]);
    return PageRef(pageId, layerId, PageData(width: width, height: height), at);
  }

  /// The base64 PNG of a `type="template"` row (native `TemplateData.image`), or null for blank /
  /// missing. Rendered as the page background.
  String? templateImage(String templateId) {
    if (templateId.isEmpty) return null;
    final rows = _db.select(
        "SELECT data FROM notebook WHERE id = ? AND type = 'template' AND deletedAt IS NULL LIMIT 1",
        [templateId]);
    if (rows.isEmpty) return null;
    try {
      final m = jsonDecode(rows.first['data'] as String) as Map<String, dynamic>;
      final img = m['image'] as String?;
      return (img == null || img.isEmpty) ? null : img;
    } catch (_) {
      return null;
    }
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

  /// All non-deleted content objects on [layerId] (strokes, headings, text, lines), in draw order.
  /// Rows whose `type` isn't a known content object are skipped.
  List<PageObject> objectsForLayer(String layerId) {
    final rows = _db.select(
        "SELECT id, type, boundingBox, data FROM notebook "
        "WHERE parentId = ? AND deletedAt IS NULL AND type IN ('stroke','heading','text','line') "
        "ORDER BY \"order\" ASC",
        [layerId]);
    final out = <PageObject>[];
    for (final r in rows) {
      final id = r['id'] as String;
      final box = BoundingBox.fromJson(r['boundingBox'] as String);
      final data = r['data'] as String;
      switch (r['type'] as String) {
        case 'stroke':
          out.add(StrokeObject(id, box, StrokeData.fromJson(data)));
        case 'heading':
          out.add(HeadingRender(id, box, HeadingObject.fromJson(data)));
        case 'text':
          out.add(TextRender(id, box, TextObject.fromJson(data)));
        case 'line':
          out.add(LineRender(id, box, LineObject.fromJson(data)));
      }
    }
    return out;
  }

  /// All non-deleted headings across the whole notebook, for the Table of Contents. Each is resolved
  /// to its layer (→ page by the caller). Boxes are parsed to top/left for document-order sorting.
  List<HeadingRow> headings() {
    final rows = _db.select(
        "SELECT id, parentId, boundingBox, data FROM notebook WHERE type = 'heading' AND deletedAt IS NULL");
    final out = <HeadingRow>[];
    for (final r in rows) {
      final box = BoundingBox.fromJson(r['boundingBox'] as String);
      final h = HeadingObject.fromJson(r['data'] as String);
      final title = h.recognizedText == null
          ? ''
          : HeadingObject.stripHeadingPrefix(h.recognizedText!);
      out.add(HeadingRow(r['id'] as String, r['parentId'] as String, box.y, box.x, h.level, title));
    }
    return out;
  }

  /// Persist one content object of [type] with the given [box] and serialized [dataJson] under
  /// [layerId]. [id] lets the caller pre-assign the row id; returns it.
  String insertObject(String layerId, String type, BoundingBox box, String dataJson, {String? id}) {
    id ??= _uuid.v4();
    final now = _now;
    final order = _nextOrder(layerId);
    _db.execute(_insert, [id, layerId, box.toJson(), order, now, now, null, type, dataJson]);
    return id;
  }

  /// Update an existing object's bounding box + data (e.g. a text edit or a move). No-op if the row
  /// is gone. `updatedAt` is bumped so staleness detection works.
  void updateObject(String id, BoundingBox box, String dataJson) {
    final now = _now;
    _db.execute('UPDATE notebook SET boundingBox = ?, data = ?, updatedAt = ? WHERE id = ?',
        [box.toJson(), dataJson, now, id]);
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
  void softDeleteStrokes(Iterable<String> ids) => setDeleted(ids, true);

  /// Set or clear the soft-delete mark on rows by id. Clearing ([deleted] = false) restores an object
  /// — the row is never physically removed, so undo/redo just toggles `deletedAt`.
  void setDeleted(Iterable<String> ids, bool deleted) {
    final now = _now;
    final stmt = _db.prepare('UPDATE notebook SET deletedAt = ?, updatedAt = ? WHERE id = ?');
    for (final id in ids) {
      stmt.execute([deleted ? now : null, now, id]);
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
