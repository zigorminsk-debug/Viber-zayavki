package by.viberlead.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Отдаёт скачанный APK обновления (filesDir/updates/<имя>) системному
 * установщику через content:// — file://-ссылки запрещены с Android 7+.
 * Работает и без AndroidX: каталог жёстко задан, выход за него невозможен.
 */
public class UpdateFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(Updater.apkDir(getContext()), name);
        if (!f.exists() || !f.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        return null;
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
        return 0;
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
        return 0;
    }
}
