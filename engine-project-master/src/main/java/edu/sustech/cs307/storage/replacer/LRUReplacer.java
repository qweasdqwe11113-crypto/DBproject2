package edu.sustech.cs307.storage.replacer;

import java.util.*;

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
}