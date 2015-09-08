/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements;

import java.nio.ByteBuffer;
import java.util.*;

import com.google.common.collect.Iterables;

import static org.apache.cassandra.cql3.statements.RequestValidations.checkNull;

import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.config.MaterializedViewDefinition;
import org.apache.cassandra.cql3.*;
import org.apache.cassandra.cql3.ColumnIdentifier.Raw;
import org.apache.cassandra.cql3.functions.Function;
import org.apache.cassandra.cql3.restrictions.StatementRestrictions;
import org.apache.cassandra.cql3.selection.Selection;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.marshal.BooleanType;
import org.apache.cassandra.db.partitions.*;
import org.apache.cassandra.db.rows.RowIterator;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.apache.cassandra.exceptions.UnauthorizedException;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.paxos.Commit;
import org.apache.cassandra.thrift.ThriftValidation;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.apache.cassandra.triggers.TriggerExecutor;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.UUIDGen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.apache.cassandra.cql3.statements.RequestValidations.checkFalse;
import static org.apache.cassandra.cql3.statements.RequestValidations.checkNotNull;

/*
 * Abstract parent class of individual modifications, i.e. INSERT, UPDATE and DELETE.
 */
//insert、update、delete三种类型的语句共用
public abstract class ModificationStatement implements CQLStatement
{
    protected static final Logger logger = LoggerFactory.getLogger(ModificationStatement.class);

    private static final ColumnIdentifier CAS_RESULT_COLUMN = new ColumnIdentifier("[applied]", false);

    protected final StatementType type;

    private final int boundTerms;
    public final CFMetaData cfm;
    private final Attributes attrs;

//<<<<<<< HEAD
//    //只能是PARTITION_KEY和CLUSTERING_COLUMN中的字段
//    protected final Map<ColumnIdentifier, Restriction> processedKeys = new HashMap<>();
//
//    //只能是REGULAR和COMPACT_VALUE字段
//    private final List<Operation> regularOperations = new ArrayList<>();
//    private final List<Operation> staticOperations = new ArrayList<>();
//=======
    private final StatementRestrictions restrictions;

    private final Operations operations;

//<<<<<<< HEAD
//    // Separating normal and static conditions makes things somewhat easier
//    private List<ColumnCondition> columnConditions; //只能是REGULAR和COMPACT_VALUE字段
//    private List<ColumnCondition> staticConditions;
//    private boolean ifNotExists;
//    private boolean ifExists;
//=======
    private final PartitionColumns updatedColumns;

    private final Conditions conditions;

    private final PartitionColumns conditionColumns;

    private final PartitionColumns requiresRead;

    public ModificationStatement(StatementType type,
                                 int boundTerms,
                                 CFMetaData cfm,
                                 Operations operations,
                                 StatementRestrictions restrictions,
                                 Conditions conditions,
                                 Attributes attrs)
    {
        this.type = type;
        this.boundTerms = boundTerms;
        this.cfm = cfm;
        this.restrictions = restrictions;
        this.operations = operations;
        this.conditions = conditions;
        this.attrs = attrs;

        if (!conditions.isEmpty())
        {
            checkFalse(cfm.isCounter(), "Conditional updates are not supported on counter tables");
            checkFalse(attrs.isTimestampSet(), "Cannot provide custom timestamp for conditional updates");
        }

        PartitionColumns.Builder conditionColumnsBuilder = PartitionColumns.builder();
        Iterable<ColumnDefinition> columns = conditions.getColumns();
        if (columns != null)
            conditionColumnsBuilder.addAll(columns);

        PartitionColumns.Builder updatedColumnsBuilder = PartitionColumns.builder();
        PartitionColumns.Builder requiresReadBuilder = PartitionColumns.builder();
        for (Operation operation : operations)
        {
            updatedColumnsBuilder.add(operation.column);
            // If the operation requires a read-before-write and we're doing a conditional read, we want to read
            // the affected column as part of the read-for-conditions paxos phase (see #7499).
            if (operation.requiresRead())
            {
                conditionColumnsBuilder.add(operation.column);
                requiresReadBuilder.add(operation.column);
            }
        }

        PartitionColumns modifiedColumns = updatedColumnsBuilder.build();
        // Compact tables have not row marker. So if we don't actually update any particular column,
        // this means that we're only updating the PK, which we allow if only those were declared in
        // the definition. In that case however, we do went to write the compactValueColumn (since again
        // we can't use a "row marker") so add it automatically.
        if (cfm.isCompactTable() && modifiedColumns.isEmpty() && updatesRegularRows())
            modifiedColumns = cfm.partitionColumns();

        this.updatedColumns = modifiedColumns;
        this.conditionColumns = conditionColumnsBuilder.build();
        this.requiresRead = requiresReadBuilder.build();
    }

