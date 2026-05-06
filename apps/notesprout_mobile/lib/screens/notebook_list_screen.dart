import 'dart:io';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:notesprout_core/notesprout_core.dart';
import 'package:path/path.dart' as p;

const _notesDir = '/storage/emulated/0/Documents/NoteSprout';
final _dateFormatter = DateFormat('MMM dd, yyyy');
final _fileNameFormatter = DateFormat('yyyyMMdd_HHmmss');

// Each notebook lives at: _notesDir/{folderName}/notebook.soil
const _soilFile = 'notebook.soil';

class _NotebookEntry {
  final String folderPath;
  final NotebookMeta meta;

  const _NotebookEntry({required this.folderPath, required this.meta});

  String get soilPath => p.join(folderPath, _soilFile);
}

class NotebookListScreen extends StatefulWidget {
  const NotebookListScreen({super.key});

  @override
  State<NotebookListScreen> createState() => _NotebookListScreenState();
}

class _NotebookListScreenState extends State<NotebookListScreen> {
  List<_NotebookEntry> _notebooks = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadNotebooks();
  }

  Future<void> _loadNotebooks() async {
    setState(() => _loading = true);

    final dir = Directory(_notesDir);
    if (!await dir.exists()) {
      await dir.create(recursive: true);
    }

    final entries = <_NotebookEntry>[];
    await for (final entity in dir.list()) {
      if (entity is! Directory) continue;

      final soilPath = p.join(entity.path, _soilFile);
      if (!await File(soilPath).exists()) continue;

      final db = SoilDatabase(soilPath);
      try {
        final meta = await db.getNotebookMeta();
        entries.add(_NotebookEntry(folderPath: entity.path, meta: meta));
      } catch (_) {
        // Skip folders whose notebook.soil is corrupt or unreadable.
      } finally {
        await db.close();
      }
    }

    entries.sort((a, b) => b.meta.updatedAt.compareTo(a.meta.updatedAt));

    if (mounted) {
      setState(() {
        _notebooks = entries;
        _loading = false;
      });
    }
  }

  // ---------------------------------------------------------------------------
  // Create
  // ---------------------------------------------------------------------------

  Future<void> _showCreateDialog() async {
    final defaultName = _fileNameFormatter.format(DateTime.now());
    final controller = TextEditingController(text: defaultName);

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('New Notebook'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'Name'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Create'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    final name = controller.text.trim();
    if (name.isEmpty) return;

    final folderPath = p.join(_notesDir, name);
    await Directory(folderPath).create(recursive: true);

    final db = SoilDatabase(p.join(folderPath, _soilFile));
    await db.initializeNotebook(name);
    await db.close();

    await _loadNotebooks();
  }

  // ---------------------------------------------------------------------------
  // Rename
  // ---------------------------------------------------------------------------

  Future<void> _showRenameDialog(_NotebookEntry entry) async {
    final controller = TextEditingController(text: entry.meta.name);

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Rename Notebook'),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'Name'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Save'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    final newName = controller.text.trim();
    if (newName.isEmpty || newName == entry.meta.name) return;

    // Update the name stored inside the database first.
    final db = SoilDatabase(entry.soilPath);
    await db.updateNotebookName(newName);
    await db.close();

    // Rename the folder — notebook.soil stays in place inside it.
    final newFolderPath = p.join(_notesDir, newName);
    await Directory(entry.folderPath).rename(newFolderPath);

    await _loadNotebooks();
  }

  // ---------------------------------------------------------------------------
  // Delete
  // ---------------------------------------------------------------------------

  Future<void> _showDeleteDialog(_NotebookEntry entry) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete Notebook'),
        content: Text(
          'Are you sure you want to delete ${entry.meta.name}? This cannot be undone.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('Delete'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    // Delete the entire folder and everything inside it.
    await Directory(entry.folderPath).delete(recursive: true);
    await _loadNotebooks();
  }

  // ---------------------------------------------------------------------------
  // Build
  // ---------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('NoteSprout')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _notebooks.isEmpty
              ? const Center(
                  child: Text(
                    'No notebooks yet. Tap + to create one.',
                    style: TextStyle(color: Colors.grey),
                  ),
                )
              : ListView.separated(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 12,
                  ),
                  itemCount: _notebooks.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (_, index) => _NotebookCard(
                    entry: _notebooks[index],
                    onTap: () => _showRenameDialog(_notebooks[index]),
                    onDelete: () => _showDeleteDialog(_notebooks[index]),
                  ),
                ),
      floatingActionButton: FloatingActionButton(
        onPressed: _showCreateDialog,
        child: const Icon(Icons.add),
      ),
    );
  }
}

class _NotebookCard extends StatelessWidget {
  final _NotebookEntry entry;
  final VoidCallback onTap;
  final VoidCallback onDelete;

  const _NotebookCard({
    required this.entry,
    required this.onTap,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        title: Text(
          entry.meta.name,
          style: const TextStyle(fontWeight: FontWeight.w500),
        ),
        subtitle: Text(
          _dateFormatter.format(entry.meta.createdAt),
          style: const TextStyle(fontSize: 12, color: Colors.grey),
        ),
        trailing: IconButton(
          icon: const Icon(Icons.delete_outline, color: Colors.grey),
          onPressed: onDelete,
        ),
        onTap: onTap,
      ),
    );
  }
}
