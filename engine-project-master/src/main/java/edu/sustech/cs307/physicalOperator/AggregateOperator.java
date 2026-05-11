package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.TempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;

public class AggregateOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final String functionName;
    private final Expression aggregateExpr;

    private Value aggregateValue;
    private boolean resultReady;
    private boolean consumed;

    public AggregateOperator(PhysicalOperator child, String functionName, Expression aggregateExpr) {
        this.child = child;
        this.functionName = functionName == null ? "" : functionName.toUpperCase();
        this.aggregateExpr = aggregateExpr;
    }

    @Override
    public boolean hasNext() {
        return resultReady && !consumed;
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        aggregateValue = null;
        resultReady = false;
        consumed = false;

        switch (functionName) {
            case "COUNT" -> computeCount();
            case "MAX" -> computeMax();
            case "MIN" -> computeMin();
            default -> throw new DBException(ExceptionTypes.InvalidSQL(functionName, "Unsupported aggregate function"));
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
        values.add(aggregateValue);
        return new TempTuple(values);
    }

    @Override
    public void Close() {
        child.Close();
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.add(new ColumnMeta(functionName.toLowerCase(), functionName.toLowerCase(), getResultType(), 0, 0));
        return schema;
    }

    private void computeCount() throws DBException {
        long count = 0;
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) {
                continue;
            }
            if (aggregateExpr == null || isCountAllExpression(aggregateExpr) || tuple.evaluateExpression(aggregateExpr) != null) {
                count++;
            }
        }
        aggregateValue = new Value(count);
    }

    private void computeMax() throws DBException {
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) {
                continue;
            }
            Value currentValue = tuple.evaluateExpression(aggregateExpr);
            if (currentValue == null) {
                continue;
            }
            if (aggregateValue == null || ValueComparer.compare(currentValue, aggregateValue) > 0) {
                aggregateValue = currentValue;
            }
        }
        if (aggregateValue == null) {
            aggregateValue = defaultAggregateValue();
        }
    }

    private void computeMin() throws DBException {
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) {
                continue;
            }
            Value currentValue = tuple.evaluateExpression(aggregateExpr);
            if (currentValue == null) {
                continue;
            }
            if (aggregateValue == null || ValueComparer.compare(currentValue, aggregateValue) < 0) {
                aggregateValue = currentValue;
            }
        }
        if (aggregateValue == null) {
            aggregateValue = defaultAggregateValue();
        }
    }

    private Value defaultAggregateValue() {
        return switch (getResultType()) {
            case INTEGER -> new Value(0L);
            case FLOAT -> new Value(0.0);
            case CHAR -> new Value("");
            case UNKNOWN -> new Value(0L);
        };
    }

    private ValueType getResultType() {
        if ("COUNT".equals(functionName)) {
            return ValueType.INTEGER;
        }
        if (aggregateValue != null) {
            return aggregateValue.type;
        }
        return ValueType.INTEGER;
    }

    private boolean isCountAllExpression(Expression expr) {
        if (expr == null) {
            return true;
        }
        String exprText = expr.toString();
        if ("*".equals(exprText)) {
            return true;
        }
        return "AllValue".equals(expr.getClass().getSimpleName());
    }
}