    public Iterable<Function> getFunctions()
    {
        return Iterables.concat(attrs.getFunctions(),
                                restrictions.getFunctions(),
                                operations.getFunctions(),
                                conditions.getFunctions());
    }

    public abstract void addUpdateForKey(PartitionUpdate update, Clustering clustering, UpdateParameters params);

    public abstract void addUpdateForKey(PartitionUpdate update, Slice slice, UpdateParameters params);

    public int getBoundTerms()
    {
        return boundTerms;
    }

    public String keyspace()
    {
        return cfm.ksName;
    }

    public String columnFamily()
    {
        return cfm.cfName;
    }

    public boolean isCounter()
    {
        return cfm.isCounter();
    }

    public boolean isMaterializedView()
    {
        return cfm.isMaterializedView();
    }

    public boolean hasMaterializedViews()
    {
        return !cfm.getMaterializedViews().isEmpty();
    }

    public long getTimestamp(long now, QueryOptions options) throws InvalidRequestException
    {
        return attrs.getTimestamp(now, options);
    }

    public boolean isTimestampSet()
    {
        return attrs.isTimestampSet();
    }

    public int getTimeToLive(QueryOptions options) throws InvalidRequestException
    {
        return attrs.getTimeToLive(options);
    }

    public void checkAccess(ClientState state) throws InvalidRequestException, UnauthorizedException
    {
        state.hasColumnFamilyAccess(keyspace(), columnFamily(), Permission.MODIFY);

        // CAS updates can be used to simulate a SELECT query, so should require Permission.SELECT as well.
        if (hasConditions())
            state.hasColumnFamilyAccess(keyspace(), columnFamily(), Permission.SELECT);

        // MV updates need to get the current state from the table, and might update the materialized views
        // Require Permission.SELECT on the base table, and Permission.MODIFY on the views
        if (hasMaterializedViews())
        {
            state.hasColumnFamilyAccess(keyspace(), columnFamily(), Permission.SELECT);
            for (MaterializedViewDefinition view : cfm.getMaterializedViews())
                state.hasColumnFamilyAccess(keyspace(), view.viewName, Permission.MODIFY);
        }

        for (Function function : getFunctions())
            state.ensureHasPermission(Permission.EXECUTE, function);
    }

    public void validate(ClientState state) throws InvalidRequestException
    {
        checkFalse(hasConditions() && attrs.isTimestampSet(), "Cannot provide custom timestamp for conditional updates");
        checkFalse(isCounter() && attrs.isTimestampSet(), "Cannot provide custom timestamp for counter updates");
        checkFalse(isCounter() && attrs.isTimeToLiveSet(), "Cannot provide custom TTL for counter updates");
        checkFalse(isMaterializedView(), "Cannot directly modify a materialized view");
    }

    public PartitionColumns updatedColumns()
    {
        return updatedColumns;
    }

    public PartitionColumns conditionColumns()
    {
        return conditionColumns;
    }

    public boolean updatesRegularRows()
    {
        // We're updating regular rows if all the clustering columns are provided.
        // Note that the only case where we're allowed not to provide clustering
        // columns is if we set some static columns, and in that case no clustering
        // columns should be given. So in practice, it's enough to check if we have
        // either the table has no clustering or if it has at least one of them set.
        return cfm.clusteringColumns().isEmpty() || restrictions.hasClusteringColumnsRestriction();
    }

    public boolean updatesStaticRow()
    {
        return operations.appliesToStaticColumns();
    }

    public List<Operation> getRegularOperations()
    {
        return operations.regularOperations();
    }

    public List<Operation> getStaticOperations()
    {
        return operations.staticOperations();
    }

    public Iterable<Operation> allOperations()
    {
        return operations;
    }

    public Iterable<ColumnDefinition> getColumnsWithConditions()
    {
         return conditions.getColumns();
    }

    public boolean hasIfNotExistCondition()
    {
        return conditions.isIfNotExists();
    }

