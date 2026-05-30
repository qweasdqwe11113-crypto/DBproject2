package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.exception.DBException;

import net.sf.jsqlparser.statement.ExplainStatement;
import org.pmw.tinylog.Logger;

import java.util.List;

public class ExplainExecutor implements DMLExecutor {

    private final ExplainStatement explainStatement;
    private final DBManager dbManager;

    public ExplainExecutor(ExplainStatement explainStatement, DBManager dbManager) {
        this.explainStatement = explainStatement;
        this.dbManager = dbManager;
    }

    @Override
    public void execute() throws DBException {
        String statementSql = explainStatement.getStatement().toString();
        LogicalOperator logicalOperator = LogicalPlanner.resolveAndPlan(dbManager, statementSql);
        if (logicalOperator == null) {
            Logger.info("EXPLAIN: nothing to explain for statement: {}", statementSql);
            return;
        }

        Logger.info("EXPLAIN Logical Plan:");
        for (String line : logicalOperator.toString().split("\\R")) {
            Logger.info(line);
        }

        // 验证物理计划可以生成 (会抛 DBException 如果不支持)。
        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        if (physicalOperator == null) {
            Logger.info("EXPLAIN: no physical plan generated.");
            return;
        }

        Logger.info("EXPLAIN Physical Plan:");
        StringBuilder sb = new StringBuilder();
        buildPhysicalTree(logicalOperator, "", true, sb);
        for (String line : sb.toString().split("\\R")) {
            Logger.info(line);
        }
    }

    /**
     * 按 {@link PhysicalPlanner} 的映射规则，递归打印完整的物理算子树。
     * 物理算子接口没有暴露 children，所以这里沿逻辑算子树遍历，并把每个逻辑节点
     * 映射成它对应的物理算子名称。
     */
    private void buildPhysicalTree(LogicalOperator op, String prefix, boolean isRoot, StringBuilder sb) {
        if (op == null) {
            return;
        }
        sb.append(prefix);
        if (!isRoot) {
            sb.append("└── ");
        }
        sb.append(physicalName(op)).append(System.lineSeparator());

        List<LogicalOperator> children = op.getChildren();
        if (children == null) {
            return;
        }
        String childPrefix = prefix + (isRoot ? "" : "    ");
        for (LogicalOperator child : children) {
            buildPhysicalTree(child, childPrefix, false, sb);
        }
    }

    private String physicalName(LogicalOperator op) {
        if (op instanceof LogicalTableScanOperator t) {
            return String.format("SeqScanOperator(table=%s)", t.getTableName());
        } else if (op instanceof LogicalFilterOperator) {
            return "FilterOperator";
        } else if (op instanceof LogicalProjectOperator) {
            return "ProjectOperator";
        } else if (op instanceof LogicalJoinOperator) {
            return "NestedLoopJoinOperator";
        } else if (op instanceof LogicalGroupAggregateOperator) {
            return "GroupAggregateOperator";
        } else if (op instanceof LogicalAggregateOperator) {
            return "AggregateOperator";
        } else if (op instanceof LogicalCountOperator) {
            return "CountOperator";
        } else if (op instanceof LogicalOrderByOperator) {
            return "OrderByOperator";
        } else if (op instanceof LogicalInsertOperator) {
            return "InsertOperator";
        } else if (op instanceof LogicalUpdateOperator) {
            return "UpdateOperator";
        } else if (op instanceof LogicalDeleteOperator) {
            return "DeleteOperator";
        }
        return op.getClass().getSimpleName();
    }
}
