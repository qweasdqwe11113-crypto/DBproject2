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
            // 检查当前页和槽位是否有效，以及是否还有后续记录
            if (currentPageNum <= totalPages) {
                while (currentPageNum <= totalPages) {
                    RecordPageHandle pageHandle = fileHandle.FetchPageHandle(currentPageNum);
                    while (currentSlotNum < recordsPerPage) {
                        if (BitMap.isSet(pageHandle.bitmap, currentSlotNum)) {
                            return true; // 找到下一条记录
                        }
                        currentSlotNum++;
                    }
                    currentPageNum++;
                    currentSlotNum = 0; // 新页从第一个槽位开始
                }
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
                RID rid = new RID(currentPageNum, currentSlotNum);
                currentRecord = fileHandle.GetRecord(rid);
                currentSlotNum++;
                if (currentSlotNum >= recordsPerPage) {
                    currentPageNum++;
                    currentSlotNum = 0;
                }
                // 只读
                fileHandle.UnpinPageHandle(currentPageNum, false);
            } else {
                currentRecord = null;
            }
        } catch (DBException e) {
            e.printStackTrace(); // 正确处理异常
            currentRecord = null;
        }
    }

    @Override
    public Tuple Current() {
        if (!isOpen || currentRecord == null) {
            return null;
        }
        return new TableTuple(tableName, tableMeta, currentRecord, new RID(this.currentPageNum, this.currentSlotNum - 1));
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
