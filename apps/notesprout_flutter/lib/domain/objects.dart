import 'dart:convert';

import 'stroke.dart';

/// Wire-compatible ports of the native content-object payloads (`data/HeadingObject.kt`,
/// `data/TextObject.kt`, `data/LineObject.kt`, `data/LiveStroke.kt`). All three top-level objects
/// are serialized by kotlinx's default `Json` (encodeDefaults = false), so on write we OMIT any
/// field equal to its default and never emit unknown keys — the native decoder uses
/// `ignoreUnknownKeys = false` and would throw on an extra key. Reads are lenient (apply defaults,
/// ignore extras) so a file from either app opens unchanged.

/// A single (x, y) sample with no pressure/tilt — the shape of a `PointF` in `LiveStroke.points`.
class Vec2 {
  const Vec2(this.x, this.y);
  final double x;
  final double y;

  Map<String, dynamic> toMap() => {'x': x, 'y': y};
  static Vec2 fromMap(Map<String, dynamic> m) =>
      Vec2((m['x'] as num).toDouble(), (m['y'] as num).toDouble());
}

/// In-memory stroke embedded inside a heading/text object (the stroke-fallback visual).
/// Distinct from [StrokeData]: it carries a stable [id], a plain `{x,y}` point list, and an
/// optional [srcPoints] list preserving pressure/tilt. Defaults (`color`, `strokeWidth`,
/// `srcPoints`) are omitted from JSON to match kotlinx.
class LiveStroke {
  const LiveStroke({
    required this.id,
    required this.points,
    this.color = defaultColor,
    this.strokeWidth = defaultStrokeWidth,
    this.srcPoints,
  });

  static const defaultColor = '#000000';
  static const defaultStrokeWidth = 3.0;

  final String id;
  final List<Vec2> points;
  final String color;
  final double strokeWidth;
  final List<StrokePoint>? srcPoints;

  Map<String, dynamic> toMap() => {
        'id': id,
        'points': points.map((p) => p.toMap()).toList(),
        if (color != defaultColor) 'color': color,
        if (strokeWidth != defaultStrokeWidth) 'strokeWidth': strokeWidth,
        if (srcPoints != null) 'srcPoints': srcPoints!.map((p) => p.toMap()).toList(),
      };

  static LiveStroke fromMap(Map<String, dynamic> m) => LiveStroke(
        id: (m['id'] as String?) ?? '',
        points: ((m['points'] as List?) ?? const [])
            .map((e) => Vec2.fromMap(e as Map<String, dynamic>))
            .toList(),
        color: (m['color'] as String?) ?? defaultColor,
        strokeWidth: (m['strokeWidth'] as num?)?.toDouble() ?? defaultStrokeWidth,
        srcPoints: (m['srcPoints'] as List?)
            ?.map((e) => StrokePoint.fromMap(e as Map<String, dynamic>))
            .toList(),
      );

  /// Render as a plain [StrokeData] so the page painter can draw it with the stroke path.
  StrokeData toStrokeData() =>
      StrokeData(color: color, strokeWidth: strokeWidth, points: [for (final p in points) StrokePoint(p.x, p.y)]);
}

/// `type = "heading"` payload. A recognized heading ([recognizedText] non-null) renders as scaled
/// canvas text and drops its strokes; an unrecognized one renders [strokes]. [level] (1–3) is the
/// authoritative H1/H2/H3 source and defaults to 1.
class HeadingObject {
  const HeadingObject({
    this.strokes = const [],
    this.recognizedText,
    this.level = 1,
  });

  final List<LiveStroke> strokes;
  final String? recognizedText;
  final int level;

  Map<String, dynamic> toMap() => {
        if (strokes.isNotEmpty) 'strokes': strokes.map((s) => s.toMap()).toList(),
        if (recognizedText != null) 'recognizedText': recognizedText,
        if (level != 1) 'level': level,
      };

  String toJson() => jsonEncode(toMap());

  static HeadingObject fromJson(String json) => fromMap(jsonDecode(json) as Map<String, dynamic>);

  static HeadingObject fromMap(Map<String, dynamic> m) => HeadingObject(
        strokes: ((m['strokes'] as List?) ?? const [])
            .map((e) => LiveStroke.fromMap(e as Map<String, dynamic>))
            .toList(),
        recognizedText: m['recognizedText'] as String?,
        level: (m['level'] as num?)?.toInt() ?? 1,
      );

  /// Markdown prefix for [level] (clamped 1–3), e.g. `"## "`.
  static String headingPrefix(int level) => '${'#' * level.clamp(1, 3)} ';

  /// Strips a leading 1–3 `#` run + spaces.
  static String stripHeadingPrefix(String text) =>
      text.replaceFirst(RegExp(r'^#{1,3}\s+'), '');

  /// [text] re-prefixed for [level]; null stays null (stroke-only headings).
  static String? applyLevel(String? text, int level) =>
      text == null ? null : headingPrefix(level) + stripHeadingPrefix(text);
}

/// `type = "text"` payload — raw Markdown [text] plus optional embedded [strokes] (unrecognized
/// lasso-converted objects where recognition failed / was not run).
class TextObject {
  const TextObject({this.text = '', this.strokes});

  final String text;
  final List<LiveStroke>? strokes;

  Map<String, dynamic> toMap() => {
        if (text.isNotEmpty) 'text': text,
        if (strokes != null) 'strokes': strokes!.map((s) => s.toMap()).toList(),
      };

  String toJson() => jsonEncode(toMap());