    public boolean hasIfExistCondition()
    {
//<<<<<<< HEAD
//        return ifExists;
//    }
//
//    private void addKeyValues(ColumnDefinition def, Restriction values) throws InvalidRequestException
//    {
//        if (def.kind == ColumnDefinition.Kind.CLUSTERING)
//            hasNoClusteringColumns = false;
//        if (processedKeys.put(def.name, values) != null)
//            throw new InvalidRequestException(String.format("Multiple definitions found for PRIMARY KEY part %s", def.name));
//    }
//
//    public void addKeyValue(ColumnDefinition def, Term value) throws InvalidRequestException
//    {
//        addKeyValues(def, new SingleColumnRestriction.EQRestriction(def, value));
//    }
//
//    //对应Update和Delete的where子句
//    //Update和Delete的where子句必须包含，where子句中只支持and，
//    //并且只能出现PARTITION_KEY和CLUSTERING_COLUMN，
//    //并且只有PARTITION_KEY和CLUSTERING_COLUMN能使用"="操作符，
//    //并且只有PARTITION_KEY能使用"in"操作符。
//    public void processWhereClause(List<Relation> whereClause, VariableSpecifications names) throws InvalidRequestException
//    {
//        for (Relation relation : whereClause)
//        {
//            if (relation.isMultiColumn())
//            {
//                throw new InvalidRequestException(
//                        String.format("Multi-column relations cannot be used in WHERE clauses for UPDATE and DELETE statements: %s", relation));
//            }
//            //如果是TokenRelation，这里就出cast异常了
////            SingleColumnRelation rel = (SingleColumnRelation) relation;
////
////            if (rel.onToken())
////                throw new InvalidRequestException(String.format("The token function cannot be used in WHERE clauses for UPDATE and DELETE statements: %s", relation));
//
//            if (relation.onToken())
//                throw new InvalidRequestException(String.format("The token function cannot be used in WHERE clauses for UPDATE and DELETE statements: %s", relation));
//            
//            SingleColumnRelation rel = (SingleColumnRelation) relation;
//            
//            ColumnIdentifier id = rel.getEntity().prepare(cfm);
//            ColumnDefinition def = cfm.getColumnDefinition(id);
//            if (def == null)
//                throw new InvalidRequestException(String.format("Unknown key identifier %s", id));
//
//            switch (def.kind)
//            {
//                case PARTITION_KEY:
//                case CLUSTERING:
//                    Restriction restriction;
//
//                    if (rel.isEQ() || (def.isPartitionKey() && rel.isIN()))
//                    {
//                        restriction = rel.toRestriction(cfm, names);
//                    }
//                    else
//                    {
//                        throw new InvalidRequestException(String.format("Invalid operator %s for PRIMARY KEY part %s", rel.operator(), def.name));
//                    }
//
//                    addKeyValues(def, restriction);
//                    break;
//                default:
//                    throw new InvalidRequestException(String.format("Non PRIMARY KEY %s found in where clause", def.name));
//            }
//        }
//=======
        return conditions.isIfExists();
    }

    //执行Insert、Update、Delete时都必须指定PARTITION_KEY
    public List<ByteBuffer> buildPartitionKeyNames(QueryOptions options)
    throws InvalidRequestException
    {
//<<<<<<< HEAD
//        MultiCBuilder keyBuilder = MultiCBuilder.create(cfm.getKeyValidatorAsClusteringComparator());
//        for (ColumnDefinition def : cfm.partitionKeyColumns())
//        {
//            Restriction r = checkNotNull(processedKeys.get(def.name), "Missing mandatory PRIMARY KEY part %s", def.name);
//            r.appendTo(keyBuilder, options);
//        }
//
//        //只有PARTITION_KEY中的最后一个字段允许在in操作中使用多个值
//        //例如PARTITION_KEY是a与b，那么where a=x and b in(y, z)
//        //就会得到两个PARTITION_KEY: (x,y)和(x,z)
//        NavigableSet<Clustering> clusterings = keyBuilder.build();
//        List<ByteBuffer> keys = new ArrayList<ByteBuffer>(clusterings.size());
//        for (Clustering clustering : clusterings)
//        {
//            ByteBuffer key = CFMetaData.serializePartitionKey(clustering);
//            ThriftValidation.validateKey(cfm, key);
//            keys.add(key);
//        }
//        return keys;
//=======
        return restrictions.getPartitionKeys(options);
    }

    public NavigableSet<Clustering> createClustering(QueryOptions options)
    throws InvalidRequestException
    {
        if (appliesOnlyToStaticColumns() && !restrictions.hasClusteringColumnsRestriction())
            return FBUtilities.singleton(CBuilder.STATIC_BUILDER.build(), cfm.comparator);

        return restrictions.getClusteringColumns(options);
    }

