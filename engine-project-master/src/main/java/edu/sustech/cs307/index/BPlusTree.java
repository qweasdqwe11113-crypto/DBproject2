package edu.sustech.cs307.index;

import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.value.Value;
import edu.sustech.cs307.value.ValueType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版 B+ 树 (in-memory B+ Tree).
 *
 * <p>键为 {@link Value} (列值), 叶子节点中每个键对应一个 {@link RID} 列表, 以支持
 * 非唯一索引 (同一列值出现在多行). 内部节点只存键 + 子指针, 叶子节点之间用 next
 * 指针串成链表, 便于范围扫描.
 *
 * <p>实现了标准的:
 * <ul>
 *   <li>insert: 自底向上分裂 (split) 传播</li>
 *   <li>delete: 下溢时向兄弟借 (borrow) 或与兄弟合并 (merge)</li>
 *   <li>search: 等值查找, 返回该键的全部 RID</li>
 *   <li>rangeScan: 区间扫描 (可单端开放), 升序返回 (键,RID) 列表</li>
 *   <li>printByLevel: 按层打印每个节点, 满足 Task3 "打印每个节点" 的要求</li>
 * </ul>
 *
 * <p>阶 (order) = 内部节点最大孩子数; 每个节点最多 order-1 个键. 默认 4, 便于在小数据集上
 * 也能展示出多层结构与分裂/合并; 对大数据集仍是 O(log n).
 */
public class BPlusTree {

    private final int order;
    private final int maxKeys;
    private final int minKeys;
    private Node root;
    private final ValueType keyType;

    public BPlusTree(ValueType keyType) {
        this(keyType, 4);
    }

    public BPlusTree(ValueType keyType, int order) {
        if (order < 3) {
            order = 3;
        }
        this.order = order;
        this.maxKeys = order - 1;
        this.minKeys = (int) Math.ceil(order / 2.0) - 1;
        this.keyType = keyType;
        this.root = null;
    }

    // ============================ 节点定义 ============================

    abstract static class Node {
        final List<Value> keys = new ArrayList<>();

        abstract boolean isLeaf();

        int keyCount() {
            return keys.size();
        }
    }

    static final class LeafNode extends Node {
        final List<List<RID>> ridLists = new ArrayList<>();
        LeafNode next;

        @Override
        boolean isLeaf() {
            return true;
        }
    }

    static final class InternalNode extends Node {
        final List<Node> children = new ArrayList<>();

        @Override
        boolean isLeaf() {
            return false;
        }
    }

    // ============================ 比较 ============================

    /** 不抛受检异常的值比较; 同一棵树内键类型一致. */
    static int compareValues(Value a, Value b) {
        switch (a.type) {
            case INTEGER:
                return Long.compare(((Number) a.value).longValue(), ((Number) b.value).longValue());
            case FLOAT:
                return Double.compare(((Number) a.value).doubleValue(), ((Number) b.value).doubleValue());
            case CHAR:
                return ((String) a.value).trim().compareTo(((String) b.value).trim());
            default:
                return 0;
        }
    }

    private static boolean ridEquals(RID a, RID b) {
        return a.pageNum == b.pageNum && a.slotNum == b.slotNum;
    }

    public ValueType getKeyType() {
        return keyType;
    }

    public boolean isEmpty() {
        return root == null;
    }

    // ============================ 插入 ============================

    private static final class Split {
        final Value key;
        final Node right;

        Split(Value key, Node right) {
            this.key = key;
            this.right = right;
        }
    }

    public void insert(Value key, RID rid) {
        if (root == null) {
            LeafNode leaf = new LeafNode();
            leaf.keys.add(key);
            List<RID> rids = new ArrayList<>();
            rids.add(rid);
            leaf.ridLists.add(rids);
            root = leaf;
            return;
        }
        Split split = insertRec(root, key, rid);
        if (split != null) {
            InternalNode newRoot = new InternalNode();
            newRoot.keys.add(split.key);
            newRoot.children.add(root);
            newRoot.children.add(split.right);
            root = newRoot;
        }
    }

    private Split insertRec(Node node, Value key, RID rid) {
        if (node.isLeaf()) {
            LeafNode leaf = (LeafNode) node;
            int pos = findKeyPos(leaf, key);
            if (pos < leaf.keys.size() && compareValues(leaf.keys.get(pos), key) == 0) {
                leaf.ridLists.get(pos).add(rid);
                return null;
            }
            List<RID> rids = new ArrayList<>();
            rids.add(rid);
            leaf.keys.add(pos, key);
            leaf.ridLists.add(pos, rids);
            if (leaf.keys.size() > maxKeys) {
                return splitLeaf(leaf);
            }
            return null;
        }

        InternalNode internal = (InternalNode) node;
        int childIdx = findChildIndex(internal, key);
        Split childSplit = insertRec(internal.children.get(childIdx), key, rid);
        if (childSplit == null) {
            return null;
        }
        internal.keys.add(childIdx, childSplit.key);
        internal.children.add(childIdx + 1, childSplit.right);
        if (internal.keys.size() > maxKeys) {
            return splitInternal(internal);
        }
        return null;
    }

