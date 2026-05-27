package edu.sustech.cs307.storage.replacer;

import java.util.HashMap;
import java.util.Map;

/**
 * 时钟页面替换器 (Clock / Second-Chance).
 *
 * <p>近似 LRU, 用一个环形数组 + 指针 (hand) 模拟时钟:
 * <ul>
 *   <li>每个 frame 有 ref 位, 命中即置 1</li>
 *   <li>{@link #Victim()} 沿时针走: 跳过 pinned 的; 遇到 ref=1 则清 0 并继续走;
 *       遇到 ref=0 即为受害者</li>
 *   <li>所有 frame 都 pinned 时返回 -1, 表示无法腾出空间</li>
 * </ul>
 *
 * <p>相比纯 LRU, 实现里没有真正的链表操作, 全部是数组索引推进, 适合大缓冲池.
 */
public class ClockReplacer implements PageReplacer {
    private static class FrameState {
        int frameId;
        boolean pinned;
        boolean ref;
    }

    private final FrameState[] frames;
    private final int capacity;
    private int currentSize;
    private int hand;
    private final Map<Integer, Integer> frameToIdx;

    public ClockReplacer(int numPages) {
        this.capacity = numPages;
        this.frames = new FrameState[numPages];
        this.currentSize = 0;
        this.hand = 0;
        this.frameToIdx = new HashMap<>();
    }

    @Override
    public int Victim() {
        if (currentSize == 0) {
            return -1;
        }
        
        // 检查是否全都被 pin 了
        boolean hasUnpinned = false;
        for (int i = 0; i < capacity; i++) {
            if (frames[i] != null && !frames[i].pinned) {
                hasUnpinned = true;
                break;
            }
        }
        if (!hasUnpinned) {
            return -1;
        }

        while (true) {
            FrameState state = frames[hand];
            if (state == null || state.pinned) {
                hand = (hand + 1) % capacity;
                continue;
            }
            if (state.ref) {
                state.ref = false;
                hand = (hand + 1) % capacity;
            } else {
                int victimId = state.frameId;
                frameToIdx.remove(victimId);
                frames[hand] = null;
                currentSize--;
                hand = (hand + 1) % capacity;
                return victimId;
            }
        }
    }

    @Override
    public void Pin(int frameId) {
        if (frameToIdx.containsKey(frameId)) {
            int idx = frameToIdx.get(frameId);
            frames[idx].pinned = true;
        } else {
            if (currentSize >= capacity) {
                throw new RuntimeException("REPLACER IS FULL");
            }
            int idx = -1;
            // 找到一个空位
            for (int i = 0; i < capacity; i++) {
                if (frames[i] == null) {
                    idx = i;
                    break;
                }
            }
            FrameState state = new FrameState();
            state.frameId = frameId;
            state.pinned = true;
            state.ref = true;
            
            frames[idx] = state;
            frameToIdx.put(frameId, idx);
            currentSize++;
        }
    }

    @Override
    public void Unpin(int frameId) {
        if (!frameToIdx.containsKey(frameId)) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        int idx = frameToIdx.get(frameId);
        if (!frames[idx].pinned) {
            throw new RuntimeException("UNPIN PAGE NOT FOUND");
        }
        frames[idx].pinned = false;
        frames[idx].ref = true;
    }

    @Override
    public int size() {
        return currentSize;
    }

    @Override
    public void Clear() {
        for (int i = 0; i < capacity; i++) {
            frames[i] = null;
        }
        frameToIdx.clear();
        currentSize = 0;
        hand = 0;
    }
}
