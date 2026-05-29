package edu.sustech.cs307.index;

import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;

import java.util.AbstractMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

/**
 * 基于 {@link BPlusTree} 的索引实现, 满足 {@link Index} 接口, 并额外暴露
 * 等值/区间查找与维护 (insert/delete) 方法供索引扫描算子和 DML 维护使用.
 */
public class BPlusTreeIndex implements Index {

    private final String tableName;
    private final String columnName;
    private final BPlusTree tree;

    public BPlusTreeIndex(String tableName, String columnName, ValueType keyType) {
        this.tableName = tableName;
        this.columnName = columnName;
        this.tree = new BPlusTree(keyType);
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    public BPlusTree getTree() {
        return tree;
    }

    public void insert(Value key, RID rid) {
        tree.insert(key, rid);
    }

    public void delete(Value key, RID rid) {
        tree.delete(key, rid);
    }

    /** 等值查找的全部 RID, 供 IndexScan 处理非唯一列. */
    public List<RID> searchAll(Value key) {
        return tree.search(key);
    }

    public List<Entry<Value, RID>> rangeScan(Value low, boolean lowInc, Value high, boolean highInc) {
        return tree.rangeScan(low, lowInc, high, highInc);
    }

    public String printByLevel() {
        return tree.printByLevel();
    }

    // ----------------- Index 接口 -----------------

    @Override
    public RID EqualTo(Value value) {
        List<RID> rids = tree.search(value);
        return rids.isEmpty() ? null : rids.get(0);
    }

    @Override
    public Iterator<Entry<Value, RID>> LessThan(Value value, boolean isEqual) {
        return tree.rangeScan(null, false, value, isEqual).iterator();
    }

    @Override
    public Iterator<Entry<Value, RID>> MoreThan(Value value, boolean isEqual) {
        return tree.rangeScan(value, isEqual, null, false).iterator();
    }

    @Override
    public Iterator<Entry<Value, RID>> Range(Value low, Value high, boolean leftEqual, boolean rightEqual) {
        return tree.rangeScan(low, leftEqual, high, rightEqual).iterator();
    }

    /** 仅用于占位/测试: 构造一个空 entry. */
    static Entry<Value, RID> entry(Value v, RID r) {
        return new AbstractMap.SimpleImmutableEntry<>(v, r);
    }
}
