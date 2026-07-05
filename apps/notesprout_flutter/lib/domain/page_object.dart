import 'objects.dart';
import 'stroke.dart';

/// A single content row on a layer, resolved to its typed payload. `type` in the `notebook` table
/// selects the subclass; every object carries its row [id] and [box] (the `boundingBox` column) so
/// the renderer and hit-testing can work uniformly across types.
sealed class PageObject {
  const PageObject(this.id, this.box);
  final String id;
  final BoundingBox box;
}

class StrokeObject extends PageObject {
  const StrokeObject(super.id, super.box, this.data);
  final StrokeData data;
}

class HeadingRender extends PageObject {
  const HeadingRender(super.id, super.box, this.data);
  final HeadingObject data;
}

class TextRender extends PageObject {
  const TextRender(super.id, super.box, this.data);
  final TextObject data;
}

class LineRender extends PageObject {
  const LineRender(super.id, super.box, this.data);
  final LineObject data;
}

/// A `type = "sticky_note"` row. The [box] is the icon's fixed rect on the page (what lasso/move
/// hit-test); the embedded content in [data] lives in its own pixel space and is edited in the
/// content window — never drawn on the page (only the icon renders).
class StickyNoteRender extends PageObject {
  const StickyNoteRender(super.id, super.box, this.data);
  final StickyNoteObject data;
}
