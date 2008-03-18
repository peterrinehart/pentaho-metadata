package org.pentaho.pms.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.pms.core.exception.PentahoMetadataException;
import org.pentaho.pms.example.AdvancedMQLQuery.AliasedSelection;
import org.pentaho.pms.messages.Messages;
import org.pentaho.pms.mql.MappedQuery;
import org.pentaho.pms.mql.OrderBy;
import org.pentaho.pms.mql.PMSFormula;
import org.pentaho.pms.mql.Path;
import org.pentaho.pms.mql.SQLGenerator;
import org.pentaho.pms.mql.Selection;
import org.pentaho.pms.mql.WhereCondition;
import org.pentaho.pms.mql.dialect.SQLDialectFactory;
import org.pentaho.pms.mql.dialect.SQLDialectInterface;
import org.pentaho.pms.mql.dialect.SQLQueryModel;
import org.pentaho.pms.mql.dialect.SQLQueryModel.OrderType;
import org.pentaho.pms.schema.BusinessColumn;
import org.pentaho.pms.schema.BusinessModel;
import org.pentaho.pms.schema.BusinessTable;
import org.pentaho.pms.schema.RelationshipMeta;

/**
 * This class demonstrates extending SQLGenerator.  The example here
 * is an alias algorithm, allowing multiple aliased join paths.
 * 
 * @author Will Gorman (wgorman@pentaho.org)
 *
 */
public class AdvancedSQLGenerator extends SQLGenerator {

  public static final String DEFAULT_ALIAS = "__DEFAULT__"; //$NON-NLS-1$
  
  class AliasedPathBusinessTable {
    String alias;
    BusinessTable table;
    AliasedPathBusinessTable(String alias, BusinessTable table) {
      this.alias = alias;
      this.table = table;
    }
    
    public boolean equals(Object obj) {
      AliasedPathBusinessTable apbt = (AliasedPathBusinessTable)obj;
      return apbt.alias.equals(alias) && apbt.table.equals(table);
    }
  }
  
