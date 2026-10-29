package app.projectv7.wrapper;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SAF root for explicitly user-manageable guest storage.
 *
 * "obb" is the REAL OBB directory of this installed wrapper package
 * (Context.getObbDir(), typically /storage/emulated/0/Android/obb/<wrapper>).
 * data/cache/files are segregated trees inside private app files storage.
 *
 * Never expose the wrapper's top-level filesDir, cacheDir, databases, or
 * preferences. A user can edit only these purpose-built guest directories.
 * This provider requires MANAGE_DOCUMENTS and URI grants from DocumentsUI.
 */
public final class Pv7GameStorageDocumentsProvider extends DocumentsProvider {
    private static final String ROOT = "pv7storage";
    private static final String VIRTUAL_ROOT = "root";
    private static final String[] FOLDERS = {"obb", "data", "cache", "files"};

    private static final String[] ROOT_PROJECTION = {
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_SUMMARY,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };
    private static final String[] DOCUMENT_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private String authority() {
        return getContext().getPackageName() + ".gamefiles";
    }

    private File directoryFor(String kind) throws FileNotFoundException {
        if (!Arrays.asList(FOLDERS).contains(kind)) throw new FileNotFoundException("Invalid storage area");
        final File target;
        if ("obb".equals(kind)) {
            target = getContext().getObbDir();
        } else {
            target = new File(getContext().getFilesDir(), "GuestSharedStorage/" + kind);
        }
        if (target == null) throw new FileNotFoundException("External OBB storage unavailable");
        if (!target.exists() && !target.mkdirs()) {
            throw new FileNotFoundException("Could not prepare " + kind + " storage");
        }
        if (!target.isDirectory()) throw new FileNotFoundException("Storage is not a directory");
        return target;
    }

    private static String category(String id) throws FileNotFoundException {
        if (id == null || VIRTUAL_ROOT.equals(id)) throw new FileNotFoundException("Invalid document area");
        String cat = id.split("/", 2)[0];
        if (!Arrays.asList(FOLDERS).contains(cat)) throw new FileNotFoundException("Unknown storage area");
        return cat;
    }

    private File resolve(String id) throws FileNotFoundException {
        String cat = category(id);
        File root = directoryFor(cat);
        String suffix = id.equals(cat) ? "" : id.substring(cat.length() + 1);
        if (suffix.indexOf('\0') >= 0) throw new FileNotFoundException("Invalid document ID");
        try {
            File canonicalRoot = root.getCanonicalFile();
            File candidate = suffix.isEmpty() ? canonicalRoot : new File(root, suffix).getCanonicalFile();
            String p = canonicalRoot.getPath();
            String c = candidate.getPath();
            if (!c.equals(p) && !c.startsWith(p + File.separator)) {
                throw new FileNotFoundException("Document outside guest storage");
            }
            if (!candidate.exists()) throw new FileNotFoundException("Document does not exist");
            return candidate;
        } catch (IOException e) {
            throw new FileNotFoundException("Invalid document path");
        }
    }

    private String idOf(String category, File file) throws FileNotFoundException {
        try {
            File root = directoryFor(category).getCanonicalFile();
            File item = file.getCanonicalFile();
            String base = root.getPath(), path = item.getPath();
            if (base.equals(path)) return category;
            if (!path.startsWith(base + File.separator)) throw new FileNotFoundException("Path outside category");
            return category + "/" + path.substring(base.length() + 1).replace(File.separatorChar, '/');
        } catch (IOException e) {
            throw new FileNotFoundException("Cannot map document");
        }
    }

    private static String safeName(String value) throws FileNotFoundException {
        if (value == null) throw new FileNotFoundException("Empty filename");
        String v = value.trim();
        if (v.isEmpty() || ".".equals(v) || "..".equals(v) || v.length() > 255 ||
                v.indexOf('/') >= 0 || v.indexOf('\\') >= 0 ||
                v.indexOf('\0') >= 0) throw new FileNotFoundException("Unsafe filename");
        return v;
    }

    private static String mime(File file) {
        if (file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        String guess = URLConnection.guessContentTypeFromName(file.getName());
        return guess != null ? guess : "application/octet-stream";
    }

    private void notifyChanged() {
        Context c = getContext();
        c.getContentResolver().notifyChange(DocumentsContract.buildRootsUri(authority()), null);
        c.getContentResolver().notifyChange(DocumentsContract.buildDocumentUri(authority(), VIRTUAL_ROOT), null);
    }

    private static MatrixCursor cursor(String[] projection, String[] defaults) {
        return new MatrixCursor(projection != null ? projection : defaults);
    }

    private static void row(MatrixCursor cursor, Map<String, Object> columns) {
        MatrixCursor.RowBuilder builder = cursor.newRow();
        for (String column : cursor.getColumnNames()) builder.add(columns.get(column));
    }

    @Override
    public boolean onCreate() {
        // OBB storage may be temporarily unavailable; let queryChildDocuments
        // show only ready roots rather than failing the whole provider.
        for (String folder : FOLDERS) {
            try { directoryFor(folder); } catch (FileNotFoundException ignored) { }
        }
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = cursor(projection, ROOT_PROJECTION);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT);
        data.put(DocumentsContract.Root.COLUMN_DOCUMENT_ID, VIRTUAL_ROOT);
        data.put(DocumentsContract.Root.COLUMN_TITLE, "PV7 Game Storage");
        data.put(DocumentsContract.Root.COLUMN_SUMMARY, "OBB, saves and guest files");
        data.put(DocumentsContract.Root.COLUMN_FLAGS,
                 DocumentsContract.Root.FLAG_LOCAL_ONLY |
                 DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        data.put(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, getContext().getFilesDir().getUsableSpace());
        row(c, data);
        c.setNotificationUri(getContext().getContentResolver(), DocumentsContract.buildRootsUri(authority()));
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor c = cursor(projection, DOCUMENT_PROJECTION);
        if (VIRTUAL_ROOT.equals(documentId)) {
            virtualRow(c, VIRTUAL_ROOT, "PV7 Game Storage");
        } else {
            String kind = category(documentId);
            File item = resolve(documentId);
            fileRow(c, kind, item);
        }
        return c;
    }

    private static void virtualRow(MatrixCursor c, String id, String label) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        data.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, label);
        data.put(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
        data.put(DocumentsContract.Document.COLUMN_FLAGS, 0);
        row(c, data);
    }

