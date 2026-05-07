package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.exception.DBException;

import net.sf.jsqlparser.statement.ExplainStatement;
import org.pmw.tinylog.Logger;

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

        PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, logicalOperator);
        Logger.info("EXPLAIN Physical Plan Root: {}", physicalOperator.getClass().getSimpleName());
    }
}
