import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../data/db_worker.dart';
import '../data/index_database.dart';
import '../main.dart';
import 'notebook_screen.dart';
import 'overflow_toolbar.dart';

/// The library: a browsable folder + notebook tree from the global index. Folders and notebooks
/// show as cards; tap a folder to descend, a breadcrumb to climb out, a notebook to open. Ports the
/// core browse experience of the native `MainActivity` (search/sort/recents/covers come later).
class LibraryScreen extends StatefulWidget {
  const LibraryScreen({super.key, required this.worker});

  final DbWorker worker;

  @override
  State<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends State<LibraryScreen> with RouteAware {
  String? _folderId; // null == root
  List<LibraryEntry> _entries = [];
  List<Crumb> _crumbs = [const Crumb(null, 'Notebooks')];

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (route is PageRoute) appRouteObserver.subscribe(this, route);
  }

  @override
  void dispose() {
    appRouteObserver.unsubscribe(this);
    super.dispose();
  }

  // Returning to the library from a notebook: restore the system bars the notebook hid (it runs
  // immersive; see appRouteObserver in main.dart for why this lives here, not in the notebook).
  @override
  void didPopNext() => SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

  Future<void> _refresh() async {
    final entries = await widget.worker.browse(_folderId);
    final crumbs = await widget.worker.breadcrumb(_folderId);
    if (mounted) {
      setState(() {
        _entries = entries;
        _crumbs = crumbs;
      });
    }
  }

  void _navigateTo(String? folderId) {
    if (folderId == _folderId) return;
    setState(() => _folderId = folderId);
    _refresh();
  }

  /// True while inside a folder — back should climb one level instead of leaving the app.
  bool get _canClimb => _folderId != null;

  void _climbOut() {
    // Parent is the crumb just before the current one (root when at depth 1).
    final parent = _crumbs.length >= 2 ? _crumbs[_crumbs.length - 2] : const Crumb(null, 'Notebooks');
    _navigateTo(parent.id);
  }

  Future<void> _newNotebook() async {
    final name = await _promptName('New notebook');
    if (name == null || name.trim().isEmpty || !mounted) return;
    final dpr = MediaQuery.of(context).devicePixelRatio;
    final size = MediaQuery.of(context).size;
    await widget.worker.createNotebook(
      name.trim(),
      size.width * dpr,
      size.height * dpr,
      parentId: _folderId,
    );
    await _refresh();
  }

  Future<void> _newFolder() async {
    final name = await _promptName('New folder');
    if (name == null || name.trim().isEmpty || !mounted) return;
    await widget.worker.createFolder(name.trim(), _folderId);
    await _refresh();
  }