    private void fileRow(MatrixCursor c, String cat, File item) throws FileNotFoundException {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean directory = item.isDirectory();
        data.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, idOf(cat, item));
        data.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                 item.equals(directoryFor(cat)) ? cat : item.getName());
        data.put(DocumentsContract.Document.COLUMN_MIME_TYPE, mime(item));
        int flags = directory
                ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                : DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        if (!item.equals(directoryFor(cat))) {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE |
                     DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
        }
        data.put(DocumentsContract.Document.COLUMN_FLAGS, flags);
        data.put(DocumentsContract.Document.COLUMN_SIZE, directory ? 0L : item.length());
        data.put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, item.lastModified());
        row(c, data);
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor c = cursor(projection, DOCUMENT_PROJECTION);
        if (VIRTUAL_ROOT.equals(parentDocumentId)) {
            for (String category : FOLDERS) {
                try { fileRow(c, category, directoryFor(category)); }
                catch (FileNotFoundException ignored) { }
            }
        } else {
            String cat = category(parentDocumentId);
            File parent = resolve(parentDocumentId);
            if (!parent.isDirectory()) throw new FileNotFoundException("Not a directory");
            File[] children = parent.listFiles();
            if (children != null) {
                Arrays.sort(children, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
                for (File child : children) {
                    try { fileRow(c, cat, child); }
                    catch (FileNotFoundException ignored) { }
                }
            }
        }
        c.setNotificationUri(getContext().getContentResolver(),
                DocumentsContract.buildChildDocumentsUri(authority(), parentDocumentId));
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = resolve(documentId);
        if (!file.isFile()) throw new FileNotFoundException("Cannot open directory");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        if (VIRTUAL_ROOT.equals(parentDocumentId)) throw new FileNotFoundException("Choose a storage folder");
        File parent = resolve(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Not a directory");
        File out = new File(parent, safeName(displayName));
        if (out.exists()) throw new FileNotFoundException("Document already exists");
        try {
            if (!(DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType) ? out.mkdir() : out.createNewFile()))
                throw new FileNotFoundException("Cannot create document");
            notifyChanged();
            return idOf(category(parentDocumentId), out);
        } catch (IOException e) {
            throw new FileNotFoundException("Cannot create document: " + e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (VIRTUAL_ROOT.equals(documentId) || Arrays.asList(FOLDERS).contains(documentId))
            throw new FileNotFoundException("Protected storage root");
        File file = resolve(documentId);
        try {
            String root = directoryFor(category(documentId)).getCanonicalPath();
            if (!deleteTree(file, root)) throw new FileNotFoundException("Deletion failed");
        } catch (IOException ex) {
            throw new FileNotFoundException("Unsafe storage path");
        }
        notifyChanged();
    }

    private static boolean deleteTree(File file, String allowedRoot) throws IOException {
        // Reject symlinks that escape the selected guest storage tree, even when
        // an untrusted game created them; never recursively erase host settings.
        String path = file.getCanonicalPath();
        if (!path.startsWith(allowedRoot + File.separator)) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) if (!deleteTree(child, allowedRoot)) return false;
        }
        return file.delete();
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (VIRTUAL_ROOT.equals(documentId) || Arrays.asList(FOLDERS).contains(documentId))
            throw new FileNotFoundException("Protected storage root");
        File source = resolve(documentId);
        File renamed = new File(source.getParentFile(), safeName(displayName));
        if (renamed.exists() || !source.renameTo(renamed))
            throw new FileNotFoundException("Rename failed");
        notifyChanged();
        return idOf(category(documentId), renamed);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            if (VIRTUAL_ROOT.equals(parentDocumentId)) {
                resolve(documentId);
                return true;
            }
            if (!category(parentDocumentId).equals(category(documentId))) return false;
            String parent = resolve(parentDocumentId).getCanonicalPath();
            String child = resolve(documentId).getCanonicalPath();
            return parent.equals(child) || child.startsWith(parent + File.separator);
        } catch (Exception ignored) { return false; }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return VIRTUAL_ROOT.equals(documentId)
                ? DocumentsContract.Document.MIME_TYPE_DIR : mime(resolve(documentId));
    }
}