    /**
     * Checks that the modification only apply to static columns.
     * @return <code>true</code> if the modification only apply to static columns, <code>false</code> otherwise.
     */
    private boolean appliesOnlyToStaticColumns()
    {
//<<<<<<< HEAD
//        CBuilder builder = CBuilder.create(cfm.comparator);
//        MultiCBuilder multiBuilder = MultiCBuilder.wrap(builder);
//
//        ColumnDefinition firstEmptyKey = null;
//        for (ColumnDefinition def : cfm.clusteringColumns())
//        {
//            Restriction r = processedKeys.get(def.name);
//            if (r == null)
//            {
//                firstEmptyKey = def;
//                //满足这个if条件的只有update语句，并且是CreateTableStatement.comparator中的第4种情况
//                checkFalse(requireFullClusteringKey() && !cfm.isDense() && cfm.isCompound(),
//                           "Missing mandatory PRIMARY KEY part %s", def.name);
//            }
//            else if (firstEmptyKey != null) //CLUSTERING_COLUMN中最前面的字段不允许为空，比如a、b两字段，不能只出现b
//            {
//                throw invalidRequest("Missing PRIMARY KEY part %s since %s is set", firstEmptyKey.name, def.name);
//            }
//            else
//            {
//                r.appendTo(multiBuilder, options);
//            }
//        }
//        return builder;
//=======
        return appliesOnlyToStaticColumns(operations, conditions);
    }

    /**
     * Checks that the specified operations and conditions only apply to static columns.
     * @return <code>true</code> if the specified operations and conditions only apply to static columns,
     * <code>false</code> otherwise.
     */
    public static boolean appliesOnlyToStaticColumns(Operations operation, Conditions conditions)
    {
        return !operation.appliesToRegularColumns() && !conditions.appliesToRegularColumns()
                && (operation.appliesToStaticColumns() || conditions.appliesToStaticColumns());
    }

    public boolean requiresRead()
    {
        // Lists SET operation incurs a read.
        for (Operation op : allOperations())
            if (op.requiresRead())
                return true;

        return false;
    }

//<<<<<<< HEAD
//    //在executeWithoutCondition时使用，但是在executeWithCondition时不使用
//    //只有Lists类的Discarder、DiscarderByIndex、SetterByIndex需要读
//    protected Map<DecoratedKey, Partition> readRequiredLists(Collection<ByteBuffer> partitionKeys, CBuilder cbuilder, boolean local, ConsistencyLevel cl)
//    throws RequestExecutionException, RequestValidationException
//=======
    private Map<DecoratedKey, Partition> readRequiredLists(Collection<ByteBuffer> partitionKeys,
                                                           ClusteringIndexFilter filter,
                                                           DataLimits limits,
                                                           boolean local,
                                                           ConsistencyLevel cl)
    {
        if (!requiresRead())
            return null;

        try
        {
            cl.validateForRead(keyspace());
        }
        catch (InvalidRequestException e)
        {
            throw new InvalidRequestException(String.format("Write operation require a read but consistency %s is not supported on reads", cl));
        }

        List<SinglePartitionReadCommand<?>> commands = new ArrayList<>(partitionKeys.size());
        int nowInSec = FBUtilities.nowInSeconds();
        for (ByteBuffer key : partitionKeys)
            commands.add(SinglePartitionReadCommand.create(cfm,
                                                           nowInSec,
                                                           ColumnFilter.selection(this.requiresRead),
                                                           RowFilter.NONE,
                                                           limits,
                                                           cfm.decorateKey(key),
                                                           filter));

        SinglePartitionReadCommand.Group group = new SinglePartitionReadCommand.Group(commands, DataLimits.NONE);

        if (local)
        {
            try (ReadOrderGroup orderGroup = group.startOrderGroup(); PartitionIterator iter = group.executeInternal(orderGroup))
            {
                return asMaterializedMap(iter);
            }
        }

        try (PartitionIterator iter = group.execute(cl, null))
        {
            return asMaterializedMap(iter);
        }
    }

    private Map<DecoratedKey, Partition> asMaterializedMap(PartitionIterator iterator)
    {
        Map<DecoratedKey, Partition> map = new HashMap<>();
        while (iterator.hasNext())
        {
            try (RowIterator partition = iterator.next())
            {
                map.put(partition.partitionKey(), FilteredPartition.create(partition));
            }
        }
        return map;
    }

    public boolean hasConditions()
    {
        return !conditions.isEmpty();
    }

    public ResultMessage execute(QueryState queryState, QueryOptions options)
    throws RequestExecutionException, RequestValidationException
    {
        if (options.getConsistency() == null)
            throw new InvalidRequestException("Invalid empty consistency level");

        if (hasConditions() && options.getProtocolVersion() == 1)
            throw new InvalidRequestException("Conditional updates are not supported by the protocol version in use. You need to upgrade to a driver using the native protocol v2.");

        return hasConditions()
             ? executeWithCondition(queryState, options)
             : executeWithoutCondition(queryState, options);
    }

