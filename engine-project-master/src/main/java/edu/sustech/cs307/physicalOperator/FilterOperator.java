package edu.sustech.cs307.physicalOperator;

import net.sf.jsqlparser.expression.Expression;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.Tuple;
import java.util.ArrayList;
import java.util.Collection;

import edu.sustech.cs307.exception.DBException;
import org.pmw.tinylog.Logger;

/**
 * 物理过滤算子 (FilterOperator).
 *
 * <p>实现 WHERE 子句的逐行过滤. 采用经典 iterator 模型:
 * <ul>
 *   <li>{@link #Begin()}   - 打开子算子, 重置内部状态</li>
 *   <li>{@link #hasNext()} - 懒查找下一条满足 whereExpr 的元组, 命中则缓存</li>
 *   <li>{@link #Next()}    - 把缓存当作当前元组, 之后再次 hasNext() 才会继续查找</li>
 *   <li>{@link #Current()} - 返回当前缓存的元组</li>
 * </ul>
 *
 * <p>表达式求值委托给 {@link Tuple#eval_expr(Expression)},
 * 该方法支持 AND/OR/BETWEEN/IN(字面量)/二元比较等; 子查询型谓词
 * 已被 {@code LogicalPlanner.rewriteSubqueryPredicates} 提前消除, 这里看不到.
 */
public class FilterOperator implements PhysicalOperator {
    private PhysicalOperator child;
    private Expression whereExpr;
    private Tuple currentTuple;
    private boolean isOpen = false;
    // readyForNext = true 表示 currentTuple 已经是"还没被 Next() 消费过的下一行".
    // 这套 hasNext/Next 拆开的设计保证 hasNext 可以被反复调用而不会跳行.
    private boolean readyForNext = false;

    public FilterOperator(PhysicalOperator child, Expression whereExpr) {
        this.child = child;
        this.whereExpr = whereExpr;
    }

    public FilterOperator(PhysicalOperator child, Collection<Expression> whereExpr) {
        this.child = child;
        // 只使用第一个表达式，简化逻辑
        this.whereExpr = whereExpr.iterator().next();
    }

    @Override
    public void Begin() throws DBException {
        Logger.debug("FilterOperator.Begin() 被调用");
        child.Begin();
        isOpen = true;
        currentTuple = null;
        readyForNext = false;

        // 在Begin后我们不主动查找第一个元组，而是等待hasNext()调用
    }

    @Override
    public boolean hasNext() throws DBException {
        Logger.debug("FilterOperator.hasNext() 被调用");
        if (!isOpen) {
            return false;
        }

        // 如果我们还没有准备好下一个元组，就尝试找一个
        if (!readyForNext) {
            return findNext();
        }

        // 如果已经准备好，且currentTuple不为null，则说明有下一个
        return currentTuple != null;
    }

    @Override
    public void Next() throws DBException {
        Logger.debug("FilterOperator.Next() 被调用");
        if (!isOpen) {
            return;
        }

        // 如果没有准备好，先准备
        if (!readyForNext) {
            hasNext(); // 这会调用findNext()来准备下一个元组
        }

        // 清除已准备状态，表示需要准备下一个元组
        readyForNext = false;
    }

    /**
     * 查找下一个符合条件的元组，并准备好它
     * 
     * @return 如果找到则返回true，否则返回false
     */
    private boolean findNext() throws DBException {
        // 标记没有找到合适的元组
        currentTuple = null;

        // 循环直到找到匹配的元组或没有更多元组
        while (child.hasNext()) {
            child.Next();
            Tuple tuple = child.Current();

            // 如果元组不为空且满足条件，则设置为当前元组并标记为已准备好
            if (tuple != null && tuple.eval_expr(whereExpr)) {
                Logger.debug("FilterOperator找到匹配的元组: " + tuple);
                currentTuple = tuple;
                readyForNext = true;
                return true;
            }
        }

        // 没有找到匹配的元组
        Logger.debug("FilterOperator没有找到更多匹配的元组");
        return false;
    }

    @Override
    public Tuple Current() {
        Logger.debug("FilterOperator.Current() 被调用，返回: " + currentTuple);
        return currentTuple;
    }

    @Override
    public void Close() {
        if (child != null) {
            child.Close();
        }
        isOpen = false;
        currentTuple = null;
        readyForNext = false;
        Logger.debug("FilterOperator.Close() 被调用");
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return child.outputSchema();
    }
}
