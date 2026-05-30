package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.index.BPlusTreeIndex;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.physicalOperator.SeqScanOperator;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.value.Value;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 运行期索引注册表.
 *
 * <p>持有当前进程内已经构建好的 B+ 树, 以 (表名 -> (列名 -> 索引)) 组织, 天然支持
 * "一张表上多个索引 / 多棵 B+ 树" 与 "多张表各自的索引".
 *
 * <p>设计要点:
 * <ul>
 *   <li>B+ 树是<b>内存</b>结构, 不落盘. 持久化的只有 {@link TableMeta} 里的索引声明 (写进 JSON).
 *       重启后第一次用到某索引时, 由 {@link #getOrBuildIndex} 扫描表数据惰性重建.</li>
 *   <li>{@code create index} 立即构建并打印整棵树; {@code drop index} 从注册表与元数据中移除.</li>
 *   <li>insert/delete/update 通过 {@link #afterInsert}/{@link #afterDelete}/{@link #afterUpdate}
 *       对<b>已经在内存中</b>的树做增量维护; 尚未构建的索引无需维护, 之后惰性重建即可拿到最新数据.</li>
 * </ul>
 */
public class IndexManager {

    private final Map<String, Map<String, BPlusTreeIndex>> indexes = new HashMap<>();

    public BPlusTreeIndex getIndex(String table, String column) {
        Map<String, BPlusTreeIndex> byCol = indexes.get(table);
        return byCol == null ? null : byCol.get(column);
    }

    public Set<String> indexedColumns(String table) {
        Map<String, BPlusTreeIndex> byCol = indexes.get(table);
        return byCol == null ? Collections.emptySet() : byCol.keySet();
    }

    /** 扫描全表数据, 构建 (或重建) table.column 上的 B+ 树, 注册并返回. */
    public BPlusTreeIndex buildIndex(DBManager dbManager, String table, String column) throws DBException {
        TableMeta meta = dbManager.getMetaManager().getTable(table);
        ColumnMeta columnMeta = meta.getColumnMeta(column);
        if (columnMeta == null) {
            throw new DBException(
                    edu.sustech.cs307.exception.ExceptionTypes.ColumnDoesNotExist(column));
        }
        BPlusTreeIndex index = new BPlusTreeIndex(table, column, columnMeta.type);
        TabCol tabCol = new TabCol(table, column);

        SeqScanOperator scan = new SeqScanOperator(table, dbManager);
        scan.Begin();
        try {
            while (scan.hasNext()) {
                scan.Next();
                TableTuple tuple = (TableTuple) scan.Current();
                if (tuple == null) {
                    continue;
                }
                Value value = tuple.getValue(tabCol);
                if (value != null) {
                    index.insert(value, new RID(tuple.getRID()));
                }
            }
        } finally {
            scan.Close();
        }
        indexes.computeIfAbsent(table, k -> new HashMap<>()).put(column, index);
        return index;
    }

    /** 若内存中已有则直接返回; 否则只有当元数据声明了该列索引时才惰性构建. */
    public BPlusTreeIndex getOrBuildIndex(DBManager dbManager, String table, String column) throws DBException {
        BPlusTreeIndex existing = getIndex(table, column);
        if (existing != null) {
            return existing;
        }
        TableMeta meta;
        try {
            meta = dbManager.getMetaManager().getTable(table);
        } catch (DBException e) {
            return null;
        }
        if (!meta.hasIndexOnColumn(column)) {
            return null;
        }
        return buildIndex(dbManager, table, column);
    }

    public void removeColumnIndex(String table, String column) {
        Map<String, BPlusTreeIndex> byCol = indexes.get(table);
        if (byCol != null) {
            byCol.remove(column);
            if (byCol.isEmpty()) {
                indexes.remove(table);
            }
        }
    }

    public void dropTable(String table) {
        indexes.remove(table);
    }

    // ------------------------- 增量维护 -------------------------

    public void afterInsert(String table, RID rid, Map<String, Value> row) {
        Map<String, BPlusTreeIndex> byCol = indexes.get(table);
        if (byCol == null) {
            return;
        }
        for (Map.Entry<String, BPlusTreeIndex> e : byCol.entrySet()) {
            Value v = row.get(e.getKey());
            if (v != null) {
                e.getValue().insert(v, new RID(rid));
            }
        }
    }

    public void afterDelete(String table, RID rid, Map<String, Value> row) {
        Map<String, BPlusTreeIndex> byCol = indexes.get(table);
        if (byCol == null) {
            return;
        }
        for (Map.Entry<String, BPlusTreeIndex> e : byCol.entrySet()) {
            Value v = row.get(e.getKey());
            if (v != null) {
                e.getValue().delete(v, rid);
            }
        }
    }

    public void afterUpdate(String table, RID rid, Map<String, Value> oldRow, Map<String, Value> newRow) {
        Map<String, BPlusTreeIndex> byCol = indexes.get(table);
        if (byCol == null) {
            return;
        }
        for (Map.Entry<String, BPlusTreeIndex> e : byCol.entrySet()) {
            String col = e.getKey();
            Value ov = oldRow.get(col);
            Value nv = newRow.get(col);
            if (ov != null) {
                e.getValue().delete(ov, rid);
            }
            if (nv != null) {
                e.getValue().insert(nv, new RID(rid));
            }
        }
    }
}
