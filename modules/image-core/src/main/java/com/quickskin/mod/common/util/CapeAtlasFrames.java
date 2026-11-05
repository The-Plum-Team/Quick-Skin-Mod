package com.quickskin.mod.common.util;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;

/**
 * Draws one source frame into its own row of a vertical cape atlas.
 *
 * <p>The cape editor places the source at a user-chosen offset and scale, which routinely makes
 * the drawn rectangle larger than one cape frame (cover-fit of a source narrower than 2:1, a
 * zoom, a drag). The live preview composes each frame on its own canvas, so the overflow is
 * simply cut off; the applied atlas stacks every frame on one canvas, so without a clip a frame
 * would spill into the rows of its neighbours and the saved cape would show strips the editor
 * never showed. This helper clips each draw to exactly its frame's rows, keeping the atlas
 * frame-for-frame identical to the preview.
 */
public final class CapeAtlasFrames {

    private CapeAtlasFrames() {
    }

    /**
     * Draws source rows {@code [srcY, srcY + srcFrameHeight)} into the atlas frame whose top row
     * is {@code frameTop}, at {@code (drawX, frameTop + drawY)} with size {@code drawW x drawH},
     * clipped to {@code [0, capeW) x [frameTop, frameTop + capeH)}. The graphics' previous clip is
     * restored afterwards.
     */
    public static void drawFrame(Graphics2D g, BufferedImage source, int srcY, int srcFrameHeight,
            int frameTop, int capeW, int capeH, int drawX, int drawY, int drawW, int drawH) {
        Shape previousClip = g.getClip();
        g.setClip(0, frameTop, capeW, capeH);
        try {
            g.drawImage(source,
                    drawX, frameTop + drawY, drawX + drawW, frameTop + drawY + drawH,
                    0, srcY, source.getWidth(), srcY + srcFrameHeight,
                    null);
        } finally {
            g.setClip(previousClip);
        }
    }
}
