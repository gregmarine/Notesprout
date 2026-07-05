import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:notesprout_flutter/data/index_database.dart';
import 'package:notesprout_flutter/data/notebook_repository.dart';
import 'package:notesprout_flutter/domain/stroke.dart';

void main() {
  late Directory tmp;
  late IndexDatabase index;
  late NotebookRepository repo;

  setUp(() {
    tmp = Directory.systemTemp.createTempSync('ns_test_');
    index = IndexDatabase.open('${tmp.path}/notesprout.db');
    repo = NotebookRepository(index: index, gardenDir: '${tmp.path}/Garden');
  });

  tearDown(() {
    index.close();
    tmp.deleteSync(recursive: true);
  });

  test('create → list notebook via index', () {
    final nb = repo.createBlankNotebook('My Journal', pageWidth: 1860, pageHeight: 2185);
    expect(nb.name, 'My Journal');
    final all = repo.listNotebooks();
    expect(all.map((n) => n.id), contains(nb.id));
    // .soil file exists on disk with the uuid name.
    expect(File(repo.soilPath(nb.id)).existsSync(), isTrue);
  });

  test('blank notebook has exactly one page with a content layer', () {
    final nb = repo.createBlankNotebook('P', pageWidth: 1000, pageHeight: 1400);
    final soil = repo.openNotebook(nb.id);
    final pages = soil.pages();
    expect(pages.length, 1);
    expect(pages.first.layerId, isNotEmpty);
    expect(pages.first.data.width, 1000);
    soil.close();
  });

  test('stroke round-trips through .soil (write → close → reopen → read)', () {
    final nb = repo.createBlankNotebook('Ink', pageWidth: 1000, pageHeight: 1400);

    var soil = repo.openNotebook(nb.id);
    final layerId = soil.pages().first.layerId;
    final written = StrokeData(points: const [
      StrokePoint(10, 20),
      StrokePoint(30, 40),
      StrokePoint(31.5, 42.25),
    ]);
    final strokeId = soil.insertStroke(layerId, written);
    soil.close();

    soil = repo.openNotebook(nb.id);
    final read = soil.strokesForLayer(layerId);
    expect(read.length, 1);
    expect(read.first.id, strokeId);
    expect(read.first.data.points.length, 3);
    expect(read.first.data.points[2].x, 31.5);
    expect(read.first.data.points[2].y, 42.25);
    expect(read.first.data.color, '#000000');
    expect(read.first.data.strokeWidth, 3.0);
    soil.close();
  });

  test('eraser soft-delete hides strokes on reload', () {
    final nb = repo.createBlankNotebook('E', pageWidth: 1000, pageHeight: 1400);
    var soil = repo.openNotebook(nb.id);
    final layerId = soil.pages().first.layerId;
    final id1 = soil.insertStroke(layerId, StrokeData(points: const [StrokePoint(0, 0), StrokePoint(5, 5)]));
    soil.insertStroke(layerId, StrokeData(points: const [StrokePoint(50, 50), StrokePoint(55, 55)]));
    soil.softDeleteStrokes([id1]);
    soil.close();

    soil = repo.openNotebook(nb.id);
    final read = soil.strokesForLayer(layerId);
    expect(read.length, 1);
    expect(read.first.data.points.first.x, 50);
    soil.close();
  });

  test('stroke JSON wire format matches native (x,y only; no null pressure/tilt)', () {
    final json = StrokeData(points: const [StrokePoint(1, 2)]).toJson();
    final m = jsonDecode(json) as Map<String, dynamic>;
    expect(m.keys.toSet(), {'color', 'strokeWidth', 'points'});
    final p = (m['points'] as List).first as Map<String, dynamic>;
    expect(p.keys.toSet(), {'x', 'y'}); // pressure/tilt/ts omitted when null
  });
}