  public MappedQuery getQuery(
      BusinessModel model, 
      List<Selection> selections,
      List<WhereCondition> constraints,
      List<OrderBy> orderbys,
      DatabaseMeta databaseMeta, 
      boolean disableDistinct, 
      String locale) throws PentahoMetadataException 
  { 
    Map<String,String> columnsMap = new HashMap<String,String>();
    if (model == null || selections.size() == 0) {
      return null;
    }
    
    // implement SQL generation here
    List<Selection> defaultList = null;
    List<List<Selection>> lists = new ArrayList<List<Selection>>();
    List<String> aliasNames = new ArrayList<String>();
    Map<String, List<Selection>> listlookup = new HashMap<String, List<Selection>>();
    
    List<Selection> selectionsAndOrderBys = new ArrayList<Selection>();
    selectionsAndOrderBys.addAll(selections);
    for (OrderBy orderBy : orderbys) {
      selectionsAndOrderBys.add(orderBy.getSelection());
    }
    
    // default + alias lists
    for (Selection selection : selectionsAndOrderBys) {
      AliasedSelection sel = (AliasedSelection)selection;
      
      if (sel.hasFormula()) {
        sel.initPMSFormula(model, databaseMeta, selections);
      }
      
      if (sel.alias == null) {
        sel.alias = DEFAULT_ALIAS;
      } 
      List<Selection> list = listlookup.get(sel.alias);
      if (list == null) {
        list = new ArrayList<Selection>();
        if (sel.alias.equals(DEFAULT_ALIAS)) {
          defaultList = list;
          lists.add(0, list);
          aliasNames.add(0, DEFAULT_ALIAS);
        } else {
          lists.add(list);
          aliasNames.add(sel.alias);
        }
        listlookup.put(sel.alias, list);
      }
      if (!list.contains(sel)) {
        list.add(sel);
      }
    }

    if (!listlookup.containsKey(DEFAULT_ALIAS)) {
      throw new PentahoMetadataException("No non-aliased columns selected"); //$NON-NLS-1$
    }
    
    // generate paths for all the lists
    List<AliasedRelationshipMeta> allRelationships = new ArrayList<AliasedRelationshipMeta>();
    
    List<BusinessTable> defaultTables = getTablesInvolved(defaultList, constraints);
    Path defaultPath = getShortestPathBetween(model, defaultTables);
    List<BusinessTable> tbls = defaultPath.getUsedTables();
    List<AliasedPathBusinessTable> allTables = new ArrayList<AliasedPathBusinessTable>();
    for (BusinessTable tbl : tbls) {
      allTables.add(new AliasedPathBusinessTable(DEFAULT_ALIAS, tbl));
    }
    if (tbls.size() == 0) {
      allTables.add(new AliasedPathBusinessTable(DEFAULT_ALIAS, defaultTables.get(0)));
    }
    
    
    if (defaultPath == null) {
      throw new PentahoMetadataException(Messages.getErrorString("BusinessModel.ERROR_0001_FAILED_TO_FIND_PATH")); //$NON-NLS-1$
    }
    
    for (int i = 0; i < defaultPath.size(); i++) {
      allRelationships.add(new AliasedRelationshipMeta(DEFAULT_ALIAS, DEFAULT_ALIAS, defaultPath.getRelationship(i)));
    }

    for (int i = 1; i < lists.size(); i++) {
      List<Selection> aliasedColumns = lists.get(i);
      List<Selection> aliasedAndDefaultColumns = new ArrayList<Selection>();
      aliasedAndDefaultColumns.addAll(aliasedColumns);
      aliasedAndDefaultColumns.addAll(defaultList);
      List<BusinessTable> aliasedTables = getTablesInvolved(aliasedColumns, new ArrayList<WhereCondition>());
      List<BusinessTable> aliasedAndDefaultTables = getTablesInvolved(aliasedAndDefaultColumns, new ArrayList<WhereCondition>());
      Path aliasedAndDefaultPath = getShortestPathBetween(model, aliasedAndDefaultTables);
      
      // Prune and connect aliased path with default path
      for (BusinessTable aliasedTable : aliasedTables) {
        // follow the path, move relationships into allRelationships and allTables
        traversePath((String)aliasNames.get(i), aliasedTable, aliasedAndDefaultPath, aliasedTables, defaultTables, allTables, allRelationships);
      }
      
    }
    SQLQueryModel sqlquery = new SQLQueryModel();
    boolean group = hasFactsInIt(selections, constraints);

    // SELECT
    
    sqlquery.setDistinct(!disableDistinct && !group);
    for (int i = 0; i < selections.size(); i++) {
      AliasedSelection selection = (AliasedSelection)selections.get(i);
      String formula;
      if (selection.hasFormula()) {
        try {
          
          formula = selection.getPMSFormula().generateSQL(locale);
        } catch (PentahoMetadataException e) {
          throw new RuntimeException(e);
        }
      } else {
        formula = getFunctionTableAndColumnForSQL(model, selection, databaseMeta, locale);
      }

      // in some database implementations, the "as" name has a finite length;
      // for instance, oracle cannot handle a name longer than 30 characters. 
      // So, we map a short name here to the longer id, and replace the id
      // later in the resultset metadata. 
      String alias = null;
      if(columnsMap != null){
        if (selections.get(i).getBusinessColumn() != null) {
          
          // BIG TODO: map bizcol correctly
          
          columnsMap.put("COL" + Integer.toString(i), selections.get(i).getBusinessColumn().getId()); //$NON-NLS-1$
        }
        alias = databaseMeta.quoteField("COL" + Integer.toString(i)); //$NON-NLS-1$
      }else{
        alias = databaseMeta.quoteField(selections.get(i).getBusinessColumn().getId());
      }
      sqlquery.addSelection(formula, alias);
    }

    // FROM
    
    for (int i = 0; i < allTables.size(); i++) {
      AliasedPathBusinessTable tbl = (AliasedPathBusinessTable)allTables.get(i);
      // if __DEFAULT__, no alias
      // otherwise TABLE_ALIAS
      String alias = tbl.table.getDisplayName(locale);
      if (!tbl.alias.equals(DEFAULT_ALIAS)) {
        alias = alias + "_" + tbl.alias; //$NON-NLS-1$
      }
      String schemaName = null;
      if (tbl.table.getTargetSchema() != null) {
        schemaName = databaseMeta.quoteField(tbl.table.getTargetSchema());
      }
      String tableName = databaseMeta.quoteField(tbl.table.getTargetTable());
      sqlquery.addTable(databaseMeta.getSchemaTableCombination(schemaName, tableName), databaseMeta.quoteField(alias));
      
    }
    
    // JOINS
    
    for (int i = 0; i < allRelationships.size(); i++) {
      AliasedRelationshipMeta relation = allRelationships.get(i);
      String join = getJoin(relation, databaseMeta, locale);
      sqlquery.addWhereFormula(join, "AND");
    }

    // WHERE CONDITIONS

    if (constraints != null) {
      boolean first = true;
      for (WhereCondition constraint : constraints) {
        // The ones with aggregates in it are for the HAVING clause
        if (!constraint.hasAggregate() && !constraint.getPMSFormula().hasAggregateFunction()) {
          String sql = constraint.getPMSFormula().generateSQL(locale);
          String[] usedTables = constraint.getPMSFormula().getBusinessTableIDs();
          sqlquery.addWhereFormula(sql, first ? "AND" : constraint.getOperator(), usedTables); //$NON-NLS-1$
          first = false;
        } else {
          sqlquery.addHavingFormula(constraint.getPMSFormula().generateSQL(locale), constraint.getOperator());
        }
      }
    }

    // GROUP BY
    if (group) {
      // can be moved to selection loop
      for (Selection selection : selections) {
        // BusinessColumn businessColumn = selection.getBusinessColumn();
        AliasedSelection aliasedSelection = (AliasedSelection)selection;
        if (!aliasedSelection.hasAggregate()) {
          sqlquery.addGroupBy(getFunctionTableAndColumnForSQL(model, aliasedSelection, databaseMeta, locale), null);
        }
      }
    }
    
    // ORDER BY
    if (orderbys != null) {
      for (OrderBy orderItem : orderbys) {
        AliasedSelection selection = (AliasedSelection)orderItem.getSelection();
        String sqlSelection = null;
        if (!selection.hasFormula()) {
          sqlSelection = getFunctionTableAndColumnForSQL(model, selection, databaseMeta, locale);
        } else {
          sqlSelection = selection.getPMSFormula().generateSQL(locale);
        }
        sqlquery.addOrderBy(sqlSelection, null, !orderItem.isAscending() ? OrderType.DESCENDING : null); //$NON-NLS-1$
      }
    }
    
    
    SQLDialectInterface dialect = SQLDialectFactory.getSQLDialect(databaseMeta);
    String sql = dialect.generateSelectStatement(sqlquery);

    MappedQuery query = new MappedQuery(sql, null, selections);
    
    // defaultPath.getUsedTables();
    
    
    // selections, constraints, order, disableDistinct, locale, etc
    
    // first, generate join paths
    
    // second generate select statement
    
    return query;
  }
  
