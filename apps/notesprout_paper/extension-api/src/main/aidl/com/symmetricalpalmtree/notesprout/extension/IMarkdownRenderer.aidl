// The MARKDOWN_RENDERER capability point (arc 4 / H0). Markdown in, image out. Stateless.
// The core binds it and lends it to object providers through a proxy (never extension-to-extension).
package com.symmetricalpalmtree.notesprout.extension;

import com.symmetricalpalmtree.notesprout.extension.RenderedImage;

interface IMarkdownRenderer {
    /** Render [markdown] (≤ MAX_MARKDOWN_CHARS) as black text on a transparent background:
     *  natural width capped at [maxWidthPx] (> 0), [dpi] the panel density (sp/dp → px), [maxLines]
     *  0 = unlimited else ellipsize END past that many lines, [paddingPx] 0..RENDER_PADDING_MAX_PX added
     *  on all four sides. Returns a lossless WEBP with alpha whose declared size equals the encoded
     *  size, ≤ MAX_IMAGE_EDGE_PX per side; null if the source renders to nothing. Called on a Binder
     *  thread. IllegalArgumentException over the caps. */
    RenderedImage render(String markdown, int maxWidthPx, float dpi, int maxLines, int paddingPx);
}