  static TextObject fromJson(String json) => fromMap(jsonDecode(json) as Map<String, dynamic>);

  static TextObject fromMap(Map<String, dynamic> m) => TextObject(
        text: (m['text'] as String?) ?? '',
        strokes: (m['strokes'] as List?)
            ?.map((e) => LiveStroke.fromMap(e as Map<String, dynamic>))
            .toList(),
      );
}

enum LineStyle { solid, dashed, dotted }

enum LineOrientation { horizontal, vertical }

/// `type = "line"` payload — a page guide. Enums serialize as their UPPERCASE Kotlin names.
class LineObject {
  const LineObject({
    required this.style,
    required this.orientation,
    this.strokeWidthDp = 1.0,
    this.dotSpacingDp = 0.0,
  });

  final LineStyle style;
  final LineOrientation orientation;
  final double strokeWidthDp;
  final double dotSpacingDp;

  Map<String, dynamic> toMap() => {
        'style': style.name.toUpperCase(),
        'orientation': orientation.name.toUpperCase(),
        if (strokeWidthDp != 1.0) 'strokeWidthDp': strokeWidthDp,
        if (dotSpacingDp != 0.0) 'dotSpacingDp': dotSpacingDp,
      };

  String toJson() => jsonEncode(toMap());

  static LineObject fromJson(String json) => fromMap(jsonDecode(json) as Map<String, dynamic>);

  static LineObject fromMap(Map<String, dynamic> m) => LineObject(
        style: _style(m['style'] as String?),
        orientation: _orientation(m['orientation'] as String?),
        strokeWidthDp: (m['strokeWidthDp'] as num?)?.toDouble() ?? 1.0,
        dotSpacingDp: (m['dotSpacingDp'] as num?)?.toDouble() ?? 0.0,
      );

  static LineStyle _style(String? s) {
    switch (s) {
      case 'DASHED':
        return LineStyle.dashed;
      case 'DOTTED':
        return LineStyle.dotted;
      default:
        return LineStyle.solid;
    }
  }

  static LineOrientation _orientation(String? s) =>
      s == 'VERTICAL' ? LineOrientation.vertical : LineOrientation.horizontal;
}

/// `type = "sticky_note"` payload — the note's embedded content, which travels inside the row's
/// `data` JSON (the LinkObject precedent) so copy/cut/paste, page copy, and export all carry it.
/// Wire-compatible with native `data/StickyNoteObject.kt` (kotlinx default `Json`,
/// `encodeDefaults = false` → every field equal to its default is OMITTED on write, and the native
/// decoder rejects unknown keys, so we emit ONLY the known keys).
///
/// Two coordinate spaces: the row's `boundingBox` is the icon rect on the page; the embedded content
/// lives in the content window's own pixel space, recorded via [contentWidth]/[contentHeight].
///
/// The Flutter editor authors [strokes] only (native deferred in-editor heading/text/line insertion).
/// The [headings]/[textObjects]/[lines]/[shapes] lists — which a native-authored note may carry — are
/// preserved as **opaque passthrough** (raw decoded JSON, re-emitted verbatim) so a note round-trips
/// losslessly without the port needing those embedded carriers yet.
class StickyNoteObject {
  const StickyNoteObject({
    this.strokes = const [],
    this.contentWidth = 0,
    this.contentHeight = 0,
    this.headings = const [],
    this.textObjects = const [],
    this.lines = const [],
    this.shapes = const [],
  });

  final List<LiveStroke> strokes;
  final double contentWidth;
  final double contentHeight;

  // Opaque passthrough — raw JSON maps preserved verbatim across a round-trip.
  final List<dynamic> headings;
  final List<dynamic> textObjects;
  final List<dynamic> lines;
  final List<dynamic> shapes;

  StickyNoteObject copyWith({
    List<LiveStroke>? strokes,
    double? contentWidth,
    double? contentHeight,
  }) =>
      StickyNoteObject(
        strokes: strokes ?? this.strokes,
        contentWidth: contentWidth ?? this.contentWidth,
        contentHeight: contentHeight ?? this.contentHeight,
        headings: headings,
        textObjects: textObjects,
        lines: lines,
        shapes: shapes,
      );

  Map<String, dynamic> toMap() => {
        if (strokes.isNotEmpty) 'strokes': strokes.map((s) => s.toMap()).toList(),
        if (headings.isNotEmpty) 'headings': headings,
        if (textObjects.isNotEmpty) 'textObjects': textObjects,
        if (lines.isNotEmpty) 'lines': lines,
        if (shapes.isNotEmpty) 'shapes': shapes,
        if (contentWidth != 0) 'contentWidth': contentWidth,
        if (contentHeight != 0) 'contentHeight': contentHeight,
      };

  String toJson() => jsonEncode(toMap());

  static StickyNoteObject fromJson(String json) =>
      fromMap(jsonDecode(json) as Map<String, dynamic>);

  static StickyNoteObject fromMap(Map<String, dynamic> m) => StickyNoteObject(
        strokes: ((m['strokes'] as List?) ?? const [])
            .map((e) => LiveStroke.fromMap(e as Map<String, dynamic>))
            .toList(),
        contentWidth: (m['contentWidth'] as num?)?.toDouble() ?? 0,
        contentHeight: (m['contentHeight'] as num?)?.toDouble() ?? 0,
        headings: (m['headings'] as List?) ?? const [],
        textObjects: (m['textObjects'] as List?) ?? const [],
        lines: (m['lines'] as List?) ?? const [],
        shapes: (m['shapes'] as List?) ?? const [],
      );
}
