import 'package:flutter/material.dart';

import '../core/markdown/markdown_parser.dart';
import '../core/markdown/markdown_render.dart';

/// Markdown source editor for a text object with a live preview.
///
/// Returns:
///  - `null` on Cancel (no change),
///  - `''` on Save with empty text (caller deletes the object / creates nothing),
///  - the markdown source on Save with content.
Future<String?> showTextDialog(BuildContext context, {String initial = ''}) => showDialog<String>(
      context: context,
      barrierColor: Colors.transparent, // e-ink: no page-dimming scrim (reads as a shadow)
      builder: (_) => _TextDialog(initial: initial),
    );

class _TextDialog extends StatefulWidget {
  const _TextDialog({required this.initial});
  final String initial;

  @override
  State<_TextDialog> createState() => _TextDialogState();
}

class _TextDialogState extends State<_TextDialog> {
  late final TextEditingController _controller = TextEditingController(text: widget.initial)
    ..addListener(() => setState(() {}));

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
        constraints: const BoxConstraints(maxWidth: 560),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Text (Markdown)',
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
                  maxLines: 6,
                  autofocus: true,
                  cursorColor: Colors.black,
                  style: const TextStyle(fontSize: 15, color: Colors.black, height: 1.3),
                  decoration: const InputDecoration.collapsed(
                    hintText: '# Heading\\n\\nSome **bold** text, a - list, > quote…',
                    hintStyle: TextStyle(color: Color(0xFF888888)),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              const Text('Preview', style: TextStyle(fontSize: 13, color: Color(0xFF888888))),
              const SizedBox(height: 4),
              Container(
                height: 160,
                width: double.infinity,
                decoration: BoxDecoration(
                  border: Border.all(color: Colors.black, width: 1),
                  borderRadius: BorderRadius.circular(4),
                ),
                padding: const EdgeInsets.all(8),
                child: _controller.text.trim().isEmpty
                    ? const Center(
                        child: Text('Nothing to preview',
                            style: TextStyle(color: Color(0xFF888888), fontSize: 13)))
                    : LayoutBuilder(
                        builder: (context, c) {
                          final blocks = MarkdownParser.parse(_controller.text);
                          final size =
                              MarkdownRender.measure(blocks, widthPx: c.maxWidth, basePx: 16, dpr: 1);
                          return SingleChildScrollView(
                            child: SizedBox(
                              width: c.maxWidth,
                              height: size.height,
                              child: CustomPaint(painter: _MdPreviewPainter(blocks, c.maxWidth)),
                            ),
                          );
                        },
                      ),
              ),
              const SizedBox(height: 20),
              Row(
                children: [
                  Expanded(child: _btn('Cancel', false, () => Navigator.pop(context))),
                  const SizedBox(width: 12),
                  Expanded(
                      child: _btn('Save', true, () => Navigator.pop(context, _controller.text.trim()))),
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

class _MdPreviewPainter extends CustomPainter {
  _MdPreviewPainter(this.blocks, this.width);
  final List<Block> blocks;
  final double width;

  @override
  void paint(Canvas canvas, Size size) {
    // Logical-space preview: draw at 16px base, dpr 1 (no canvas scale).
    MarkdownRender.layout(canvas, blocks, widthPx: width, basePx: 16, dpr: 1);
  }

  @override
  bool shouldRepaint(_MdPreviewPainter old) => old.blocks != blocks || old.width != width;
}
