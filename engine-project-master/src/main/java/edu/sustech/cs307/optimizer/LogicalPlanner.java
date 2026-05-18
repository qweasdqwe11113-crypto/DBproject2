package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.Update;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.LogicalAggregateOperator;
import edu.sustech.cs307.logicalOperator.LogicalDeleteOperator;
import edu.sustech.cs307.logicalOperator.LogicalFilterOperator;
import edu.sustech.cs307.logicalOperator.LogicalGroupAggregateOperator;
import edu.sustech.cs307.logicalOperator.LogicalInsertOperator;
import edu.sustech.cs307.logicalOperator.LogicalJoinOperator;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.logicalOperator.LogicalOrderByOperator;
import edu.sustech.cs307.logicalOperator.LogicalProjectOperator;
import edu.sustech.cs307.logicalOperator.LogicalTableScanOperator;
import edu.sustech.cs307.logicalOperator.LogicalUpdateOperator;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.system.DBManager;

public class LogicalPlanner {
    private static final Pattern BEGIN_PATTERN = Pattern.compile("(?i)^BEGIN(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern START_TRANSACTION_PATTERN = Pattern.compile("(?i)^START\\s+TRANSACTION$");
    private static final Pattern COMMIT_PATTERN = Pattern.compile("(?i)^COMMIT(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("(?i)^ROLLBACK(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^SAVEPOINT\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern ROLLBACK_TO_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^ROLLBACK\\s+TO(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern RELEASE_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^RELEASE(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern SHOW_TABLES_PATTERN = Pattern.compile("(?i)^SHOW\\s+TABLES$");
    private static final Pattern SHOW_DATABASES_PATTERN = Pattern.compile("(?i)^SHOW\\s+DATABASES$");
    private static final Pattern DESC_TABLE_PATTERN =
            Pattern.compile("(?i)^(?:DESC|DESCRIBE)\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern DROP_TABLE_PATTERN =
            Pattern.compile("(?i)^DROP\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)$");

    public static LogicalOperator resolveAndPlan(DBManager dbManager, String sql) throws DBException {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        if (handleManualTransactionCommand(dbManager, sql)) {
            return null;
        }
        if (handleManualShowCommand(dbManager, sql)) {
            return null;
        }
        if (handleManualDescCommand(dbManager, sql)) {
            return null;
        }
        if (handleManualDropTableCommand(dbManager, sql)) {
            return null;
        }

        JSqlParser parser = new CCJSqlParserManager();
        Statement stmt;
        try {
            stmt = parser.parse(new StringReader(sql));
        } catch (JSQLParserException e) {
            throw new DBException(ExceptionTypes.InvalidSQL(sql, e.getMessage()));
        }

        if (stmt instanceof Select selectStmt) {
            return handleSelect(dbManager, selectStmt);
        } else if (stmt instanceof Insert insertStmt) {
            return handleInsert(dbManager, insertStmt);
        } else if (stmt instanceof Update updateStmt) {
            return handleUpdate(dbManager, updateStmt);
        } else if (stmt instanceof Delete deleteStmt) {
            return handleDelete(dbManager, deleteStmt);
        } else if (stmt instanceof Commit) {
            dbManager.commitTransaction();
            return null;
        } else if (stmt instanceof CreateTable createTableStmt) {
            CreateTableExecutor createTable = new CreateTableExecutor(createTableStmt, dbManager, sql);
            createTable.execute();
            return null;
        } else if (stmt instanceof ExplainStatement explainStatement) {
            ExplainExecutor explainExecutor = new ExplainExecutor(explainStatement, dbManager);
            explainExecutor.execute();
            return null;
        } else if (stmt instanceof ShowStatement showStatement) {
            ShowDatabaseExecutor showDatabaseExecutor = new ShowDatabaseExecutor(showStatement, dbManager);
            showDatabaseExecutor.execute();
            return null;
        }

        throw new DBException(ExceptionTypes.UnsupportedCommand(stmt.toString()));
    }

    public static LogicalOperator handleSelect(DBManager dbManager, Select selectStmt) throws DBException {
        PlainSelect plainSelect = selectStmt.getPlainSelect();
        if (plainSelect.getFromItem() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand(plainSelect.toString()));
        }

        LogicalOperator root = new LogicalTableScanOperator(plainSelect.getFromItem().toString(), dbManager);

        int depth = 0;
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                root = new LogicalJoinOperator(
                        root,
                        new LogicalTableScanOperator(join.getRightItem().toString(), dbManager),
                        join.getOnExpressions(),
                        depth);
                depth += 1;
            }
        }

        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }

        if (hasGroupBy(plainSelect)) {
            root = new LogicalGroupAggregateOperator(root, plainSelect.getSelectItems(), getGroupByExpressions(plainSelect));
        } else if (isAggregateQuery(plainSelect.getSelectItems())) {
            root = new LogicalAggregateOperator(
                    root,
                    getAggregateFunctionName(plainSelect.getSelectItems()),
                    getAggregateExpression(plainSelect.getSelectItems()));
        } else {
            root = new LogicalProjectOperator(root, plainSelect.getSelectItems());
        }

        if (plainSelect.getOrderByElements() != null && !plainSelect.getOrderByElements().isEmpty()) {
            root = new LogicalOrderByOperator(root, plainSelect.getOrderByElements());
        }

        return root;
    }

    private static boolean isAggregateQuery(List<SelectItem<?>> selectItems) {
        if (selectItems == null || selectItems.size() != 1) {
            return false;
        }
        var expr = selectItems.get(0).getExpression();
        if (!(expr instanceof Function function)) {
            return false;
        }
        String functionName = function.getName();
        return "COUNT".equalsIgnoreCase(functionName)
                || "MAX".equalsIgnoreCase(functionName)
                || "MIN".equalsIgnoreCase(functionName);
    }

    private static boolean hasGroupBy(PlainSelect plainSelect) {
        return plainSelect.getGroupBy() != null
                && plainSelect.getGroupBy().getGroupByExpressions() != null
                && !plainSelect.getGroupBy().getGroupByExpressions().getExpressions().isEmpty();
    }

    private static String getAggregateFunctionName(List<SelectItem<?>> selectItems) {
        Function function = (Function) selectItems.get(0).getExpression();
        return function.getName();
    }

    private static Expression getAggregateExpression(List<SelectItem<?>> selectItems) {
        Function function = (Function) selectItems.get(0).getExpression();
        if (function.isAllColumns()) {
            return null;
        }
        if (function.getParameters() == null || function.getParameters().getExpressions().isEmpty()) {
            return null;
        }
        return function.getParameters().getExpressions().get(0);
    }

    private static List<Expression> getGroupByExpressions(PlainSelect plainSelect) {
        ArrayList<Expression> expressions = new ArrayList<>();
        if (!hasGroupBy(plainSelect)) {
            return expressions;
        }
        for (Object expr : plainSelect.getGroupBy().getGroupByExpressions().getExpressions()) {
            expressions.add((Expression) expr);
        }
        return expressions;
    }

    private static LogicalOperator handleInsert(DBManager dbManager, Insert insertStmt) {
        return new LogicalInsertOperator(insertStmt.getTable().getName(), insertStmt.getColumns(),
                insertStmt.getValues());
    }

    private static LogicalOperator handleUpdate(DBManager dbManager, Update updateStmt) throws DBException {
        LogicalOperator root = new LogicalTableScanOperator(updateStmt.getTable().getName(), dbManager);
        return new LogicalUpdateOperator(root, updateStmt.getTable().getName(), updateStmt.getUpdateSets(),
                updateStmt.getWhere());
    }

    private static LogicalOperator handleDelete(DBManager dbManager, Delete deleteStmt) throws DBException {
        String tableName = deleteStmt.getTable().getName();
        LogicalOperator root = new LogicalTableScanOperator(tableName, dbManager);
        return new LogicalDeleteOperator(root, tableName, deleteStmt.getWhere());
    }

    private static String normalizeSql(String sql) {
        String normalizedSql = sql == null ? "" : sql.trim();
        while (normalizedSql.endsWith(";")) {
            normalizedSql = normalizedSql.substring(0, normalizedSql.length() - 1).trim();
        }
        return normalizedSql;
    }

    private static boolean handleManualTransactionCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (BEGIN_PATTERN.matcher(normalizedSql).matches() || START_TRANSACTION_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.beginTransaction();
            return true;
        }
        if (COMMIT_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.commitTransaction();
            return true;
        }
        if (ROLLBACK_PATTERN.matcher(normalizedSql).matches()) {
            dbManager.getTransactionManager().rollback();
            return true;
        }
        Matcher savepointMatcher = SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (savepointMatcher.matches()) {
            dbManager.getTransactionManager().savepoint(savepointMatcher.group(1));
            return true;
        }
        Matcher rollbackToMatcher = ROLLBACK_TO_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (rollbackToMatcher.matches()) {
            dbManager.getTransactionManager().rollbackToSavepoint(rollbackToMatcher.group(1));
            return true;
        }
        Matcher releaseMatcher = RELEASE_SAVEPOINT_PATTERN.matcher(normalizedSql);
        if (releaseMatcher.matches()) {
            dbManager.getTransactionManager().releaseSavepoint(releaseMatcher.group(1));
            return true;
        }
        return false;
    }

    private static boolean handleManualShowCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        if (SHOW_DATABASES_PATTERN.matcher(normalizedSql).matches()) {
            ShowDatabaseExecutor showDatabaseExecutor = new ShowDatabaseExecutor(null, dbManager);
            showDatabaseExecutor.execute("DATABASES");
            return true;
        }
        if (SHOW_TABLES_PATTERN.matcher(normalizedSql).matches()) {
            ShowDatabaseExecutor showDatabaseExecutor = new ShowDatabaseExecutor(null, dbManager);
            showDatabaseExecutor.execute("TABLES");
            return true;
        }
        return false;
    }

    private static boolean handleManualDescCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        Matcher matcher = DESC_TABLE_PATTERN.matcher(normalizedSql);
        if (!matcher.matches()) {
            return false;
        }
        dbManager.descTable(matcher.group(1));
        return true;
    }

    private static boolean handleManualDropTableCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        Matcher matcher = DROP_TABLE_PATTERN.matcher(normalizedSql);
        if (!matcher.matches()) {
            return false;
        }
        dbManager.dropTable(matcher.group(1));
        return true;
    }
}
