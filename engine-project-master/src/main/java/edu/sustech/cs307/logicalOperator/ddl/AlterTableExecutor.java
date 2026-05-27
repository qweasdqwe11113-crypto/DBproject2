package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 处理 ALTER TABLE 语句的执行器, 支持以下子句:
 *
 * <ul>
 *   <li>{@code ALTER TABLE t ADD COLUMN c TYPE}           - 在表末尾追加一列</li>
 *   <li>{@code ALTER TABLE t DROP COLUMN c}               - 删除指定列</li>
 *   <li>{@code ALTER TABLE t RENAME COLUMN c TO c2}       - 重命名列 (元数据级)</li>
 *   <li>{@code ALTER TABLE t RENAME TO t2}                - 重命名表 (同时迁移数据目录)</li>
 * </ul>
 *
 * <p>实现说明:
 * <ul>
 *   <li>这是一个教学级 DBMS, 我们不重写已有数据页, ADD/DROP 都只改元数据:
 *       <ul>
 *         <li>ADD COLUMN 把新列加到 columns / columns_list 的末尾, offset 设为
 *             当前 record 末尾, 旧记录在新列对应字节区域保持 0 (相当于默认值).</li>
 *         <li>DROP COLUMN 从元数据里移除, 旧记录字节并不被搬移, 因此真实占用空间
 *             不会减少, 但是查询/投影都不会再看到这列.</li>
 *       </ul>
 *   </li>
 *   <li>RENAME COLUMN / RENAME TABLE 都仅修改 in-memory + JSON 元数据, 同时
 *       把磁盘上 currentDir/&lt;tableName&gt;/ 目录改名以匹配新表名.</li>
 *   <li>每次执行完成后立刻调用 {@code MetaManager.saveToJson()}, 让 DDL 持久化,
 *       避免 commit/exit 之前掉电导致 schema 漂移.</li>
 * </ul>
 */
public class AlterTableExecutor implements DMLExecutor {

    private final Alter alterStmt;
    private final DBManager dbManager;

    public AlterTableExecutor(Alter alterStmt, DBManager dbManager) {
        this.alterStmt = alterStmt;
        this.dbManager = dbManager;
    }

    @Override
    public void execute() throws DBException {
        String tableName = alterStmt.getTable().getName();
        if (!dbManager.isTableExists(tableName)) {
            throw new DBException(ExceptionTypes.TableDoesNotExist(tableName));
        }
        TableMeta meta = dbManager.getMetaManager().getTable(tableName);

        // 一条 ALTER TABLE 语句可以包含多个 AlterExpression, 例如:
        //   ALTER TABLE t ADD COLUMN c1 INT, DROP COLUMN c2;
        // 这里按顺序逐个应用.
        if (alterStmt.getAlterExpressions() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand("ALTER TABLE (empty)"));
        }
        for (AlterExpression ae : alterStmt.getAlterExpressions()) {
            applyOne(meta, ae);
        }

