package com.quickskin.mod.e2e.scenario;

/** Shared scanline clustering; each visual probe owns its own area and pixel thresholds. */
final class ImageProbeClusters {
    private ImageProbeClusters() {
    }

    /**
     * First and last index of the run of populated buckets holding the most pixels.
     *
     * <p>Buckets are joined into one run while the blank stretch between them stays under
     * {@code gap}, so glyph spacing does not split a splash while a bee several dozen
     * pixels away stays its own, much smaller, run. {@code null} when the winning run is too small
     * to be a splash.
     */
    static int[] densest(int[] buckets, int gap, int minimumPixels) {
        int bestStart = -1;
        int bestEnd = -1;
        int bestWeight = 0;
        int start = -1;
        int end = -1;
        int weight = 0;
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] > 0) {
                if (start < 0 || i - end > gap) {
                    if (weight > bestWeight) {
                        bestWeight = weight;
                        bestStart = start;
                        bestEnd = end;
                    }
                    start = i;
                    weight = 0;
                }
                end = i;
                weight += buckets[i];
            }
        }
        if (weight > bestWeight) {
            bestWeight = weight;
            bestStart = start;
            bestEnd = end;
        }
        return bestWeight >= minimumPixels ? new int[] {bestStart, bestEnd} : null;
    }

}
