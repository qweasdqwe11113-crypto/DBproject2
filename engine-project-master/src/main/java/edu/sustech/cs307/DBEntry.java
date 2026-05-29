package edu.sustech.cs307;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.logicalOperator.LogicalOperator;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.meta.MetaManager;
import edu.sustech.cs307.optimizer.LogicalPlanner;
import edu.sustech.cs307.optimizer.PhysicalPlanner;
import edu.sustech.cs307.physicalOperator.PhysicalOperator;
import edu.sustech.cs307.storage.BufferPool;
import edu.sustech.cs307.storage.DiskManager;
import edu.sustech.cs307.system.DBManager;
import edu.sustech.cs307.system.RecordManager;
import edu.sustech.cs307.tuple.Tuple;

import org.apache.commons.lang3.StringUtils;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.TerminalBuilder;
import org.pmw.tinylog.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 程序入口 (REPL 主循环).
 *
 * <p>启动流程:
 * <ol>
 *   <li>读取 disk_manager_meta.json -> 构造 DiskManager (恢复每个文件的 page 数)</li>
 *   <li>用 POOL_SIZE 初始化 BufferPool (默认 LRUReplacer)</li>
 *   <li>RecordManager 包裹 DiskManager + BufferPool 提供记录级 API</li>
 *   <li>MetaManager 从 meta/meta_data.json 加载所有表的 schema</li>
 *   <li>DBManager 把以上四件玩具串成一个门面对象</li>
 * </ol>
 *
 * <p>主循环:
 * 用 jline 读一行 SQL -> {@link LogicalPlanner#resolveAndPlan} 转为逻辑算子 ->
 * {@link PhysicalPlanner#generateOperator} 转为物理算子 -> Begin/hasNext/Next/Current/Close
 * 迭代器协议依次打印每行结果 -> 最后 flush 缓冲池.
 *
 * <p>输入 "exit" 退出, "help" 显示帮助; 解析或执行抛 DBException 时打印错误但不退出.
 */
public class DBEntry {
    public static final String DB_NAME = "CS307-DB";
    public static final int POOL_SIZE = 256 * 512;
    private static final int COLUMN_WIDTH = 15;

    public static void printHelp() {
        Logger.info("Type 'exit' to exit the program.");
        Logger.info("Type 'help' to see this message again.");
    }

    public static void main(String[] args) throws DBException {
        Logger.getConfiguration().formatPattern("{date: HH:mm:ss.SSS} {level}: {message}").activate();

        Logger.info("Hello, This is CS307-DB!");
        Logger.info("Initializing...");
        DBManager dbManager = null;
        try {
            Map<String, Integer> diskManagerMeta = new HashMap<>(DiskManager.read_disk_manager_meta());
            DiskManager diskManager = new DiskManager(DB_NAME, diskManagerMeta);
            BufferPool bufferPool = new BufferPool(POOL_SIZE, diskManager);
            RecordManager recordManager = new RecordManager(diskManager, bufferPool);
            MetaManager metaManager = new MetaManager(DB_NAME + "/meta");
            dbManager = new DBManager(diskManager, bufferPool, recordManager, metaManager);
        } catch (DBException e) {
            Logger.error(e.getMessage());
            Logger.error("An error occurred during initializing. Exiting....");
            return;
        }

        String sql = "";
        boolean running = true;
        try {
            while (running) {
                try {
                    LineReader scanner = LineReaderBuilder.builder()
                            .terminal(
                                    TerminalBuilder
                                            .builder()
                                            .dumb(true)
                                            .build()
                            )
                            .build();
                    Logger.info("CS307-DB> ");
                    sql = scanner.readLine();
                    if (sql.equalsIgnoreCase("exit")) {
                        running = false;
                        continue;
                    } else if (sql.equalsIgnoreCase("help")) {
                        printHelp();
                        continue;
                    }
                } catch (Exception e) {
                    Logger.error(e.getMessage());
                    Logger.error("An error occurred. Exiting....");
                }

                try {
                    LogicalOperator operator = LogicalPlanner.resolveAndPlan(dbManager, sql);
                    if (operator == null) {
                        continue;
                    }
                    PhysicalOperator physicalOperator = PhysicalPlanner.generateOperator(dbManager, operator);
                    if (physicalOperator == null) {
                        Logger.info(operator);
                        continue;
                    }

                    physicalOperator.Begin();
                    ArrayList<ColumnMeta> outputSchema = physicalOperator.outputSchema();
                    Logger.info(getBorderLine(outputSchema.size()));
                    Logger.info(getHeaderString(outputSchema));
                    Logger.info(getBorderLine(outputSchema.size()));
                    while (physicalOperator.hasNext()) {
                        physicalOperator.Next();
                        Tuple tuple = physicalOperator.Current();
                        Logger.info(getRecordString(tuple));
                        Logger.info(getBorderLine(physicalOperator.outputSchema().size()));
                    }
                    physicalOperator.Close();
                    // 每条命令执行完整地持久化: flush 脏页 + dump disk meta + save table meta,
                    // 保证 insert/update/delete 实时落盘且 page count 元数据不丢失。
                    dbManager.persistRuntimeState();
                } catch (DBException e) {
                    Logger.error(e.getMessage());
                    Logger.error("An error occurred. Please try again.");
                    Logger.error(Arrays.toString(e.getStackTrace()));
                }
            }
            // 正常退出 (输入 exit): 完整收尾, 落盘所有元数据。
            dbManager.closeDBManager();
            Logger.info("Bye!");
        } catch (Exception e) {
            e.printStackTrace();
            try {
                dbManager.closeDBManager();
            } catch (DBException ex) {
                Logger.error(ex.getMessage());
            }
            Logger.error("Some error occurred. Exiting after persistdata...");
        }
    }

    private static String getHeaderString(ArrayList<ColumnMeta> columnMetas) {
        StringBuilder header = new StringBuilder("|");
        for (var entry : columnMetas) {
            String tabCol = (entry.tableName == null || entry.tableName.isBlank())
                    ? entry.name
                    : String.format("%s.%s", entry.tableName, entry.name);
            String centeredText = StringUtils.center(tabCol, COLUMN_WIDTH, ' ');
            header.append(centeredText).append("|");
        }
        return header.toString();
    }

    private static String getRecordString(Tuple tuple) throws DBException {
        StringBuilder tupleString = new StringBuilder("|");
        for (var entry : tuple.getValues()) {
            String tabCol = String.format("%s", entry);
            String centeredText = StringUtils.center(tabCol, COLUMN_WIDTH, ' ');
            tupleString.append(centeredText).append("|");
        }
        return tupleString.toString();
    }

    private static String getBorderLine(int width) {
        StringBuilder line = new StringBuilder("+");
        for (int i = 0; i < width; i++) {
            line.append("-".repeat(COLUMN_WIDTH));
            line.append("+");
        }
        return line.toString();
    }
}
