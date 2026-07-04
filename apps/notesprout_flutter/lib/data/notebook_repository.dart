import 'dart:io';

import 'index_database.dart';
import 'soil_database.dart';

/// Higher-level API over the global index + per-notebook `.soil` files. Mirrors the split in the
/// native app (`IndexRepository` for the tree, `NotebookFactory`/`NotebookDao` for the file).
///
/// [gardenDir] is where `.soil` files live — `<externalDir>/Garden` on device, a temp dir in tests.
/// The single index db lives at `<gardenParent>/notesprout.db` (passed in as [indexPath]).
class NotebookRepository {
  NotebookRepository({required this.index, required this.gardenDir});

  final IndexDatabase index;
  final String gardenDir;

  /// The sole `.soil` path deriver — flat dir, UUID filenames (ports `SoilFile.kt`).
  String soilPath(String notebookId) => '$gardenDir/$notebookId.soil';

  void _ensureGarden() {
    final d = Directory(gardenDir);
    if (!d.existsSync()) d.createSync(recursive: true);
  }

  /// Create a blank notebook under [parentId] (null == root): index row + a fully bootstrapped
  /// `.soil` (one page, one layer).
  NotebookEntry createBlankNotebook(
    String name, {
    required double pageWidth,
    required double pageHeight,
    String? parentId,
  }) {
    _ensureGarden();
    final id = index.createNotebook(name, parentId: parentId);
    final soil = SoilDatabase.open(soilPath(id));
    try {
      soil.bootstrapBlank(title: name, width: pageWidth, height: pageHeight);
    } finally {
      soil.close();
    }
    return index.notebooks().firstWhere((n) => n.id == id);
  }

  /// Create a folder under [parentId] (null == root). Returns the folder id.
  String createFolder(String name, {String? parentId}) =>
      index.createFolder(name, parentId: parentId);

  /// Direct children (folders + notebooks) of [parentId] for the library grid.
  List<LibraryEntry> browse(String? parentId) => index.children(parentId);

  /// Breadcrumb trail from root to [folderId].
  List<Crumb> breadcrumb(String? folderId) => index.breadcrumb(folderId);

  List<NotebookEntry> listNotebooks() => index.notebooks();

  /// Open a notebook's `.soil` for reading/writing. Caller must [SoilDatabase.close] when done.
  SoilDatabase openNotebook(String notebookId) => SoilDatabase.open(soilPath(notebookId));
}
