import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// One toolbar entry. Three shapes:
///  - **icon button** — pass [icon] (an SVG asset path); rendered borderless white, gaining a black
///    border when [selected] (native `bg_toolbar_button`). [label] is its overflow-menu text.
///  - **text button** — no [icon]; rendered as a bordered pill, inverted (black fill) when [selected].
///  - **divider** — `TbButton.divider()`; a 1dp group separator (auto-dropped at an overflow edge).
class TbButton {
  const TbButton(this.label, {this.onTap, this.selected = false, this.enabled = true, this.icon})
      : isDivider = false;
  const TbButton.divider()
      : label = '',
        onTap = null,
        selected = false,
        enabled = true,
        icon = null,
        isDivider = true;

  final String label;
  final VoidCallback? onTap;
  final bool selected;
  final bool enabled;
  final String? icon; // SVG asset path → icon button; null → text button
  final bool isDivider;
}

const _kLabelStyle = TextStyle(fontSize: 15, fontWeight: FontWeight.w600);
const _kHPad = 14.0;
const _kOverflowLabel = '⋯';
const _inkLight = Color(0xFF888888);
const _kIconBtnW = 40.0;
const _kIconBtnH = 40.0;
const _kIconSize = 24.0;
const _kDividerMargin = 4.0;
const _kDividerH = 26.0;

double _itemWidth(TbButton it) {
  if (it.isDivider) return 1 + _kDividerMargin * 2;
  if (it.icon != null) return _kIconBtnW;
  final tp = TextPainter(
    text: TextSpan(text: it.label, style: _kLabelStyle),
    textDirection: TextDirection.ltr,
  )..layout();
  return tp.width + _kHPad * 2 + 2; // text + horizontal padding + 1dp border each side
}

/// A single-row toolbar that never scrolls: it lays out as many [items] as fit the available width
/// and collapses the rest behind a trailing "⋯" button that opens them in a popup menu (the native
/// toolbar overflow pattern). Group dividers at the visible/hidden boundary are dropped so the row
/// never ends on a dangling separator. Give it a bounded width (an `Expanded`/`ConstrainedBox`).
class OverflowToolbar extends StatelessWidget {
  const OverflowToolbar(this.items, {super.key, this.spacing = 8, this.height = 46});

  final List<TbButton> items;
  final double spacing;
  final double height;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final maxW = constraints.maxWidth;
      final widths = [for (final it in items) _itemWidth(it)];
      final total =
          widths.fold(0.0, (a, b) => a + b) + (items.isEmpty ? 0 : spacing * (items.length - 1));

      List<TbButton> visible, hidden;
      if (items.isEmpty || total <= maxW) {
        visible = items;
        hidden = const [];
      } else {
        final reserve = _itemWidth(const TbButton(_kOverflowLabel)) + spacing;
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
        // Never end the visible run on a divider (native cut-point rule).
        while (k > 0 && items[k - 1].isDivider) {
          k--;
        }
        visible = items.sublist(0, k);
        hidden = items.sublist(k);
      }

      final children = <Widget>[];
      var placed = 0;
      for (var i = 0; i < visible.length; i++) {
        final it = visible[i];
        if (it.isDivider) {
          children.add(_dividerWidget());
          continue;
        }
        if (placed > 0) children.add(SizedBox(width: spacing));
        children.add(_button(it));
        placed++;
      }
      if (hidden.any((it) => !it.isDivider)) {
        if (children.isNotEmpty) children.add(SizedBox(width: spacing));
        children.add(Builder(
          builder: (btnCtx) => _iconlessOverflowButton(
            () => showOverflowMenu(btnCtx, [for (final it in hidden) if (!it.isDivider) it]),
          ),
        ));
      }
      return SizedBox(
        height: height,
        child: Row(mainAxisSize: MainAxisSize.min, children: children),
      );
    });
  }

  Widget _button(TbButton it) => it.icon != null ? _iconButton(it) : _textButton(it);
}

/// A full-width toolbar whose overflow stacks into a **secondary toolbar row** directly below the
/// main bar (wrapping to more rows on narrow screens) instead of a dropdown menu — the native
/// stacked-overflow behaviour. Pinned [leading] (e.g. Close) and [trailing] (e.g. the Customize gear
/// + [trailingLabel] page indicator) stay on the main row; the middle [items] (with dividers) fill
/// it and any that don't fit move to the secondary row behind a "⋯" toggle. Reports its total
/// logical height via [onHeight] so the caller can exclude the whole stack from the pen region.
class StackedToolbar extends StatefulWidget {
  const StackedToolbar({
    super.key,
    this.leading = const [],
    required this.items,
    this.trailing = const [],
    this.trailingLabel,
    this.expanded = false,
    this.onExpandedChanged,
    this.axis = Axis.horizontal,
    this.mainBorder = const Border(bottom: BorderSide(color: Colors.black, width: 1)),
    this.secondaryBefore = false,
    this.spread = true,
    this.rowHeight = 56,
    this.spacing = 3,
    this.padding = 6,
    this.onHeight,
  });

