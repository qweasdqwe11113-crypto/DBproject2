package edu.sustech.cs307.logicalOperator;

import java.util.Collections;

import net.sf.jsqlparser.expression.Expression;

public class LogicalAggregateOperator extends LogicalOperator {
    private final LogicalOperator child;
    private final String functionName;
    private final Expression aggregateExpr;

    public LogicalAggregateOperator(LogicalOperator child, String functionName, Expression aggregateExpr) {
        super(Collections.singletonList(child));
        this.child = child;
        this.functionName = functionName;
        this.aggregateExpr = aggregateExpr;
    }

    public LogicalOperator getChild() {
        return child;
    }

    public String getFunctionName() {
        return functionName;
    }

    public Expression getAggregateExpr() {
        return aggregateExpr;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String nodeHeader = "AggregateOperator(func=" + functionName + ", expr=" + aggregateExpr + ")";
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
