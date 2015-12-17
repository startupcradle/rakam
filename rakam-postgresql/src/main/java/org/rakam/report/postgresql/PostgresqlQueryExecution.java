package org.rakam.report.postgresql;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.report.QueryError;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryResult;
import org.rakam.report.QueryStats;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static java.lang.String.format;
import static org.rakam.analysis.postgresql.PostgresqlMetastore.fromSql;

public class PostgresqlQueryExecution implements QueryExecution {

    private final CompletableFuture<QueryResult> result;
    private final String query;

    public PostgresqlQueryExecution(JDBCPoolDataSource connectionPool, String sqlQuery, boolean update) {
        this.query = sqlQuery;

        // TODO: unnecessary threads will be spawn
        Supplier<QueryResult> task = () -> {
            try (Connection connection = connectionPool.getConnection()) {
                Statement statement = connection.createStatement();
                if (update) {
                    statement.executeUpdate(sqlQuery);
                    // CREATE TABLE queries doesn't return any value and
                    // fail when using executeQuery so we face the result data
                    List<SchemaField> cols = ImmutableList.of(new SchemaField("result", FieldType.BOOLEAN, true));
                    List<List<Object>> data = ImmutableList.of(ImmutableList.of(true));
                    return new QueryResult(cols, data);
                } else {
                    long beforeExecuted = System.currentTimeMillis();
                    ResultSet resultSet = statement.executeQuery(sqlQuery);
                    final QueryResult queryResult = resultSetToQueryResult(resultSet,
                            System.currentTimeMillis() - beforeExecuted);
                    return queryResult;
                }
            } catch (Exception e) {
                QueryError error;
                if (e instanceof SQLException) {
                    SQLException cause = (SQLException) e;
                    error = new QueryError(cause.getMessage(), cause.getSQLState(), cause.getErrorCode(), query);
                } else {
                    error = new QueryError("Internal query execution error", null, 0, query);
                }
                PostgresqlQueryExecutor.LOGGER.debug(e, format("Error while executing Postgresql query: \n%s", query));
                return QueryResult.errorResult(error);
            }
        };

        CompletableFuture<QueryResult> future = CompletableFuture.supplyAsync(task, PostgresqlQueryExecutor.QUERY_EXECUTOR);
        this.result = future;
    }

    @Override
    public QueryStats currentStats() {
        if (result.isDone()) {
            return new QueryStats(100, QueryStats.State.FINISHED, null, null, null, null, null, null);
        } else {
            return new QueryStats(0, QueryStats.State.RUNNING, null, null, null, null, null, null);
        }
    }

    @Override
    public boolean isFinished() {
        return result.isDone();
    }

    @Override
    public CompletableFuture<QueryResult> getResult() {
        return result;
    }

    @Override
    public String getQuery() {
        return query;
    }

    @Override
    public void kill() {
        // TODO: Find a way to kill Postgresql query.
    }

    private static QueryResult resultSetToQueryResult(ResultSet resultSet, long executionTimeInMillis) {
        List<SchemaField> columns;
        List<List<Object>> data;
        try {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();

            columns = new ArrayList<>(columnCount);
            for (int i = 1; i < columnCount + 1; i++) {
                columns.add(new SchemaField(metaData.getColumnName(i), fromSql(metaData.getColumnType(i), metaData.getColumnTypeName(i)), true));
            }

            ImmutableList.Builder<List<Object>> builder = ImmutableList.builder();
            while (resultSet.next()) {
                List<Object> rowBuilder = Arrays.asList(new Object[columnCount]);
                for (int i = 1; i < columnCount + 1; i++) {
                    Object object = resultSet.getObject(i);
                    if (object instanceof Timestamp) {
                        // we remove timezone
                        object = ((Timestamp) object).toInstant();
                    }
                    rowBuilder.set(i - 1, object);
                }
                builder.add(rowBuilder);
            }
            data = builder.build();
            return new QueryResult(columns, data, ImmutableMap.of(QueryResult.EXECUTION_TIME, executionTimeInMillis));
        } catch (SQLException e) {
            QueryError error = new QueryError(e.getMessage(), e.getSQLState(), e.getErrorCode());
            return QueryResult.errorResult(error);
        }
    }
}