  public boolean hasFactsInIt(List<Selection> selections, List<WhereCondition> conditions) {
    for (Selection selection : selections) {
      AliasedSelection aliasedSelection = (AliasedSelection)selection;
      if (aliasedSelection.hasAggregate()) {
        return true;
      }
    }
    if (conditions != null) {
      for (WhereCondition condition : conditions) {
        if (condition.hasAggregate())
          return true;
      }
    }
    return false;
  }
  
  protected List<BusinessTable> getTablesInvolved(List<Selection> selections, List<WhereCondition> conditions) {
    Set<BusinessTable> treeSet = new TreeSet<BusinessTable>();

    for (Selection selection : selections) {
      AliasedSelection aliasedSelection = (AliasedSelection)selection;
      if (aliasedSelection.hasFormula()) {
        List cols = aliasedSelection.getPMSFormula().getBusinessColumns();
        Iterator iter = cols.iterator();
        while (iter.hasNext()) {
          BusinessColumn col = (BusinessColumn)iter.next();
          BusinessTable businessTable = col.getBusinessTable();
          treeSet.add(businessTable); //$NON-NLS-1$
        }
      } else {
        BusinessTable businessTable = selection.getBusinessColumn().getBusinessTable();
        treeSet.add(businessTable); //$NON-NLS-1$
      }
    }
    for(WhereCondition condition : conditions) {
      List cols = condition.getBusinessColumns();
      Iterator iter = cols.iterator();
      while (iter.hasNext()) {
        BusinessColumn col = (BusinessColumn)iter.next();
        BusinessTable businessTable = col.getBusinessTable();
        treeSet.add(businessTable); //$NON-NLS-1$
      }
    }
    return new ArrayList<BusinessTable>(treeSet);
  }
  