    private ResultMessage executeWithoutCondition(QueryState queryState, QueryOptions options)
    throws RequestExecutionException, RequestValidationException
    {
        ConsistencyLevel cl = options.getConsistency();
        if (isCounter())
            cl.validateCounterForWrite(cfm);
        else
            cl.validateForWrite(cfm.ksName);

        Collection<? extends IMutation> mutations = getMutations(options, false, options.getTimestamp(queryState));
        if (!mutations.isEmpty())
            StorageProxy.mutateWithTriggers(mutations, cl, false);

        //我加上的，用于测试，触发memtable的flush
        if(queryState.getClientState().count > 200) {
            Keyspace.open(cfm.ksName).getColumnFamilyStore(cfm.cfName).forceBlockingFlush();
            queryState.getClientState().count = 0;
        }
        queryState.getClientState().count++;
        return null;
    }

    public ResultMessage executeWithCondition(QueryState queryState, QueryOptions options)
    throws RequestExecutionException, RequestValidationException
    {
        CQL3CasRequest request = makeCasRequest(queryState, options);

        try (RowIterator result = StorageProxy.cas(keyspace(),
                                                   columnFamily(),
                                                   request.key,
                                                   request,
                                                   options.getSerialConsistency(),
                                                   options.getConsistency(),
                                                   queryState.getClientState()))
        {
            return new ResultMessage.Rows(buildCasResultSet(result, options));
        }
    }

    private CQL3CasRequest makeCasRequest(QueryState queryState, QueryOptions options)
    {
        List<ByteBuffer> keys = buildPartitionKeyNames(options);
        // We don't support IN for CAS operation so far
        checkFalse(keys.size() > 1,
                   "IN on the partition key is not supported with conditional %s",
                   type.isUpdate()? "updates" : "deletions");

        DecoratedKey key = cfm.decorateKey(keys.get(0));
        long now = options.getTimestamp(queryState);
        SortedSet<Clustering> clusterings = createClustering(options);

        checkFalse(clusterings.size() > 1,
                   "IN on the clustering key columns is not supported with conditional %s",
                    type.isUpdate()? "updates" : "deletions");

        Clustering clustering = Iterables.getOnlyElement(clusterings);

        CQL3CasRequest request = new CQL3CasRequest(cfm, key, false, conditionColumns(), updatesRegularRows(), updatesStaticRow());

        addConditions(clustering, request, options);
        request.addRowUpdate(clustering, this, options, now);

        return request;
    }

    public void addConditions(Clustering clustering, CQL3CasRequest request, QueryOptions options) throws InvalidRequestException
    {
        conditions.addConditionsTo(request, clustering, options);
    }

    private ResultSet buildCasResultSet(RowIterator partition, QueryOptions options) throws InvalidRequestException
    {
        return buildCasResultSet(keyspace(), columnFamily(), partition, getColumnsWithConditions(), false, options);
    }

    public static ResultSet buildCasResultSet(String ksName, String tableName, RowIterator partition, Iterable<ColumnDefinition> columnsWithConditions, boolean isBatch, QueryOptions options)
    throws InvalidRequestException
    {
        boolean success = partition == null;

        ColumnSpecification spec = new ColumnSpecification(ksName, tableName, CAS_RESULT_COLUMN, BooleanType.instance);
        ResultSet.ResultMetadata metadata = new ResultSet.ResultMetadata(Collections.singletonList(spec));
        List<List<ByteBuffer>> rows = Collections.singletonList(Collections.singletonList(BooleanType.instance.decompose(success)));

        //只是一个默认结果集，占位
        ResultSet rs = new ResultSet(metadata, rows);
        return success ? rs : merge(rs, buildCasFailureResultSet(partition, columnsWithConditions, isBatch, options));
    }

    private static ResultSet merge(ResultSet left, ResultSet right)
    {
        if (left.size() == 0)
            return right;
        else if (right.size() == 0)
            return left;

        assert left.size() == 1;
        int size = left.metadata.names.size() + right.metadata.names.size();
        List<ColumnSpecification> specs = new ArrayList<ColumnSpecification>(size);
        specs.addAll(left.metadata.names);
        specs.addAll(right.metadata.names);
        List<List<ByteBuffer>> rows = new ArrayList<>(right.size());
        for (int i = 0; i < right.size(); i++)
        {
            List<ByteBuffer> row = new ArrayList<ByteBuffer>(size);
            row.addAll(left.rows.get(0));
            row.addAll(right.rows.get(i));
            rows.add(row);
        }
        return new ResultSet(new ResultSet.ResultMetadata(specs), rows);
    }

