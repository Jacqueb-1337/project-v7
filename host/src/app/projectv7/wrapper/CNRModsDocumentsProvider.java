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

/**
 * Exposes the wrapper-private CNRMods directory through Android's Storage
 * Access Framework. Guest ARM32 code sees /sdcard/CNRMods, while the native
 * bridge redirects that tree to files/CNRMods in this app's sandbox.
 */
public final class CNRModsDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "app.projectv7.wrapper.documents";

    private static final String ROOT_ID = "cnrmods";
    private static final String ROOT_DOCUMENT_ID = "root";

    private static String authorityFor(Context context) {
        return context.getPackageName() + ".documents";
    }

    public static void notifyRootChanged(Context context) {
        if (context == null) return;
        final String authority = authorityFor(context);
        final Uri rootsUri = DocumentsContract.buildRootsUri(authority);
        final Uri rootDocumentUri = DocumentsContract.buildDocumentUri(authority, ROOT_DOCUMENT_ID);
        final Uri rootChildrenUri = DocumentsContract.buildChildDocumentsUri(authority, ROOT_DOCUMENT_ID);
        context.getContentResolver().notifyChange(rootsUri, null);
        context.getContentResolver().notifyChange(rootDocumentUri, null);
        context.getContentResolver().notifyChange(rootChildrenUri, null);
    }

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    @Override
    public boolean onCreate() {
        try {
            ensureRootDirectory();
            return true;
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final File root = ensureRootDirectory();
        final MatrixCursor cursor = new MatrixCursor(
                projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        final MatrixCursor.RowBuilder row = cursor.newRow();
        addRootColumn(row, cursor, DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        addRootColumn(row, cursor, DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
        addRootColumn(row, cursor, DocumentsContract.Root.COLUMN_TITLE, "CNR64 Mods");
        addRootColumn(row, cursor, DocumentsContract.Root.COLUMN_SUMMARY, "Cops N Robbers mod files");
        addRootColumn(row, cursor, DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY
                        | DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                        | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        addRootColumn(row, cursor, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, root.getUsableSpace());
        cursor.setNotificationUri(getContext().getContentResolver(),
                DocumentsContract.buildRootsUri(authorityFor(getContext())));
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor cursor = new MatrixCursor(
                projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(cursor, fileForDocumentId(documentId));
        cursor.setNotificationUri(getContext().getContentResolver(),
                DocumentsContract.buildDocumentUri(authorityFor(getContext()), documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        final File parent = fileForDocumentId(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Not a directory: " + parentDocumentId);

        final MatrixCursor cursor = new MatrixCursor(
                projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File[] children = parent.listFiles();
        if (children != null) {
            for (File child : children) includeFile(cursor, child);
        }
        cursor.setNotificationUri(getContext().getContentResolver(),
                DocumentsContract.buildChildDocumentsUri(authorityFor(getContext()), parentDocumentId));
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        final File file = fileForDocumentId(documentId);
        if (file.isDirectory()) throw new FileNotFoundException("Cannot open directory as file");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        final File parent = fileForDocumentId(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Parent is not a directory");

        final boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
        final File target = uniqueChild(parent, sanitizeDisplayName(displayName), directory);
        try {
            final boolean created = directory ? target.mkdir() : target.createNewFile();
            if (!created) throw new IOException("create returned false");
            final String documentId = documentIdForFile(target);
            notifyRootChanged(getContext());
            return documentId;
        } catch (IOException e) {
            throw fileNotFound("Could not create " + target.getName(), e);
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            throw new FileNotFoundException("The CNRMods root cannot be deleted");
        }
        final File file = fileForDocumentId(documentId);
        if (!deleteRecursively(file)) throw new FileNotFoundException("Could not delete " + documentId);
        notifyRootChanged(getContext());
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (ROOT_DOCUMENT_ID.equals(documentId)) return ROOT_DOCUMENT_ID;
        final File file = fileForDocumentId(documentId);
        final File parent = file.getParentFile();
        if (parent == null) throw new FileNotFoundException("Missing parent");
        final File target = uniqueChild(parent, sanitizeDisplayName(displayName), file.isDirectory());
        if (!file.renameTo(target)) throw new FileNotFoundException("Could not rename " + documentId);
        final String renamedDocumentId = documentIdForFile(target);
        notifyRootChanged(getContext());
        return renamedDocumentId;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            final File parent = fileForDocumentId(parentDocumentId).getCanonicalFile();
            final File child = fileForDocumentId(documentId).getCanonicalFile();
            final String parentPath = parent.getPath();
            final String childPath = child.getPath();
            return childPath.equals(parentPath) || childPath.startsWith(parentPath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return mimeTypeForFile(fileForDocumentId(documentId));
    }

    private File ensureRootDirectory() throws FileNotFoundException {
        final File root = new File(getContext().getFilesDir(), "CNRMods");
        if (!root.exists() && !root.mkdirs()) {
            throw new FileNotFoundException("Could not create private CNRMods directory");
        }
        if (!root.isDirectory()) throw new FileNotFoundException("CNRMods root is not a directory");
        return root;
    }

    private File fileForDocumentId(String documentId) throws FileNotFoundException {
        final File root = ensureRootDirectory();
        final File requested;
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            requested = root;
        } else if (documentId != null && documentId.startsWith(ROOT_DOCUMENT_ID + "/")) {
            requested = new File(root, documentId.substring((ROOT_DOCUMENT_ID + "/").length()));
        } else {
            throw new FileNotFoundException("Unknown document id: " + documentId);
        }

        try {
            final File canonicalRoot = root.getCanonicalFile();
            final File canonicalRequested = requested.getCanonicalFile();
            final String rootPath = canonicalRoot.getPath();
            final String requestedPath = canonicalRequested.getPath();
            if (!requestedPath.equals(rootPath)
                    && !requestedPath.startsWith(rootPath + File.separator)) {
                throw new FileNotFoundException("Document escapes CNRMods root");
            }
            if (!canonicalRequested.exists()) throw new FileNotFoundException("Missing document: " + documentId);
            return canonicalRequested;
        } catch (IOException e) {
            throw fileNotFound("Could not resolve document", e);
        }
    }

    private String documentIdForFile(File file) throws FileNotFoundException {
        try {
            final File root = ensureRootDirectory().getCanonicalFile();
            final File canonical = file.getCanonicalFile();
            final String rootPath = root.getPath();
            final String filePath = canonical.getPath();
            if (filePath.equals(rootPath)) return ROOT_DOCUMENT_ID;
            if (!filePath.startsWith(rootPath + File.separator)) {
                throw new FileNotFoundException("File is outside CNRMods root");
            }
            return ROOT_DOCUMENT_ID + "/" + filePath.substring(rootPath.length() + 1)
                    .replace(File.separatorChar, '/');
        } catch (IOException e) {
            throw fileNotFound("Could not create document id", e);
        }
    }

    private void includeFile(MatrixCursor cursor, File file) throws FileNotFoundException {
        final MatrixCursor.RowBuilder row = cursor.newRow();
        final String documentId = documentIdForFile(file);
        int flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
        if (file.isDirectory()) {
            flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (file.canWrite()) {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        }

        addDocumentColumn(row, cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId);
        addDocumentColumn(row, cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ROOT_DOCUMENT_ID.equals(documentId) ? "CNRMods" : file.getName());
        addDocumentColumn(row, cursor, DocumentsContract.Document.COLUMN_MIME_TYPE, mimeTypeForFile(file));
        addDocumentColumn(row, cursor, DocumentsContract.Document.COLUMN_FLAGS, flags);
        addDocumentColumn(row, cursor, DocumentsContract.Document.COLUMN_SIZE, file.isFile() ? file.length() : null);
        addDocumentColumn(row, cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private static String mimeTypeForFile(File file) {
        if (file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        final String guessed = URLConnection.guessContentTypeFromName(file.getName());
        return guessed != null ? guessed : "application/octet-stream";
    }

    private static String sanitizeDisplayName(String name) {
        if (name == null || name.length() == 0) return "untitled";
        name = name.replace('/', '_').replace('\\', '_').replace('\0', '_');
        if (".".equals(name) || "..".equals(name)) return "untitled";
        return name;
    }

    private static File uniqueChild(File parent, String displayName, boolean directory) {
        File candidate = new File(parent, displayName);
        if (!candidate.exists()) return candidate;

        String base = displayName;
        String extension = "";
        if (!directory) {
            final int dot = displayName.lastIndexOf('.');
            if (dot > 0) {
                base = displayName.substring(0, dot);
                extension = displayName.substring(dot);
            }
        }
        for (int i = 1; i < 10000; ++i) {
            candidate = new File(parent, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(parent, base + "-" + System.currentTimeMillis() + extension);
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) if (!deleteRecursively(child)) return false;
            }
        }
        return file.delete();
    }

    private static FileNotFoundException fileNotFound(String message, Throwable cause) {
        final FileNotFoundException result = new FileNotFoundException(message);
        result.initCause(cause);
        return result;
    }

    private static void addRootColumn(MatrixCursor.RowBuilder row, MatrixCursor cursor,
                                      String column, Object value) {
        if (cursor.getColumnIndex(column) >= 0) row.add(column, value);
    }

    private static void addDocumentColumn(MatrixCursor.RowBuilder row, MatrixCursor cursor,
                                          String column, Object value) {
        if (cursor.getColumnIndex(column) >= 0) row.add(column, value);
    }
}