  final List<TbButton> leading;
  final List<TbButton> items;
  final List<TbButton> trailing;
  final String? trailingLabel;

  /// Whether the secondary overflow line is open. Controlled by the host so it can also be dismissed
  /// externally (e.g. on a page tap / stroke).
  final bool expanded;
  final ValueChanged<bool>? onExpandedChanged;

  /// Main axis of the bar: horizontal (TOP/BOTTOM) or vertical (LEFT/RIGHT).
  final Axis axis;

  /// Border on the main line's inner edge (bottom for TOP, top for BOTTOM, right for LEFT, …).
  final Border mainBorder;

  /// Whether the secondary overflow line sits BEFORE the main line in layout order (true for BOTTOM
  /// and RIGHT, where the inner/page side is toward the start).
  final bool secondaryBefore;

  /// Anchored bars (true) fill their track and push the trailing cluster to the far edge; a floating
  /// bar (false) hugs its content — no trailing spacer.
  final bool spread;

  final double rowHeight; // cross-axis thickness of the main line
  final double spacing;
  final double padding;

  /// Reports the bar's total cross-axis extent (thickness + open secondary line) so the host can
  /// exclude the whole stack from the pen region.
  final ValueChanged<double>? onHeight;

  @override
  State<StackedToolbar> createState() => _StackedToolbarState();
}

const _kDividerBlockW = 1 + _kDividerMargin * 2;
const _kLabelStyleSmall = TextStyle(fontSize: 14, fontWeight: FontWeight.w600);

class _StackedToolbarState extends State<StackedToolbar> {
  double? _lastReported;

  bool get _vert => widget.axis == Axis.vertical;

  /// A gap along the bar's main axis.
  Widget _gap(double s) => SizedBox(width: _vert ? 0 : s, height: _vert ? s : 0);

  double _sumRow(List<TbButton> row) {
    if (row.isEmpty) return 0;
    var w = 0.0;
    for (var i = 0; i < row.length; i++) {
      w += _itemWidth(row[i]);
      if (i > 0) w += widget.spacing;
    }
    return w;
  }

  double _labelExtent(String? s) {
    if (s == null) return 0;
    final tp = TextPainter(
      text: TextSpan(text: s, style: _kLabelStyleSmall),
      textDirection: TextDirection.ltr,
    )..layout();
    return _vert ? tp.height : tp.width;
  }

