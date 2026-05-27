package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.OrderByElement;

/**
 * 排序算子 (ORDER BY).
 *
 * <p>实现是经典的 "sort by buffer":
 * <ol>
 *   <li>{@link #Begin()} 把子算子的全部行收到内存里 (ArrayList).</li>
 *   <li>用 jdk 自带的 TimSort + 自定义比较器排序;
 *       比较器按 ORDER BY 子句里的列依次比较, 遇到第一列分出胜负就停止.</li>
 *   <li>支持 ASC / DESC (默认 ASC), NULL 视为最小.</li>
 *   <li>每次 {@link #Next()} 把游标向前推一格, {@link #Current()} 返回该位置元组.</li>
 * </ol>
 *
 * <p>注意: 该算子是阻塞型 (pipeline breaker), 整个子树 drain 完才能产生第一行.
 */
public class OrderByOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<OrderByElement> orderByElements;

    private final ArrayList<Tuple> sortedTuples = new ArrayList<>();
    private int cursor;
    private Tuple currentTuple;

    public OrderByOperator(PhysicalOperator child, List<OrderByElement> orderByElements) {
        this.child = child;
        this.orderByElements = new ArrayList<>(orderByElements);
        this.cursor = 0;
    }

    @Override
    public boolean hasNext() {
        return cursor < sortedTuples.size();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        sortedTuples.clear();
        cursor = 0;
        currentTuple = null;

        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple != null) {
                sortedTuples.add(tuple);
            }
        }

        sortedTuples.sort(buildComparator());
    }

    @Override
    public void Next() {
        if (cursor >= sortedTuples.size()) {
            currentTuple = null;
            return;
        }
        currentTuple = sortedTuples.get(cursor);
        cursor++;
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        child.Close();
        sortedTuples.clear();
        cursor = 0;
        currentTuple = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return child.outputSchema();
    }

    private Comparator<Tuple> buildComparator() {
        return (left, right) -> {
            try {
                for (OrderByElement orderByElement : orderByElements) {
                    Value leftValue = resolveOrderValue(left, orderByElement.getExpression());
                    Value rightValue = resolveOrderValue(right, orderByElement.getExpression());
                    int cmp = compareNullable(leftValue, rightValue);
                    if (cmp != 0) {
                        boolean asc = !orderByElement.isAscDescPresent() || orderByElement.isAsc();
                        return asc ? cmp : -cmp;
                    }
                }
                return 0;
            } catch (DBException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private int compareNullable(Value left, Value right) throws DBException {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return ValueComparer.compare(left, right);
    }

    private Value resolveOrderValue(Tuple tuple, Expression expression) throws DBException {
        if (expression instanceof Column column) {
            String tableName = column.getTableName();
            if (tableName == null || tableName.isBlank()) {
                return resolveColumnValue(tuple, column.getColumnName());
            }
            return tuple.getValue(new TabCol(tableName, column.getColumnName()));
        }
        return tuple.evaluateExpression(expression);
    }

    private Value resolveColumnValue(Tuple tuple, String columnName) throws DBException {
        Value matched = null;
        for (TabCol tabCol : tuple.getTupleSchema()) {
            if (!tabCol.getColumnName().equalsIgnoreCase(columnName)) {
                continue;
            }
            Value value = tuple.getValue(tabCol);
            if (value == null) {
                continue;
            }
            if (matched != null) {
                throw new DBException(edu.sustech.cs307.exception.ExceptionTypes.InvalidSQL(
                        columnName, "Ambiguous column in order by"));
            }
            matched = value;
        }
        return matched;
    }
}
