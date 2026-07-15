package org.nahoft.swatch;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore.Images;
import static androidx.core.content.FileProvider.getUriForFile;

/**
 * Android internals have been modified to store images in the media folder with
 * the correct date meta data
 * @author samuelkirton
 */
public class CapturePhotoUtils
{
    public static Uri insertImage(Context context, Bitmap source)
    {
        try
        {
            File encodedCacheDir = new File(context.getCacheDir(), "encoded_saves");
            if (!encodedCacheDir.exists()) {
                encodedCacheDir.mkdirs();
            }

            File tempFile = File.createTempFile("image", ".png", encodedCacheDir);

            try (FileOutputStream outputStream = new FileOutputStream(tempFile))
            {
                Uri fileUri = getUriForFile(context, "org.nahoft.fileprovider", tempFile);
                source.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                return fileUri;
            }
            catch (Exception e)
            {
                System.out.println(e);
                return null;
            }

        } catch (Exception error) {
            return null;
        }
    }

    /**
     * A copy of the Android internals StoreThumbnail method, it used with the insertImage to
     * populate the android.provider.MediaStore.Images.Media#insertImage with all the correct
     * meta data. The StoreThumbnail method is private so it must be duplicated here.
     * @see android.provider.MediaStore.Images.Media (StoreThumbnail private method)
     */
    private static Bitmap storeThumbnail(
            ContentResolver cr,
            Bitmap source,
            long id,
            float width,
            float height,
            int kind) throws IOException {

        // create the matrix to scale it
        Matrix matrix = new Matrix();

        float scaleX = width / source.getWidth();
        float scaleY = height / source.getHeight();

        matrix.setScale(scaleX, scaleY);

        Bitmap thumb = Bitmap.createBitmap(source, 0, 0,
                source.getWidth(),
                source.getHeight(), matrix,
                true
        );

        ContentValues values = new ContentValues(4);
        values.put(Images.Thumbnails.KIND,kind);
        values.put(Images.Thumbnails.IMAGE_ID,(int)id);
        values.put(Images.Thumbnails.HEIGHT,thumb.getHeight());
        values.put(Images.Thumbnails.WIDTH,thumb.getWidth());

        Uri url = cr.insert(Images.Thumbnails.EXTERNAL_CONTENT_URI, values);

        try {
            OutputStream thumbOut = cr.openOutputStream(url);
            thumb.compress(Bitmap.CompressFormat.JPEG, 100, thumbOut);
            thumbOut.close();
            return thumb;
        } catch (FileNotFoundException ex) {
            return null;
        }
    }
}