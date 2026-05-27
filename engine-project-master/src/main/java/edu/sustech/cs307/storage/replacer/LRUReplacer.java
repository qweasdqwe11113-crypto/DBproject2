package edu.sustech.cs307.storage.replacer;

import java.util.*;

/**
 * LRU 页面替换器 (Least Recently Used).
 *
 * <p>维护两组数据结构:
 * <ul>
 *   <li>{@code pinnedFrames}  - 当前被钉住 (pin_count > 0) 的 frame, 不可被驱逐</li>
 *   <li>{@code LRUList}+Hash  - 已 Unpin 的 frame 按"最近使用顺序"排, 队首 = 最久未用</li>
 * </ul>
 *
 * <p>语义:
 * <ul>
 *   <li>{@link #Pin(int)}    - 标记 frame 在用, 从 LRU 链表里摘除</li>
 *   <li>{@link #Unpin(int)}  - 表示 frame 闲置, 加到链表尾部 (= 最近用过)</li>
 *   <li>{@link #Victim()}    - 取出链表头部 frame, 即"被淘汰的受害者"</li>
 * </ul>
 *
 * <p>注意: 总容量 = pinned + LRU 之和, 超过 maxSize 抛 "REPLACER IS FULL".
 */
public class LRUReplacer implements PageReplacer {

    private final int maxSize;
    private final Set<Integer> pinnedFrames = new HashSet<>();
    private final Set<Integer> LRUHash = new HashSet<>();
    private final LinkedList<Integer> LRUList = new LinkedList<>();

    public LRUReplacer(int numPages) {
        this.maxSize = numPages;
    }

    public int Victim() {
        if (LRUList.isEmpty()) {
            return -1;
        }
        int frameId = LRUList.removeFirst();
        LRUHash.remove(frameId);
        return frameId;
    }

    public void Pin(int frameId) {
        if (pinnedFrames.contains(frameId)) {
            return;
        }
        if (LRUHash.contains(frameId)) {
            LRUHash.remove(frameId);
            LRUList.remove(Integer.valueOf(frameId));
            pinnedFrames.add(frameId);
        } else {
            if (size() >= maxSize) {
                throw new RuntimeException("REPLACER IS FULL");
            }
            pinnedFrames.add(frameId);
        }
    }


    public void Unpin(int frameId) {
        if (!pinnedFrames.contains(frameId)) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        pinnedFrames.remove(frameId);
        LRUHash.add(frameId);
        LRUList.addLast(frameId);
    }


    public int size() {
        return LRUList.size() + pinnedFrames.size();
    }

    @Override
    public void Clear() {
        pinnedFrames.clear();
        LRUHash.clear();
        LRUList.clear();
    }
}
