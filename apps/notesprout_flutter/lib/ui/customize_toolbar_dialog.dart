import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../data/toolbar_config.dart';
import 'toolbar_registry.dart';

/// "Customize Toolbar" dialog — reorder buttons by dragging the grip, show/hide by tapping a row.
/// The pinned Close and Customize buttons can be reordered but never hidden. Returns the updated
/// [ToolbarConfig] on Save, or null on Cancel. (Placement / float / mini controls land in later
/// increments; this increment covers order + hidden.)
Future<ToolbarConfig?> showCustomizeToolbarDialog(BuildContext context, ToolbarConfig current) =>
    showDialog<ToolbarConfig>(
      context: context,
      barrierColor: Colors.transparent, // e-ink: no dimming scrim (reads as a shadow)
      builder: (_) => _CustomizeDialog(current: current),
    );

const _inkLight = Color(0xFF888888);

class _CustomizeDialog extends StatefulWidget {
  const _CustomizeDialog({required this.current});
  final ToolbarConfig current;

  @override
  State<_CustomizeDialog> createState() => _CustomizeDialogState();
}

const _miniMax = 5;

class _CustomizeDialogState extends State<_CustomizeDialog> {
  late List<String> _order;
  late Set<String> _hidden;
  late ToolbarPlacement _placement;
  late ToolbarAxis _floatAxis;
  late bool _miniEnabled;
  late Set<String> _miniSelection;

  bool get _isFloat => _placement == ToolbarPlacement.float;

  @override
  void initState() {
    super.initState();
    _order = _workingOrder(widget.current.order);
    _hidden = {
      for (final k in widget.current.hidden)
        if (ToolbarRegistry.spec(k)?.pinned == false) k,
    };
    _placement = widget.current.placement;
    _floatAxis = widget.current.floatAxis;
    _miniEnabled = widget.current.miniEnabled;
    _miniSelection = {
      for (final k in widget.current.miniSet)
        if (k != ToolbarRegistry.pinnedKey && k != ToolbarRegistry.settingsKey) k,
    };
  }

  void _toggleMini(String key) {
    setState(() {
      if (_miniSelection.contains(key)) {
        _miniSelection.remove(key);
      } else if (_miniSelection.length < _miniMax) {
        _miniSelection.add(key);
      }
    });
  }

  /// The full button list to display: the saved order, dropping unknown keys and appending any
  /// registry keys it doesn't yet mention (so newly-added buttons always show up).
  static List<String> _workingOrder(List<String> order) {
    final seen = <String>{};
    final out = <String>[
      for (final k in order)
        if (ToolbarRegistry.spec(k) != null && seen.add(k)) k,
    ];
    for (final k in ToolbarRegistry.defaultOrder) {
      if (seen.add(k)) out.add(k);
    }
    return out;
  }

  void _reorder(int oldIndex, int newIndex) {
    setState(() {
      if (newIndex > oldIndex) newIndex--;
      final k = _order.removeAt(oldIndex);
      _order.insert(newIndex, k);
    });
  }

  void _toggleHidden(String key) {
    if (ToolbarRegistry.spec(key)?.pinned == true) return;
    setState(() => _hidden.contains(key) ? _hidden.remove(key) : _hidden.add(key));
  }

  void _reset() {
    setState(() {
      _order = List.of(ToolbarRegistry.defaultOrder);
      _hidden = {};
      _placement = ToolbarPlacement.top;
      _floatAxis = ToolbarAxis.horizontal;
      _miniEnabled = false;
      _miniSelection = {...ToolbarRegistry.defaultMini};
    });
  }

