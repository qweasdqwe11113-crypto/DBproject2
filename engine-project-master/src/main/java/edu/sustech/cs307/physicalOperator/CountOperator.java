package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;

public class CountOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final Expression countExpr;

    private long count;
    private boolean resultReady;
    private boolean consumed;

    public CountOperator(PhysicalOperator child, Expression countExpr) {
        this.child = child;
        this.countExpr = countExpr;
        this.count = 0;
        this.resultReady = false;
        this.consumed = false;
    }

    @Override
    public boolean hasNext() {
        return resultReady && !consumed;
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        count = 0;
        resultReady = false;
        consumed = false;

        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) {
                continue;
            }
            if (shouldCountTuple(tuple)) {
                count++;
            }
        }
        resultReady = true;
    }

    @Override
    public void Next() {
        consumed = true;
    }

    @Override
    public Tuple Current() {
        ArrayList<Value> values = new ArrayList<>();
        values.add(new Value(count));
        return new TempTuple(values);
    }

    @Override
    public void Close() {
        child.Close();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta("count", "count", ValueType.INTEGER, 0, 0));
        return schema;
    }

    private boolean shouldCountTuple(Tuple tuple) throws DBException {
        if (countExpr == null || isCountAllExpression(countExpr)) {
            return true;
        }
        return tuple.evaluateExpression(countExpr) != null;
    }

    private boolean isCountAllExpression(Expression expr) {
        if (expr == null) {
            return true;
        }
        String exprText = expr.toString();
        if ("*".equals(exprText)) {
            return true;
        }
        // JSqlParser 在不同版本中可能用 AllValue 表示 COUNT(*)
        return "AllValue".equals(expr.getClass().getSimpleName());
    }
}