    private static ResultSet buildCasFailureResultSet(RowIterator partition, Iterable<ColumnDefinition> columnsWithConditions, boolean isBatch, QueryOptions options)
    throws InvalidRequestException
    {
        CFMetaData cfm = partition.metadata();
        Selection selection;
        if (columnsWithConditions == null)
        {
            selection = Selection.wildcard(cfm);
        }
        else
        {
            // We can have multiple conditions on the same columns (for collections) so use a set
            // to avoid duplicate, but preserve the order just to it follows the order of IF in the query in general
            Set<ColumnDefinition> defs = new LinkedHashSet<>();
            // Adding the partition key for batches to disambiguate if the conditions span multipe rows (we don't add them outside
            // of batches for compatibility sakes).
            if (isBatch)
            {
                defs.addAll(cfm.partitionKeyColumns());
                defs.addAll(cfm.clusteringColumns());
            }
            for (ColumnDefinition def : columnsWithConditions)
                defs.add(def);
            selection = Selection.forColumns(cfm, new ArrayList<>(defs));

        }

        Selection.ResultSetBuilder builder = selection.resultSetBuilder(false);
        SelectStatement.forSelection(cfm, selection).processPartition(partition, options, builder, FBUtilities.nowInSeconds());

        return builder.build(options.getProtocolVersion());
    }

    public ResultMessage executeInternal(QueryState queryState, QueryOptions options) throws RequestValidationException, RequestExecutionException
    {
        return hasConditions()
               ? executeInternalWithCondition(queryState, options)
               : executeInternalWithoutCondition(queryState, options);
    }

    public ResultMessage executeInternalWithoutCondition(QueryState queryState, QueryOptions options) throws RequestValidationException, RequestExecutionException
    {
        for (IMutation mutation : getMutations(options, true, queryState.getTimestamp()))
        {
            assert mutation instanceof Mutation || mutation instanceof CounterMutation;

            if (mutation instanceof Mutation)
                ((Mutation) mutation).apply();
            else if (mutation instanceof CounterMutation)
                ((CounterMutation) mutation).apply();
        }
        return null;
    }

    public ResultMessage executeInternalWithCondition(QueryState state, QueryOptions options) throws RequestValidationException, RequestExecutionException
    {
        CQL3CasRequest request = makeCasRequest(state, options);
        try (RowIterator result = casInternal(request, state))
        {
            return new ResultMessage.Rows(buildCasResultSet(result, options));
        }
    }

    static RowIterator casInternal(CQL3CasRequest request, QueryState state)
    {
        UUID ballot = UUIDGen.getTimeUUIDFromMicros(state.getTimestamp());

        SinglePartitionReadCommand<?> readCommand = request.readCommand(FBUtilities.nowInSeconds());
        FilteredPartition current;
        try (ReadOrderGroup orderGroup = readCommand.startOrderGroup(); PartitionIterator iter = readCommand.executeInternal(orderGroup))
        {
            current = FilteredPartition.create(PartitionIterators.getOnlyElement(iter, readCommand));
        }

        if (!request.appliesTo(current))
            return current.rowIterator();

        PartitionUpdate updates = request.makeUpdates(current);
        updates = TriggerExecutor.instance.execute(updates);

        Commit proposal = Commit.newProposal(ballot, updates);
        proposal.makeMutation().apply();
        return null;
    }

    /**
     * Convert statement into a list of mutations to apply on the server
     *
     * @param options value for prepared statement markers
     * @param local if true, any requests (for collections) performed by getMutation should be done locally only.
     * @param now the current timestamp in microseconds to use if no timestamp is user provided.
     *
     * @return list of the mutations
     */
    //在executeWithoutCondition时使用，但是在executeWithCondition时不使用
    private Collection<? extends IMutation> getMutations(QueryOptions options, boolean local, long now)
    {
        UpdatesCollector collector = new UpdatesCollector(updatedColumns, 1);
        addUpdates(collector, options, local, now);
        return collector.toMutations();
    }