  /// Cross-axis extent (thickness) added by the open secondary line, given the main-axis space
  /// available to the wrap.
  double _secondaryExtent(double mainAvail, int hiddenCount) {
    final avail = mainAvail - widget.padding * 2;
    final per = math.max(1, ((avail + widget.spacing) / (_kIconBtnW + widget.spacing)).floor());
    final lines = (hiddenCount / per).ceil();
    return lines * _kIconBtnH + (lines - 1) * 6 + 12; // runSpacing 6 + 6px padding each side
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, c) {
      final sp = widget.spacing;
      final mainMax = _vert ? c.maxHeight : c.maxWidth; // the bounded dimension for our axis

      final leadW = _sumRow(widget.leading);
      final trailW = _sumRow(widget.trailing);
      final labelExt = _labelExtent(widget.trailingLabel);
      final labelGap = widget.trailingLabel != null ? 6.0 : 0.0;
      final leadDiv = widget.leading.isNotEmpty ? _kDividerBlockW : 0.0;
      final trailDiv = widget.trailing.isNotEmpty ? _kDividerBlockW : 0.0;

      // No inter-item spacing is rendered around the pinned dividers (they carry their own margins),
      // so `fixed` must not add it — over-reserving would push a button into overflow early.
      final fixed =
          widget.padding * 2 + leadW + leadDiv + trailDiv + trailW + labelGap + labelExt;
      final availMiddle = math.max(0.0, mainMax - fixed);

      final middle = widget.items;
      final widths = [for (final it in middle) _itemWidth(it)];
      double prefixWidth(int count) {
        var w = 0.0;
        for (var i = 0; i < count; i++) {
          w += widths[i];
          if (i > 0) w += sp;
        }
        return w;
      }

      int k;
      bool overflow;
      if (prefixWidth(middle.length) <= availMiddle) {
        k = middle.length;
        overflow = false;
      } else {
        overflow = true;
        final budget = availMiddle - (_kIconBtnW + sp); // reserve the ⋯ toggle
        var used = 0.0;
        k = 0;
        for (var i = 0; i < middle.length; i++) {
          final add = (k == 0 ? 0.0 : sp) + widths[i];
          if (used + add <= budget) {
            used += add;
            k++;
          } else {
            break;
          }
        }
        while (k > 0 && middle[k - 1].isDivider) {
          k--;
        }
      }
      final visible = middle.sublist(0, k);
      final hidden = [for (final it in middle.sublist(k)) if (!it.isDivider) it];
      final showSecondary = widget.expanded && overflow && hidden.isNotEmpty;

      // ── Main line (Flex along the axis) ──
      final line = <Widget>[];
      var prevButton = false;
      void pushButton(Widget w) {
        if (prevButton) line.add(_gap(sp));
        line.add(w);
        prevButton = true;
      }

      void pushDivider() {
        line.add(_dividerWidget(axis: widget.axis));
        prevButton = false;
      }

      for (final it in widget.leading) {
        pushButton(it.icon != null ? _iconButton(it) : _textButton(it));
      }
      if (widget.leading.isNotEmpty && (visible.isNotEmpty || overflow)) pushDivider();
      for (final it in visible) {
        if (it.isDivider) {
          pushDivider();
        } else {
          pushButton(it.icon != null ? _iconButton(it) : _textButton(it));
        }
      }
      if (overflow) {
        pushButton(_overflowToggle());
      }
      // No trailing Spacer: content left-packs and any slack falls at the far edge (native's
      // left-packed layout), instead of a button-sized gap between the tools and the pinned cluster.
      prevButton = false;
      if (widget.trailing.isNotEmpty) pushDivider();
      for (final it in widget.trailing) {
        pushButton(it.icon != null ? _iconButton(it) : _textButton(it));
      }
      if (widget.trailingLabel != null) {
        // Pin the page indicator to the far edge on anchored bars (the trailing space is its home,
        // with room to grow past 99 pages); keep it inline on a content-hugging float bar.
        if (widget.spread) {
          line.add(const Spacer());
        } else {
          line.add(_gap(labelGap));
        }
        line.add(Text(widget.trailingLabel!, style: _kLabelStyleSmall));
        if (widget.spread) line.add(_gap(2));
      }

      final totalExtent =
          widget.rowHeight + (showSecondary ? _secondaryExtent(mainMax, hidden.length) : 0);
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        if (_lastReported != totalExtent) {
          _lastReported = totalExtent;
          widget.onHeight?.call(totalExtent);
        }
      });

      final mainDeco = BoxDecoration(color: Colors.white, border: widget.mainBorder);
      final mainLine = Container(
        width: _vert ? widget.rowHeight : null,
        height: _vert ? null : widget.rowHeight,
        decoration: mainDeco,
        padding: _vert
            ? EdgeInsets.symmetric(vertical: widget.padding)
            : EdgeInsets.symmetric(horizontal: widget.padding),
        child: Flex(
          direction: widget.axis,
          mainAxisSize: widget.spread ? MainAxisSize.max : MainAxisSize.min,
          children: line,
        ),
      );

      Widget? secondary;
      if (showSecondary) {
        secondary = Container(
          decoration: mainDeco,
          padding: _vert
              ? EdgeInsets.symmetric(horizontal: 6, vertical: widget.padding)
              : EdgeInsets.symmetric(vertical: 6, horizontal: widget.padding),
          child: Wrap(
            direction: widget.axis,
            spacing: sp,
            runSpacing: 6,
            children: [for (final it in hidden) _iconButton(it)],
          ),
        );
      }

      final stack = <Widget>[
        if (secondary != null && widget.secondaryBefore) secondary,
        mainLine,
        if (secondary != null && !widget.secondaryBefore) secondary,
      ];
      // Anchored bars stretch to fill their track; a floating bar hugs its content (start-aligned),
      // otherwise CrossAxisAlignment.stretch would force it to the full available width.
      final cross = widget.spread ? CrossAxisAlignment.stretch : CrossAxisAlignment.start;
      return _vert
          ? Row(mainAxisSize: MainAxisSize.min, crossAxisAlignment: cross, children: stack)
          : Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: cross, children: stack);
    });
  }

  Widget _overflowToggle() => GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => widget.onExpandedChanged?.call(!widget.expanded),
        child: Container(
          width: _kIconBtnW,
          height: _kIconBtnH,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: Colors.white,
            border:
                Border.all(color: widget.expanded ? Colors.black : Colors.transparent, width: 1.5),
            borderRadius: BorderRadius.circular(4),
          ),
          child: const Text(_kOverflowLabel,
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, height: 1.0)),
        ),
      );
}

