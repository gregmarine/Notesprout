import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../domain/objects.dart';
import '../domain/stroke.dart';

/// A computed line ready to insert: its bounding box + serialized [LineObject].
class PlacedLine {
  const PlacedLine(this.box, this.line);
  final BoundingBox box;
  final LineObject line;
}

/// Opens the line-guide insertion dialog. Returns the computed lines to insert, or null on cancel.
/// [pageW]/[pageH] are the page's px dimensions; [dpr] converts the dp-valued fields to px.
Future<List<PlacedLine>?> showLineDialog(
        BuildContext context, double pageW, double pageH, double dpr) =>
    showDialog<List<PlacedLine>>(
      context: context,
      builder: (_) => _LineDialog(pageW: pageW, pageH: pageH, dpr: dpr),
    );

class _LineDialog extends StatefulWidget {
  const _LineDialog({required this.pageW, required this.pageH, required this.dpr});
  final double pageW;
  final double pageH;
  final double dpr;

  @override
  State<_LineDialog> createState() => _LineDialogState();
}

class _LineDialogState extends State<_LineDialog> {
  LineStyle _style = LineStyle.solid;
  LineOrientation _orientation = LineOrientation.horizontal;
  int _count = 20;
  double _strokeWidthDp = 1;
  double _marginDp = 24;

  List<PlacedLine> _build() {
    final marginPx = _marginDp * widget.dpr;
    final swPx = _strokeWidthDp * widget.dpr;
    final band = math.max(swPx, 8 * widget.dpr); // thin hit-band; the visible line sits at its center
    final out = <PlacedLine>[];
    final horizontal = _orientation == LineOrientation.horizontal;
    final span = horizontal ? widget.pageH : widget.pageW;
    final cross = horizontal ? widget.pageW : widget.pageH;
    final usable = span - 2 * marginPx;
    if (usable <= 0 || _count < 1) return out;

    for (var i = 0; i < _count; i++) {
      final pos =
          _count == 1 ? marginPx + usable / 2 : marginPx + usable * i / (_count - 1);
      final dotDp = _count > 1 ? (usable / (_count - 1)) / widget.dpr : 0.0;
      final line = LineObject(
        style: _style,
        orientation: _orientation,
        strokeWidthDp: _strokeWidthDp,
        dotSpacingDp: _style == LineStyle.dotted ? dotDp : 0.0,
      );
      final box = horizontal
          ? BoundingBox(marginPx, pos - band / 2, cross - 2 * marginPx, band)
          : BoundingBox(pos - band / 2, marginPx, band, cross - 2 * marginPx);
      out.add(PlacedLine(box, line));
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.white,
      shape: RoundedRectangleBorder(
        side: const BorderSide(color: Colors.black, width: 1),
        borderRadius: BorderRadius.circular(4),
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Insert lines',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.black)),
              const SizedBox(height: 16),
              _label('Style'),
              _segmented<LineStyle>(
                {LineStyle.solid: 'Solid', LineStyle.dashed: 'Dashed', LineStyle.dotted: 'Dotted'},
                _style,
                (v) => setState(() => _style = v),
              ),
              const SizedBox(height: 12),
              _label('Orientation'),
              _segmented<LineOrientation>(
                {LineOrientation.horizontal: 'Horizontal', LineOrientation.vertical: 'Vertical'},
                _orientation,
                (v) => setState(() => _orientation = v),
              ),
              const SizedBox(height: 12),
              _stepper('Count', _count.toDouble(), 1, 60, 1,
                  (v) => setState(() => _count = v.round()), (v) => v.round().toString()),
              _stepper('Stroke width (dp)', _strokeWidthDp, 0.5, 4, 0.5,
                  (v) => setState(() => _strokeWidthDp = v), (v) => v.toStringAsFixed(1)),
              _stepper('Margin (dp)', _marginDp, 0, 96, 4,
                  (v) => setState(() => _marginDp = v), (v) => v.round().toString()),
              const SizedBox(height: 20),
              Row(
                children: [
                  Expanded(child: _btn('Cancel', false, () => Navigator.pop(context))),
                  const SizedBox(width: 12),
                  Expanded(child: _btn('Insert', true, () => Navigator.pop(context, _build()))),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _label(String s) => Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Text(s, style: const TextStyle(fontSize: 13, color: Colors.black)),
      );

  Widget _segmented<T>(Map<T, String> options, T selected, ValueChanged<T> onSelect) {
    return Row(
      children: [
        for (final e in options.entries) ...[
          Expanded(child: _btn(e.value, e.key == selected, () => onSelect(e.key))),
          if (e.key != options.keys.last) const SizedBox(width: 8),
        ],
      ],
    );
  }

  Widget _stepper(String label, double value, double min, double max, double step,
      ValueChanged<double> onChange, String Function(double) fmt) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          Expanded(child: Text(label, style: const TextStyle(fontSize: 13, color: Colors.black))),
          _btn('−', false, () => onChange((value - step).clamp(min, max))),
          Container(
            width: 56,
            alignment: Alignment.center,
            child: Text(fmt(value),
                style: const TextStyle(
                    fontSize: 15, fontWeight: FontWeight.w600, color: Colors.black)),
          ),
          _btn('+', false, () => onChange((value + step).clamp(min, max))),
        ],
      ),
    );
  }

  Widget _btn(String label, bool selected, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
        decoration: BoxDecoration(
          color: selected ? Colors.black : Colors.white,
          border: Border.all(color: Colors.black, width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        alignment: Alignment.center,
        child: Text(label,
            style: TextStyle(
                color: selected ? Colors.white : Colors.black,
                fontSize: 14,
                fontWeight: FontWeight.w600)),
      ),
    );
  }
}
