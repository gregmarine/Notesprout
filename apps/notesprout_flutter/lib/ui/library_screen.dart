import 'package:flutter/material.dart';

import '../data/db_worker.dart';
import '../data/index_database.dart';
import 'notebook_screen.dart';

/// The library: the notebook list from the global index + create-new. Tapping a notebook opens it.
class LibraryScreen extends StatefulWidget {
  const LibraryScreen({super.key, required this.worker});

  final DbWorker worker;

  @override
  State<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends State<LibraryScreen> {
  List<NotebookEntry> _notebooks = [];

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final list = await widget.worker.listNotebooks();
    if (mounted) setState(() => _notebooks = list);
  }

  Future<void> _create() async {
    final name = await _promptName();
    if (name == null || name.trim().isEmpty || !mounted) return;
    final size = MediaQuery.of(context).size;
    final dpr = MediaQuery.of(context).devicePixelRatio;
    await widget.worker.createNotebook(name.trim(), size.width * dpr, size.height * dpr);
    await _refresh();
  }

  Future<String?> _promptName() {
    final controller = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: Colors.white,
        shape: RoundedRectangleBorder(
          side: const BorderSide(color: Colors.black, width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        title: const Text('New notebook'),
        content: TextField(
          controller: controller,
          autofocus: true,
          cursorColor: Colors.black,
          decoration: const InputDecoration(
            hintText: 'Name',
            enabledBorder: UnderlineInputBorder(borderSide: BorderSide(color: Colors.black)),
            focusedBorder: UnderlineInputBorder(borderSide: BorderSide(color: Colors.black)),
          ),
          onSubmitted: (v) => Navigator.of(ctx).pop(v),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Cancel', style: TextStyle(color: Colors.black)),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(controller.text),
            child: const Text('Create', style: TextStyle(color: Colors.black)),
          ),
        ],
      ),
    );
  }

  Future<void> _open(NotebookEntry nb) async {
    await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => NotebookScreen(worker: widget.worker, notebookId: nb.id, title: nb.name),
    ));
    await _refresh(); // updatedAt / page count may have changed
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Column(
          children: [
            _header(),
            const Divider(height: 1, thickness: 1, color: Colors.black),
            Expanded(
              child: _notebooks.isEmpty
                  ? const Center(
                      child: Text('No notebooks yet.\nTap “New” to plant one. 🌱',
                          textAlign: TextAlign.center,
                          style: TextStyle(fontSize: 16, color: Colors.black)))
                  : ListView.separated(
                      itemCount: _notebooks.length,
                      separatorBuilder: (_, _) =>
                          const Divider(height: 1, thickness: 1, color: Colors.black),
                      itemBuilder: (_, i) {
                        final nb = _notebooks[i];
                        return ListTile(
                          title: Text(nb.name,
                              style: const TextStyle(
                                  fontSize: 17, fontWeight: FontWeight.w600, color: Colors.black)),
                          subtitle: Text('${nb.pageCount} page${nb.pageCount == 1 ? '' : 's'}',
                              style: const TextStyle(color: Colors.black)),
                          onTap: () => _open(nb),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _header() {
    return Container(
      height: 56,
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Row(
        children: [
          const Text('Notesprout',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: Colors.black)),
          const Spacer(),
          GestureDetector(
            onTap: _create,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
              decoration: BoxDecoration(
                border: Border.all(color: Colors.black, width: 1),
                borderRadius: BorderRadius.circular(4),
              ),
              child: const Text('New',
                  style: TextStyle(
                      fontSize: 16, fontWeight: FontWeight.w600, color: Colors.black)),
            ),
          ),
        ],
      ),
    );
  }
}