        // 元数据持久化, 让重启后 schema 一致.
        dbManager.getMetaManager().saveToJson();
        Logger.info("Successfully altered table: {}", meta.tableName);
    }

    /**
     * 单个 AlterExpression 的分派函数.
     */
    private void applyOne(TableMeta meta, AlterExpression ae) throws DBException {
        switch (ae.getOperation()) {
            case ADD -> doAddColumn(meta, ae);
            case DROP -> doDropColumn(meta, ae);
            case RENAME -> doRenameColumn(meta, ae);
            case RENAME_TABLE -> doRenameTable(meta, ae);
            default -> throw new DBException(ExceptionTypes.UnsupportedCommand(
                    "ALTER TABLE: unsupported operation " + ae.getOperation()));
        }
    }

    // -------------------------------------------------------------------
    // ADD COLUMN
    // -------------------------------------------------------------------

    /**
     * ADD COLUMN: 把 ColDataTypeList 中的每一列追加到 TableMeta 末尾,
     * 同时维护 column_name -> ColumnMeta 的映射 (TableMeta.columns) 和
     * 显示顺序列表 (TableMeta.columns_list).
     *
     * 新列在已存在记录中的字节区域保持为 0, 等价于默认值.
     */
    private void doAddColumn(TableMeta meta, AlterExpression ae) throws DBException {
        if (ae.getColDataTypeList() == null || ae.getColDataTypeList().isEmpty()) {
            throw new DBException(ExceptionTypes.UnsupportedCommand("ALTER TABLE ADD COLUMN: missing column"));
        }
        // 新列的 offset 从当前 record 末尾开始算起.
        int offset = currentRecordSize(meta);
        for (AlterExpression.ColumnDataType cdt : ae.getColDataTypeList()) {
            String colName = cdt.getColumnName();
            if (meta.hasColumn(colName)) {
                throw new DBException(ExceptionTypes.ColumnAlreadyExist(colName));
            }
            ColumnMeta col = buildColumnMeta(meta.tableName, colName, cdt.getColDataType(), offset);
            meta.addColumn(col);
            meta.columns_list.add(col);
            offset += col.len;
            Logger.info("ADD COLUMN {}.{} (type={}, len={}, offset={})",
                    meta.tableName, colName, col.type, col.len, col.offset);
        }
    }

    /** 把 jsqlparser 的 ColDataType 翻译成内部 ColumnMeta. */
    private ColumnMeta buildColumnMeta(String tableName, String colName, ColDataType cdt, int offset)
            throws DBException {
        String type = cdt.getDataType().toLowerCase();
        return switch (type) {
            case "char", "varchar" -> new ColumnMeta(tableName, colName, ValueType.CHAR, Value.CHAR_SIZE, offset);
            case "int", "integer", "bigint" -> new ColumnMeta(tableName, colName, ValueType.INTEGER, Value.INT_SIZE, offset);
            case "float", "double", "real", "decimal" -> new ColumnMeta(tableName, colName, ValueType.FLOAT, Value.FLOAT_SIZE, offset);
            default -> throw new DBException(ExceptionTypes.UnsupportedCommand("Unsupported column type: " + type));
        };
    }

    private int currentRecordSize(TableMeta meta) {
        int sum = 0;
        for (ColumnMeta cm : meta.columns_list) {
            sum += cm.len;
        }
        return sum;
    }

    // -------------------------------------------------------------------
    // DROP COLUMN
    // -------------------------------------------------------------------

    /**
     * DROP COLUMN: 从 columns / columns_list 中删掉对应列.
     * 注意: 我们不重写已有数据页, 那段字节会被遗弃, 后续查询不再读取.
     */
    private void doDropColumn(TableMeta meta, AlterExpression ae) throws DBException {
        String colName = ae.getColumnName();
        if (colName == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand("ALTER TABLE DROP COLUMN: missing column"));
        }
        if (!meta.hasColumn(colName)) {
            throw new DBException(ExceptionTypes.ColumnDoesNotExist(colName));
        }
        // 1) 哈希映射里移除
        meta.dropColumn(colName);
        // 2) 顺序列表里同步移除 (TableMeta.dropColumn 只动 columns Map, 不动 columns_list)
        Iterator<ColumnMeta> it = meta.columns_list.iterator();
        while (it.hasNext()) {
            if (it.next().name.equals(colName)) {
                it.remove();
                break;
            }
        }
        Logger.info("DROP COLUMN {}.{}", meta.tableName, colName);
    }

    // -------------------------------------------------------------------
    // RENAME COLUMN
    // -------------------------------------------------------------------

    /**
     * RENAME COLUMN c TO c2: 仅修改元数据中的列名, offset/len/type 保持不变,
     * 因此磁盘上已有的记录无需重写.
     */
    private void doRenameColumn(TableMeta meta, AlterExpression ae) throws DBException {
        String oldName = ae.getColumnOldName();
        String newName = ae.getColumnName();
        if (oldName == null || newName == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand("ALTER TABLE RENAME COLUMN: missing names"));
        }
        if (!meta.hasColumn(oldName)) {
            throw new DBException(ExceptionTypes.ColumnDoesNotExist(oldName));
        }
        if (meta.hasColumn(newName)) {
            throw new DBException(ExceptionTypes.ColumnAlreadyExist(newName));
        }

        // 1) 更新 columns Map
        ColumnMeta old = meta.columns.remove(oldName);
        ColumnMeta renamed = new ColumnMeta(meta.tableName, newName, old.type, old.len, old.offset);
        meta.columns.put(newName, renamed);

        // 2) 同步 columns_list (保持显示顺序)
        for (int i = 0; i < meta.columns_list.size(); i++) {
            if (meta.columns_list.get(i).name.equals(oldName)) {
                meta.columns_list.set(i, renamed);
                break;
            }
        }
        Logger.info("RENAME COLUMN {}.{} -> {}.{}", meta.tableName, oldName, meta.tableName, newName);
    }

    // -------------------------------------------------------------------
    // RENAME TABLE
    // -------------------------------------------------------------------

    /**
     * RENAME TABLE t TO t2: 同时修改元数据 (MetaManager.tables Map 的 key,
     * 以及每个 ColumnMeta.tableName) 以及磁盘目录 (currentDir/&lt;t&gt;/ -> currentDir/&lt;t2&gt;/).
     *
     * 缓冲池中如有该表的脏页, 应先 flush, 否则改名后 PagePosition.filename 将无法回写.
     */
    private void doRenameTable(TableMeta meta, AlterExpression ae) throws DBException {
        String oldName = meta.tableName;
        String newName = ae.getNewTableName();
        if (newName == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand("ALTER TABLE RENAME TO: missing new name"));
        }
        if (dbManager.isTableExists(newName)) {
            throw new DBException(ExceptionTypes.TableAlreadyExist(newName));
        }

        // 1) flush 该表所有缓冲页, 避免改名后回写到旧路径
        dbManager.getBufferPool().FlushAllPages(oldName + "/data");

        // 2) 修改 MetaManager 的 tables 映射 key
        Map<String, TableMeta> tableMap = peekTableMap();
        TableMeta moved = tableMap.remove(oldName);
        moved.tableName = newName;
        ArrayList<ColumnMeta> rebuiltList = new ArrayList<>();
        HashMap<String, ColumnMeta> rebuiltMap = new HashMap<>();
        for (ColumnMeta cm : moved.columns_list) {
            ColumnMeta nc = new ColumnMeta(newName, cm.name, cm.type, cm.len, cm.offset);
            rebuiltList.add(nc);
            rebuiltMap.put(nc.name, nc);
        }
        moved.columns_list = rebuiltList;
        moved.setColumns(rebuiltMap);
        tableMap.put(newName, moved);

        // 3) 改 disk_manager_meta 中的文件键
        renameFileKey(oldName + "/data", newName + "/data");

        // 4) 物理目录改名
        String dir = dbManager.getDiskManager().getCurrentDir();
        File oldDir = new File(dir + "/" + oldName);
        File newDir = new File(dir + "/" + newName);
        if (oldDir.exists() && !oldDir.renameTo(newDir)) {
            throw new DBException(ExceptionTypes.BadIOError(
                    "Failed to rename directory: " + oldDir + " -> " + newDir));
        }
        Logger.info("RENAME TABLE {} -> {}", oldName, newName);
    }

    /**
     * 通过反射拿到 MetaManager 内部 tables Map 的引用. MetaManager 没有公开
     * 替换/重命名 key 的 API, 这里采用最小侵入的方式而不修改 MetaManager.
     */
    @SuppressWarnings("unchecked")
    private Map<String, TableMeta> peekTableMap() throws DBException {
        try {
            java.lang.reflect.Field f = dbManager.getMetaManager().getClass().getDeclaredField("tables");
            f.setAccessible(true);
            return (Map<String, TableMeta>) f.get(dbManager.getMetaManager());
        } catch (ReflectiveOperationException e) {
            throw new DBException(ExceptionTypes.BadIOError("Cannot access MetaManager.tables: " + e.getMessage()));
        }
    }

    /**
     * 更新 DiskManager.filePages 中文件键的名字, 保留 pageCount 不变.
     */
    private void renameFileKey(String oldKey, String newKey) {
        Map<String, Integer> map = dbManager.getDiskManager().filePages;
        if (map.containsKey(oldKey)) {
            Integer count = map.remove(oldKey);
            map.put(newKey, count);
        }
    }
}
