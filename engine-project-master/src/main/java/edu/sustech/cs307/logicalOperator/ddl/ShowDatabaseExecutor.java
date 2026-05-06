package edu.sustech.cs307.logicalOperator.ddl;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.system.DBManager;
import net.sf.jsqlparser.statement.ShowStatement;
import org.pmw.tinylog.Logger;

public class ShowDatabaseExecutor implements DMLExecutor {

    ShowStatement showStatement;
    DBManager dbManager;

    public ShowDatabaseExecutor(ShowStatement showStatement, DBManager dbManager) {
        this.showStatement = showStatement;
        this.dbManager = dbManager;
    }

    @Override
    public void execute() throws DBException {
        execute(showStatement.getName());
    }

    public void execute(String command) throws DBException {
        if (command.equalsIgnoreCase("DATABASES")) {
            // we only have one database
            Logger.info("|-----------|");
            Logger.info("| Databases |");
            Logger.info("|-----------|");
            Logger.info("|   CS307   |");
            Logger.info("|-----------|");
        } else if (command.equalsIgnoreCase("TABLES")) {
            dbManager.showTables();
        } else {
            throw new DBException(ExceptionTypes.UnsupportedCommand(String.format("SHOW %s", command)));
        }
    }

}
