/// Hand-rolled Markdown parser — a faithful Dart port of the native `core/markdown/MarkdownParser.kt`
/// so text objects parse identically on both apps. Supported subset: headings (h1–h6), bold, italic,
/// strikethrough, links (underlined display text, url discarded), unordered/ordered/task lists with
/// nesting + auto-renumbering, blockquotes, horizontal rules. Out of scope: inline code, code blocks,
/// tables, images, raw HTML.
library;

// ── Block-level elements ─────────────────────────────────────────────────────

sealed class Block {
  const Block();
}

class Heading extends Block {
  const Heading(this.level, this.inlines);
  final int level;
  final List<Inline> inlines;
}

class Paragraph extends Block {
  const Paragraph(this.inlines);
  final List<Inline> inlines;
}

/// Covers unordered bullets, ordered numbers, and task-list checkboxes.
class ListItemBlock extends Block {
  const ListItemBlock({
    required this.ordered,
    required this.depth,
    required this.displayNumber,
    required this.isTask,
    required this.checked,
    required this.inlines,
  });
  final bool ordered;
  final int depth;

  /// Computed sequential number (1-based) for ordered items; 0 for unordered.
  final int displayNumber;
  final bool isTask;
  final bool checked;
  final List<Inline> inlines;
}

class Blockquote extends Block {
  const Blockquote(this.inlines);
  final List<Inline> inlines;
}

class HorizontalRule extends Block {
  const HorizontalRule();
}

// ── Inline elements ──────────────────────────────────────────────────────────

sealed class Inline {
  const Inline();
}

class InlineText extends Inline {
  const InlineText(this.text);
  final String text;
}

class Bold extends Inline {
  const Bold(this.children);
  final List<Inline> children;
}

class Italic extends Inline {
  const Italic(this.children);
  final List<Inline> children;
}

class Strikethrough extends Inline {
  const Strikethrough(this.children);
  final List<Inline> children;
}

/// Rendered as underlined display text; [url] is discarded (not clickable).
class Link extends Inline {
  const Link(this.displayText, this.url);
  final String displayText;
  final String url;
}

// ── Parser ───────────────────────────────────────────────────────────────────

class MarkdownParser {
  MarkdownParser._();

  static final _headingRegex = RegExp(r'^(#{1,6})\s+(.+)');
  static final _blockquoteRegex = RegExp(r'^>\s?(.*)');
  static final _taskItemRegex = RegExp(r'^[-*+]\s+\[([xX ])\]\s+(.*)');
  static final _unorderedItemRegex = RegExp(r'^[-*+]\s+(.+)');
  static final _orderedItemRegex = RegExp(r'^(\d+)\.\s+(.+)');

  static List<Block> parse(String markdown) {
    final lines = markdown.split('\n');
    final blocks = <Block>[];
    // ordered list counter per nesting depth; cleared by any non-ordered-list block
    final orderedCounters = <int, int>{};
    var i = 0;

    while (i < lines.length) {
      final line = lines[i];
      final trimmedStart = line.trimLeft();
      final indent = line.length - trimmedStart.length;
      final depth = indent ~/ 2;
      final trimmed = trimmedStart.trimRight();

      if (trimmed.isEmpty) {
        orderedCounters.clear();
        i++;
        continue;
      }

      // Heading
      final headingMatch = _headingRegex.matchAsPrefix(trimmed);
      if (headingMatch != null) {
        final level = headingMatch.group(1)!.length;
        final text = headingMatch.group(2)!.trim();
        blocks.add(Heading(level, parseInlines(text)));
        orderedCounters.clear();
        i++;
        continue;
      }

      // Horizontal rule
      if (_isHorizontalRule(trimmed)) {
        blocks.add(const HorizontalRule());
        orderedCounters.clear();
        i++;
        continue;
      }

      // Blockquote
      final bqMatch = _blockquoteRegex.matchAsPrefix(trimmed);
      if (bqMatch != null) {
        final bqLines = <String>[bqMatch.group(1)!];
        while (i + 1 < lines.length) {
          final next = lines[i + 1].trimLeft().trimRight();
          final nextMatch = _blockquoteRegex.matchAsPrefix(next);
          if (nextMatch != null) {
            bqLines.add(nextMatch.group(1)!);
            i++;
          } else {
            break;
          }
        }
        blocks.add(Blockquote(parseInlines(bqLines.join(' '))));
        orderedCounters.clear();
        i++;
        continue;
      }

      // Task list item (must precede unordered check)
      final taskMatch = _taskItemRegex.matchAsPrefix(trimmed);
      if (taskMatch != null) {
        final checked = taskMatch.group(1)!.toLowerCase() == 'x';
        final text = taskMatch.group(2)!;
        blocks.add(ListItemBlock(
          ordered: false, depth: depth, displayNumber: 0,
          isTask: true, checked: checked, inlines: parseInlines(text),
        ));
        orderedCounters.remove(depth);
        i++;
        continue;
      }

      // Unordered list item
      final ulMatch = _unorderedItemRegex.matchAsPrefix(trimmed);
      if (ulMatch != null) {
        final text = ulMatch.group(1)!;
        blocks.add(ListItemBlock(
          ordered: false, depth: depth, displayNumber: 0,
          isTask: false, checked: false, inlines: parseInlines(text),
        ));
        orderedCounters.remove(depth);
        i++;
        continue;
      }

      // Ordered list item (auto-renumbered; literal number in source is ignored)
      final olMatch = _orderedItemRegex.matchAsPrefix(trimmed);
      if (olMatch != null) {
        final text = olMatch.group(2)!;
        final num = (orderedCounters[depth] ?? 0) + 1;
        orderedCounters[depth] = num;
        blocks.add(ListItemBlock(
          ordered: true, depth: depth, displayNumber: num,
          isTask: false, checked: false, inlines: parseInlines(text),
        ));
        i++;
        continue;
      }

      // Paragraph: collect consecutive non-block lines
      final paraLines = <String>[trimmed];
      while (i + 1 < lines.length) {
        final nextTrimmed = lines[i + 1].trimLeft().trimRight();
        if (nextTrimmed.isEmpty || _isBlockStart(nextTrimmed)) break;
        paraLines.add(nextTrimmed);
        i++;
      }
      blocks.add(Paragraph(parseInlines(paraLines.join(' '))));
      orderedCounters.clear();
      i++;
    }

    return blocks;
  }

