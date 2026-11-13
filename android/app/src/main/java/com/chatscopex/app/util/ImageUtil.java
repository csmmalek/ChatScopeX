package com.chatscopex.app.util;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Shrinks a picked photo before upload: at most 1280 px on the long side, JPEG, under the 5 MB server limit. */
public final class ImageUtil {
    private static final int MAX_SIDE = 1280;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private ImageUtil() {}

    /** Returns JPEG bytes, or null when the file cannot be read as an image. Call from a background thread. */
    public static byte[] compress(ContentResolver cr, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        if (in == null) return null;
        try { BitmapFactory.decodeStream(in, null, bounds); } finally { in.close(); }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / sample > MAX_SIDE * 2 || bounds.outHeight / sample > MAX_SIDE * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        in = cr.openInputStream(uri);
        if (in == null) return null;
        try { bmp = BitmapFactory.decodeStream(in, null, opts); } finally { in.close(); }
        if (bmp == null) return null;

        Matrix m = new Matrix();
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (scale < 1f) m.postScale(scale, scale);
        int rotation = exifRotation(cr, uri);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);

        int quality = 82;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        while (bos.size() > MAX_BYTES && quality > 40) {
            quality -= 12;
            bos.reset();
            out.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        }
        return bos.toByteArray();
    }

    private static int exifRotation(ContentResolver cr, Uri uri) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return 0;
            int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException e) {
            return 0;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }
}
