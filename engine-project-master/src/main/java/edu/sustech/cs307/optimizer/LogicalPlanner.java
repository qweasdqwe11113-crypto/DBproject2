package edu.sustech.cs307.optimizer;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.parser.JSqlParser;
import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
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
import edu.sustech.cs307.logicalOperator.ddl.AlterTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.CreateTableExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ExplainExecutor;
import edu.sustech.cs307.logicalOperator.ddl.ShowDatabaseExecutor;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;

/**
 * 逻辑计划生成器 (Logical Planner).
 *
 * <p>职责:
 * <ol>
 *   <li>把用户输入的 SQL 字符串先做手写正则匹配, 处理 jsqlparser 不完整支持的命令
 *       (BEGIN/COMMIT/ROLLBACK/SAVEPOINT, SHOW TABLES, DESC t, DROP TABLE t).</li>
 *   <li>其余语句交给 jsqlparser 解析为 {@link Statement}, 再按类型分派:
 *       <ul>
 *         <li>{@link Select}   - 构建 TableScan/Filter/Project/Join/Aggregate/OrderBy 逻辑算子树</li>
 *         <li>{@link Insert}   - 构建 LogicalInsertOperator</li>
 *         <li>{@link Update}   - 构建 LogicalUpdateOperator + TableScan</li>
 *         <li>{@link Delete}   - 构建 LogicalDeleteOperator + TableScan</li>
 *         <li>{@link CreateTable} - 调用 CreateTableExecutor 立即执行</li>
 *         <li>{@link Alter}    - 调用 AlterTableExecutor 立即执行 (本次新增)</li>
 *       </ul>
 *   </li>
 *   <li>WHERE 中的子查询 (IN/NOT IN/EXISTS) 由 {@link #rewriteSubqueryPredicates}
 *       提前求值并替换成常量, 让下游 FilterOperator 无需感知子查询.</li>
 * </ol>
 *
 * <p>本类只负责"语法 -> 算子树"的翻译, 不负责具体执行;
 * 物理执行路径由 {@link PhysicalPlanner} 接管.
 */
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
    private static final Pattern CREATE_INDEX_PATTERN =
            Pattern.compile("(?i)^CREATE\\s+INDEX\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+ON\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)$");
    private static final Pattern DROP_INDEX_PATTERN =
            Pattern.compile("(?i)^DROP\\s+INDEX\\s+([A-Za-z_][A-Za-z0-9_]*)"
                    + "(?:\\s+ON\\s+[A-Za-z_][A-Za-z0-9_]*)?$");

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
        if (handleManualIndexCommand(dbManager, sql)) {
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
        } else if (stmt instanceof Alter alterStmt) {
            // -------------------------------------------------------------
            // ALTER TABLE 入口:
            // jsqlparser 把 "ALTER TABLE t ..." 解析为 net.sf.jsqlparser.statement.alter.Alter,
            // 里面包含一个或多个 AlterExpression (每个对应一个子句, 例如 ADD COLUMN c INT).
            // 真正语义在 AlterTableExecutor 中实现, 这里仅做路由.
            // -------------------------------------------------------------
            new AlterTableExecutor(alterStmt, dbManager).execute();
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
            // -------------------------------------------------------------
            // 在交给 FilterOperator 之前, 先把 WHERE 中出现的子查询展开:
            //   * IN  (SELECT ...) / NOT IN (SELECT ...)
            //       -> 把子查询第一列的结果物化成 ExpressionList,
            //          复用现有 Tuple.evaluateInExpression 即可.
            //   * EXISTS (SELECT ...) / NOT EXISTS (SELECT ...)
            //       -> 直接执行子查询, 看是否至少返回一行, 替换为常量 1 或 0.
            // 仅支持非相关 (uncorrelated) 子查询: 子查询的 WHERE 不能引用外层表的列.
            // -------------------------------------------------------------
            Expression where = rewriteSubqueryPredicates(dbManager, plainSelect.getWhere());
            root = new LogicalFilterOperator(root, where);
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

    /**
     * 手写解析 CREATE INDEX / DROP INDEX (jsqlparser 对这两条命令支持参差,
     * 与项目里其它 DDL 一样走正则更稳妥):
     *   CREATE INDEX idx ON t(col)
     *   DROP   INDEX idx [ON t]
     */
    private static boolean handleManualIndexCommand(DBManager dbManager, String sql) throws DBException {
        String normalizedSql = normalizeSql(sql);
        Matcher create = CREATE_INDEX_PATTERN.matcher(normalizedSql);
        if (create.matches()) {
            dbManager.createIndex(create.group(2), create.group(1), create.group(3));
            return true;
        }
        Matcher drop = DROP_INDEX_PATTERN.matcher(normalizedSql);
        if (drop.matches()) {
            dbManager.dropIndex(drop.group(1));
            return true;
        }
        return false;
    }

    // =====================================================================
    // ===== 以下是 IN(子查询) / NOT IN(子查询) / EXISTS / NOT EXISTS 支持 =====
    // =====================================================================

    /**
     * 递归遍历 WHERE 表达式树, 把所有非相关子查询求值并替换成等价的常量节点.
     *
     * <p>处理顺序很重要: 必须先递归到 AND/OR 的两个子节点, 然后再判断当前节点
     * 是否是子查询型谓词, 这样嵌套子查询 (子查询的 WHERE 中又有子查询) 也能展开.
     */
    private static Expression rewriteSubqueryPredicates(DBManager dbManager, Expression expr) throws DBException {
        if (expr == null) {
            return null;
        }
        if (expr instanceof Parenthesis paren) {
            // 括号包裹层: 递归处理内部, 不需要保留括号节点本身.
            paren.setExpression(rewriteSubqueryPredicates(dbManager, paren.getExpression()));
            return paren;
        }
        if (expr instanceof AndExpression andExpr) {
            andExpr.setLeftExpression(rewriteSubqueryPredicates(dbManager, andExpr.getLeftExpression()));
            andExpr.setRightExpression(rewriteSubqueryPredicates(dbManager, andExpr.getRightExpression()));
            return andExpr;
        }
        if (expr instanceof OrExpression orExpr) {
            orExpr.setLeftExpression(rewriteSubqueryPredicates(dbManager, orExpr.getLeftExpression()));
            orExpr.setRightExpression(rewriteSubqueryPredicates(dbManager, orExpr.getRightExpression()));
            return orExpr;
        }
        if (expr instanceof ExistsExpression existsExpr) {
            // EXISTS / NOT EXISTS 子查询求值:
            // 跑一遍子查询计划, 看是否至少有一行结果即可.
            Select sub = extractSelect(existsExpr.getRightExpression());
            boolean any = (sub != null) && executeAndHasAtLeastOneRow(dbManager, sub);
            // NOT EXISTS 等价于 ! any.
            boolean truth = existsExpr.isNot() != any;
            // 用一个常量 LongValue(1/0) 替换整个 EXISTS 节点.
            // Tuple.evaluateBinaryExpression / 顶层布尔判断不会去消费这个常量,
            // 所以我们把它包成 "1 = 1" / "1 = 0" 这种永真/永假的二元比较, 兼容现有引擎.
            return constantBoolean(truth);
        }
        if (expr instanceof InExpression inExpr) {
            // 仅当右值是子查询时才需要重写; 普通 ExpressionList 已经被 Tuple 支持.
            Expression rhs = inExpr.getRightExpression();
            Select sub = extractSelect(rhs);
            if (sub != null) {
                List<Expression> literals = materializeFirstColumn(dbManager, sub);
                ExpressionList<Expression> list = new ExpressionList<>(literals);
                inExpr.setRightExpression(list);
            }
            return inExpr;
        }
        // 其他表达式 (二元比较, BETWEEN, 列引用, 常量 ...) 不含子查询, 原样返回.
        return expr;
    }

    /**
     * 把可能被 ParenthesedSelect 包裹的子查询节点剥出来.
     * jsqlparser 通常把 "(SELECT ...)" 解析成 ParenthesedSelect, 再 wrap 上一层 Select.
     */
    private static Select extractSelect(Expression expr) {
        if (expr instanceof ParenthesedSelect ps) {
            return ps;
        }
        if (expr instanceof Select select) {
            return select;
        }
        return null;
    }

    /**
     * 执行一个 Select 子查询, 收集第一列的全部值, 转回 jsqlparser 的字面量节点,
     * 以便塞进 ExpressionList, 复用现有 IN 字面量比较路径.
     */
    private static List<Expression> materializeFirstColumn(DBManager dbManager, Select sub) throws DBException {
        LogicalOperator lop = handleSelect(dbManager, sub);
        PhysicalOperator pop = PhysicalPlanner.generateOperator(dbManager, lop);
        List<Expression> out = new ArrayList<>();
        pop.Begin();
        try {
            while (pop.hasNext()) {
                pop.Next();
                Tuple t = pop.Current();
                if (t == null) {
                    continue;
                }
                Value[] vs = t.getValues();
                if (vs == null || vs.length == 0 || vs[0] == null) {
                    continue;
                }
                out.add(valueToLiteral(vs[0]));
            }
        } finally {
            pop.Close();
        }
        return out;
    }

    /** 执行子查询, 返回是否至少有一行结果 (用于 EXISTS). */
    private static boolean executeAndHasAtLeastOneRow(DBManager dbManager, Select sub) throws DBException {
        LogicalOperator lop = handleSelect(dbManager, sub);
        PhysicalOperator pop = PhysicalPlanner.generateOperator(dbManager, lop);
        pop.Begin();
        try {
            return pop.hasNext();
        } finally {
            pop.Close();
        }
    }

    /** 把内部 Value 转换成 jsqlparser 的字面量 Expression, 以便嵌回 AST. */
    private static Expression valueToLiteral(Value v) {
        if (v == null || v.type == ValueType.UNKNOWN) {
            return new NullValue();
        }
        return switch (v.type) {
            case INTEGER -> new LongValue(((Number) v.value).longValue());
            case FLOAT -> new DoubleValue(String.valueOf(((Number) v.value).doubleValue()));
            case CHAR -> new StringValue(String.valueOf(v.value));
            default -> new NullValue();
        };
    }

    /**
     * 用 "1 = 1" 或 "1 = 0" 这种永真/永假表达式替换 EXISTS 节点,
     * 这样后续的 Tuple.evaluateBinaryExpression 能继续按二元比较来求值,
     * 不需要在 FilterOperator/Tuple 中额外加分支.
     */
    private static Expression constantBoolean(boolean truth) {
        net.sf.jsqlparser.expression.operators.relational.EqualsTo eq =
                new net.sf.jsqlparser.expression.operators.relational.EqualsTo();
        eq.setLeftExpression(new LongValue(1));
        eq.setRightExpression(new LongValue(truth ? 1 : 0));
        return eq;
    }
}
