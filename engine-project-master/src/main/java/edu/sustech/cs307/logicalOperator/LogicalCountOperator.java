package edu.sustech.cs307.logicalOperator;

import java.util.Collections;

import net.sf.jsqlparser.expression.Expression;

public class LogicalCountOperator extends LogicalOperator {
    private final LogicalOperator child;
    private final Expression countExpr;

    public LogicalCountOperator(LogicalOperator child, Expression countExpr) {
        super(Collections.singletonList(child));
        this.child = child;
        this.countExpr = countExpr;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public Expression getCountExpr() {
        return countExpr;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String nodeHeader = "CountOperator(expr=" + countExpr + ")";
        String[] childLines = child.toString().split("\\R");

        sb.append(nodeHeader);
        if (childLines.length > 0) {
            sb.append("\n|-- ").append(childLines[0]);
            for (int i = 1; i < childLines.length; i++) {
                sb.append("\n|   ").append(childLines[i]);
            }
        }
        return sb.toString();
    }
}
