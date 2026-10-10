package com.poc.gctune;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * Icon rendering for the recolor tab.
 *
 * A faithful port of Origin Icons (IconPackEngine.render / renderTinted):
 *
 *   1. the source icon — adaptive included — is drawn over the FULL canvas;
 *   2. it is desaturated;
 *   3. multiplied by the normalized tint (channel / max channel);
 *   4. written as PNG. The launcher scales the PNG into the icon slot as-is.
 *
 * Nothing else touches geometry. The full canvas is what keeps every icon the
 * same size (adaptive icons come out platform-masked = uniformly rounded for
 * free); the old pipeline's 24px inset and per-source base scales (x1.25 /
 * x0.78) are exactly what made icons jump in size and paint over their own
 * rounded corners.
 *
 * The optional compose layer (IconPack Studio style, bgEnabled) draws a
 * rounded background tile over the SAME full canvas and a normalised glyph on
 * top, so geometry stays identical to the plain pipeline.
 */
final class IconsTinter {

    static final int SIZE = 192;

    private IconsTinter() {
    }

    /** Flat render, Origin Icons look (defaults). */
    static Bitmap tint(Drawable icon, int tint) {
        return tint(icon, tint, 100, 0, 100);
    }

    /** Flat render with adjustable pipeline. */
    static Bitmap tint(Drawable icon, int tint, int desat, int lighten, int strength) {
        return compose(icon, tint, desat, lighten, strength, false, 0, 100, 0);
    }

