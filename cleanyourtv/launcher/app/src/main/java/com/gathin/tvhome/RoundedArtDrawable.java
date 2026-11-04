package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * App artwork cropped to fill its bounds (centerCrop) and drawn with rounded corners of its own.
 *
 * A view's setClipToOutline is not enough while the view, or a parent, fades: the fade draws it into an offscreen
 * buffer, and on Android 9 that buffer ignores the outline, so the corners show square until the fade ends. These
 * corners are part of the picture, so they hold in every frame.
 */
final class RoundedArtDrawable extends Drawable {
    private final Drawable art;
    private final float radius;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF shape = new RectF();
    private Bitmap bitmap;

    RoundedArtDrawable(Drawable art, float radius) {
        this.art = art;
        this.radius = radius;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        shape.set(bounds);
        if (bounds.isEmpty()) return;
        if (bitmap != null && bitmap.getWidth() == bounds.width() && bitmap.getHeight() == bounds.height()) return;
        // The artwork is drawn once, at the card's size; each frame then only fills a rounded rect with it.
        bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int w = art.getIntrinsicWidth(), h = art.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            art.setBounds(0, 0, bounds.width(), bounds.height());
        } else {
            float scale = Math.max((float) bounds.width() / w, (float) bounds.height() / h);
            int sw = Math.round(w * scale), sh = Math.round(h * scale);
            int left = (bounds.width() - sw) / 2, top = (bounds.height() - sh) / 2;
            art.setBounds(left, top, left + sw, top + sh);
        }
        art.draw(canvas);
        BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix offset = new Matrix();
        offset.setTranslate(bounds.left, bounds.top);
        shader.setLocalMatrix(offset);
        paint.setShader(shader);
    }

    @Override
    public void draw(Canvas canvas) {
        if (bitmap != null) canvas.drawRoundRect(shape, radius, radius, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