    final void addUpdates(UpdatesCollector collector,
                          QueryOptions options,
                          boolean local,
                          long now)
    {
        List<ByteBuffer> keys = buildPartitionKeyNames(options);

        if (type.allowClusteringColumnSlices()
                && restrictions.hasClusteringColumnsRestriction()
                && restrictions.isColumnRange())
        {
            Slices slices = createSlice(options);

            // If all the ranges were invalid we do not need to do anything.
            if (slices.isEmpty())
                return;

            UpdateParameters params = makeUpdateParameters(keys,
                                                           new ClusteringIndexSliceFilter(slices, false),
                                                           options,
                                                           DataLimits.NONE,
                                                           local,
                                                           now);
            for (ByteBuffer key : keys)
            {
                ThriftValidation.validateKey(cfm, key);
                DecoratedKey dk = cfm.decorateKey(key);

                PartitionUpdate upd = collector.getPartitionUpdate(cfm, dk, options.getConsistency());

                for (Slice slice : slices)
                    addUpdateForKey(upd, slice, params);
            }
        }
        else
        {
            NavigableSet<Clustering> clusterings = createClustering(options);

            UpdateParameters params = makeUpdateParameters(keys, clusterings, options, local, now);

            for (ByteBuffer key : keys)
            {
                ThriftValidation.validateKey(cfm, key);
                DecoratedKey dk = cfm.decorateKey(key);

                PartitionUpdate upd = collector.getPartitionUpdate(cfm, dk, options.getConsistency());

                if (clusterings.isEmpty())
                {
                    addUpdateForKey(upd, Clustering.EMPTY, params);
                }
                else
                {
                    for (Clustering clustering : clusterings)
                        addUpdateForKey(upd, clustering, params);
                }
            }
        }
    }

    private Slices createSlice(QueryOptions options)
    {
        SortedSet<Slice.Bound> startBounds = restrictions.getClusteringColumnsBounds(Bound.START, options);
        SortedSet<Slice.Bound> endBounds = restrictions.getClusteringColumnsBounds(Bound.END, options);

        return toSlices(startBounds, endBounds);
    }

    private UpdateParameters makeUpdateParameters(Collection<ByteBuffer> keys,
                                                  NavigableSet<Clustering> clusterings,
                                                  QueryOptions options,
                                                  boolean local,
                                                  long now)
    {
        if (clusterings.contains(Clustering.STATIC_CLUSTERING))
            return makeUpdateParameters(keys,
                                        new ClusteringIndexSliceFilter(Slices.ALL, false),
                                        options,
                                        DataLimits.cqlLimits(1),
                                        local,
                                        now);

        return makeUpdateParameters(keys,
                                    new ClusteringIndexNamesFilter(clusterings, false),
                                    options,
                                    DataLimits.NONE,
                                    local,
                                    now);
    }

    private UpdateParameters makeUpdateParameters(Collection<ByteBuffer> keys,
                                                  ClusteringIndexFilter filter,
                                                  QueryOptions options,
                                                  DataLimits limits,
                                                  boolean local,
                                                  long now)
    {
        // Some lists operation requires reading
        Map<DecoratedKey, Partition> lists = readRequiredLists(keys, filter, limits, local, options.getConsistency());
        return new UpdateParameters(cfm, updatedColumns(), options, getTimestamp(now, options), getTimeToLive(options), lists, true);
    }

    private Slices toSlices(SortedSet<Slice.Bound> startBounds, SortedSet<Slice.Bound> endBounds)
    {
        assert startBounds.size() == endBounds.size();

        Slices.Builder builder = new Slices.Builder(cfm.comparator);

        Iterator<Slice.Bound> starts = startBounds.iterator();
        Iterator<Slice.Bound> ends = endBounds.iterator();

        while (starts.hasNext())
        {
            Slice slice = Slice.make(starts.next(), ends.next());
            if (!slice.isEmpty(cfm.comparator))
            {
                builder.add(slice);
            }
        }

        return builder.build();
    }

    public static abstract class Parsed extends CFStatement
    {
        private final Attributes.Raw attrs;
        private final List<Pair<ColumnIdentifier.Raw, ColumnCondition.Raw>> conditions;
        private final boolean ifNotExists;
        private final boolean ifExists;

        //对于insert来说attrs可以有timestamp和TTL，conditions必定是null，ifNotExists可以是true和false
        //对于update来说attrs可以有timestamp和TTL，conditions不一定是null，ifNotExists必定是false
        //对于delete来说attrs只能有timestamp，conditions不一定是null，ifNotExists必定是false
        protected Parsed(CFName name, Attributes.Raw attrs, List<Pair<ColumnIdentifier.Raw, ColumnCondition.Raw>> conditions, boolean ifNotExists, boolean ifExists)
        {
            super(name);
            this.attrs = attrs;
            this.conditions = conditions == null ? Collections.<Pair<ColumnIdentifier.Raw, ColumnCondition.Raw>>emptyList() : conditions;
            this.ifNotExists = ifNotExists;
            this.ifExists = ifExists;
        }

