import 'dart:async';
import 'dart:isolate';

import '../domain/page_object.dart';
import '../domain/stroke.dart';
import 'index_database.dart';
import 'notebook_repository.dart';
import 'soil_database.dart';

/// All SQLite access runs on a dedicated background isolate so the UI/EPD thread never blocks on
/// disk I/O — mirroring the native app's `Dispatchers.IO` DB work. The UI isolate holds NO database
/// connection; it sends commands here and awaits results (reads) or fires-and-forgets (writes).
class DbWorker {
  DbWorker._(this._tx);

  final SendPort _tx;
  final _pending = <int, Completer<dynamic>>{};
  int _seq = 0;

  static Future<DbWorker> start({required String indexPath, required String gardenDir}) async {
    final rx = ReceivePort();
    final ready = Completer<SendPort>();
    final replies = StreamController<_Res>.broadcast();

    rx.listen((msg) {
      if (msg is SendPort) {
        ready.complete(msg);
      } else if (msg is _Res) {
        replies.add(msg);
      }
    });

    await Isolate.spawn(_entry, _Init(rx.sendPort, indexPath, gardenDir));
    final worker = DbWorker._(await ready.future);
    replies.stream.listen((res) {
      final c = worker._pending.remove(res.id);
      if (c == null) return;
      res.error != null ? c.completeError(res.error!) : c.complete(res.result);
    });
    return worker;
  }

  Future<T> _call<T>(String op, [Map<String, dynamic> args = const {}]) {
    final id = _seq++;
    final c = Completer<T>();
    _pending[id] = c;
    _tx.send(_Req(id, op, args));
    return c.future;
  }

  /// Fire-and-forget (writes) — the isolate processes messages FIFO, so a later read/close always
  /// observes earlier writes.
  void _fire(String op, [Map<String, dynamic> args = const {}]) => _tx.send(_Req(-1, op, args));

  Future<List<NotebookEntry>> listNotebooks() => _call('listNotebooks');

  /// Direct children (folders + notebooks) of [parentId] (null == root) for the library grid.
  Future<List<LibraryEntry>> browse(String? parentId) => _call('browse', {'parentId': parentId});

  /// Breadcrumb trail from root down to [folderId].
  Future<List<Crumb>> breadcrumb(String? folderId) =>
      _call('breadcrumb', {'folderId': folderId});

  Future<String> createFolder(String name, String? parentId) =>
      _call('createFolder', {'name': name, 'parentId': parentId});

  Future<NotebookEntry> createNotebook(String name, double w, double h, {String? parentId}) =>
      _call('createNotebook', {'name': name, 'w': w, 'h': h, 'parentId': parentId});
  Future<List<PageRef>> openNotebook(String id) => _call('openNotebook', {'id': id});
  Future<PageRef> addPage(String id, double w, double h) =>
      _call('addPage', {'id': id, 'w': w, 'h': h});

  /// Insert a page relative to [refPageId] ([before] ? before : after). Returns the new page.
  Future<PageRef> insertPage(String id, String refPageId, bool before, double w, double h) =>
      _call('insertPage', {'id': id, 'ref': refPageId, 'before': before, 'w': w, 'h': h});

  /// Base64 PNG of a page's template (or null for blank/missing).
  Future<String?> templateImage(String id, String templateId) =>
      _call('templateImage', {'id': id, 'templateId': templateId});
  Future<List<StrokeRow>> strokes(String id, String layerId) =>
      _call('strokes', {'id': id, 'layer': layerId});

  /// All content objects (strokes, headings, text, lines) on a layer, in draw order.
  Future<List<PageObject>> objects(String id, String layerId) =>
      _call('objects', {'id': id, 'layer': layerId});

  void insertStroke(String id, String layerId, String strokeId, StrokeData data) =>
      _fire('insertStroke',
          {'id': id, 'layer': layerId, 'strokeId': strokeId, 'json': data.toJson()});

  /// Insert any content object (heading/text/line) with a pre-assigned [objectId].
  void insertObject(String id, String layerId, String objectId, String type, BoundingBox box,
          String dataJson) =>
      _fire('insertObject', {
        'id': id,
        'layer': layerId,
        'objectId': objectId,
        'type': type,
        'box': box.toJson(),
        'json': dataJson,
      });

  /// Update an existing object's box + data (text edit / move).
  void updateObject(String id, String objectId, BoundingBox box, String dataJson) => _fire(
      'updateObject', {'id': id, 'objectId': objectId, 'box': box.toJson(), 'json': dataJson});
  void softDelete(String id, List<String> strokeIds) =>
      _fire('softDelete', {'id': id, 'ids': strokeIds});

