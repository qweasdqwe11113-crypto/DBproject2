package edu.sustech.cs307.tuple;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.TabCol;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueComparer;
import edu.sustech.cs307.value.ValueType;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;

/**
 * 元组的抽象基类. 子类 (TableTuple / TempTuple / JoinTuple / ProjectTuple / NamedTempTuple)
 * 各自实现 {@link #getValue(TabCol)} / {@link #getValues()} / {@link #getTupleSchema()},
 * 这里则统一提供了 WHERE 表达式求值 ({@link #eval_expr}) 与单个表达式求值
 * ({@link #evaluateExpression}) 的实现, 供过滤/连接/聚合/排序算子共用.
 *
 * <p>支持的表达式类型: 括号、AND/OR、BETWEEN、IN (字面量列表)、二元比较 (=, !=, <>, >, >=, <, <=).
 * 子查询型 IN/EXISTS 已在 LogicalPlanner.rewriteSubqueryPredicates 阶段被消除,
 * 走到这里的都是普通表达式.
 */
public abstract class Tuple {
    /** 根据 (表名, 列名) 取一个字段的值. 子类按自己的存储形式实现. */
    public abstract Value getValue(TabCol tabCol) throws DBException;

    /** 当前元组的 schema (字段顺序与 getValues() 一致). */
    public abstract TabCol[] getTupleSchema();

    /** 按 schema 顺序返回所有字段的值, 用于打印/序列化/聚合. */
    public abstract Value[] getValues() throws DBException;

    /**
     * 把表达式当布尔条件求值: 例如 WHERE a > 3 AND b = 'x' 用在 FilterOperator 里.
     * 内部分派给 {@link #evaluateCondition}, 支持递归.
     */
    public boolean eval_expr(Expression expr) throws DBException {
        return evaluateCondition(this, expr);
    }

    /**
     * 表达式 -> 布尔的递归求值. 按 AST 结构逐层下钻:
     *   Parenthesis  -> 透传
     *   AND/OR       -> 短路求值左右子表达式
     *   BETWEEN      -> low <= expr <= high (考虑 NOT BETWEEN)
     *   InExpression -> 左值与右侧字面量列表逐个比较 (考虑 NOT IN)
     *   BinaryExpression -> 走 evaluateBinaryExpression
     */
    private boolean evaluateCondition(Tuple tuple, Expression whereExpr) throws DBException {
        if (whereExpr == null) {
            return true;
        }
        if (whereExpr instanceof Parenthesis parenthesis) {
            return evaluateCondition(tuple, parenthesis.getExpression());
        }
        if (whereExpr instanceof AndExpression andExpr) {
            return evaluateCondition(tuple, andExpr.getLeftExpression())
                    && evaluateCondition(tuple, andExpr.getRightExpression());
        } else if (whereExpr instanceof OrExpression orExpr) {
            return evaluateCondition(tuple, orExpr.getLeftExpression())
                    || evaluateCondition(tuple, orExpr.getRightExpression());
        } else if (whereExpr instanceof Between betweenExpr) {
            return evaluateBetweenExpression(tuple, betweenExpr);
        } else if (whereExpr instanceof InExpression inExpr) {
            return evaluateInExpression(tuple, inExpr);
        } else if (whereExpr instanceof BinaryExpression binaryExpression) {
            return evaluateBinaryExpression(tuple, binaryExpression);
        } else {
            throw new DBException(ExceptionTypes.UnsupportedExpression(whereExpr));
        }
    }