  /// True when [trimmed] opens a new block and should terminate a running paragraph.
  static bool _isBlockStart(String trimmed) =>
      _headingRegex.matchAsPrefix(trimmed) != null ||
      _isHorizontalRule(trimmed) ||
      _blockquoteRegex.matchAsPrefix(trimmed) != null ||
      _taskItemRegex.matchAsPrefix(trimmed) != null ||
      _unorderedItemRegex.matchAsPrefix(trimmed) != null ||
      _orderedItemRegex.matchAsPrefix(trimmed) != null;

  static bool _isHorizontalRule(String trimmed) {
    final stripped = trimmed.replaceAll(' ', '').replaceAll('\t', '');
    if (stripped.length < 3) return false;
    return stripped.split('').every((c) => c == '-') ||
        stripped.split('').every((c) => c == '*') ||
        stripped.split('').every((c) => c == '_');
  }

  // ── Inline parser ──────────────────────────────────────────────────────────

  static List<Inline> parseInlines(String text) {
    final result = <Inline>[];
    var i = 0;
    while (i < text.length) {
      if (text.startsWith('~~', i)) {
        final end = text.indexOf('~~', i + 2);
        if (end >= 0) {
          result.add(Strikethrough(parseInlines(text.substring(i + 2, end))));
          i = end + 2;
        } else {
          _appendChar(result, text[i]);
          i++;
        }
      } else if (text.startsWith('**', i)) {
        final end = text.indexOf('**', i + 2);
        if (end >= 0) {
          result.add(Bold(parseInlines(text.substring(i + 2, end))));
          i = end + 2;
        } else {
          _appendChar(result, text[i]);
          i++;
        }
      } else if (text.startsWith('__', i)) {
        final end = text.indexOf('__', i + 2);
        if (end >= 0) {
          result.add(Bold(parseInlines(text.substring(i + 2, end))));
          i = end + 2;
        } else {
          _appendChar(result, text[i]);
          i++;
        }
      } else if (text[i] == '[') {
        final textEnd = text.indexOf(']', i + 1);
        if (textEnd >= 0 && textEnd + 1 < text.length && text[textEnd + 1] == '(') {
          final urlEnd = text.indexOf(')', textEnd + 2);
          if (urlEnd >= 0) {
            result.add(Link(text.substring(i + 1, textEnd), text.substring(textEnd + 2, urlEnd)));
            i = urlEnd + 1;
          } else {
            _appendChar(result, text[i]);
            i++;
          }
        } else {
          _appendChar(result, text[i]);
          i++;
        }
      } else if (text[i] == '*') {
        final end = text.indexOf('*', i + 1);
        if (end >= 0) {
          result.add(Italic(parseInlines(text.substring(i + 1, end))));
          i = end + 1;
        } else {
          _appendChar(result, text[i]);
          i++;
        }
      } else if (text[i] == '_') {
        final end = text.indexOf('_', i + 1);
        if (end >= 0) {
          result.add(Italic(parseInlines(text.substring(i + 1, end))));
          i = end + 1;
        } else {
          _appendChar(result, text[i]);
          i++;
        }
      } else {
        _appendChar(result, text[i]);
        i++;
      }
    }
    return result;
  }

  static void _appendChar(List<Inline> result, String c) {
    final last = result.isEmpty ? null : result.last;
    if (last is InlineText) {
      result[result.length - 1] = InlineText(last.text + c);
    } else {
      result.add(InlineText(c));
    }
  }
}
