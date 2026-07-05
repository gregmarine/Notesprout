import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../core/toc.dart';

/// Opens the Table of Contents (port of the native `TocDialog`): a collapsible, **paginated** panel
/// — fullscreen on a narrow screen, a left sidebar on a wide one — with active-page highlight and
/// tap-to-navigate. Returns the selected page id (navigate to it) or null if dismissed.
Future<String?> showTocPanel(BuildContext context, List<TocNode> roots, int currentPageIndex) =>
    showDialog<String>(
      context: context,
      barrierColor: Colors.transparent, // e-ink: no dimming scrim
      useSafeArea: false,
      builder: (_) => _TocPanel(roots: roots, currentPageIndex: currentPageIndex),
    );

const _inkLight = Color(0xFF888888);
const _rowHeight = 60.0;
const _wideThreshold = 480.0;
const _sidebarFraction = 0.6;
const _levelIndent = 18.0;

class _TocPanel extends StatefulWidget {
  const _TocPanel({required this.roots, required this.currentPageIndex});
  final List<TocNode> roots;
  final int currentPageIndex;

  @override
  State<_TocPanel> createState() => _TocPanelState();
}

class _TocPanelState extends State<_TocPanel> {
  final Set<String> _expanded = {}; // heading ids that are expanded
  late final Map<String, TocNode> _parent; // child id → parent node
  TocNode? _active; // node for the current page (deepest with pageIndex ≤ current)
  int _tocPage = 0;
  int _itemsPerPage = 1;
  bool _pagedToHighlight = false;

  @override
  void initState() {
    super.initState();
    _parent = _buildParentMap(widget.roots);
    // Active node = deepest heading whose page is at/-before the current page.
    final flat = _flatten(widget.roots);
    for (final n in flat) {
      if (n.pageIndex <= widget.currentPageIndex) {
        if (_active == null || n.pageIndex >= _active!.pageIndex) _active = n;
      }
    }
    // Pre-expand the active node's ancestor chain so it's visible when the panel opens.
    var a = _active == null ? null : _parent[_active!.headingId];
    while (a != null) {
      _expanded.add(a.headingId);
      a = _parent[a.headingId];
    }
  }

  List<TocNode> get _visible {
    final out = <TocNode>[];
    void visit(TocNode n) {
      out.add(n);
      if (_expanded.contains(n.headingId)) n.children.forEach(visit);
    }
    widget.roots.forEach(visit);
    return out;
  }

  String? get _highlightId {
    if (_active == null) return null;
    final visibleIds = _visible.map((n) => n.headingId).toSet();
    TocNode? c = _active;
    while (c != null) {
      if (visibleIds.contains(c.headingId)) return c.headingId;
      c = _parent[c.headingId];
    }
    return null;
  }

