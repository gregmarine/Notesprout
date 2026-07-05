import 'dart:convert';
import 'dart:io';

import 'recents.dart';
import 'toolbar_config.dart';

/// Device-local app settings persisted as a small JSON file (mirrors the native app's
/// SharedPreferences-style stores — NOT in any `.soil` or the global index). Loaded once at
/// startup; reads are synchronous from the in-memory cache, writes fire-and-forget to disk.
class AppSettings {
  AppSettings._(this._file, this._data);

  final File? _file;
  final Map<String, dynamic> _data;

  static AppSettings _instance = AppSettings._(null, {}); // safe defaults until load()
  static AppSettings get instance => _instance;

  /// Read the settings file at [path] into memory. Tolerant of a missing/corrupt file (→ defaults).
  static Future<void> load(String path) async {
    final f = File(path);
    var data = <String, dynamic>{};
    try {
      if (await f.exists()) {
        data = (jsonDecode(await f.readAsString()) as Map).cast<String, dynamic>();
      }
    } catch (_) {
      data = {};
    }
    _instance = AppSettings._(f, data);
  }

  bool get snapEnabled => _data['snapEnabled'] == true;
  Future<void> setSnapEnabled(bool v) async {
    _data['snapEnabled'] = v;
    await _save();
  }

  /// Global notebook-toolbar customization (order, hidden, placement, float, mini, collapsed).
  ToolbarConfig get toolbarConfig {
    final j = _data['toolbarConfig'];
    return j is Map ? ToolbarConfig.fromJson(j.cast<String, dynamic>()) : ToolbarConfig();
  }

  Future<void> setToolbarConfig(ToolbarConfig c) async {
    _data['toolbarConfig'] = c.toJson();
    await _save();
  }

  // ── Recently-opened notebooks (device-local; ports native RecentsManager store) ──
  static const _maxRecents = 20;

  /// Recent entries, newest-first. Resolve against the index before display.
  List<RecentEntry> get recents {
    final raw = _data['recents'];
    if (raw is! List) return const [];
    final list = [for (final e in raw) if (e is Map) RecentEntry.fromJson(e)];
    list.sort((a, b) => b.timestamp.compareTo(a.timestamp));
    return list;
  }

  Future<void> _writeRecents(List<RecentEntry> list) async {
    _data['recents'] = [for (final e in list) e.toJson()];
    await _save();
  }

  /// Record a notebook open: move it to the top with a fresh timestamp, cap at [_maxRecents].
  Future<void> recordRecentOpen(String notebookId) async {
    if (notebookId.isEmpty) return;
    final now = DateTime.now().millisecondsSinceEpoch;
    final list = [
      RecentEntry(notebookId, now),
      ...recents.where((e) => e.notebookId != notebookId),
    ].take(_maxRecents).toList();
    await _writeRecents(list);
  }

  /// Record a notebook close: refresh its timestamp if present (no-op if it was never opened).
  Future<void> recordRecentClose(String notebookId) async {
    if (notebookId.isEmpty) return;
    final existing = recents;
    if (!existing.any((e) => e.notebookId == notebookId)) return;
    final now = DateTime.now().millisecondsSinceEpoch;
    final list = existing
        .map((e) => e.notebookId == notebookId ? RecentEntry(notebookId, now) : e)
        .toList()
      ..sort((a, b) => b.timestamp.compareTo(a.timestamp));
    await _writeRecents(list);
  }

  /// Drop entries whose notebooks no longer resolve (missing/deleted), discovered at display time.
  Future<void> pruneRecents(Iterable<String> notebookIds) async {
    final drop = notebookIds.toSet();
    if (drop.isEmpty) return;
    final kept = recents.where((e) => !drop.contains(e.notebookId)).toList();
    if (kept.length == recents.length) return;
    await _writeRecents(kept);
  }

  Future<void> _save() async {
    final f = _file;
    if (f == null) return;
    try {
      await f.writeAsString(jsonEncode(_data));
    } catch (_) {
      // best-effort; a failed settings write must never break the app
    }
  }
}
