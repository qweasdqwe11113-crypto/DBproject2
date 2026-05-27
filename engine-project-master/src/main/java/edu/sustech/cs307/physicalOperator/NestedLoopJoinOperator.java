package edu.sustech.cs307.physicalOperator;

import java.util.ArrayList;
import java.util.Collection;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.tuple.JoinTuple;
import edu.sustech.cs307.tuple.Tuple;
import net.sf.jsqlparser.expression.Expression;

/**
 * 嵌套循环连接 (Nested-Loop Join).
 *
 * <p>实现等值/通用谓词的内连接, 算法为最朴素的双层循环:
 * <pre>
 *   for left_tuple in leftOperator:
 *       for right_tuple in rightOperator:   // 整个 right 在 Begin() 时一次性物化到 rightTuples
 *           if expr(joined(left, right)) all true:
 *               emit joined tuple
 * </pre>
 *
 * <p>实现细节:
 * <ul>
 *   <li>{@link #Begin()} 把右表全部 drain 到内存的 {@code rightTuples} 列表里,
 *       避免每条左行都重新打开右子算子.</li>
 *   <li>{@link #hasNext()} 内部维护游标 {@code rightCursor}, 一行一行地往前推进;
 *       右表用尽就推进左行并重置游标 (见 {@link #findNextMatch}).</li>
 *   <li>{@link JoinTuple} 把左右行拼接成一行供 WHERE/ON 表达式求值, schema 由
 *       {@link #buildOutputSchema} 在构造时按 "左列 + 右列" 顺序拼好.</li>
 * </ul>
 *
 * <p>复杂度 O(|left| × |right|); 后续可拓展为 Hash Join / Sort-Merge Join 以求优化.
 */
public class NestedLoopJoinOperator implements PhysicalOperator {

    private final PhysicalOperator leftOperator;
    private final PhysicalOperator rightOperator;
    private final Collection<Expression> expr;
    private final ArrayList<ColumnMeta> outputSchema;
    private final TabCol[] tupleSchema;
    private final ArrayList<Tuple> rightTuples;

    private Tuple leftTuple;
    private Tuple currentTuple;
    private Tuple nextTuple;
    private int rightCursor;
    private boolean isOpen;

    public NestedLoopJoinOperator(PhysicalOperator leftOperator, PhysicalOperator rightOperator,
            Collection<Expression> expr) {
        this.leftOperator = leftOperator;
        this.rightOperator = rightOperator;
        this.expr = expr;
        this.outputSchema = buildOutputSchema(leftOperator.outputSchema(), rightOperator.outputSchema());
        this.tupleSchema = buildTupleSchema(outputSchema);
        this.rightTuples = new ArrayList<>();
    }

    @Override
    public boolean hasNext() throws DBException {
        if (!isOpen) {
            return false;
        }
        if (nextTuple != null) {
            return true;
        }
        nextTuple = findNextMatch();
        return nextTuple != null;
    }

    @Override
    public void Begin() throws DBException {
        leftOperator.Begin();
        rightOperator.Begin();
        rightTuples.clear();
        while (rightOperator.hasNext()) {
            rightOperator.Next();
            Tuple tuple = rightOperator.Current();
            if (tuple != null) {
                rightTuples.add(tuple);
            }
        }
        leftTuple = null;
        currentTuple = null;
        nextTuple = null;
        rightCursor = 0;
        isOpen = true;
    }

    @Override
    public void Next() throws DBException {
        if (!hasNext()) {
            currentTuple = null;
            return;
        }
        currentTuple = nextTuple;
        nextTuple = null;
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        leftOperator.Close();
        rightOperator.Close();
        rightTuples.clear();
        leftTuple = null;
        currentTuple = null;
        nextTuple = null;
        rightCursor = 0;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return outputSchema;
    }

    private Tuple findNextMatch() throws DBException {
        while (true) {
            if (leftTuple == null) {
                if (!leftOperator.hasNext()) {
                    return null;
                }
                leftOperator.Next();
                leftTuple = leftOperator.Current();
                rightCursor = 0;
                if (leftTuple == null) {
                    continue;
                }
            }

            while (rightCursor < rightTuples.size()) {
                Tuple joinedTuple = new JoinTuple(leftTuple, rightTuples.get(rightCursor), tupleSchema);
                rightCursor++;
                if (matchesJoinCondition(joinedTuple)) {
                    return joinedTuple;
                }
            }

            leftTuple = null;
        }
    }

    private boolean matchesJoinCondition(Tuple joinedTuple) throws DBException {
        if (expr == null || expr.isEmpty()) {
            return true;
        }
        for (Expression expression : expr) {
            if (expression != null && !joinedTuple.eval_expr(expression)) {
                return false;
            }
        }
        return true;
    }

    private ArrayList<ColumnMeta> buildOutputSchema(ArrayList<ColumnMeta> leftSchema,
                                                     ArrayList<ColumnMeta> rightSchema) {
        ArrayList<ColumnMeta> schema = new ArrayList<>();
        schema.addAll(leftSchema);
        schema.addAll(rightSchema);
        return schema;
    }

    private TabCol[] buildTupleSchema(ArrayList<ColumnMeta> schema) {
        ArrayList<TabCol> tabCols = new ArrayList<>();
        for (ColumnMeta columnMeta : schema) {
            tabCols.add(new TabCol(columnMeta.tableName, columnMeta.name));
        }
        return tabCols.toArray(new TabCol[0]);
    }
}