  Future<void> _open(LibraryEntry nb) async {
    await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => NotebookScreen(worker: widget.worker, notebookId: nb.id, title: nb.name),
    ));
    await _refresh(); // updatedAt / page count may have changed
  }

  Future<String?> _promptName(String title) {
    final controller = TextEditingController();
    return showDialog<String>(
      context: context,
      // E-ink: no full-screen dim behind the dialog (the default black54 scrim renders as a gray
      // wash over the whole page). The dialog's 1dp border provides all the separation we need.
      barrierColor: Colors.transparent,
      builder: (ctx) => AlertDialog(
        backgroundColor: Colors.white,
        // E-ink: no drop shadow, no M3 surface tint — a flat white box with a 1dp inkBlack border
        // (matches native's `elevation 0` + `shape_bordered`).
        elevation: 0,
        shadowColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          side: const BorderSide(color: Colors.black, width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        title: Text(title, style: const TextStyle(color: Colors.black)),
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

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_canClimb,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && _canClimb) _climbOut();
      },
      child: Scaffold(
        backgroundColor: Colors.white,
        body: SafeArea(
          child: Column(
            children: [
              _header(),
              const Divider(height: 1, thickness: 1, color: Colors.black),
              _breadcrumbBar(),
              const Divider(height: 1, thickness: 1, color: Colors.black),
              Expanded(child: _entries.isEmpty ? _emptyState() : _grid()),
            ],
          ),
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
          _actionButton('New Folder', _newFolder),
          const SizedBox(width: 8),
          _actionButton('New Notebook', _newNotebook),
        ],
      ),
    );
  }

  Widget _actionButton(String label, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          border: Border.all(color: Colors.black, width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(label,
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: Colors.black)),
      ),
    );
  }

  Widget _breadcrumbBar() {
    return Container(
      height: 40,
      alignment: Alignment.centerLeft,
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: LayoutBuilder(builder: (context, constraints) {
        final crumbs = _crumbs;
        final sepW = _textWidth(' › ');
        final full =
            crumbs.fold(0.0, (a, c) => a + _textWidth(c.name)) + sepW * (crumbs.length - 1);
        // Never scroll: show the whole trail when it fits, else collapse the middle into a "…"
        // menu (keeping root + current visible) — the breadcrumb form of toolbar overflow.
        if (crumbs.length <= 1 || full <= constraints.maxWidth) {
          final children = <Widget>[];
          for (var i = 0; i < crumbs.length; i++) {
            if (i > 0) children.add(_sep());
            children.add(_crumb(crumbs[i], isLast: i == crumbs.length - 1));
          }
          return Row(mainAxisSize: MainAxisSize.min, children: children);
        }
        final hidden = crumbs.sublist(1, crumbs.length - 1);
        return Row(mainAxisSize: MainAxisSize.min, children: [
          _crumb(crumbs.first, isLast: false),
          _sep(),
          Builder(
            builder: (btnCtx) => GestureDetector(
              onTap: hidden.isEmpty
                  ? null
                  : () => showOverflowMenu(btnCtx,
                      [for (final c in hidden) TbButton(c.name, onTap: () => _navigateTo(c.id))]),
              child: const Text('…', style: TextStyle(fontSize: 15, color: Colors.black)),
            ),
          ),
          _sep(),
          _crumb(crumbs.last, isLast: true),
        ]);
      }),
    );
  }

  Widget _crumb(Crumb c, {required bool isLast}) => GestureDetector(
        onTap: isLast ? null : () => _navigateTo(c.id),
        child: Text(c.name,
            style: TextStyle(
                fontSize: 15,
                color: Colors.black,
                fontWeight: isLast ? FontWeight.w700 : FontWeight.w400)),
      );

  Widget _sep() => const Padding(
        padding: EdgeInsets.symmetric(horizontal: 6),
        child: Text('›', style: TextStyle(fontSize: 16, color: Colors.black)),
      );

  double _textWidth(String s) {
    final tp = TextPainter(
      text: TextSpan(text: s, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
      textDirection: TextDirection.ltr,
    )..layout();
    return tp.width;
  }

  Widget _emptyState() {
    final msg = _folderId == null
        ? 'No notebooks yet.\nTap “New Notebook” to plant one. 🌱'
        : 'This folder is empty.';
    return Center(
      child: Text(msg,
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 16, color: Colors.black)),
    );
  }

  Widget _grid() {
    return GridView.builder(
      padding: const EdgeInsets.all(16),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 180,
        childAspectRatio: 0.82,
        crossAxisSpacing: 12,
        mainAxisSpacing: 12,
      ),
      itemCount: _entries.length,
      itemBuilder: (_, i) => _card(_entries[i]),
    );
  }

  Widget _card(LibraryEntry e) {
    return GestureDetector(
      onTap: () => e.isFolder ? _navigateTo(e.id) : _open(e),
      child: Container(
        decoration: BoxDecoration(
          color: Colors.white,
          border: Border.all(color: Colors.black, width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Center(
                child: Icon(
                  e.isFolder ? Icons.folder_outlined : Icons.description_outlined,
                  size: 48,
                  color: Colors.black,
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              e.name,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                  fontSize: 15, fontWeight: FontWeight.w600, color: Colors.black),
            ),
            if (!e.isFolder)
              Text('${e.pageCount} page${e.pageCount == 1 ? '' : 's'}',
                  style: const TextStyle(fontSize: 12, color: Colors.black)),
          ],
        ),
      ),
    );
  }
}
