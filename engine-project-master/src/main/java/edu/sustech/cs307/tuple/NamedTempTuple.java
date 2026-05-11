package edu.sustech.cs307.tuple;

import java.util.ArrayList;
import java.util.List;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;

public class NamedTempTuple extends Tuple {
    private final List<TabCol> schema;
    private final List<Value> values;

    public NamedTempTuple(List<TabCol> schema, List<Value> values) {
        this.schema = new ArrayList<>(schema);
        this.values = new ArrayList<>(values);
    }

    @Override
    public Value getValue(TabCol tabCol) throws DBException {
        for (int i = 0; i < schema.size(); i++) {
            TabCol current = schema.get(i);
            boolean columnMatches = current.getColumnName().equalsIgnoreCase(tabCol.getColumnName());
            boolean tableMatches = tabCol.getTableName() == null
                    || tabCol.getTableName().isBlank()
                    || current.getTableName().equalsIgnoreCase(tabCol.getTableName());
            if (columnMatches && tableMatches) {
                return values.get(i);
            }
        }
        return null;
    }

    @Override
    public TabCol[] getTupleSchema() {
        return schema.toArray(new TabCol[0]);
    }

    @Override
    public Value[] getValues() throws DBException {
        return values.toArray(new Value[0]);
    }
}