    private Split splitLeaf(LeafNode leaf) {
        int splitIdx = leaf.keys.size() / 2;
        LeafNode right = new LeafNode();
        for (int i = splitIdx; i < leaf.keys.size(); i++) {
            right.keys.add(leaf.keys.get(i));
            right.ridLists.add(leaf.ridLists.get(i));
        }
        leaf.keys.subList(splitIdx, leaf.keys.size()).clear();
        leaf.ridLists.subList(splitIdx, leaf.ridLists.size()).clear();
        right.next = leaf.next;
        leaf.next = right;
        return new Split(right.keys.get(0), right);
    }

    private Split splitInternal(InternalNode node) {
        int mid = node.keys.size() / 2;
        Value upKey = node.keys.get(mid);
        InternalNode right = new InternalNode();
        for (int i = mid + 1; i < node.keys.size(); i++) {
            right.keys.add(node.keys.get(i));
        }
        for (int i = mid + 1; i < node.children.size(); i++) {
            right.children.add(node.children.get(i));
        }
        node.keys.subList(mid, node.keys.size()).clear();
        node.children.subList(mid + 1, node.children.size()).clear();
        return new Split(upKey, right);
    }

    private int findKeyPos(LeafNode leaf, Value key) {
        int lo = 0;
        int hi = leaf.keys.size();
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (compareValues(leaf.keys.get(m), key) < 0) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo;
    }

    private int findChildIndex(InternalNode node, Value key) {
        int i = 0;
        while (i < node.keys.size() && compareValues(key, node.keys.get(i)) >= 0) {
            i++;
        }
        return i;
    }

    // ============================ 查找 ============================

    private LeafNode findLeaf(Value key) {
        Node cur = root;
        while (cur != null && !cur.isLeaf()) {
            InternalNode in = (InternalNode) cur;
            cur = in.children.get(findChildIndex(in, key));
        }
        return (LeafNode) cur;
    }

    /** 返回该键对应的全部 RID (升序行不保证), 不存在返回空列表. */
    public List<RID> search(Value key) {
        List<RID> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        LeafNode leaf = findLeaf(key);
        int pos = findKeyPos(leaf, key);
        if (pos < leaf.keys.size() && compareValues(leaf.keys.get(pos), key) == 0) {
            result.addAll(leaf.ridLists.get(pos));
        }
        return result;
    }

