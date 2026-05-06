import 'package:sqflite/sqflite.dart';
import 'package:uuid/uuid.dart';

import '../models/layer_model.dart';
import '../models/notebook_meta.dart';
import '../models/page_model.dart';
import '../models/stroke_model.dart';

const _uuid = Uuid();

class SoilDatabase {
  final String filePath;
  Database? _db;

  SoilDatabase(this.filePath);

  Future<Database> get _database async {
    _db ??= await _open();
    return _db!;
  }

  Future<Database> _open() async {
    return openDatabase(
      filePath,
      version: 1,
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE notebook_meta (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            syncVersion INTEGER NOT NULL DEFAULT 0
          )
        ''');

        await db.execute('''
          CREATE TABLE pages (
            id TEXT PRIMARY KEY,
            parentId TEXT,
            type TEXT NOT NULL,
            subtype TEXT,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            deletedAt INTEGER,
            pageNumber INTEGER NOT NULL,
            width REAL NOT NULL,
            height REAL NOT NULL
          )
        ''');

        await db.execute('''
          CREATE TABLE layers (
            id TEXT PRIMARY KEY,
            parentId TEXT,
            type TEXT NOT NULL,
            subtype TEXT,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            deletedAt INTEGER,
            isLocked INTEGER NOT NULL DEFAULT 0,
            isVisible INTEGER NOT NULL DEFAULT 1,
            opacity REAL NOT NULL DEFAULT 1.0
          )
        ''');

        await db.execute('''
          CREATE TABLE strokes (
            id TEXT PRIMARY KEY,
            parentId TEXT,
            type TEXT NOT NULL,
            subtype TEXT,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            deletedAt INTEGER,
            points TEXT NOT NULL,
            color INTEGER NOT NULL,
            width REAL NOT NULL
          )
        ''');
      },
    );
  }

  // ---------------------------------------------------------------------------
  // Notebook meta
  // ---------------------------------------------------------------------------

  Future<void> initializeNotebook(String name) async {
    final db = await _database;
    final now = DateTime.now();

    final meta = NotebookMeta(
      id: _uuid.v4(),
      name: name,
      createdAt: now,
      updatedAt: now,
    );
    await db.insert('notebook_meta', meta.toMap());

    final page = PageModel(
      id: _uuid.v4(),
      createdAt: now,
      updatedAt: now,
      pageNumber: 1,
      width: 1404,
      height: 1872,
    );
    await db.insert('pages', page.toMap());

    final layer = LayerModel(
      id: _uuid.v4(),
      parentId: page.id,
      createdAt: now,
      updatedAt: now,
    );
    await db.insert('layers', layer.toMap());
  }

  Future<NotebookMeta> getNotebookMeta() async {
    final db = await _database;
    final rows = await db.query('notebook_meta', limit: 1);
    return NotebookMeta.fromMap(rows.first);
  }

  Future<void> updateNotebookName(String name) async {
    final db = await _database;
    final now = DateTime.now();
    await db.update(
      'notebook_meta',
      {'name': name, 'updatedAt': now.millisecondsSinceEpoch},
    );
  }

  // ---------------------------------------------------------------------------
  // Pages
  // ---------------------------------------------------------------------------

  Future<List<PageModel>> getPages() async {
    final db = await _database;
    final rows = await db.query(
      'pages',
      where: 'deletedAt IS NULL',
      orderBy: 'pageNumber ASC',
    );
    return rows.map(PageModel.fromMap).toList();
  }

  Future<PageModel> addPage(double width, double height) async {
    final db = await _database;
    final pages = await getPages();
    final now = DateTime.now();

    final page = PageModel(
      id: _uuid.v4(),
      createdAt: now,
      updatedAt: now,
      pageNumber: pages.length + 1,
      width: width,
      height: height,
    );
    await db.insert('pages', page.toMap());
    return page;
  }

  Future<void> deletePage(String pageId) async {
    final db = await _database;
    final now = DateTime.now().millisecondsSinceEpoch;

    await db.update(
      'pages',
      {'deletedAt': now, 'updatedAt': now},
      where: 'id = ?',
      whereArgs: [pageId],
    );

    // Renumber remaining pages sequentially.
    final remaining = await getPages();
    final batch = db.batch();
    for (var i = 0; i < remaining.length; i++) {
      batch.update(
        'pages',
        {'pageNumber': i + 1, 'updatedAt': now},
        where: 'id = ?',
        whereArgs: [remaining[i].id],
      );
    }
    await batch.commit(noResult: true);
  }

  // ---------------------------------------------------------------------------
  // Layers
  // ---------------------------------------------------------------------------

  Future<List<LayerModel>> getLayers(String pageId) async {
    final db = await _database;
    final rows = await db.query(
      'layers',
      where: 'parentId = ? AND deletedAt IS NULL',
      whereArgs: [pageId],
    );
    return rows.map(LayerModel.fromMap).toList();
  }

  // ---------------------------------------------------------------------------
  // Strokes
  // ---------------------------------------------------------------------------

  Future<List<StrokeModel>> getStrokes(String layerId) async {
    final db = await _database;
    final rows = await db.query(
      'strokes',
      where: 'parentId = ? AND deletedAt IS NULL',
      whereArgs: [layerId],
    );
    return rows.map(StrokeModel.fromMap).toList();
  }

  Future<void> addStroke(StrokeModel stroke) async {
    final db = await _database;
    await db.insert('strokes', stroke.toMap());
  }

  Future<void> deleteStroke(String strokeId) async {
    final db = await _database;
    final now = DateTime.now().millisecondsSinceEpoch;
    await db.update(
      'strokes',
      {'deletedAt': now, 'updatedAt': now},
      where: 'id = ?',
      whereArgs: [strokeId],
    );
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  Future<void> close() async {
    await _db?.close();
    _db = null;
  }
}
