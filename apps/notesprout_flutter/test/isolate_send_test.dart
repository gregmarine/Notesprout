import 'dart:async';
import 'dart:isolate';

import 'package:flutter_test/flutter_test.dart';
import 'package:notesprout_flutter/domain/objects.dart';
import 'package:notesprout_flutter/domain/page_object.dart';
import 'package:notesprout_flutter/domain/stroke.dart';

/// The DbWorker returns `List<PageObject>` across a SendPort. Confirm the concrete subtypes
/// (esp. HeadingRender) survive the isolate copy — the suspected cause of the heading vanishing.
void main() {
  test('List<PageObject> survives SendPort with concrete subtypes intact', () async {
    final rx = ReceivePort();
    await Isolate.spawn(_producer, rx.sendPort);
    final list = await rx.first as List<PageObject>;
    rx.close();

    expect(list.length, 3);
    expect(list.whereType<StrokeObject>().length, 1);
    expect(list.whereType<HeadingRender>().length, 1);
    expect(list.whereType<LineRender>().length, 1);
    expect(list.whereType<HeadingRender>().single.data.recognizedText, '# Morning');
  });
}

void _producer(SendPort tx) {
  final list = <PageObject>[
    StrokeObject('s', const BoundingBox(0, 0, 1, 1),
        const StrokeData(points: [StrokePoint(0, 0), StrokePoint(1, 1)])),
    const HeadingRender('h', BoundingBox(0, 0, 10, 10), HeadingObject(recognizedText: '# Morning')),
    const LineRender('l', BoundingBox(0, 0, 10, 1),
        LineObject(style: LineStyle.solid, orientation: LineOrientation.horizontal)),
  ];
  tx.send(list);
}
