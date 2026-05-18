package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;


public class TransactionManager {

    private final DBManager dbManager;
    private Snapshot transactionSnapshot;
    private final List<SavepointSnapshot> savepoints = new ArrayList<>();


    public TransactionManager(DBManager dbManager) {
        this.dbManager = dbManager;
    }


    public void begin() throws DBException {
        if (isTransactionActive()) {
            throw new DBException(ExceptionTypes.TransactionAlreadyActive());
        }
        transactionSnapshot = createSnapshot();
        savepoints.clear();
    }


    public void commit() throws DBException {
        if (!isTransactionActive()) {
            return;
        }
        dbManager.persistRuntimeState();
        cleanupSnapshot(transactionSnapshot);
        for (SavepointSnapshot savepoint : savepoints) {
            cleanupSnapshot(savepoint.snapshot);
        }
        savepoints.clear();
        transactionSnapshot = null;
    }


    public void rollback() throws DBException {
        if (!isTransactionActive()) {
            return;
        }
        restoreSnapshot(transactionSnapshot);
        cleanupSnapshot(transactionSnapshot);
        for (SavepointSnapshot savepoint : savepoints) {
            cleanupSnapshot(savepoint.snapshot);
        }
        savepoints.clear();
        transactionSnapshot = null;
    }


    public void savepoint(String savepointName) throws DBException {
        requireTransaction();
        savepoints.add(new SavepointSnapshot(savepointName, createSnapshot()));
    }


    public void rollbackToSavepoint(String savepointName) throws DBException {
        requireTransaction();
        int index = findLatestSavepoint(savepointName);
        if (index < 0) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }

        restoreSnapshot(savepoints.get(index).snapshot);
        for (int i = savepoints.size() - 1; i > index; i--) {
            cleanupSnapshot(savepoints.remove(i).snapshot);
        }
    }


    public void releaseSavepoint(String savepointName) throws DBException {
        requireTransaction();
        int index = findLatestSavepoint(savepointName);
        if (index < 0) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        cleanupSnapshot(savepoints.remove(index).snapshot);
    }

    private Snapshot createSnapshot() throws DBException {
        dbManager.persistRuntimeState();
        Path snapshotDir;
        try {
            snapshotDir = Files.createTempDirectory("cs307-txn-");
            copyDirectoryContents(getDbRoot(), snapshotDir);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
        return new Snapshot(snapshotDir, new HashMap<>(dbManager.getDiskManager().filePages));
    }

    private void restoreSnapshot(Snapshot snapshot) throws DBException {
        try {
            clearDirectoryContents(getDbRoot());
            copyDirectoryContents(snapshot.path, getDbRoot());
            dbManager.getDiskManager().filePages.clear();
            dbManager.getDiskManager().filePages.putAll(snapshot.filePages);
            dbManager.getBufferPool().Clear();
            dbManager.getMetaManager().reloadFromJson();
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }

    private boolean isTransactionActive() {
        return transactionSnapshot != null;
    }

    private void requireTransaction() throws DBException {
        if (!isTransactionActive()) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }
    }

    private int findLatestSavepoint(String savepointName) {
        for (int i = savepoints.size() - 1; i >= 0; i--) {
            if (savepoints.get(i).name.equals(savepointName)) {
                return i;
            }
        }
        return -1;
    }

    private void cleanupSnapshot(Snapshot snapshot) throws DBException {
        if (snapshot == null) {
            return;
        }
        try {
            deleteDirectory(snapshot.path);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }

    private Path getDbRoot() {
        return Path.of(dbManager.getDiskManager().getCurrentDir());
    }

    private void copyDirectoryContents(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.exists(sourceRoot)) {
            Files.createDirectories(targetRoot);
            return;
        }
        Files.createDirectories(targetRoot);
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void clearDirectoryContents(Path root) throws IOException {
        if (!Files.exists(root)) {
            Files.createDirectories(root);
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void deleteDirectory(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Snapshot(Path path, Map<String, Integer> filePages) {
    }

    private record SavepointSnapshot(String name, Snapshot snapshot) {
    }
}
