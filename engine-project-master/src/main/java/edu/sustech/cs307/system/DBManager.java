package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.storage.replacer.ClockReplacer;
import edu.sustech.cs307.storage.replacer.PageReplacer;
import org.apache.commons.lang3.StringUtils;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.function.IntFunction;

public class DBManager {
    private final MetaManager metaManager;
    /* --- --- --- */
    private final DiskManager diskManager;
    private final BufferPool bufferPool;
    private final RecordManager recordManager;
    private TransactionManager transactionManager;
    private final IntFunction<PageReplacer> replacerFactory;

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager) {
        this(diskManager, bufferPool, recordManager, metaManager, null, ClockReplacer::new);
    }

    public DBManager(DiskManager diskManager, BufferPool bufferPool, RecordManager recordManager,
                     MetaManager metaManager, TransactionManager transactionManager,
                     IntFunction<PageReplacer> replacerFactory) {
        this.diskManager = diskManager;
        this.bufferPool = bufferPool;
        this.recordManager = recordManager;
        this.metaManager = metaManager;
        this.replacerFactory = replacerFactory;
        this.transactionManager = transactionManager == null ? new TransactionManager(this) : transactionManager;
    }

    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public BufferPool getBufferPool() {
        return bufferPool;
    }

    public RecordManager getRecordManager() {
        return recordManager;
    }

    public DiskManager getDiskManager() {
        return diskManager;
    }

    public MetaManager getMetaManager() {
        return metaManager;
    }

    public boolean isDirExists(String dir) {
        File file = new File(dir);
        return file.exists() && file.isDirectory();
    }

    /**
     * Displays a formatted table listing all available tables in the database.
     * The output is presented in a bordered ASCII table format with centered table
     * names.
     * Each table name is displayed in a separate row within the ASCII borders.
     */
    public void showTables() {
        ArrayList<String> tableNames = new ArrayList<>(metaManager.getTableNames());
        tableNames.sort(String::compareTo);

        int contentWidth = "TABLE".length();
        for (String tableName : tableNames) {
            contentWidth = Math.max(contentWidth, tableName.length());
        }
        contentWidth += 2;

        String border = "|" + StringUtils.repeat("-", contentWidth) + "|";
        Logger.info(border);
        Logger.info("|{}|", StringUtils.center("TABLE", contentWidth, ' '));
        Logger.info(border);
        for (String tableName : tableNames) {
            Logger.info("|{}|", StringUtils.center(tableName, contentWidth, ' '));
        }
        Logger.info(border);
    }

    public void descTable(String table_name) throws DBException {
        TableMeta tableMeta = metaManager.getTable(table_name);
        ArrayList<ColumnMeta> columns = new ArrayList<>(tableMeta.columns_list);
        columns.sort((left, right) -> Integer.compare(left.offset, right.offset));

        String fieldHeader = "TABLE Field";
        String typeHeader = "Column Type";
        int fieldWidth = fieldHeader.length();
        int typeWidth = typeHeader.length();

        for (ColumnMeta column : columns) {
            fieldWidth = Math.max(fieldWidth, column.name.length());
            typeWidth = Math.max(typeWidth, column.type.toString().length());
        }

        fieldWidth += 2;
        typeWidth += 2;

        String border = "|" + StringUtils.repeat("-", fieldWidth) + "|" + StringUtils.repeat("-", typeWidth) + "|";
        Logger.info(border);
        Logger.info("|{}|{}|",
                StringUtils.center(fieldHeader, fieldWidth, ' '),
                StringUtils.center(typeHeader, typeWidth, ' '));
        Logger.info(border);
        for (ColumnMeta column : columns) {
            Logger.info("|{}|{}|",
                    StringUtils.center(column.name, fieldWidth, ' '),
                    StringUtils.center(column.type.toString(), typeWidth, ' '));
        }
        Logger.info(border);
    }

    /**
     * Creates a new table in the database with specified name and column metadata.
     * This method sets up both the table metadata and the physical storage
     * structure.
     *
     * @param table_name The name of the table to be created
     * @param columns    List of column metadata defining the table structure
     * @throws DBException If there is an error during table creation
     */
    public void createTable(String table_name, ArrayList<ColumnMeta> columns) throws DBException {
        TableMeta tableMeta = new TableMeta(
                table_name, columns);
        metaManager.createTable(tableMeta);
        String table_folder = String.format("%s/%s", diskManager.getCurrentDir(), table_name);
        File file_folder = new File(table_folder);
        if (!file_folder.exists()) {
            file_folder.mkdirs();
        }
        int record_size = 0;
        for (var col : columns) {
            record_size += col.len;
        }
        String data_file = String.format("%s/%s", table_name, "data");
        recordManager.CreateFile(data_file, record_size);
    }

    /**
     * Drops a table from the database by removing its metadata and associated
     * files.
     *
     * @param table_name The name of the table to be dropped
     * @throws DBException If the table directory does not exist or encounters IO
     *                     errors during deletion
     */
    public void dropTable(String table_name) throws DBException {
        if (!isTableExists(table_name)) {
            Logger.warn("Table does not exist: {}", table_name);
            return;
        }

        String tableFolder = String.format("%s/%s", diskManager.getCurrentDir(), table_name);
        File tableDir = new File(tableFolder);
        if (tableDir.exists()) {
            deleteDirectory(tableDir);
        } else {
            Logger.warn("Table directory does not exist: {}", tableDir.getAbsolutePath());
        }

        metaManager.dropTable(table_name);
        Logger.info("Successfully dropped table: {}", table_name);
    }

    /**
     * Recursively deletes a directory and all its contents.
     * If the given file is a directory, it first deletes all its entries
     * recursively.
     * Finally deletes the file/directory itself.
     *
     * @param file The file or directory to be deleted
     * @throws IOException If deletion of any file or directory fails
     */
    private void deleteDirectory(File file) throws DBException {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteDirectory(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new DBException(ExceptionTypes.BadIOError("File deletion failed: " + file.getAbsolutePath()));
        }
    }

    /**
     * Checks if a table exists in the database.
     *
     * @param table the name of the table to check
     * @return true if the table exists, false otherwise
     */
    public boolean isTableExists(String table) {
        return metaManager.getTableNames().contains(table);
    }

    /**
     * Closes the database manager and performs cleanup operations.
     * This method flushes all pages in the buffer pool, dumps disk manager
     * metadata,
     * and saves meta manager state to JSON format.
     *
     * @throws DBException if an error occurs during the closing process
     */
    public void closeDBManager() throws DBException {
        this.bufferPool.FlushAllPages(null);
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }

    public void beginTransaction() throws DBException {
        transactionManager.begin();
    }

    public void commitTransaction() throws DBException{
        transactionManager.commit();
    }

    public void persistRuntimeState() throws DBException {
        this.bufferPool.FlushAllPages("");
        DiskManager.dump_disk_manager_meta(this.diskManager);
        this.metaManager.saveToJson();
    }
}