/// A standalone icon toolbar button, same styling as those inside [OverflowToolbar] — for pinned
/// buttons (Close, Customize gear) rendered outside the overflow row.
Widget tbIconButton(TbButton it) => _iconButton(it);

/// A standalone group divider, for use between pinned buttons and the overflow row.
Widget tbDivider() => _dividerWidget();

/// A group divider oriented for the bar's [axis]: a vertical rule (1×26) in a horizontal bar, a
/// horizontal rule (26×1) in a vertical bar, with margins along the main axis.
Widget _dividerWidget({Axis axis = Axis.horizontal}) => axis == Axis.vertical
    ? Container(
        width: _kDividerH,
        height: 1,
        margin: const EdgeInsets.symmetric(vertical: _kDividerMargin),
        color: Colors.black,
      )
    : Container(
        width: 1,
        height: _kDividerH,
        margin: const EdgeInsets.symmetric(horizontal: _kDividerMargin),
        color: Colors.black,
      );

/// Icon button — native `bg_toolbar_button`: white fill, no border by default, 1.5dp black border
/// when selected (persistent active-tool state). Disabled dims the glyph to inkLight.
Widget _iconButton(TbButton it) {
  final fg = it.enabled ? Colors.black : _inkLight;
  return GestureDetector(
    onTap: it.enabled ? it.onTap : null,
    behavior: HitTestBehavior.opaque,
    child: Container(
      width: _kIconBtnW,
      height: _kIconBtnH,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: Colors.white,
        border: Border.all(color: it.selected ? Colors.black : Colors.transparent, width: 1.5),
        borderRadius: BorderRadius.circular(4),
      ),
      child: SvgPicture.asset(
        it.icon!,
        width: _kIconSize,
        height: _kIconSize,
        colorFilter: ColorFilter.mode(fg, BlendMode.srcIn),
      ),
    ),
  );
}

Widget _textButton(TbButton it) {
  final border = it.selected ? Colors.black : (it.enabled ? Colors.black : _inkLight);
  final fg = it.selected ? Colors.white : (it.enabled ? Colors.black : _inkLight);
  return GestureDetector(
    onTap: it.enabled ? it.onTap : null,
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: _kHPad, vertical: 8),
      decoration: BoxDecoration(
        color: it.selected ? Colors.black : Colors.white,
        border: Border.all(color: border, width: 1),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(it.label, style: _kLabelStyle.copyWith(color: fg)),
    ),
  );
}

Widget _iconlessOverflowButton(VoidCallback onTap) => GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        width: _kIconBtnW,
        height: _kIconBtnH,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: Colors.white,
          border: Border.all(color: Colors.transparent, width: 1.5),
          borderRadius: BorderRadius.circular(4),
        ),
        child: const Text(_kOverflowLabel,
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, height: 1.0)),
      ),
    );

/// Show [items] as a flat, shadow-free e-ink popup menu anchored under [anchorCtx] (the "⋯" button,
/// or a breadcrumb "…"). Icon entries render icon + label. Dismisses on outside tap or selection.
void showOverflowMenu(BuildContext anchorCtx, List<TbButton> items) {
  final overlay = Overlay.of(anchorCtx);
  final rb = anchorCtx.findRenderObject() as RenderBox;
  final overlayBox = overlay.context.findRenderObject() as RenderBox;
  final topLeft = rb.localToGlobal(Offset.zero, ancestor: overlayBox);
  final screen = overlayBox.size;
  const menuW = 240.0;
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
  final fg = it.enabled ? Colors.black : _inkLight;
  return GestureDetector(
    onTap: it.enabled
        ? () {
            close();
            it.onTap?.call();
          }
        : null,
    child: Container(
      width: double.infinity,
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      child: Row(
        children: [
          if (it.icon != null) ...[
            SvgPicture.asset(it.icon!,
                width: 22, height: 22, colorFilter: ColorFilter.mode(fg, BlendMode.srcIn)),
            const SizedBox(width: 12),
          ],
          Expanded(child: Text(it.label, style: _kLabelStyle.copyWith(color: fg))),
          if (it.selected)
            const Text('✓', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
        ],
      ),
    ),
  );
}
