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