    /**
     * Full renderer.
     *
     * @param desat     0 = keep original colors, 100 = full grayscale (Origin default)
     * @param lighten   0 = as tinted, 100 = blend fully toward white
     * @param strength  0 = original icon, 100 = fully tinted icon
     * @param bgEnabled draw a rounded background tile under the icon
     * @param bgColor   background tile color
     * @param fgScale   glyph size in percent (100 = adaptive safe zone, 66.7% of the tile)
     * @param radiusPct corner radius in percent of the tile (0..50, 50 = circle)
     */
    static Bitmap compose(Drawable icon, int tint, int desat, int lighten, int strength,
                          boolean bgEnabled, int bgColor, int fgScale, int radiusPct) {
        if (icon == null) return null;
        desat = clamp(desat);
        lighten = clamp(lighten);
        strength = clamp(strength);
        fgScale = Math.max(20, Math.min(160, fgScale));
        radiusPct = clamp(radiusPct);

        if (!bgEnabled) {
            // Origin pipeline + the same slider: 100% = content bbox equals
            // the full canvas (the launcher scales the PNG as a whole, so the
            // bbox is the only thing that changes the visible size)
            Bitmap layer = drawIcon(icon, 0f, 0f, SIZE, SIZE);
            if (layer == null) return null;
            Bitmap tinted = tintBitmap(layer, tint, desat, lighten, strength);
            layer.recycle();
            if (tinted == null) return null;
            Rect bbox = new Rect();
            if (!alphaBBox(tinted, bbox, 10)) return tinted;
            Bitmap out = fitTo(tinted, bbox, SIZE * fgScale / 100.0f);
            tinted.recycle();
            return out;
        }

        // ---- compose layer: full-canvas rounded tile + normalised glyph ----
        Drawable fgSource = icon;
        if (icon instanceof AdaptiveIconDrawable) {
            try {
                Drawable adaptiveFg = ((AdaptiveIconDrawable) icon).getForeground();
                if (adaptiveFg != null) fgSource = adaptiveFg;
            } catch (Throwable ignored) {
            }
        }

        Bitmap layer = drawIcon(fgSource, 0f, 0f, SIZE, SIZE);
        Rect bbox = new Rect();
        boolean ok = layer != null && alphaBBox(layer, bbox, 10)
                && bbox.width() >= SIZE * 0.2f && bbox.height() >= SIZE * 0.2f;
        if (!ok && fgSource != icon) {
            // empty/negligible foreground (web shortcuts, bg-only adaptive
            // icons) — fall back to the whole drawable
            if (layer != null) layer.recycle();
            layer = drawIcon(icon, 0f, 0f, SIZE, SIZE);
            ok = layer != null && alphaBBox(layer, bbox, 10)
                    && bbox.width() >= SIZE * 0.12f && bbox.height() >= SIZE * 0.12f;
        }
        if (!ok) {
            // never emit an empty colored square — plain pipeline instead,
            // with the same size normalisation so it matches the rest
            if (layer != null) layer.recycle();
            Bitmap plain = drawIcon(icon, 0f, 0f, SIZE, SIZE);
            if (plain == null) return null;
            Bitmap plainTinted = tintBitmap(plain, tint, desat, lighten, strength);
            plain.recycle();
            if (plainTinted == null) return null;
            Rect plainBox = new Rect();
            if (!alphaBBox(plainTinted, plainBox, 10)) return plainTinted;
            Bitmap plainOut = fitTo(plainTinted, plainBox,
                    SIZE * 0.667f * fgScale / 100.0f);
            plainTinted.recycle();
            return plainOut;
        }

        Bitmap tinted = tintBitmap(layer, tint, desat, lighten, strength);
        layer.recycle();
        if (tinted == null) return null;

        RectF tile = new RectF(0f, 0f, SIZE, SIZE);
        float radius = SIZE * (radiusPct / 100.0f) / 2.0f;

        // Uniform, slider-controlled glyph: fit the content bbox to the tile
        // fraction from the "Размер иконки" slider (100% = adaptive safe zone,
        // 66.7% of the tile). Every source type goes through the same fit, so
        // the size is both uniform and actually reacts to the slider — the old
        // full-bleed shortcut ignored it and stretched the artwork to the edge.
        float target = SIZE * 0.667f * fgScale / 100.0f;
        float s = target / Math.max(bbox.width(), bbox.height());
        float w = bbox.width() * s;
        float h = bbox.height() * s;
        RectF dst = new RectF(SIZE / 2f - w / 2f, SIZE / 2f - h / 2f,
                SIZE / 2f + w / 2f, SIZE / 2f + h / 2f);

        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(out);
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(bgColor);
            c.drawRoundRect(tile, radius, radius, bp);
            Path clip = new Path();
            clip.addRoundRect(tile, radius, radius, Path.Direction.CW);
            c.clipPath(clip);
            Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(tinted, bbox, dst, gp);
        } catch (Throwable t) {
            out.recycle();
            tinted.recycle();
            return null;
        }
        tinted.recycle();
        return out;
    }

    // ------------------------------------------------------------------ pipeline

    /** Draws the drawable into the given box of a fresh SIZE x SIZE bitmap. */
    private static Bitmap drawIcon(Drawable icon, float l, float t, float r, float b) {
        if (icon == null) return null;
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(bmp);
            icon.setBounds((int) l, (int) t, (int) r, (int) b);
            icon.draw(c);
        } catch (Throwable err) {
            bmp.recycle();
            return null;
        }
        return bmp;
    }

    /** Desaturate -> multiply by normalized tint -> lighten -> mix with original. */
    private static Bitmap tintBitmap(Bitmap src, int tint, int desat, int lighten, int strength) {
        Bitmap gray = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            Paint p1 = new Paint(1);
            ColorMatrix sat = new ColorMatrix();
            sat.setSaturation(1.0f - desat / 100.0f);
            p1.setColorFilter(new ColorMatrixColorFilter(sat));
            Canvas c1 = new Canvas(gray);
            c1.drawBitmap(src, 0.0f, 0.0f, p1);

            float r = Color.red(tint);
            float g = Color.green(tint);
            float b = Color.blue(tint);
            float max = Math.max(r, Math.max(g, b));
            max = Math.max(max, 1.0f);
            r /= max;
            g /= max;
            b /= max;

            Paint p2 = new Paint(1);
            p2.setColorFilter(new ColorMatrixColorFilter(new float[]{
                    r, 0f, 0f, 0f, 0f,
                    0f, g, 0f, 0f, 0f,
                    0f, 0f, b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f}));

            Bitmap tinted = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            try {
                Canvas c2 = new Canvas(tinted);
                c2.drawBitmap(gray, 0.0f, 0.0f, p2);
                gray.recycle();

                if (lighten > 0) {
                    c2.drawColor(Color.argb(lighten * 255 / 100, 255, 255, 255),
                            PorterDuff.Mode.SRC_ATOP);
                }

                if (strength >= 100) return tinted;

                Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
                try {
                    Canvas c3 = new Canvas(out);
                    c3.drawBitmap(src, 0.0f, 0.0f, null);
                    Paint p4 = new Paint(1);
                    p4.setAlpha(strength * 255 / 100);
                    c3.drawBitmap(tinted, 0.0f, 0.0f, p4);
                } catch (Throwable t) {
                    out.recycle();
                    return tinted;
                }
                tinted.recycle();
                return out;
            } catch (Throwable t) {
                tinted.recycle();
                gray.recycle();
                return src;
            }
        } catch (Throwable t) {
            gray.recycle();
            return src;
        }
    }

    /** Alpha bounding box of the bitmap; false when nothing is drawn. */
    private static boolean alphaBBox(Bitmap bmp, Rect out, int minAlpha) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int minx = w, miny = h, maxx = -1, maxy = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            boolean hit = false;
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) >= minAlpha) {
                    if (x < minx) minx = x;
                    if (x > maxx) maxx = x;
                    hit = true;
                }
            }
            if (hit) {
                if (y < miny) miny = y;
                maxy = y;
            }
        }
        if (maxx < 0) return false;
        out.set(minx, miny, maxx + 1, maxy + 1);
        return true;
    }

    /**
     * Copies {@code src[bbox]} centred and scaled so its larger side equals
     * {@code targetMax} px — aspect ratio preserved, no cropping.
     */
    private static Bitmap fitTo(Bitmap src, Rect bbox, float targetMax) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = targetMax / Math.max(bbox.width(), bbox.height());
        float w = bbox.width() * s;
        float h = bbox.height() * s;
        RectF dst = new RectF(SIZE / 2f - w / 2f, SIZE / 2f - h / 2f,
                SIZE / 2f + w / 2f, SIZE / 2f + h / 2f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, bbox, dst, p);
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }
}
