package edu.sustech.cs307.logicalOperator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.select.SelectItem;

public class LogicalGroupAggregateOperator extends LogicalOperator {
    private final LogicalOperator child;
    private final List<SelectItem<?>> selectItems;
    private final List<Expression> groupByExpressions;

    public LogicalGroupAggregateOperator(LogicalOperator child, List<SelectItem<?>> selectItems,
                                         List<Expression> groupByExpressions) {
        super(Collections.singletonList(child));
        this.child = child;
        this.selectItems = new ArrayList<>(selectItems);
        this.groupByExpressions = new ArrayList<>(groupByExpressions);
    }

    public LogicalOperator getChild() {
        return child;
    }

    public List<SelectItem<?>> getSelectItems() {
        return selectItems;
    }

    public List<Expression> getGroupByExpressions() {
        return groupByExpressions;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String nodeHeader = "GroupAggregateOperator(groupBy=" + groupByExpressions + ", selectItems=" + selectItems + ")";
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
