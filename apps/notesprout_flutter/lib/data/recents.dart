/// One device-local "recently opened notebook" record (port of native `RecentEntry`). Stored — never
/// in `notesprout.db` or any `.soil` — as a JSON list in [AppSettings], ordered newest-first.
class RecentEntry {
  const RecentEntry(this.notebookId, this.timestamp);
  final String notebookId;
  final int timestamp; // ms since epoch; set on open, refreshed on close

  Map<String, dynamic> toJson() => {'notebookId': notebookId, 'timestamp': timestamp};

  static RecentEntry fromJson(Map j) =>
      RecentEntry(j['notebookId'] as String, (j['timestamp'] as num).toInt());
}

/// A [RecentEntry] resolved against the index into a display-ready model (port of native
/// `ResolvedRecent`). [folderPath] is the full breadcrumb, e.g. `"Notebooks › A › B"`.
class ResolvedRecent {
  const ResolvedRecent(this.notebookId, this.name, this.folderPath, this.timestamp);
  final String notebookId;
  final String name;
  final String folderPath;
  final int timestamp;
}