  // we should do something with this other than a static method that is alias aware.  
  // The folks that call this should be alias aware or not, and call a different method possibly?
  // this is primarily due to the context that would need to get passed into PMSFormula
  // we don't want the pentaho MQL solution to ever come across aliases, etc. 
  public static String getFunctionTableAndColumnForSQL(BusinessModel businessModel, AliasedSelection selection, DatabaseMeta databaseMeta, String locale) {
      if (selection.getBusinessColumn().isExact()) { 
        // convert to sql using libformula subsystem
        try {
          // we'll need to pass in some context to PMSFormula so it can resolve aliases if necessary
          PMSFormula formula = new AliasAwarePMSFormula(businessModel, selection.getBusinessColumn().getBusinessTable(), databaseMeta, selection.getBusinessColumn().getFormula(), selection.getAlias());
          formula.parseAndValidate();
          return formula.generateSQL(locale);
        } catch (PentahoMetadataException e) {
          // this is for backwards compatibility.
          // eventually throw any errors
          throw new RuntimeException(Messages.getErrorString("BusinessColumn.ERROR_0001_FAILED_TO_PARSE_FORMULA", selection.getBusinessColumn().getFormula())); //$NON-NLS-1$  
        }
      } else {
          String tableColumn = ""; //$NON-NLS-1$
          
          // TODO: WPG: is this correct?  shouldn't we be getting an alias for the table vs. it's display name?
          String tblName = selection.getBusinessColumn().getBusinessTable().getDisplayName(locale);
          if (!selection.getAlias().equals(DEFAULT_ALIAS)) {
            tblName += "_" + selection.getAlias(); //$NON-NLS-1$
          }
          tableColumn += databaseMeta.quoteField( tblName  );
          tableColumn += "."; //$NON-NLS-1$
          
          // TODO: WPG: instead of using formula, shouldn't we use the physical column's name?
          tableColumn += databaseMeta.quoteField( selection.getBusinessColumn().getFormula() );
          
          if (selection.getBusinessColumn().hasAggregate()) // For the having clause, for example: HAVING sum(turnover) > 100
          {
              return getFunctionExpression(selection.getBusinessColumn(), tableColumn, databaseMeta);
          }
          else
          {
              return tableColumn;
          }
      }
  }

  
  public String getJoin(AliasedRelationshipMeta relation, DatabaseMeta databaseMeta, String locale) throws PentahoMetadataException
  {
    String join=""; //$NON-NLS-1$
    
    if (relation.relation.isComplex()) {
      throw new PentahoMetadataException("unsupported"); //$NON-NLS-1$
      // join = relation.getComplexJoin();
    } else if (relation.relation.getTableFrom() != null && relation.relation.getTableTo() != null && relation.relation.getFieldFrom() !=null && relation.relation.getFieldTo() != null) {
        String leftAlias = relation.relation.getTableTo().getDisplayName(locale);
        if (!relation.leftAlias.equals(DEFAULT_ALIAS)) {
          leftAlias = leftAlias + "_" + relation.leftAlias; //$NON-NLS-1$
        } 

        String rightAlias = relation.relation.getTableFrom().getDisplayName(locale);
        if (!relation.rightAlias.equals(DEFAULT_ALIAS)) {
          rightAlias = rightAlias + "_" + relation.rightAlias; //$NON-NLS-1$
        }             
        
        // Left side
        join  = databaseMeta.quoteField(rightAlias );
        join += "."; //$NON-NLS-1$
        join += databaseMeta.quoteField( relation.relation.getFieldFrom().getFormula() );
        
        // Equals
        join += " = "; //$NON-NLS-1$
        
        // Right side
        join += databaseMeta.quoteField(leftAlias );
        join += "."; //$NON-NLS-1$
        join += databaseMeta.quoteField( relation.relation.getFieldTo().getFormula() );
    }
    
    return join;
  }

