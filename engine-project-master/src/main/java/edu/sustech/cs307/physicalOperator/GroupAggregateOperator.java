package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.NamedTempTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.SelectItem;

public class GroupAggregateOperator implements PhysicalOperator {
    private final PhysicalOperator child;
    private final List<SelectItem<?>> selectItems;
    private final List<Expression> groupByExpressions;

    private final ArrayList<Tuple> outputTuples = new ArrayList<>();
    private int cursor;
    private ArrayList<ColumnMeta> outputSchema;
    private Tuple currentTuple;

    public GroupAggregateOperator(PhysicalOperator child, List<SelectItem<?>> selectItems,
                                  List<Expression> groupByExpressions) {
        this.child = child;
        this.selectItems = new ArrayList<>(selectItems);
        this.groupByExpressions = new ArrayList<>(groupByExpressions);
        this.cursor = 0;
    }

    @Override
    public boolean hasNext() {
        return cursor < outputTuples.size();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
        outputTuples.clear();
        cursor = 0;
        currentTuple = null;

        LinkedHashMap<GroupKey, GroupState> groups = new LinkedHashMap<>();
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();
            if (tuple == null) {
                continue;
            }
            GroupKey key = buildGroupKey(tuple);
            GroupState state = groups.computeIfAbsent(key, k -> new GroupState(key.values));
            accumulate(state, tuple);
        }

        if (groups.isEmpty()) {
            outputSchema = buildOutputSchema(null);
            return;
        }

        outputSchema = buildOutputSchema(groups.values().iterator().next());
        for (GroupState state : groups.values()) {
            outputTuples.add(buildOutputTuple(state));
        }
    }

    @Override
    public void Next() {
        if (cursor >= outputTuples.size()) {
            currentTuple = null;
            return;
        }
        currentTuple = outputTuples.get(cursor);
        cursor++;
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        child.Close();
        outputTuples.clear();
        cursor = 0;
        currentTuple = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return outputSchema == null ? new ArrayList<>() : outputSchema;
    }

    private GroupKey buildGroupKey(Tuple tuple) throws DBException {
        ArrayList<Value> keyValues = new ArrayList<>();
        for (Expression expr : groupByExpressions) {
            keyValues.add(tuple.evaluateExpression(expr));
        }
        return new GroupKey(keyValues);
    }

    private void accumulate(GroupState state, Tuple tuple) throws DBException {
        for (int i = 0; i < selectItems.size(); i++) {
            Expression expr = selectItems.get(i).getExpression();
            if (expr instanceof Column) {
                if (state.results.size() <= i) {
                    state.results.add(tuple.evaluateExpression(expr));
                }
                continue;
            }

            if (!(expr instanceof Function function)) {
                throw new DBException(ExceptionTypes.NotSupportedOperation(expr));
            }

            String functionName = function.getName().toUpperCase();
            Expression aggregateExpr = getFunctionArgument(function);
            if (state.results.size() <= i) {
                state.results.add(initialAggregateValue(tuple, functionName, aggregateExpr));
                continue;
            }

            Value currentValue = state.results.get(i);
            Value tupleValue = "COUNT".equals(functionName) ? new Value(1L) : tuple.evaluateExpression(aggregateExpr);
            state.results.set(i, mergeAggregateValue(currentValue, tupleValue, functionName));
        }
    }

    private Value initialAggregateValue(Tuple tuple, String functionName, Expression aggregateExpr) throws DBException {
        return switch (functionName) {
            case "COUNT" -> new Value(1L);
            case "MAX", "MIN" -> tuple.evaluateExpression(aggregateExpr);
            default -> throw new DBException(ExceptionTypes.InvalidSQL(functionName, "Unsupported aggregate function"));
        };
    }

    private Value mergeAggregateValue(Value currentValue, Value tupleValue, String functionName) throws DBException {
        if (currentValue == null) {
            return tupleValue;
        }
        if (tupleValue == null) {
            return currentValue;
        }

        return switch (functionName) {
            case "COUNT" -> new Value((Long) currentValue.value + (Long) tupleValue.value);
            case "MAX" -> ValueComparer.compare(tupleValue, currentValue) > 0 ? tupleValue : currentValue;
            case "MIN" -> ValueComparer.compare(tupleValue, currentValue) < 0 ? tupleValue : currentValue;
            default -> throw new DBException(ExceptionTypes.InvalidSQL(functionName, "Unsupported aggregate function"));
        };
    }

    private NamedTempTuple buildOutputTuple(GroupState state) throws DBException {
        ArrayList<TabCol> schema = new ArrayList<>();
        ArrayList<Value> values = new ArrayList<>();

        int groupIndex = 0;
        for (int i = 0; i < selectItems.size(); i++) {
            Expression expr = selectItems.get(i).getExpression();
            String outputName = getOutputName(selectItems.get(i));
            schema.add(new TabCol("", outputName));
            if (expr instanceof Column) {
                values.add(state.groupValues.get(groupIndex++));
            } else {
                values.add(state.results.get(i));
            }
        }
        return new NamedTempTuple(schema, values);
    }

    private ArrayList<ColumnMeta> buildOutputSchema(GroupState sampleState) throws DBException {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        int groupIndex = 0;
        for (SelectItem<?> selectItem : selectItems) {
            Expression expr = selectItem.getExpression();
            String outputName = getOutputName(selectItem);
            ValueType valueType = ValueType.UNKNOWN;

            if (expr instanceof Column) {
                if (sampleState != null && sampleState.groupValues.size() > groupIndex && sampleState.groupValues.get(groupIndex) != null) {
                    valueType = sampleState.groupValues.get(groupIndex).type;
                }
                groupIndex++;
            } else if (expr instanceof Function function) {
                String functionName = function.getName().toUpperCase();
                if ("COUNT".equals(functionName)) {
                    valueType = ValueType.INTEGER;
                } else if (sampleState != null && sampleState.results.size() > schema.size() && sampleState.results.get(schema.size()) != null) {
                    valueType = sampleState.results.get(schema.size()).type;
                }
            }
            schema.add(new ColumnMeta("", outputName, valueType, 0, 0));
        }
        return schema;
    }

    private Expression getFunctionArgument(Function function) {
        if (function.isAllColumns() || function.getParameters() == null || function.getParameters().getExpressions().isEmpty()) {
            return null;
        }
        return function.getParameters().getExpressions().get(0);
    }

    private String getOutputName(SelectItem<?> selectItem) {
        if (selectItem.getAlias() != null && selectItem.getAlias().getName() != null) {
            return selectItem.getAlias().getName();
        }
        Expression expr = selectItem.getExpression();
        if (expr instanceof Column column) {
            return column.getColumnName();
        }
        return expr.toString();
    }

    private static class GroupState {
        private final ArrayList<Value> groupValues;
        private final ArrayList<Value> results = new ArrayList<>();

        private GroupState(List<Value> groupValues) {
            this.groupValues = new ArrayList<>(groupValues);
        }
    }

    private static class GroupKey {
        private final ArrayList<Value> values;

        private GroupKey(List<Value> values) {
            this.values = new ArrayList<>(values);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof GroupKey other) || values.size() != other.values.size()) {
                return false;
            }
            for (int i = 0; i < values.size(); i++) {
                if (!sameValue(values.get(i), other.values.get(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = 1;
            for (Value value : values) {
                Object raw = value == null ? null : value.value;
                result = 31 * result + (raw == null ? 0 : raw.hashCode());
            }
            return result;
        }

        private boolean sameValue(Value left, Value right) {
            if (left == null || right == null) {
                return left == right;
            }
            return left.type == right.type && left.value.equals(right.value);
        }
    }
}
