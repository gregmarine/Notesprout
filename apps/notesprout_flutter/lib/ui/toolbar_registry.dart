import 'nb_icons.dart';

/// Static metadata for one customizable toolbar button — the Flutter port of the native
/// `ToolbarButtonRegistry.ButtonSpec`. Behaviour (onTap/selected) is supplied by the notebook
/// screen; this only describes the button (stable key, icon, label, group, pinned).
class ButtonSpec {
  const ButtonSpec(this.key, this.icon, this.label, this.group, {this.pinned = false});
  final String key;
  final String icon; // SVG asset path
  final String label;
  final String group; // consecutive buttons whose group differs get an auto-divider
  final bool pinned; // Close + Customize gear: always present, never hideable
}

/// Single source of truth mapping each toolbar button key → its metadata. Keys are stable and
/// persisted in [ToolbarConfig]; treat as append-only. Order below seeds [defaultOrder] (a logical
/// grouped order matching the shipped bar).
class ToolbarRegistry {
  ToolbarRegistry._();

  static const groupFile = 'file';
  static const groupNotebook = 'notebook';
  static const groupTools = 'tools';
  static const groupHistory = 'history';
  static const groupPageView = 'pageView';
  static const groupPageEdit = 'pageEdit';
  static const groupSettings = 'settings';

  static const pinnedKey = 'close';
  static const settingsKey = 'toolbarSettings';

  static const List<ButtonSpec> specs = [
    ButtonSpec(pinnedKey, NbIcons.close, 'Close', groupFile, pinned: true),
    ButtonSpec('recents', NbIcons.recents, 'Recents', groupFile),
    ButtonSpec('toc', NbIcons.toc, 'Table of Contents', groupNotebook),
    ButtonSpec('cover', NbIcons.cover, 'Set Cover', groupNotebook),
    ButtonSpec('export', NbIcons.export, 'Export', groupNotebook),
    ButtonSpec('pin', NbIcons.pin, 'Pin', groupNotebook),
    ButtonSpec('lock', NbIcons.lock, 'Encrypt', groupNotebook),
    ButtonSpec('scratchpad', NbIcons.scratchpad, 'Scratch Pad', groupNotebook),
    ButtonSpec('calendar', NbIcons.calendar, 'Calendar', groupNotebook),
    ButtonSpec('pen', NbIcons.pen, 'Pen', groupTools),
    ButtonSpec('eraser', NbIcons.eraser, 'Eraser', groupTools),
    ButtonSpec('lassoEraser', NbIcons.lassoEraser, 'Lasso Eraser', groupTools),
    ButtonSpec('eraseAll', NbIcons.eraseAll, 'Erase All', groupTools),
    ButtonSpec('insertText', NbIcons.insertText, 'Insert Text', groupTools),
    ButtonSpec('insertLines', NbIcons.insertLines, 'Insert Lines', groupTools),
    ButtonSpec('lasso', NbIcons.lasso, 'Lasso', groupTools),
    ButtonSpec('stickyNote', NbIcons.stickyNote, 'Insert Sticky Note', groupTools),
    ButtonSpec('insertShape', NbIcons.insertShape, 'Insert Shape', groupTools),
    ButtonSpec('undo', NbIcons.undo, 'Undo', groupHistory),
    ButtonSpec('redo', NbIcons.redo, 'Redo', groupHistory),
    ButtonSpec('template', NbIcons.template, 'Template', groupPageView),
    ButtonSpec('pageIndex', NbIcons.pageIndex, 'Page Index', groupPageView),
    ButtonSpec('insertPageBefore', NbIcons.insertPageBefore, 'Insert Page Before', groupPageEdit),
    ButtonSpec('insertPageAfter', NbIcons.insertPageAfter, 'Insert Page After', groupPageEdit),
    ButtonSpec('deletePage', NbIcons.deletePage, 'Delete Page', groupPageEdit),
    ButtonSpec('copyPage', NbIcons.copyPage, 'Copy Page', groupPageEdit),
    ButtonSpec('pastePage', NbIcons.pastePage, 'Paste Page', groupPageEdit),
    ButtonSpec(settingsKey, NbIcons.settings, 'Customize Toolbar', groupSettings, pinned: true),
  ];

  static final Map<String, ButtonSpec> _byKey = {for (final s in specs) s.key: s};

  static ButtonSpec? spec(String key) => _byKey[key];

  /// Full default order (registry order).
  static final List<String> defaultOrder = [for (final s in specs) s.key];

  /// Default mini set — a compact everyday subset (excludes Close/gear, which are always in mini).
  static const List<String> defaultMini = ['pen', 'eraser', 'undo', 'lasso', 'pageIndex'];

  /// Resolve the ordered, filtered list of visible keys for [order]/[hidden] (or the mini subset).
  /// Port of the native `resolveVisibleKeys`: pinned Close leads and the gear trails, both always
  /// present; unknown keys dropped; any registry key missing from a stale [order] appended.
  static List<String> resolveVisible({
    required List<String> order,
    required Set<String> hidden,
    required List<String> miniSet,
    required bool mini,
  }) {
    if (mini) {
      return [
        if (spec(pinnedKey) != null) pinnedKey,
        for (final k in miniSet)
          if (k != pinnedKey && k != settingsKey && spec(k) != null) k,
        if (spec(settingsKey) != null) settingsKey,
      ];
    }
    final result = <String>[
      for (final k in order)
        if (spec(k) case final s?)
          if (s.pinned || !hidden.contains(k)) k,
    ];
    for (final k in defaultOrder) {
      if (!order.contains(k) && !hidden.contains(k) && !result.contains(k)) result.add(k);
    }
    if (!result.contains(pinnedKey) && spec(pinnedKey) != null) result.insert(0, pinnedKey);
    if (!result.contains(settingsKey) && spec(settingsKey) != null) result.add(settingsKey);
    return result;
  }
}
