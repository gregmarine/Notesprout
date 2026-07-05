import 'package:notesprout_flutter/data/soil_database.dart';
import 'package:notesprout_flutter/domain/objects.dart';
import 'package:notesprout_flutter/domain/stroke.dart';

/// Dev tool: writes a self-contained `.soil` exercising every markdown feature so 2B text-object
/// rendering can be eyeballed on-device. Usage: `dart run tool/make_showcase.dart <out.soil>`.
void main(List<String> args) {
  final out = args.first;
  final soil = SoilDatabase.open(out);
  final pageId = soil.bootstrapBlank(title: '2B Markdown Showcase', width: 1860, height: 2480);
  final layerId = soil.pages().first.layerId;

  soil.insertObject(layerId, 'heading', const BoundingBox(40, 40, 700, 120),
      const HeadingObject(recognizedText: '# Markdown Showcase', level: 1).toJson());

  const md = '## Inline formatting\n'
      '\n'
      'This has **bold**, _italic_, ~~strikethrough~~, and a [link](https://notesprout.app).\n'
      '\n'
      '### Unordered (nested)\n'
      '- first\n'
      '- second\n'
      '  - nested a\n'
      '  - nested b\n'
      '\n'
      '### Ordered (auto-numbered)\n'
      '1. one\n'
      '1. two\n'
      '1. three\n'
      '\n'
      '### Tasks\n'
      '- [x] shipped Phase 2A\n'
      '- [ ] shipping Phase 2B\n'
      '\n'
      '> Where thought has a place to grow.\n'
      '\n'
      '---';
  soil.insertObject(layerId, 'text', const BoundingBox(40, 200, 1100, 1600), const TextObject(text: md).toJson());

  soil.insertObject(layerId, 'line', const BoundingBox(40, 1900, 1100, 8),
      const LineObject(style: LineStyle.dashed, orientation: LineOrientation.horizontal).toJson());

  soil.close();
  // ignore: avoid_print
  print('wrote showcase page=$pageId to $out');
}
