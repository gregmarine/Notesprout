import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../data/recents.dart';

/// Opens the Recent Notebooks switcher (port of the native `RecentsDialog`): a **paginated** panel —
/// fullscreen on a narrow screen, a left sidebar on a wide one — listing recently-opened notebooks
/// newest-first. Returns the picked notebook (switch to it) or null if dismissed.
Future<ResolvedRecent?> showRecentsPanel(BuildContext context, List<ResolvedRecent> entries) =>
    showDialog<ResolvedRecent>(
      context: context,
      barrierColor: Colors.transparent, // e-ink: no dimming scrim
      useSafeArea: false,
      builder: (_) => _RecentsPanel(entries: entries),
    );

const _inkLight = Color(0xFF888888);
const _rowHeight = 72.0;
const _wideThreshold = 480.0;
const _sidebarFraction = 0.6;

const _months = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', //
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

String _formatWhen(int ms) {
  final d = DateTime.fromMillisecondsSinceEpoch(ms);
  final h12 = d.hour % 12 == 0 ? 12 : d.hour % 12;
  final ampm = d.hour < 12 ? 'AM' : 'PM';
  final mm = d.minute.toString().padLeft(2, '0');
  return '${_months[d.month - 1]} ${d.day}, ${d.year}, $h12:$mm $ampm';
}

class _RecentsPanel extends StatefulWidget {
  const _RecentsPanel({required this.entries});
  final List<ResolvedRecent> entries;

  @override
  State<_RecentsPanel> createState() => _RecentsPanelState();
}

class _RecentsPanelState extends State<_RecentsPanel> {
  int _page = 0;

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.of(context).size.width >= _wideThreshold;
    final panel = Container(
      width: wide ? MediaQuery.of(context).size.width * _sidebarFraction : double.infinity,
      decoration: BoxDecoration(
        color: Colors.white,
        border: wide ? const Border(right: BorderSide(color: Colors.black, width: 1)) : null,
      ),
      // One measure of the whole panel so the list and the footer's page count agree on the same
      // items-per-page (header + footer are each 48h).
      child: LayoutBuilder(builder: (context, c) {
        final per = math.max(1, ((c.maxHeight - 96) / _rowHeight).floor());
        final total = widget.entries.isEmpty ? 1 : (widget.entries.length / per).ceil();
        _page = _page.clamp(0, total - 1);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _header(),
            Expanded(child: _list(per)),
            _footer(total),
          ],
        );
      }),
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
            const Text('Recent',
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
    if (widget.entries.isEmpty) {
      return const Center(
        child: Text('No recent notebooks', style: TextStyle(fontSize: 15, color: _inkLight)),
      );
    }
    final start = _page * per;
    final slice = widget.entries.sublist(start, math.min(start + per, widget.entries.length));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [for (final e in slice) _row(e)],
    );
  }

  Widget _row(ResolvedRecent e) => GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => Navigator.pop(context, e),
        child: Container(
          height: _rowHeight,
          decoration: const BoxDecoration(
            color: Colors.white,
            border: Border(bottom: BorderSide(color: _inkLight, width: 1)),
          ),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                e.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 16, fontWeight: FontWeight.w700, color: Colors.black),
              ),
              const SizedBox(height: 3),
              Text(
                _formatWhen(e.timestamp),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 12, color: _inkLight),
              ),
              const SizedBox(height: 1),
              Text(
                e.folderPath,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 12, fontWeight: FontWeight.w500, color: _inkLight),
              ),
            ],
          ),
        ),
      );

  Widget _footer(int total) {
    void go(int p) => setState(() => _page = p.clamp(0, total - 1));
    return Container(
      height: 48,
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: Colors.black, width: 1)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: [
          _navBtn('≪', _page > 0, () => go(0)),
          _navBtn('‹', _page > 0, () => go(_page - 1)),
          Expanded(
            child: Center(
              child: Text('${_page + 1} / $total',
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
            ),
          ),
          _navBtn('›', _page < total - 1, () => go(_page + 1)),
          _navBtn('≫', _page < total - 1, () => go(total - 1)),
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
}
