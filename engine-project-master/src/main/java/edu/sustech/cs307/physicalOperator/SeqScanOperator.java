package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.meta.ColumnMeta;

import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.tuple.TableTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.TableMeta;
import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.record.RID;
import edu.sustech.cs307.record.RecordPageHandle;
import edu.sustech.cs307.record.BitMap;
import edu.sustech.cs307.record.Record;
import edu.sustech.cs307.record.RecordFileHandle;

import java.util.ArrayList;

public class SeqScanOperator implements PhysicalOperator {
    private String tableName;
    private DBManager dbManager;
    private TableMeta tableMeta;
    private RecordFileHandle fileHandle;
    private Record currentRecord;
    private RID currentRid;

    private int currentPageNum;
    private int currentSlotNum;
    private int totalPages;
    private int recordsPerPage;
    private boolean isOpen = false;

    public SeqScanOperator(String tableName, DBManager dbManager) {
        this.tableName = tableName;
        this.dbManager = dbManager;
        try {
            this.tableMeta = dbManager.getMetaManager().getTable(tableName);
        } catch (DBException e) {
            // 正确处理异常，可记录日志或重新抛出
            e.printStackTrace();
        }
    }

    @Override
    public boolean hasNext() {
        if (!isOpen)
            return false;
        try {
            // 扫描每一页的 bitmap，找到下一条有效记录的位置。
            // 注意：每次 FetchPageHandle 都会 pin 住该页，必须在用完后 unpin，
            // 否则反复调用 hasNext() 会造成 pin count 泄漏。
            while (currentPageNum <= totalPages) {
                RecordPageHandle pageHandle = fileHandle.FetchPageHandle(currentPageNum);
                try {
                    while (currentSlotNum < recordsPerPage) {
                        if (BitMap.isSet(pageHandle.bitmap, currentSlotNum)) {
                            return true; // 找到下一条记录
                        }
                        currentSlotNum++;
                    }
                } finally {
                    fileHandle.UnpinPageHandle(currentPageNum, false);
                }
                currentPageNum++;
                currentSlotNum = 0; // 新页从第一个槽位开始
            }
        } catch (DBException e) {
            e.printStackTrace(); // 正确处理异常
        }
        return false; // 没有更多记录
    }

    @Override
    public void Begin() throws DBException {
        try {
            // 打开记录文件并初始化相关信息
            //把这张表对应的 data 文件打开，拿到 RecordFileHandle。后续读页、读记录都通过它做
            fileHandle = dbManager.getRecordManager().OpenFile(tableName);
            // 获取总页数和每页记录数以便迭代
            totalPages = fileHandle.getFileHeader().getNumberOfPages();
            // 获取每页记录数以便正确迭代槽位
            recordsPerPage = fileHandle.getFileHeader().getNumberOfRecordsPrePage();
            currentPageNum = 1; // 从第一页开始
            currentSlotNum = 0; // 从第一个槽位开始
            isOpen = true;
        } catch (DBException e) {
            e.printStackTrace(); // 正确处理异常
            isOpen = false;
        }
    }

    @Override
    public void Next() {
        if (!isOpen)
            return;
        try {
            if (hasNext()) { // 前进到下一条记录
                // 先记录本次命中的 RID，再推进 cursor，避免跨页后 RID 计算错误。
                currentRid = new RID(currentPageNum, currentSlotNum);
                // GetRecord 内部自行 fetch + unpin，pin count 保持平衡。
                currentRecord = fileHandle.GetRecord(currentRid);
                currentSlotNum++;
                if (currentSlotNum >= recordsPerPage) {
                    currentPageNum++;
                    currentSlotNum = 0;
                }
            } else {
                currentRecord = null;
                currentRid = null;
            }
        } catch (DBException e) {
            e.printStackTrace(); // 正确处理异常
            currentRecord = null;
            currentRid = null;
        }
    }

    @Override
    public Tuple Current() {
        if (!isOpen || currentRecord == null) {
            return null;
        }
        return new TableTuple(tableName, tableMeta, currentRecord, currentRid);
    }

    @Override
    public void Close() {
        if (!isOpen)
            return;
        try {
            dbManager.getRecordManager().CloseFile(fileHandle);
        } catch (DBException e) {
            e.printStackTrace(); // 正确处理异常
        }
        fileHandle = null;
        currentRecord = null;
        currentRid = null;
        isOpen = false;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return tableMeta.columns_list;
    }

    public RecordFileHandle getFileHandle() {
        return fileHandle;
    }
}