        public ParsedStatement.Prepared prepare()
        {
            VariableSpecifications boundNames = getBoundVariables();
            ModificationStatement statement = prepare(boundNames);
            CFMetaData cfm = ThriftValidation.validateColumnFamily(keyspace(), columnFamily());
            return new ParsedStatement.Prepared(statement, boundNames, boundNames.getPartitionKeyBindIndexes(cfm));
        }

        //PARTITION_KEY和CLUSTERING_COLUMN不能出现在if子句中
        public ModificationStatement prepare(VariableSpecifications boundNames)
        {
            CFMetaData metadata = ThriftValidation.validateColumnFamily(keyspace(), columnFamily());

            Attributes preparedAttributes = attrs.prepare(keyspace(), columnFamily());
            preparedAttributes.collectMarkerSpecification(boundNames);

            Conditions preparedConditions = prepareConditions(metadata, boundNames);

            return prepareInternal(metadata,
                                   boundNames,
                                   preparedConditions,
                                   preparedAttributes);
        }

        /**
         * Returns the column conditions.
         *
         * @param metadata the column family meta data
         * @param boundNames the bound names
         * @return the column conditions.
         */
        private Conditions prepareConditions(CFMetaData metadata, VariableSpecifications boundNames)
        {
            // To have both 'IF EXISTS'/'IF NOT EXISTS' and some other conditions doesn't make sense.
            // So far this is enforced by the parser, but let's assert it for sanity if ever the parse changes.
            if (ifExists)
            {
                assert conditions.isEmpty();
                assert !ifNotExists;
                return Conditions.IF_EXISTS_CONDITION;
            }

            if (ifNotExists)
            {
                assert conditions.isEmpty();
                assert !ifExists;
                return Conditions.IF_NOT_EXISTS_CONDITION;
            }

            if (conditions.isEmpty())
                return Conditions.EMPTY_CONDITION;

            return prepareColumnConditions(metadata, boundNames);
        }

        /**
         * Returns the column conditions.
         *
         * @param metadata the column family meta data
         * @param boundNames the bound names
         * @return the column conditions.
         */
        private ColumnConditions prepareColumnConditions(CFMetaData metadata, VariableSpecifications boundNames)
        {
            checkNull(attrs.timestamp, "Cannot provide custom timestamp for conditional updates");

            ColumnConditions.Builder builder = ColumnConditions.newBuilder();

            for (Pair<ColumnIdentifier.Raw, ColumnCondition.Raw> entry : conditions)
            {
                ColumnIdentifier id = entry.left.prepare(metadata);
                ColumnDefinition def = metadata.getColumnDefinition(id);
                checkNotNull(metadata.getColumnDefinition(id), "Unknown identifier %s in IF conditions", id);

                ColumnCondition condition = entry.right.prepare(keyspace(), def);
                condition.collectMarkerSpecification(boundNames);

                checkFalse(def.isPrimaryKeyColumn(), "PRIMARY KEY column '%s' cannot have IF conditions", id);
                builder.add(condition);
            }
            return builder.build();
        }

        protected abstract ModificationStatement prepareInternal(CFMetaData cfm,
                                                                 VariableSpecifications boundNames,
                                                                 Conditions conditions,
                                                                 Attributes attrs);

        /**
         * Creates the restrictions.
         *
         * @param type the statement type
         * @param cfm the column family meta data
         * @param boundNames the bound names
         * @param operations the column operations
         * @param relations the where relations
         * @param conditions the conditions
         * @return the restrictions
         */
        protected static StatementRestrictions newRestrictions(StatementType type,
                                                               CFMetaData cfm,
                                                               VariableSpecifications boundNames,
                                                               Operations operations,
                                                               List<Relation> relations,
                                                               Conditions conditions)
        {
            boolean applyOnlyToStaticColumns = appliesOnlyToStaticColumns(operations, conditions);
            return new StatementRestrictions(type, cfm, relations, boundNames, applyOnlyToStaticColumns, false, false);
        }

        /**
         * Retrieves the <code>ColumnDefinition</code> corresponding to the specified raw <code>ColumnIdentifier</code>.
         *
         * @param cfm the column family meta data
         * @param rawId the raw <code>ColumnIdentifier</code>
         * @return the <code>ColumnDefinition</code> corresponding to the specified raw <code>ColumnIdentifier</code>
         */
        protected static ColumnDefinition getColumnDefinition(CFMetaData cfm, Raw rawId)
        {
            ColumnIdentifier id = rawId.prepare(cfm);
            return checkNotNull(cfm.getColumnDefinition(id), "Unknown identifier %s", id);
        }
    }
}