    /**
     * 区间扫描: low/high 为 null 表示该端开放. 升序返回 (键, RID).
     */
    public List<Map.Entry<Value, RID>> rangeScan(Value low, boolean lowInclusive,
                                                 Value high, boolean highInclusive) {
        List<Map.Entry<Value, RID>> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        LeafNode leaf = (low == null) ? leftmostLeaf() : findLeaf(low);
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                Value k = leaf.keys.get(i);
                if (low != null) {
                    int c = compareValues(k, low);
                    if (c < 0 || (c == 0 && !lowInclusive)) {
                        continue;
                    }
                }
                if (high != null) {
                    int c = compareValues(k, high);
                    if (c > 0 || (c == 0 && !highInclusive)) {
                        return out;
                    }
                }
                for (RID rid : leaf.ridLists.get(i)) {
                    out.add(new java.util.AbstractMap.SimpleImmutableEntry<>(k, rid));
                }
            }
            leaf = leaf.next;
        }
        return out;
    }

    private LeafNode leftmostLeaf() {
        Node cur = root;
        while (cur != null && !cur.isLeaf()) {
            cur = ((InternalNode) cur).children.get(0);
        }
        return (LeafNode) cur;
    }

    // ============================ 删除 ============================

    public void delete(Value key, RID rid) {
        if (root == null) {
            return;
        }
        deleteRec(root, key, rid);
        if (!root.isLeaf() && root.keys.isEmpty()) {
            root = ((InternalNode) root).children.get(0);
        } else if (root.isLeaf() && root.keys.isEmpty()) {
            root = null;
        }
    }

    private void deleteRec(Node node, Value key, RID rid) {
        if (node.isLeaf()) {
            LeafNode leaf = (LeafNode) node;
            int pos = findKeyPos(leaf, key);
            if (pos < leaf.keys.size() && compareValues(leaf.keys.get(pos), key) == 0) {
                List<RID> rids = leaf.ridLists.get(pos);
                rids.removeIf(r -> ridEquals(r, rid));
                if (rids.isEmpty()) {
                    leaf.keys.remove(pos);
                    leaf.ridLists.remove(pos);
                }
            }
            return;
        }
        InternalNode internal = (InternalNode) node;
        int childIdx = findChildIndex(internal, key);
        Node child = internal.children.get(childIdx);
        deleteRec(child, key, rid);
        if (child.keyCount() < minKeys) {
            fixUnderflow(internal, childIdx);
        }
    }

    private void fixUnderflow(InternalNode parent, int idx) {
        Node child = parent.children.get(idx);
        Node left = idx > 0 ? parent.children.get(idx - 1) : null;
        Node right = idx < parent.children.size() - 1 ? parent.children.get(idx + 1) : null;

        if (left != null && left.keyCount() > minKeys) {
            borrowFromLeft(parent, idx, child, left);
        } else if (right != null && right.keyCount() > minKeys) {
            borrowFromRight(parent, idx, child, right);
        } else if (left != null) {
            mergeNodes(parent, idx - 1, left, child);
        } else if (right != null) {
            mergeNodes(parent, idx, child, right);
        }
    }

    private void borrowFromLeft(InternalNode parent, int idx, Node child, Node left) {
        if (child.isLeaf()) {
            LeafNode c = (LeafNode) child;
            LeafNode l = (LeafNode) left;
            int last = l.keys.size() - 1;
            c.keys.add(0, l.keys.remove(last));
            c.ridLists.add(0, l.ridLists.remove(last));
            parent.keys.set(idx - 1, c.keys.get(0));
        } else {
            InternalNode c = (InternalNode) child;
            InternalNode l = (InternalNode) left;
            c.keys.add(0, parent.keys.get(idx - 1));
            parent.keys.set(idx - 1, l.keys.remove(l.keys.size() - 1));
            c.children.add(0, l.children.remove(l.children.size() - 1));
        }
    }

    private void borrowFromRight(InternalNode parent, int idx, Node child, Node right) {
        if (child.isLeaf()) {
            LeafNode c = (LeafNode) child;
            LeafNode r = (LeafNode) right;
            c.keys.add(r.keys.remove(0));
            c.ridLists.add(r.ridLists.remove(0));
            parent.keys.set(idx, r.keys.get(0));
        } else {
            InternalNode c = (InternalNode) child;
            InternalNode r = (InternalNode) right;
            c.keys.add(parent.keys.get(idx));
            parent.keys.set(idx, r.keys.remove(0));
            c.children.add(r.children.remove(0));
        }
    }

    /** 把 children[rightIdx] 合并进 children[leftIdx] (leftIdx = rightIdx-1), 删除分隔键 parent.keys[leftIdx]. */
    private void mergeNodes(InternalNode parent, int leftIdx, Node left, Node right) {
        if (left.isLeaf()) {
            LeafNode l = (LeafNode) left;
            LeafNode r = (LeafNode) right;
            l.keys.addAll(r.keys);
            l.ridLists.addAll(r.ridLists);
            l.next = r.next;
        } else {
            InternalNode l = (InternalNode) left;
            InternalNode r = (InternalNode) right;
            l.keys.add(parent.keys.get(leftIdx));
            l.keys.addAll(r.keys);
            l.children.addAll(r.children);
        }
        parent.keys.remove(leftIdx);
        parent.children.remove(leftIdx + 1);
    }

    // ============================ 打印 ============================

    /** 按层打印每个节点, 返回多行字符串. 叶子节点用 L[...], 内部节点用 I[...]. */
    public String printByLevel() {
        StringBuilder sb = new StringBuilder();
        if (root == null) {
            sb.append("<empty tree>");
            return sb.toString();
        }
        Deque<Node> current = new ArrayDeque<>();
        current.add(root);
        int level = 0;
        while (!current.isEmpty()) {
            Deque<Node> next = new ArrayDeque<>();
            sb.append("Level ").append(level).append(": ");
            boolean first = true;
            for (Node node : current) {
                if (!first) {
                    sb.append("  ");
                }
                first = false;
                sb.append(formatNode(node));
                if (!node.isLeaf()) {
                    next.addAll(((InternalNode) node).children);
                }
            }
            sb.append(System.lineSeparator());
            current = next;
            level++;
        }
        return sb.toString();
    }

    private String formatNode(Node node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.isLeaf() ? "L[" : "I[");
        for (int i = 0; i < node.keys.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(keyToString(node.keys.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private String keyToString(Value v) {
        return String.valueOf(v.value);
    }

    /** 全部 (键 -> RID 数) 的有序快照, 便于调试/校验. */
    public Map<Value, Integer> dumpKeyCounts() {
        Map<Value, Integer> out = new LinkedHashMap<>();
        LeafNode leaf = leftmostLeaf();
        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                out.put(leaf.keys.get(i), leaf.ridLists.get(i).size());
            }
            leaf = leaf.next;
        }
        return out;
    }
}
