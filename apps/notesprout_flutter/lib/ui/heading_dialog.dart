import 'package:flutter/material.dart';

/// Simple single-line editor for a heading's text (port of native's `showHeadingTextEditDialog`).
/// The field shows only the words — the `#` prefix is stripped by the caller and re-applied on save
/// (level is controlled by the H1/H2/H3 popover). Returns the trimmed text, or null on Cancel.
Future<String?> showHeadingDialog(BuildContext context, {String initial = ''}) => showDialog<String>(
      context: context,
      barrierColor: Colors.transparent, // e-ink: no page-dimming scrim (reads as a shadow)
      builder: (_) => _HeadingDialog(initial: initial),
    );

class _HeadingDialog extends StatefulWidget {
  const _HeadingDialog({required this.initial});
  final String initial;

  @override
  State<_HeadingDialog> createState() => _HeadingDialogState();
}

class _HeadingDialogState extends State<_HeadingDialog> {
  late final TextEditingController _controller = TextEditingController(text: widget.initial)
    ..selection = TextSelection(baseOffset: 0, extentOffset: widget.initial.length);

  void _save() => Navigator.pop(context, _controller.text.trim());

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: Colors.white,
      elevation: 0,
      shadowColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      insetPadding: const EdgeInsets.all(24),
      shape: RoundedRectangleBorder(
        side: const BorderSide(color: Colors.black, width: 1),
        borderRadius: BorderRadius.circular(4),
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Edit Heading',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.black)),
              const SizedBox(height: 12),
              Container(
                decoration: BoxDecoration(
                  border: Border.all(color: Colors.black, width: 1),
                  borderRadius: BorderRadius.circular(4),
                ),
                padding: const EdgeInsets.all(8),
                child: TextField(
                  controller: _controller,
                  autofocus: true,
                  cursorColor: Colors.black,
                  textInputAction: TextInputAction.done,
                  onSubmitted: (_) => _save(),
                  style: const TextStyle(fontSize: 16, color: Colors.black, height: 1.3),
                  decoration: const InputDecoration.collapsed(
                    hintText: 'Heading text',
                    hintStyle: TextStyle(color: Color(0xFF888888)),
                  ),
                ),
              ),
              const SizedBox(height: 20),
              Row(
                children: [
                  Expanded(child: _btn('Cancel', false, () => Navigator.pop(context))),
                  const SizedBox(width: 12),
                  Expanded(child: _btn('Save', true, _save)),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _btn(String label, bool selected, VoidCallback onTap) => GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            color: selected ? Colors.black : Colors.white,
            border: Border.all(color: Colors.black, width: 1),
            borderRadius: BorderRadius.circular(4),
          ),
          alignment: Alignment.center,
          child: Text(label,
              style: TextStyle(
                  color: selected ? Colors.white : Colors.black,
                  fontSize: 15,
                  fontWeight: FontWeight.w600)),
        ),
      );
}
