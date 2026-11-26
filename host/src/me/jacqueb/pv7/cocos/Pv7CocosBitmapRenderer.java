package me.jacqueb.pv7.cocos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.Rect;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Android-side implementation of the historical Cocos2dxBitmap.createTextBitmap
 * call. Reference: cocos2d-2.1rc0-x-2.1.2, Cocos2dxBitmap.java.
 *
 * The host MUST send the returned native-order pixels to the ARM32 guest's
 * original Java_org_cocos2dx_lib_Cocos2dxBitmap_nativeInitBitmapDC entrypoint.
 * This class deliberately does not call a host-side fake native callback.
 */
public final class Pv7CocosBitmapRenderer {
    public interface BitmapSink {
        void nativeInitBitmapDC(int width, int height, byte[] nativeOrderArgb8888);
    }

    private static final int MAX_PIXELS_BYTES = 64 * 1024 * 1024;

    private Pv7CocosBitmapRenderer() {}
    /** JNI-friendly output: {int[]{width,height}, byte[] native-order ARGB pixels}. */
    public static Object[] renderForGuest(String source, String font, int size,
                                          int alignment, int width, int height) {
        final Object[][] output = new Object[1][];
        try {
            createTextBitmap(source, font, size, alignment, width, height,
                (actualWidth, actualHeight, pixels) ->
                    output[0] = new Object[] {
                        new int[] {actualWidth, actualHeight}, pixels
                    });
        } catch (Throwable failure) {
            android.util.Log.e("PV7CocosRenderer", "Android Paint/Canvas bitmap failed", failure);
            throw new IllegalStateException("Android Paint/Canvas bitmap failed", failure);
        }
        if (output[0] == null) {
            throw new IllegalStateException("Bitmap callback produced no pixels");
        }
        return output[0];
    }

    public static void createTextBitmap(String source, String font, int size,
                                        int alignment, int width, int height,
                                        BitmapSink receiver) {
        if (receiver == null) throw new IllegalArgumentException("bitmap receiver required");
        if (source == null) source = "";
        if (font == null) font = "";
        if (size <= 0 || size > 4096 || width < 0 || height < 0 ||
            width > 16384 || height > 16384) {
            throw new IllegalArgumentException("invalid bitmap request");
        }
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(size);
        // app_process launches without Activity/system font-map initialization.
        // A normal Activity uses the named font, while a standalone diagnostic
        // can still rasterize real glyphs from the device's installed TTF.
        Typeface typeface = null;
        try {
            typeface = Typeface.create(font, Typeface.NORMAL);
        } catch (RuntimeException missingSystemFontMap) {
            android.util.Log.w("PV7CocosRenderer",
                    "System font map unavailable in ART diagnostic, using local Roboto", missingSystemFontMap);
        }
        if (typeface == null) {
            typeface = Typeface.createFromFile("/system/fonts/Roboto-Regular.ttf");
        }
        paint.setTypeface(typeface);

        final int horizontal = alignment & 0x0f;
        final int vertical = (alignment >> 4) & 0x0f;
        if (horizontal == 2) paint.setTextAlign(Paint.Align.RIGHT);
        else if (horizontal == 3) paint.setTextAlign(Paint.Align.CENTER);
        else paint.setTextAlign(Paint.Align.LEFT);

        final Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        final int lineHeight = (int)Math.ceil(metrics.bottom - metrics.top);
        final List<String> lines = new ArrayList<>();
        // Preserve literal line breaks; wrap to the caller's width when given.
        for (String paragraph : source.replace("\r\n", "\n").split("\n", -1)) {
            if (width == 0 || paint.measureText(paragraph) <= width) {
                lines.add(paragraph);
                continue;
            }
            String remaining = paragraph;
            while (!remaining.isEmpty()) {
                int chars = paint.breakText(remaining, true, width, null);
                if (chars <= 0) chars = 1;
                lines.add(remaining.substring(0, chars));
                remaining = remaining.substring(chars);
            }
        }
        int bitmapWidth = width;
        if (bitmapWidth == 0) {
            for (String line : lines) {
                bitmapWidth = Math.max(bitmapWidth, (int)Math.ceil(paint.measureText(line)));
            }
        }
        final int totalHeight = lineHeight * lines.size();
        final int bitmapHeight = height == 0 ? totalHeight : height;
        if (bitmapWidth <= 0 || bitmapHeight <= 0 ||
            ((long)bitmapWidth * bitmapHeight * 4L) > MAX_PIXELS_BYTES) {
            throw new IllegalArgumentException("bitmap dimensions outside supported range");
        }
        final Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight,
                                                  Bitmap.Config.ARGB_8888);
        try {
            final Canvas canvas = new Canvas(bitmap);
            int y = -metrics.top;
            if (vertical == 2) y += bitmapHeight - totalHeight;
            else if (vertical == 3) y += (bitmapHeight - totalHeight) / 2;
            for (String line : lines) {
                int x = horizontal == 2 ? bitmapWidth :
                        horizontal == 3 ? bitmapWidth / 2 : 0;
                canvas.drawText(line, x, y, paint);
                y += lineHeight;
            }
            byte[] pixels = new byte[bitmapWidth * bitmapHeight * 4];
            ByteBuffer bytes = ByteBuffer.wrap(pixels);
            bytes.order(ByteOrder.nativeOrder());
            bitmap.copyPixelsToBuffer(bytes);
            receiver.nativeInitBitmapDC(bitmapWidth, bitmapHeight, pixels);
        } finally {
            bitmap.recycle();
        }
    }
}
