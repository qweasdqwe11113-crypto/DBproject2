package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

public class InsertOperator implements PhysicalOperator {
    private final String data_file;
    private final List<String> columnNames;
    private final List<Value> values;
    private final DBManager dbManager;
    private final int columnSize;
    private int rowCount;
    private boolean outputed;

    public InsertOperator(String data_file, List<String> columnNames, List<Value> values, DBManager dbManager) {
        this.data_file = data_file;
        this.columnNames = columnNames;
        this.values = values;
        this.dbManager = dbManager;
        this.columnSize = columnNames.size();
        this.rowCount = 0;
        this.outputed = false;
    }

    @Override
    public boolean hasNext() {
        return !this.outputed;
    }

    @Override
    public void Begin() throws DBException {
        try {
            var fileHandle = dbManager.getRecordManager().OpenFile(data_file);
            // 哪些被插入的列建有内存索引 (列名 -> 在本次 VALUES 中的位置), 仅这些列需要维护.
            Set<String> indexedColumns = dbManager.getIndexManager().indexedColumns(data_file);
            // 逐行序列化并插入, 拿到 RID 后对已构建的索引做增量维护.
            int totalRows = values.size() / columnSize;
            for (int row = 0; row < totalRows; row++) {
                ByteBuf buffer = Unpooled.buffer();
                for (int j = 0; j < columnSize; j++) {
                    buffer.writeBytes(values.get(row * columnSize + j).ToFixedByte());
                }
                var rid = fileHandle.InsertRecord(buffer);
                if (!indexedColumns.isEmpty()) {
                    Map<String, Value> indexedRow = new HashMap<>();
                    for (int j = 0; j < columnSize; j++) {
                        String col = columnNames.get(j);
                        if (indexedColumns.contains(col)) {
                            indexedRow.put(col, values.get(row * columnSize + j));
                        }
                    }
                    dbManager.getIndexManager().afterInsert(data_file, rid, indexedRow);
                }
            }
            this.rowCount = totalRows;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to insert record: " + e.getMessage() + "\n");
        }
    }

    @Override
    public void Next() {
    }

    @Override
    public Tuple Current() {
        ArrayList<Value> values = new ArrayList<>();
        values.add(new Value(rowCount, ValueType.INTEGER));
        this.outputed = true;
        return new TempTuple(values);
    }

    @Override
    public void Close() {
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> outputSchema = new ArrayList<>();
        outputSchema.add(new ColumnMeta("insert", "numberOfInsertRows", ValueType.INTEGER, 0, 0));
        return outputSchema;
    }

    public void reset() {
        // nothing to do
    }

    public Tuple getNextTuple() {
        return null;
    }
}