  /// Set/clear the soft-delete mark (undo/redo restore).
  void setDeleted(String id, List<String> ids, bool deleted) =>
      _fire('setDeleted', {'id': id, 'ids': ids, 'deleted': deleted});
  void closeNotebook(String id, int pageCount) =>
      _fire('closeNotebook', {'id': id, 'count': pageCount});
}

// ── Isolate protocol ─────────────────────────────────────────────────────────

class _Init {
  _Init(this.tx, this.indexPath, this.gardenDir);
  final SendPort tx;
  final String indexPath;
  final String gardenDir;
}

class _Req {
  _Req(this.id, this.op, this.args);
  final int id; // -1 = fire-and-forget
  final String op;
  final Map<String, dynamic> args;
}

class _Res {
  _Res(this.id, this.result, this.error);
  final int id;
  final dynamic result;
  final String? error;
}

void _entry(_Init init) {
  // sqlite3's default Android open() does DynamicLibrary.open('libsqlite3.so'), which resolves the
  // lib bundled by sqlite3_flutter_libs from any isolate — no per-isolate override needed here.
  final rx = ReceivePort();
  init.tx.send(rx.sendPort);

  final index = IndexDatabase.open(init.indexPath);
  final repo = NotebookRepository(index: index, gardenDir: init.gardenDir);
  final open_ = <String, SoilDatabase>{};
  SoilDatabase soil(String id) => open_.putIfAbsent(id, () => repo.openNotebook(id));

  rx.listen((msg) {
    final req = msg as _Req;
    try {
      dynamic r;
      switch (req.op) {
        case 'listNotebooks':
          r = repo.listNotebooks();
        case 'browse':
          r = repo.browse(req.args['parentId'] as String?);
        case 'breadcrumb':
          r = repo.breadcrumb(req.args['folderId'] as String?);
        case 'createFolder':
          r = repo.createFolder(req.args['name'] as String,
              parentId: req.args['parentId'] as String?);
        case 'createNotebook':
          r = repo.createBlankNotebook(req.args['name'] as String,
              pageWidth: req.args['w'] as double,
              pageHeight: req.args['h'] as double,
              parentId: req.args['parentId'] as String?);
        case 'openNotebook':
          r = soil(req.args['id'] as String).pages();
        case 'addPage':
          r = soil(req.args['id'] as String)
              .addPage(width: req.args['w'] as double, height: req.args['h'] as double);
        case 'insertPage':
          r = soil(req.args['id'] as String).insertPage(
              req.args['ref'] as String, req.args['before'] as bool,
              width: req.args['w'] as double, height: req.args['h'] as double);
        case 'templateImage':
          r = soil(req.args['id'] as String).templateImage(req.args['templateId'] as String);
        case 'strokes':
          r = soil(req.args['id'] as String).strokesForLayer(req.args['layer'] as String);
        case 'objects':
          r = soil(req.args['id'] as String).objectsForLayer(req.args['layer'] as String);
        case 'insertStroke':
          soil(req.args['id'] as String).insertStroke(
              req.args['layer'] as String, StrokeData.fromJson(req.args['json'] as String),
              id: req.args['strokeId'] as String);
        case 'insertObject':
          soil(req.args['id'] as String).insertObject(
              req.args['layer'] as String,
              req.args['type'] as String,
              BoundingBox.fromJson(req.args['box'] as String),
              req.args['json'] as String,
              id: req.args['objectId'] as String);
        case 'updateObject':
          soil(req.args['id'] as String).updateObject(req.args['objectId'] as String,
              BoundingBox.fromJson(req.args['box'] as String), req.args['json'] as String);
        case 'softDelete':
          soil(req.args['id'] as String)
              .softDeleteStrokes((req.args['ids'] as List).cast<String>());
        case 'setDeleted':
          soil(req.args['id'] as String).setDeleted(
              (req.args['ids'] as List).cast<String>(), req.args['deleted'] as bool);
        case 'closeNotebook':
          open_.remove(req.args['id'] as String)?.close();
          index.touchNotebook(req.args['id'] as String, pageCount: req.args['count'] as int);
      }
      if (req.id >= 0) init.tx.send(_Res(req.id, r, null));
    } catch (e, st) {
      // ignore: avoid_print
      print('DbWorker op=${req.op} failed: $e\n$st');
      if (req.id >= 0) init.tx.send(_Res(req.id, null, '$e'));
    }
  });
}
