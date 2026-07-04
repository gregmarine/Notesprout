import 'dart:ui' as ui;

import 'package:flutter_test/flutter_test.dart';
import 'package:notesprout_flutter/domain/objects.dart';
import 'package:notesprout_flutter/domain/page_object.dart';
import 'package:notesprout_flutter/domain/stroke.dart';
import 'package:notesprout_flutter/ui/page_painter.dart';

/// Rasterizes PagePainter with a single recognized heading and asserts it produces ink.
void main() {
  test('recognized heading paints visible pixels', () async {
    const heading = HeadingRender(
      'h1',
      BoundingBox(17.5, 18.75, 371, 155),
      HeadingObject(recognizedText: '# Morning'),
    );

    final recorder = ui.PictureRecorder();
    final canvas = ui.Canvas(recorder);
    // white background
    canvas.drawRect(const ui.Rect.fromLTWH(0, 0, 400, 200),
        ui.Paint()..color = const ui.Color(0xFFFFFFFF));
    PagePainter(const [heading], 2.0).paint(canvas, const ui.Size(400, 200));

    final img = await recorder.endRecording().toImage(400, 200);
    final bytes = (await img.toByteData(format: ui.ImageByteFormat.rawRgba))!;
    var darkPixels = 0;
    for (var i = 0; i < bytes.lengthInBytes; i += 4) {
      final r = bytes.getUint8(i);
      if (r < 128) darkPixels++; // black text on white
    }
    // ignore: avoid_print
    print('darkPixels=$darkPixels');
    expect(darkPixels, greaterThan(0), reason: 'heading produced no ink');
  });
}
