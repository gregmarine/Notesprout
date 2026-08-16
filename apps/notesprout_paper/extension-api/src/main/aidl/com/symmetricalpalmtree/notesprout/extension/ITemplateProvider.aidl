package com.symmetricalpalmtree.notesprout.extension;

import com.symmetricalpalmtree.notesprout.extension.TemplateInfo;
import com.symmetricalpalmtree.notesprout.extension.RenderedTemplate;

interface ITemplateProvider {
    /** Templates this provider offers, in display order. Ids are stable, ASCII, unique per provider. */
    List<TemplateInfo> listTemplates();

    /** Render [templateId] at exactly widthPx x heightPx for a panel of [dpi] as a lossless WEBP.
     *  Returns null if the id is unknown. Called on a Binder thread; may take seconds on e-ink CPUs. */
    RenderedTemplate render(String templateId, int widthPx, int heightPx, float dpi);
}