  void _toggle(String id) {
    setState(() {
      if (!_expanded.remove(id)) _expanded.add(id);
      final total = math.max(1, (_visible.length / _itemsPerPage).ceil());
      _tocPage = _tocPage.clamp(0, total - 1);
    });
  }

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.of(context).size.width >= _wideThreshold;
    final panel = Container(
      width: wide
          ? MediaQuery.of(context).size.width * _sidebarFraction
          : double.infinity,
      decoration: BoxDecoration(
        color: Colors.white,
        // The sidebar needs its own right edge; a fullscreen panel already spans the screen.
        border: wide ? const Border(right: BorderSide(color: Colors.black, width: 1)) : null,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _header(),
          Expanded(
            child: LayoutBuilder(builder: (context, c) {
              final per = math.max(1, (c.maxHeight / _rowHeight).floor());
              if (per != _itemsPerPage || !_pagedToHighlight) {
                _itemsPerPage = per;
                // On first measure, open on the page holding the highlighted node.
                if (!_pagedToHighlight) {
                  _pagedToHighlight = true;
                  final hid = _highlightId;
                  final i = hid == null ? -1 : _visible.indexWhere((n) => n.headingId == hid);
                  if (i >= 0) _tocPage = i ~/ per;
                }
              }
              return _list(per);
            }),
          ),
          _footer(),
        ],
      ),
    );

    return Material(
      type: MaterialType.transparency,
      child: wide
          ? Row(children: [
              panel,
              Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => Navigator.pop(context),
                  child: const SizedBox.expand(),
                ),
              ),
            ])
          : panel,
    );
  }

  Widget _header() => Container(
        height: 48,
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: Colors.black, width: 1)),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Row(
          children: [
            const Text('Contents',
                style: TextStyle(fontSize: 17, fontWeight: FontWeight.bold, color: Colors.black)),
            const Spacer(),
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => Navigator.pop(context),
              child: const Padding(
                padding: EdgeInsets.all(8),
                child: Text('✕', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
              ),
            ),
          ],
        ),
      );

  Widget _list(int per) {
    final visible = _visible;
    if (visible.isEmpty) {
      return const Center(
        child: Text('No headings yet', style: TextStyle(fontSize: 15, color: _inkLight)),
      );
    }
    final total = (visible.length / per).ceil();
    _tocPage = _tocPage.clamp(0, total - 1);
    final start = _tocPage * per;
    final slice = visible.sublist(start, math.min(start + per, visible.length));
    final hid = _highlightId;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [for (final n in slice) _row(n, n.headingId == hid)],
    );
  }

  Widget _row(TocNode n, bool active) {
    final hasChildren = n.children.isNotEmpty;
    final isExpanded = _expanded.contains(n.headingId);
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => Navigator.pop(context, n.pageId),
      child: Container(
        height: _rowHeight,
        decoration: BoxDecoration(
          color: Colors.white,
          // Active page → a thick marker on the far-right edge, flush against the pane border.
          border: Border(
            right: BorderSide(color: active ? Colors.black : Colors.transparent, width: 5),
            bottom: const BorderSide(color: _inkLight, width: 1),
          ),
        ),
        padding: EdgeInsets.only(left: 6 + (n.level - 1) * _levelIndent, right: 12),
        child: Row(
          children: [
            SizedBox(
              width: 30,
              child: hasChildren
                  ? GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () => _toggle(n.headingId),
                      child: Container(
                        alignment: Alignment.center,
                        child: Text(isExpanded ? '−' : '+',
                            style:
                                const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
                      ),
                    )
                  : const SizedBox.shrink(),
            ),
            Container(
              width: 34,
              alignment: Alignment.centerLeft,
              child: Text('${n.pageNumber}',
                  style: const TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w600, color: _inkLight)),
            ),
            Expanded(
              child: Text(
                n.title.isEmpty ? '(heading)' : n.title,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: n.level == 1 ? 16 : 15,
                  fontWeight: n.level == 1 ? FontWeight.w700 : FontWeight.w500,
                  color: n.title.isEmpty ? _inkLight : Colors.black,
                  fontStyle: n.title.isEmpty ? FontStyle.italic : FontStyle.normal,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _footer() {
    final visible = _visible;
    final total = visible.isEmpty ? 1 : (visible.length / _itemsPerPage).ceil();
    void go(int p) => setState(() => _tocPage = p.clamp(0, total - 1));
    return Container(
      height: 48,
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: Colors.black, width: 1)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: [
          _navBtn('≪', _tocPage > 0, () => go(0)),
          _navBtn('‹', _tocPage > 0, () => go(_tocPage - 1)),
          Expanded(
            child: Center(
              child: Text('${_tocPage + 1} / $total',
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
            ),
          ),
          _navBtn('›', _tocPage < total - 1, () => go(_tocPage + 1)),
          _navBtn('≫', _tocPage < total - 1, () => go(total - 1)),
        ],
      ),
    );
  }

  Widget _navBtn(String glyph, bool enabled, VoidCallback onTap) => GestureDetector(
        onTap: enabled ? onTap : null,
        behavior: HitTestBehavior.opaque,
        child: Container(
          width: 40,
          height: 34,
          alignment: Alignment.center,
          child: Text(glyph,
              style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w700,
                  color: enabled ? Colors.black : _inkLight)),
        ),
      );

  // ── tree helpers ──
  static Map<String, TocNode> _buildParentMap(List<TocNode> roots) {
    final map = <String, TocNode>{};
    void visit(TocNode n) {
      for (final c in n.children) {
        map[c.headingId] = n;
        visit(c);
      }
    }

    roots.forEach(visit);
    return map;
  }

  static List<TocNode> _flatten(List<TocNode> roots) {
    final out = <TocNode>[];
    void visit(TocNode n) {
      out.add(n);
      n.children.forEach(visit);
    }

    roots.forEach(visit);
    return out;
  }
}