    private boolean evaluateBinaryExpression(Tuple tuple, BinaryExpression binaryExpr) throws DBException {
        Value leftValue = resolveExpressionValue(tuple, binaryExpr.getLeftExpression());
        Value rightValue = resolveExpressionValue(tuple, binaryExpr.getRightExpression());
        if (leftValue == null || rightValue == null) {
            return false;
        }

        int comparisonResult = ValueComparer.compare(leftValue, rightValue);
        String operator = binaryExpr.getStringExpression();
        if (binaryExpr instanceof NotEqualsTo || "<>".equals(operator) || "!=".equals(operator)) {
            return comparisonResult != 0;
        }
        if ("=".equals(operator)) {
            return comparisonResult == 0;
        }
        if (">".equals(operator)) {
            return comparisonResult > 0;
        }
        if (">=".equals(operator)) {
            return comparisonResult >= 0;
        }
        if ("<".equals(operator)) {
            return comparisonResult < 0;
        }
        if ("<=".equals(operator)) {
            return comparisonResult <= 0;
        }
        throw new DBException(ExceptionTypes.UnsupportedExpression(binaryExpr));
    }

    private boolean evaluateBetweenExpression(Tuple tuple, Between betweenExpr) throws DBException {
        Value leftValue = resolveExpressionValue(tuple, betweenExpr.getLeftExpression());
        Value startValue = resolveExpressionValue(tuple, betweenExpr.getBetweenExpressionStart());
        Value endValue = resolveExpressionValue(tuple, betweenExpr.getBetweenExpressionEnd());
        if (leftValue == null || startValue == null || endValue == null) {
            return false;
        }
        boolean inRange = ValueComparer.compare(leftValue, startValue) >= 0
                && ValueComparer.compare(leftValue, endValue) <= 0;
        return betweenExpr.isNot() ? !inRange : inRange;
    }

    private boolean evaluateInExpression(Tuple tuple, InExpression inExpr) throws DBException {
        Value leftValue = resolveExpressionValue(tuple, inExpr.getLeftExpression());
        if (leftValue == null) {
            return false;
        }

        Expression rightExpression = inExpr.getRightExpression();
        if (!(rightExpression instanceof ExpressionList<?> expressionList)) {
            throw new DBException(ExceptionTypes.UnsupportedExpression(inExpr));
        }

        boolean matched = false;
        for (Expression expr : expressionList.getExpressions()) {
            Value rightValue = resolveExpressionValue(tuple, expr);
            if (rightValue != null && ValueComparer.compare(leftValue, rightValue) == 0) {
                matched = true;
                break;
            }
        }
        return inExpr.isNot() ? !matched : matched;
    }

    private Value resolveExpressionValue(Tuple tuple, Expression expr) throws DBException {
        if (expr instanceof Parenthesis parenthesis) {
            return resolveExpressionValue(tuple, parenthesis.getExpression());
        }
        if (expr instanceof Column column) {
            String tableName = column.getTableName();
            if (tableName == null || tableName.isBlank()) {
                return resolveColumnValue(tuple, column.getColumnName());
            }
            return tuple.getValue(new TabCol(tableName, column.getColumnName()));
        }
        Value constantValue = getConstantValue(expr);
        if (constantValue != null) {
            return constantValue;
        }
        throw new DBException(ExceptionTypes.UnsupportedExpression(expr));
    }

    private Value resolveColumnValue(Tuple tuple, String columnName) throws DBException {
        Value matchedValue = null;
        for (TabCol tabCol : tuple.getTupleSchema()) {
            if (!tabCol.getColumnName().equalsIgnoreCase(columnName)) {
                continue;
            }
            Value value = tuple.getValue(tabCol);
            if (value == null) {
                continue;
            }
            if (matchedValue != null) {
                throw new DBException(ExceptionTypes.InvalidSQL(columnName, "Ambiguous column in expression"));
            }
            matchedValue = value;
        }
        return matchedValue;
    }

    private Value getConstantValue(Expression expr) {
        if (expr instanceof StringValue) {
            return new Value(((StringValue) expr).getValue(), ValueType.CHAR);
        } else if (expr instanceof DoubleValue) {
            return new Value(((DoubleValue) expr).getValue(), ValueType.FLOAT);
        } else if (expr instanceof LongValue) {
            return new Value(((LongValue) expr).getValue(), ValueType.INTEGER);
        }
        return null; // Unsupported constant type
    }

    public Value evaluateExpression(Expression expr) throws DBException {
        return resolveExpressionValue(this, expr);
    }

}
