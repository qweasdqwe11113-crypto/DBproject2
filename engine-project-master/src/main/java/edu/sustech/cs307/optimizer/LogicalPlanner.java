package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.create.table.CreateTable;

import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.logicalOperator.*;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.exception.DBException;

public class LogicalPlanner {
    private static final Pattern BEGIN_PATTERN = Pattern.compile("(?i)^BEGIN(?:\\s+(?:WORK|TRANSACTION))?$");
    private static final Pattern START_TRANSACTION_PATTERN = Pattern.compile("(?i)^START\\s+TRANSACTION$");
    private static final Pattern SHOW_TABLES_PATTERN = Pattern.compile("(?i)^SHOW\\s+TABLES$");
    private static final Pattern SHOW_DATABASES_PATTERN = Pattern.compile("(?i)^SHOW\\s+DATABASES$");
    private static final Pattern DESC_TABLE_PATTERN =
            Pattern.compile("(?i)^(?:DESC|DESCRIBE)\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern DROP_TABLE_PATTERN =
            Pattern.compile("(?i)^DROP\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)$");
    private static final Pattern RELEASE_SAVEPOINT_PATTERN =
            Pattern.compile("(?i)^RELEASE(?:\\s+SAVEPOINT)?\\s+([A-Za-z_][A-Za-z0-9_]*)$");

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
        Statement stmt = null;
        try {
            stmt = parser.parse(new StringReader(sql));
        } catch (JSQLParserException e) {
            throw new DBException(ExceptionTypes.InvalidSQL(sql, e.getMessage()));
        }
        LogicalOperator operator = null;
        // Query
        if (stmt instanceof Select selectStmt) {
            operator = handleSelect(dbManager, selectStmt);
        } else if (stmt instanceof Insert insertStmt) {
            operator = handleInsert(dbManager, insertStmt);
        } else if (stmt instanceof Update updateStmt) {
            operator = handleUpdate(dbManager, updateStmt);
        } else if (stmt instanceof Delete deleteStmt) {
            operator = handleDelete(dbManager, deleteStmt);
        }else if (stmt instanceof Commit) {
            dbManager.commitTransaction();
            return null;
        }
        // functional
        else if (stmt instanceof CreateTable createTableStmt) {
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
        } else {
            throw new DBException(ExceptionTypes.UnsupportedCommand((stmt.toString())));
        }
        return operator;
    }


    public static LogicalOperator handleSelect(DBManager dbManager, Select selectStmt) throws DBException {
        PlainSelect plainSelect = selectStmt.getPlainSelect();
        if (plainSelect.getFromItem() == null) {
            throw new DBException(ExceptionTypes.UnsupportedCommand((plainSelect.toString())));
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

        // 在 Join 之后应用 Filter，Filter 的输入是 Join 的结果 (root)
        if (plainSelect.getWhere() != null) {
            root = new LogicalFilterOperator(root, plainSelect.getWhere());
        }
        if (isAggregateQuery(plainSelect.getSelectItems())) {
            root = new LogicalAggregateOperator(
                    root,
                    getAggregateFunctionName(plainSelect.getSelectItems()),
                    getAggregateExpression(plainSelect.getSelectItems()));
        } else {
            root = new LogicalProjectOperator(root, plainSelect.getSelectItems());
        }
        return root;
    }

    private static boolean isAggregateQuery(java.util.List<SelectItem<?>> selectItems) {
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

    private static String getAggregateFunctionName(java.util.List<SelectItem<?>> selectItems) {
        Function function = (Function) selectItems.get(0).getExpression();
        return function.getName();
    }

    private static net.sf.jsqlparser.expression.Expression getAggregateExpression(java.util.List<SelectItem<?>> selectItems) {
        Function function = (Function) selectItems.get(0).getExpression();
        if (function.isAllColumns()) {
            return null;
        }
        if (function.getParameters() == null || function.getParameters().getExpressions().isEmpty()) {
            return null;
        }
        return function.getParameters().getExpressions().get(0);
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
