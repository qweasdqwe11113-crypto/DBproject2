package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.index.BPlusTreeIndex;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 B+ 树索引的扫描算子.
 *
 * <p>在 {@link #Begin()} 时, 根据比较谓词 (= / &lt; / &lt;= / &gt; / &gt;=) 向索引查询命中的
 * {@link RID} 集合, 然后逐个按 RID 读出完整记录, 产出 {@link TableTuple}, 让上层
 * Project/Filter 仍可访问所有列. 相比全表扫描, 命中行少时显著更快.
 */
public class IndexScanOperator implements PhysicalOperator {

    private final DBManager dbManager;
    private final String tableName;
    private final TableMeta tableMeta;
    private final BPlusTreeIndex index;
    private final String operator;
    private final Value key;

    private RecordFileHandle fileHandle;
    private List<RID> rids;
    private int cursor;
    private Record currentRecord;
    private RID currentRid;
    private boolean isOpen = false;

    public IndexScanOperator(DBManager dbManager, String tableName, TableMeta tableMeta,
                             BPlusTreeIndex index, String operator, Value key) {
        this.dbManager = dbManager;
        this.tableName = tableName;
        this.tableMeta = tableMeta;
        this.index = index;
        this.operator = operator;
        this.key = key;
    }

    @Override
    public void Begin() throws DBException {
        fileHandle = dbManager.getRecordManager().OpenFile(tableName);
        rids = new ArrayList<>();
        switch (operator) {
            case "=" -> rids.addAll(index.searchAll(key));
            case "<" -> collect(index.rangeScan(null, false, key, false));
            case "<=" -> collect(index.rangeScan(null, false, key, true));
            case ">" -> collect(index.rangeScan(key, false, null, false));
            case ">=" -> collect(index.rangeScan(key, true, null, false));
            default -> throw new DBException(
                    edu.sustech.cs307.exception.ExceptionTypes.UnsupportedOperator("IndexScan " + operator));
        }
        cursor = 0;
        isOpen = true;
    }

    private void collect(List<Map.Entry<Value, RID>> entries) {
        for (Map.Entry<Value, RID> e : entries) {
            rids.add(e.getValue());
        }
    }

    @Override
    public boolean hasNext() {
        return isOpen && rids != null && cursor < rids.size();
    }

    @Override
    public void Next() {
        if (!isOpen || cursor >= rids.size()) {
            currentRecord = null;
            currentRid = null;
            return;
        }
        try {
            currentRid = rids.get(cursor++);
            currentRecord = fileHandle.GetRecord(currentRid);
        } catch (DBException e) {
            e.printStackTrace();
            currentRecord = null;
            currentRid = null;
        }
    }

    @Override
    public Tuple Current() {
        if (!isOpen || currentRecord == null) {
            return null;
        }
        return new TableTuple(tableName, tableMeta, currentRecord, currentRid);
    }

    @Override
    public void Close() {
        if (!isOpen) {
            return;
        }
        try {
            dbManager.getRecordManager().CloseFile(fileHandle);
        } catch (DBException e) {
            e.printStackTrace();
        }
        fileHandle = null;
        rids = null;
        currentRecord = null;
        currentRid = null;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return tableMeta.columns_list;
    }
}
