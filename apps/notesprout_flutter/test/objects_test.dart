import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:notesprout_flutter/data/index_database.dart';
import 'package:notesprout_flutter/data/notebook_repository.dart';
import 'package:notesprout_flutter/domain/objects.dart';
import 'package:notesprout_flutter/domain/page_object.dart';
import 'package:notesprout_flutter/domain/stroke.dart';

/// Verifies the content-object payloads are byte-compatible with the native app's kotlinx default
/// `Json` (encodeDefaults = false): defaults are omitted on write, no unknown keys are emitted, and
/// JSON exactly as the native app would write it decodes cleanly.
void main() {
  group('wire format — defaults omitted (encodeDefaults=false parity)', () {
    test('HeadingObject: recognized heading omits empty strokes + default level', () {
      final m = jsonDecode(const HeadingObject(recognizedText: '# Title').toJson())
          as Map<String, dynamic>;
      expect(m.keys.toSet(), {'recognizedText'}); // strokes empty, level==1 → omitted
      expect(m['recognizedText'], '# Title');
    });

    test('HeadingObject: level written only when != 1', () {
      final h2 =
          jsonDecode(const HeadingObject(recognizedText: '## S', level: 2).toJson()) as Map;
      expect(h2['level'], 2);
    });

    test('HeadingObject: stroke fallback carries strokes, drops null recognizedText', () {
      final m = jsonDecode(const HeadingObject(
        strokes: [LiveStroke(id: 'a', points: [Vec2(1, 2), Vec2(3, 4)])],
      ).toJson()) as Map<String, dynamic>;
      expect(m.keys.toSet(), {'strokes'});
      final s = (m['strokes'] as List).first as Map<String, dynamic>;
      // LiveStroke omits its own defaults (color/strokeWidth/srcPoints).
      expect(s.keys.toSet(), {'id', 'points'});
      expect((s['points'] as List).first, {'x': 1, 'y': 2});
    });

    test('TextObject: markdown body only; blank text omitted', () {
      final withText = jsonDecode(const TextObject(text: '**hi**').toJson()) as Map;
      expect(withText.keys.toSet(), {'text'});
      final blank = jsonDecode(const TextObject().toJson()) as Map;
      expect(blank.keys, isEmpty); // text=="" and strokes==null both omitted
    });

    test('LineObject: enums UPPERCASE; defaults omitted', () {
      final m = jsonDecode(
              const LineObject(style: LineStyle.dashed, orientation: LineOrientation.vertical)
                  .toJson())
          as Map<String, dynamic>;
      expect(m.keys.toSet(), {'style', 'orientation'}); // strokeWidthDp==1, dotSpacingDp==0 omitted
      expect(m['style'], 'DASHED');
      expect(m['orientation'], 'VERTICAL');
    });

    test('LineObject: non-default numerics are written', () {
      final m = jsonDecode(const LineObject(
        style: LineStyle.dotted,
        orientation: LineOrientation.horizontal,
        strokeWidthDp: 2.0,
        dotSpacingDp: 40.0,
      ).toJson()) as Map<String, dynamic>;
      expect(m['strokeWidthDp'], 2.0);
      expect(m['dotSpacingDp'], 40.0);
      expect(m['style'], 'DOTTED');
    });
  });

  group('decode — JSON exactly as the native app writes it', () {
    test('recognized H2 from native', () {
      final h = HeadingObject.fromJson('{"recognizedText":"## Chapter","level":2}');
      expect(h.recognizedText, '## Chapter');
      expect(h.level, 2);
      expect(h.strokes, isEmpty);
    });

    test('old row with no level decodes as H1 (no migration)', () {
      expect(HeadingObject.fromJson('{"recognizedText":"# Old"}').level, 1);
    });

    test('LiveStroke with color/width/srcPoints (pressure/tilt) decodes', () {
      final t = TextObject.fromJson(
          '{"strokes":[{"id":"x","points":[{"x":5,"y":6}],"color":"#123456","strokeWidth":2.5,'
          '"srcPoints":[{"x":5,"y":6,"pressure":0.8}]}]}');
      final s = t.strokes!.single;
      expect(s.id, 'x');
      expect(s.color, '#123456');
      expect(s.strokeWidth, 2.5);
      expect(s.srcPoints!.single.pressure, 0.8);
      expect(t.text, isEmpty);
    });

    test('line from native (solid horizontal, default widths absent)', () {
      final l = LineObject.fromJson('{"style":"SOLID","orientation":"HORIZONTAL"}');
      expect(l.style, LineStyle.solid);
      expect(l.orientation, LineOrientation.horizontal);
      expect(l.strokeWidthDp, 1.0);
      expect(l.dotSpacingDp, 0.0);
    });

    test('unknown future keys are ignored (forward-compat)', () {
      final h = HeadingObject.fromJson('{"recognizedText":"# T","someFutureField":true}');
      expect(h.recognizedText, '# T');
    });
  });

  group('round-trip through .soil via objectsForLayer', () {
    late Directory tmp;
    late IndexDatabase index;
    late NotebookRepository repo;

    setUp(() {
      tmp = Directory.systemTemp.createTempSync('ns_obj_');
      index = IndexDatabase.open('${tmp.path}/notesprout.db');
      repo = NotebookRepository(index: index, gardenDir: '${tmp.path}/Garden');
    });
    tearDown(() {
      index.close();
      tmp.deleteSync(recursive: true);
    });

    test('heading + text + line + stroke all reload with correct types', () {
      final nb = repo.createBlankNotebook('Mixed', pageWidth: 1000, pageHeight: 1400);
      var soil = repo.openNotebook(nb.id);
      final layerId = soil.pages().first.layerId;

      soil.insertObject(layerId, 'heading', const BoundingBox(10, 10, 200, 60),
          const HeadingObject(recognizedText: '# Hello', level: 1).toJson());
      soil.insertObject(layerId, 'text', const BoundingBox(10, 100, 300, 80),
          const TextObject(text: '**bold** and _italic_').toJson());
      soil.insertObject(layerId, 'line', const BoundingBox(0, 300, 1000, 8),
          const LineObject(style: LineStyle.dashed, orientation: LineOrientation.horizontal)
              .toJson());
      soil.insertStroke(layerId, StrokeData(points: const [StrokePoint(5, 5), StrokePoint(9, 9)]));
      soil.close();

      soil = repo.openNotebook(nb.id);
      final objs = soil.objectsForLayer(layerId);
      expect(objs.length, 4);
      expect(objs.whereType<HeadingRender>().single.data.recognizedText, '# Hello');
      expect(objs.whereType<TextRender>().single.data.text, '**bold** and _italic_');
      final line = objs.whereType<LineRender>().single;
      expect(line.data.style, LineStyle.dashed);
      expect(line.box.width, 1000);
      expect(objs.whereType<StrokeObject>().single.data.points.length, 2);
      soil.close();
    });

    test('updateObject changes a text object box + data in place', () {
      final nb = repo.createBlankNotebook('Edit', pageWidth: 1000, pageHeight: 1400);
      var soil = repo.openNotebook(nb.id);
      final layerId = soil.pages().first.layerId;
      final id = soil.insertObject(layerId, 'text', const BoundingBox(0, 0, 100, 40),
          const TextObject(text: 'before').toJson());

      soil.updateObject(id, const BoundingBox(5, 6, 220, 80), const TextObject(text: '# after').toJson());
      soil.close();

      soil = repo.openNotebook(nb.id);
      final t = soil.objectsForLayer(layerId).whereType<TextRender>().single;
      expect(t.id, id);
      expect(t.data.text, '# after');
      expect(t.box.x, 5);
      expect(t.box.width, 220);
      soil.close();
    });
  });
}
