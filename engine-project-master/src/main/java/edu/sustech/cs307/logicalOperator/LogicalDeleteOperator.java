package edu.sustech.cs307.logicalOperator;

import net.sf.jsqlparser.expression.Expression;

import java.util.Collections;

public class LogicalDeleteOperator extends LogicalOperator {
    private final String tableName;
    private final Expression whereExpr;

    public LogicalDeleteOperator(LogicalOperator child, String tableName, Expression whereExpr) {
        super(Collections.singletonList(child));
        this.tableName = tableName;
        this.whereExpr = whereExpr;
    }

    public String getTableName() {
        return tableName;
    }

    public Expression getWhereExpr() {
        return whereExpr;
    }

    @Override
    public String toString() {
        return "DeleteOperator(table=" + tableName + ", where=" + whereExpr + ")\n \u2514\u2500 " + childern.get(0);
    }
}
