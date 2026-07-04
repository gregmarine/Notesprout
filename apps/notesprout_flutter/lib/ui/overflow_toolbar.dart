import 'package:flutter/material.dart';

/// One toolbar button spec. Rendered as a bordered e-ink button (filled inkBlack when [selected]);
/// dimmed to inkLight when [enabled] is false. The same spec is used both inline and inside the
/// overflow menu.
class TbButton {
  const TbButton(this.label, {required this.onTap, this.selected = false, this.enabled = true});
  final String label;
  final VoidCallback onTap;
  final bool selected;
  final bool enabled;
}

const _kLabelStyle = TextStyle(fontSize: 15, fontWeight: FontWeight.w600);
const _kHPad = 14.0;
const _kOverflowLabel = '⋯';
const _inkLight = Color(0xFF888888);

double _buttonWidth(String label) {
  final tp = TextPainter(
    text: TextSpan(text: label, style: _kLabelStyle),
    textDirection: TextDirection.ltr,
  )..layout();
  return tp.width + _kHPad * 2 + 2; // text + horizontal padding + 1dp border each side
}

/// A single-row toolbar that never scrolls: it lays out as many [items] as fit the available width
/// and collapses the rest behind a trailing "⋯" button that opens them in a popup menu (the native
/// toolbar overflow pattern). Give it a bounded width (an `Expanded`, or a `ConstrainedBox`).
class OverflowToolbar extends StatelessWidget {
  const OverflowToolbar(this.items, {super.key, this.spacing = 8, this.height = 46});

  final List<TbButton> items;
  final double spacing;
  final double height;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final maxW = constraints.maxWidth;
      final widths = [for (final it in items) _buttonWidth(it.label)];
      final total =
          widths.fold(0.0, (a, b) => a + b) + (items.isEmpty ? 0 : spacing * (items.length - 1));

      List<TbButton> visible, hidden;
      if (items.isEmpty || total <= maxW) {
        visible = items;
        hidden = const [];
      } else {
        final reserve = _buttonWidth(_kOverflowLabel) + spacing;
        var used = 0.0, k = 0;
        for (var i = 0; i < items.length; i++) {
          final add = (k == 0 ? 0.0 : spacing) + widths[i];
          if (used + add + reserve <= maxW) {
            used += add;
            k++;
          } else {
            break;
          }
        }
        visible = items.sublist(0, k);
        hidden = items.sublist(k);
      }

      final children = <Widget>[];
      for (var i = 0; i < visible.length; i++) {
        if (i > 0) children.add(SizedBox(width: spacing));
        children.add(_button(visible[i]));
      }
      if (hidden.isNotEmpty) {
        if (children.isNotEmpty) children.add(SizedBox(width: spacing));
        children.add(Builder(
          builder: (btnCtx) => _rawButton(
            _kOverflowLabel,
            selected: false,
            enabled: true,
            onTap: () => showOverflowMenu(btnCtx, hidden),
          ),
        ));
      }
      return SizedBox(
        height: height,
        child: Row(mainAxisSize: MainAxisSize.min, children: children),
      );
    });
  }

  Widget _button(TbButton it) =>
      _rawButton(it.label, selected: it.selected, enabled: it.enabled, onTap: it.onTap);
}

Widget _rawButton(String label,
    {required bool selected, required bool enabled, required VoidCallback onTap}) {
  final border = selected ? Colors.black : (enabled ? Colors.black : _inkLight);
  final fg = selected ? Colors.white : (enabled ? Colors.black : _inkLight);
  return GestureDetector(
    onTap: enabled ? onTap : null,
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: _kHPad, vertical: 8),
      decoration: BoxDecoration(
        color: selected ? Colors.black : Colors.white,
        border: Border.all(color: border, width: 1),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(label, style: _kLabelStyle.copyWith(color: fg)),
    ),
  );
}

/// Show [items] as a flat, borderless-shadow e-ink popup menu anchored under [anchorCtx] (the "⋯"
/// button, or a breadcrumb "…"). Dismisses on outside tap or on selecting an item.
void showOverflowMenu(BuildContext anchorCtx, List<TbButton> items) {
  final overlay = Overlay.of(anchorCtx);
  final rb = anchorCtx.findRenderObject() as RenderBox;
  final overlayBox = overlay.context.findRenderObject() as RenderBox;
  final topLeft = rb.localToGlobal(Offset.zero, ancestor: overlayBox);
  final screen = overlayBox.size;
  const menuW = 220.0;
  var left = topLeft.dx + rb.size.width - menuW; // right-align the menu under the button
  left = left.clamp(4.0, (screen.width - menuW - 4).clamp(4.0, double.infinity));
  final top = topLeft.dy + rb.size.height + 4;

  late OverlayEntry entry;
  entry = OverlayEntry(
    builder: (_) => Stack(children: [
      Positioned.fill(
        child: GestureDetector(behavior: HitTestBehavior.opaque, onTap: () => entry.remove()),
      ),
      Positioned(
        left: left,
        top: top,
        width: menuW,
        child: Material(
          color: Colors.white,
          elevation: 0,
          surfaceTintColor: Colors.transparent,
          shape: RoundedRectangleBorder(
            side: const BorderSide(color: Colors.black, width: 1),
            borderRadius: BorderRadius.circular(4),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var i = 0; i < items.length; i++) ...[
                if (i > 0) const Divider(height: 1, thickness: 1, color: Colors.black),
                _menuRow(items[i], () => entry.remove()),
              ],
            ],
          ),
        ),
      ),
    ]),
  );
  overlay.insert(entry);
}

Widget _menuRow(TbButton it, VoidCallback close) {
  final fg = it.selected ? Colors.white : (it.enabled ? Colors.black : _inkLight);
  return GestureDetector(
    onTap: it.enabled
        ? () {
            close();
            it.onTap();
          }
        : null,
    child: Container(
      width: double.infinity,
      color: it.selected ? Colors.black : Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      child: Text(it.label, style: _kLabelStyle.copyWith(color: fg)),
    ),
  );
}
