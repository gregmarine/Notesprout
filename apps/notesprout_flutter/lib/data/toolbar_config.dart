import '../ui/toolbar_registry.dart';

/// Which edge the toolbar anchors to, or FLOAT (a detached, draggable bar).
enum ToolbarPlacement { top, right, bottom, left, float }

/// Orientation of the floating bar.
enum ToolbarAxis { horizontal, vertical }

/// Global, device-local configuration for the notebook toolbar — one config for every notebook,
/// persisted (JSON) by [AppSettings], never in a `.soil`. Port of the native `ToolbarConfig`.
///
/// The default reproduces the current fixed top toolbar (full button set, top-anchored).
class ToolbarConfig {
  ToolbarConfig({
    this.placement = ToolbarPlacement.top,
    List<String>? order,
    this.hidden = const {},
    List<String>? miniSet,
    this.miniEnabled = false,
    this.floatX = -1,
    this.floatY = -1,
    this.floatAxis = ToolbarAxis.horizontal,
    this.collapsed = false,
  })  : order = order ?? ToolbarRegistry.defaultOrder,
        miniSet = miniSet ?? ToolbarRegistry.defaultMini;

  final ToolbarPlacement placement;
  final List<String> order; // full button order as registry keys, first→last
  final Set<String> hidden; // hidden keys (Close/gear can never be hidden)
  final List<String> miniSet; // ≤5 EXTRA keys shown in mini (excludes Close/gear)
  final bool miniEnabled; // only takes effect when placement == float
  final double floatX; // last float position (-1 = uninitialised → center)
  final double floatY;
  final ToolbarAxis floatAxis;
  final bool collapsed; // bar currently hidden (double-tap toggle)

  ToolbarConfig copyWith({
    ToolbarPlacement? placement,
    List<String>? order,
    Set<String>? hidden,
    List<String>? miniSet,
    bool? miniEnabled,
    double? floatX,
    double? floatY,
    ToolbarAxis? floatAxis,
    bool? collapsed,
  }) =>
      ToolbarConfig(
        placement: placement ?? this.placement,
        order: order ?? this.order,
        hidden: hidden ?? this.hidden,
        miniSet: miniSet ?? this.miniSet,
        miniEnabled: miniEnabled ?? this.miniEnabled,
        floatX: floatX ?? this.floatX,
        floatY: floatY ?? this.floatY,
        floatAxis: floatAxis ?? this.floatAxis,
        collapsed: collapsed ?? this.collapsed,
      );

  Map<String, dynamic> toJson() => {
        'placement': placement.name,
        'order': order,
        'hidden': hidden.toList(),
        'miniSet': miniSet,
        'miniEnabled': miniEnabled,
        'floatX': floatX,
        'floatY': floatY,
        'floatAxis': floatAxis.name,
        'collapsed': collapsed,
      };

  /// Tolerant parse — any malformed/missing field falls back to its default (matches native's
  /// `ignoreUnknownKeys` + defaulting so a removed field never breaks an old saved config).
  static ToolbarConfig fromJson(Map<String, dynamic> j) => ToolbarConfig(
        placement: _enumByName(ToolbarPlacement.values, j['placement'], ToolbarPlacement.top),
        order: _stringList(j['order']) ?? ToolbarRegistry.defaultOrder,
        hidden: _stringList(j['hidden'])?.toSet() ?? const {},
        miniSet: _stringList(j['miniSet']) ?? ToolbarRegistry.defaultMini,
        miniEnabled: j['miniEnabled'] as bool? ?? false,
        floatX: (j['floatX'] as num?)?.toDouble() ?? -1,
        floatY: (j['floatY'] as num?)?.toDouble() ?? -1,
        floatAxis: _enumByName(ToolbarAxis.values, j['floatAxis'], ToolbarAxis.horizontal),
        collapsed: j['collapsed'] as bool? ?? false,
      );

  static List<String>? _stringList(Object? v) =>
      v is List ? [for (final e in v) e.toString()] : null;

  static T _enumByName<T extends Enum>(List<T> values, Object? name, T fallback) {
    for (final v in values) {
      if (v.name == name) return v;
    }
    return fallback;
  }
}
