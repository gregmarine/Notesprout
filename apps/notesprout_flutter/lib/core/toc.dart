import '../data/soil_database.dart';

/// One entry in the Table of Contents tree (port of the native `TocNode`). H2/H3 nodes hang off
/// their parent via [children]; the builder returns the H1 roots.
class TocNode {
  TocNode({
    required this.pageNumber,
    required this.pageIndex,
    required this.pageId,
    required this.level,
    required this.title,
    required this.headingId,
  });

  final int pageNumber;
  final int pageIndex;
  final String pageId;
  final int level; // 1, 2, or 3
  final String title; // prefix-stripped; "" if unrecognized
  final String headingId;
  final List<TocNode> children = [];
}

/// Build the TOC as a tree in document order (port of native `TocRepository.buildTocTree`):
/// headings resolved to their page via the layer→page map, sorted by pageIndex/top/left, then a
/// single pass with running H1/H2 pointers (level-2 orphans without an H1, and level-3 without an
/// H2, are skipped). [pages] is the notebook's page list in canonical order.
List<TocNode> buildTocTree(List<HeadingRow> headings, List<PageRef> pages) {
  final layerToIndex = <String, int>{};
  for (var i = 0; i < pages.length; i++) {
    layerToIndex[pages[i].layerId] = i;
  }

  final entries = <_Entry>[];
  for (final h in headings) {
    final idx = layerToIndex[h.layerId];
    if (idx == null) continue; // heading on a deleted/unknown page
    entries.add(_Entry(idx, pages[idx].pageId, h));
  }
  entries.sort((a, b) {
    if (a.pageIndex != b.pageIndex) return a.pageIndex.compareTo(b.pageIndex);
    final t = a.h.top.compareTo(b.h.top);
    return t != 0 ? t : a.h.left.compareTo(b.h.left);
  });

  final roots = <TocNode>[];
  TocNode? curH1, curH2;
  for (final e in entries) {
    final level = e.h.level.clamp(1, 3).toInt();
    final node = TocNode(
      pageNumber: e.pageIndex + 1,
      pageIndex: e.pageIndex,
      pageId: e.pageId,
      level: level,
      title: e.h.title,
      headingId: e.h.id,
    );
    switch (level) {
      case 1:
        roots.add(node);
        curH1 = node;
        curH2 = null;
      case 2:
        if (curH1 != null) {
          curH1.children.add(node);
          curH2 = node;
        }
      default:
        if (curH2 != null) curH2.children.add(node);
    }
  }
  return roots;
}

class _Entry {
  _Entry(this.pageIndex, this.pageId, this.h);
  final int pageIndex;
  final String pageId;
  final HeadingRow h;
}
