import 'dart:convert';
import 'dart:io';

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
