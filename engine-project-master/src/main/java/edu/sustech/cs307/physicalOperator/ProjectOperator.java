package edu.sustech.cs307.physicalOperator;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;
import edu.sustech.cs307.meta.ColumnMeta;
import edu.sustech.cs307.tuple.ProjectTuple;
import edu.sustech.cs307.tuple.Tuple;
import edu.sustech.cs307.meta.TabCol;

import java.util.ArrayList;
import java.util.List;

public class ProjectOperator implements PhysicalOperator {
    private PhysicalOperator child;
    private List<TabCol> outputSchema; // Use bounded wildcard
    private ArrayList<ColumnMeta> projectedSchema;
    private Tuple currentTuple;

    public ProjectOperator(PhysicalOperator child, List<TabCol> outputSchema) throws DBException { // Use bounded wildcard
        this.child = child;
        this.outputSchema = new ArrayList<>(outputSchema);
        if (this.outputSchema.size() == 1 && this.outputSchema.get(0).getTableName().equals("*")) {
            List<TabCol> newOutputSchema = new ArrayList<>();
            for (ColumnMeta tabCol : child.outputSchema()) {
                newOutputSchema.add(new TabCol(tabCol.tableName, tabCol.name));
            }
            this.outputSchema = newOutputSchema;
        }
        this.projectedSchema = resolveProjectedSchema(child.outputSchema(), this.outputSchema);
    }

    @Override
    public boolean hasNext() throws DBException {
        return child.hasNext();
    }

    @Override
    public void Begin() throws DBException {
        child.Begin();
    }

    @Override
    public void Next() throws DBException {
        if (hasNext()) {
            child.Next();
            Tuple inputTuple = child.Current();
            if (inputTuple != null) {

                currentTuple = new ProjectTuple(inputTuple, outputSchema); // Create ProjectTuple
            } else {
                currentTuple = null;
            }
        } else {
            currentTuple = null;
        }
    }

    @Override
    public Tuple Current() {
        return currentTuple;
    }

    @Override
    public void Close() {
        child.Close();
        currentTuple = null;
    }

    @Override
    public ArrayList<ColumnMeta> outputSchema() {
        return projectedSchema;
    }

    private ArrayList<ColumnMeta> resolveProjectedSchema(ArrayList<ColumnMeta> childSchema, List<TabCol> requestedColumns)
            throws DBException {
        ArrayList<ColumnMeta> resolvedSchema = new ArrayList<>();
        for (TabCol requestedColumn : requestedColumns) {
            ColumnMeta matchedColumn = null;
            for (ColumnMeta childColumn : childSchema) {
                boolean columnNameMatches = childColumn.name.equals(requestedColumn.getColumnName());
                boolean tableNameMatches = requestedColumn.getTableName().isBlank()
                        || childColumn.tableName.equals(requestedColumn.getTableName());
                if (!columnNameMatches || !tableNameMatches) {
                    continue;
                }
                if (matchedColumn != null) {
                    throw new DBException(ExceptionTypes.InvalidSQL(
                            requestedColumn.getColumnName(),
                            "Ambiguous column in projection list"));
                }
                matchedColumn = childColumn;
            }
            if (matchedColumn == null) {
                throw new DBException(ExceptionTypes.ColumnDoesNotExist(requestedColumn.getColumnName()));
            }
            resolvedSchema.add(matchedColumn);
        }
        return resolvedSchema;
    }
}