  void _save() {
    // miniSet preserves the full-list order, filtered to the chosen membership (matches native).
    final miniSet = [for (final k in _order) if (_miniSelection.contains(k)) k];
    Navigator.pop(
      context,
      widget.current.copyWith(
        order: _order,
        hidden: _hidden.toSet(),
        placement: _placement,
        floatAxis: _floatAxis,
        miniEnabled: _miniEnabled,
        miniSet: miniSet,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final maxH = MediaQuery.of(context).size.height * 0.8;
    return Dialog(
      backgroundColor: Colors.white,
      elevation: 0,
      shadowColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        side: const BorderSide(color: Colors.black, width: 1),
        borderRadius: BorderRadius.circular(4),
      ),
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: 460, maxHeight: maxH),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Customize Toolbar',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.black)),
              const SizedBox(height: 4),
              const Text('Drag to reorder · tap a row to show or hide',
                  style: TextStyle(fontSize: 13, color: _inkLight)),
              const SizedBox(height: 12),
              const Text('Placement', style: TextStyle(fontSize: 13, color: _inkLight)),
              const SizedBox(height: 6),
              Row(
                children: [
                  for (final e in const {
                    ToolbarPlacement.top: 'Top',
                    ToolbarPlacement.bottom: 'Bottom',
                    ToolbarPlacement.left: 'Left',
                    ToolbarPlacement.right: 'Right',
                    ToolbarPlacement.float: 'Float',
                  }.entries) ...[
                    Expanded(
                      child: _btn(e.value, _placement == e.key,
                          () => setState(() => _placement = e.key)),
                    ),
                    if (e.key != ToolbarPlacement.float) const SizedBox(width: 6),
                  ],
                ],
              ),
              if (_isFloat) ...[
                const SizedBox(height: 12),
                Row(
                  children: [
                    const SizedBox(
                        width: 92,
                        child: Text('Float axis', style: TextStyle(fontSize: 13, color: _inkLight))),
                    Expanded(
                        child: _btn('Horizontal', _floatAxis == ToolbarAxis.horizontal,
                            () => setState(() => _floatAxis = ToolbarAxis.horizontal))),
                    const SizedBox(width: 8),
                    Expanded(
                        child: _btn('Vertical', _floatAxis == ToolbarAxis.vertical,
                            () => setState(() => _floatAxis = ToolbarAxis.vertical))),
                  ],
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    const SizedBox(
                        width: 92,
                        child: Text('Size', style: TextStyle(fontSize: 13, color: _inkLight))),
                    Expanded(
                        child: _btn('Full', !_miniEnabled,
                            () => setState(() => _miniEnabled = false))),
                    const SizedBox(width: 8),
                    Expanded(
                        child:
                            _btn('Mini', _miniEnabled, () => setState(() => _miniEnabled = true))),
                  ],
                ),
                if (_miniEnabled)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text('Pick up to $_miniMax buttons for the mini bar '
                        '(${_miniSelection.length}/$_miniMax) — Close and Customize are always shown',
                        style: const TextStyle(fontSize: 12, color: _inkLight)),
                  ),
              ],
              const SizedBox(height: 12),
              Flexible(
                child: ReorderableListView(
                  buildDefaultDragHandles: false,
                  onReorder: _reorder,
                  proxyDecorator: (child, index, animation) => Material(
                    color: Colors.white,
                    elevation: 0,
                    shadowColor: Colors.transparent,
                    surfaceTintColor: Colors.transparent,
                    child: child,
                  ),
                  children: [
                    for (var i = 0; i < _order.length; i++) _row(_order[i], i),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(child: _btn('Cancel', false, () => Navigator.pop(context))),
                  const SizedBox(width: 10),
                  Expanded(child: _btn('Reset', false, _reset)),
                  const SizedBox(width: 10),
                  Expanded(child: _btn('Save', true, _save)),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _row(String key, int index) {
    final spec = ToolbarRegistry.spec(key)!;
    final hidden = _hidden.contains(key);
    final fg = hidden ? _inkLight : Colors.black;
    return Container(
      key: ValueKey(key),
      margin: const EdgeInsets.only(bottom: 6),
      decoration: BoxDecoration(
        border: Border.all(color: hidden ? _inkLight : Colors.black, width: 1),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Row(
        children: [
          ReorderableDragStartListener(
            index: index,
            child: const Padding(
              padding: EdgeInsets.symmetric(horizontal: 10, vertical: 12),
              child: Icon(Icons.drag_handle, size: 22, color: _inkLight),
            ),
          ),
          Expanded(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => _toggleHidden(key),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: Row(
                  children: [
                    SvgPicture.asset(spec.icon,
                        width: 22, height: 22, colorFilter: ColorFilter.mode(fg, BlendMode.srcIn)),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(spec.label,
                          style: TextStyle(
                              fontSize: 15, fontWeight: FontWeight.w600, color: fg)),
                    ),
                    Text(
                      spec.pinned ? 'Always on' : (hidden ? 'Hidden' : 'Shown'),
                      style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: spec.pinned ? _inkLight : fg),
                    ),
                    const SizedBox(width: 12),
                  ],
                ),
              ),
            ),
          ),
          if (_isFloat && _miniEnabled && !spec.pinned) ...[
            _miniChip(key),
            const SizedBox(width: 10),
          ],
        ],
      ),
    );
  }

  Widget _miniChip(String key) {
    final on = _miniSelection.contains(key);
    return GestureDetector(
      onTap: () => _toggleMini(key),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: on ? Colors.black : Colors.white,
          border: Border.all(color: Colors.black, width: 1),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text('Mini',
            style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: on ? Colors.white : Colors.black)),
      ),
    );
  }

  Widget _btn(String label, bool selected, VoidCallback onTap) => GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
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