  class AliasedRelationshipMeta {
    String leftAlias;
    String rightAlias;
    RelationshipMeta relation;
    AliasedRelationshipMeta(String left, String right, RelationshipMeta rel) {
      this.leftAlias = left;
      this.rightAlias = right;
      this.relation = rel;
    }
  }
  
  protected void traversePath(String alias, BusinessTable aliasedTable, Path aliasedPath, List<BusinessTable> aliasedTables, List<BusinessTable> defaultTables, List<AliasedPathBusinessTable> allTables, List<AliasedRelationshipMeta> allRelationships) {
    AliasedPathBusinessTable aliasedPathTable = new AliasedPathBusinessTable(alias, aliasedTable);
    if (allTables.contains(aliasedPathTable)) {
      allTables.add(aliasedPathTable);
    }
    allTables.add(aliasedPathTable);
    List<RelationshipMeta> cachedAliasedPath = new ArrayList<RelationshipMeta>();
    for (int i = 0; i < aliasedPath.size(); i++) {
      cachedAliasedPath.add(aliasedPath.getRelationship(i));
    }
    for (int i = 0; i < cachedAliasedPath.size(); i++) {
      RelationshipMeta rel = cachedAliasedPath.get(i);
      int index = -1;
      for (int j = 0; j < aliasedPath.size(); j++) {
        if (aliasedPath.getRelationship(j) == rel) {
          index = j;
          break;
        }
      }
      if (index == -1) {
        continue;
      }
      if (rel.isUsingTable(aliasedTable)) {
        // this needs to either be an aliased relation or a alias to default relation
        boolean joinsToADefaultTable = false;
        for (BusinessTable defaultTable : defaultTables) {
          if (rel.isUsingTable(defaultTable)) {
            boolean inAliasedTables = false;
            for (BusinessTable aliased : aliasedTables) { 
              if (defaultTable.equals(aliased)) {
                inAliasedTables = true;
              }
            }
          
            if (!inAliasedTables) {
              joinsToADefaultTable = true;
              // this relation will join to the default path 
              aliasedPath.removeRelationship(index);
              String leftAlias = null;
              String rightAlias = null;
              if (aliasedTable.equals(rel.getTableTo())) {
                leftAlias = alias;
                rightAlias = DEFAULT_ALIAS;
              } else {
                leftAlias = DEFAULT_ALIAS;
                rightAlias = alias;
              }
              allRelationships.add(new AliasedRelationshipMeta(leftAlias,rightAlias,rel));
            }
          }
        }
        if (!joinsToADefaultTable) {
            // this relation is joined to either an aliased or a soon to be aliased table
            aliasedPath.removeRelationship(index);
            // we need to add this to the aliased path list, along with the table we're joining to
            // check for uniqueness?
            allRelationships.add(new AliasedRelationshipMeta(alias,alias,rel));
            BusinessTable tbl = rel.getTableTo() == aliasedTable ? rel.getTableFrom() : rel.getTableTo();
            traversePath(alias, tbl, aliasedPath, aliasedTables, defaultTables, allTables, allRelationships);
        }
      }
    }
  }
}