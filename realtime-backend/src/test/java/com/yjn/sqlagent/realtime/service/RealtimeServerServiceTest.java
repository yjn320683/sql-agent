package com.yjn.sqlagent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yjn.sqlagent.realtime.repository.RealtimeSyncRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class RealtimeServerServiceTest {

    @ParameterizedTest
    @ValueSource(ints = {20, 100})
    void batchSchemaReadUsesOneConnection(int tableCount) throws Exception {
        RealtimeSyncRepository repository = mock(RealtimeSyncRepository.class);
        Map<String, Object> server = Map.of(
                "address", "127.0.0.1:3306", "databaseName", "sales",
                "account", "reader", "password", "secret");
        when(repository.requiredServer(3L, true)).thenReturn(server);
        Connection connection = mock(Connection.class);
        PreparedStatement columnStatement = mock(PreparedStatement.class);
        PreparedStatement primaryKeyStatement = mock(PreparedStatement.class);
        RealtimeServerService service = org.mockito.Mockito.spy(new RealtimeServerService(repository));
        doReturn(connection).when(service).connection(server);
        List<String> tables = IntStream.range(0, tableCount)
                .mapToObj(index -> "orders_" + index).collect(Collectors.toList());
        ResultSet columnRows = schemaRows(tables);
        ResultSet keyRows = primaryKeyRows(tables);
        when(connection.prepareStatement(anyString())).thenReturn(columnStatement, primaryKeyStatement);
        when(columnStatement.executeQuery()).thenReturn(columnRows);
        when(primaryKeyStatement.executeQuery()).thenReturn(keyRows);

        Map<String, Map<String, Object>> schemas = service.schemas(3L, tables);

        assertEquals(tableCount, schemas.size());
        assertEquals(tables, List.copyOf(schemas.keySet()));
        verify(service, times(1)).connection(server);
        verify(connection, times(2)).prepareStatement(anyString());
        verify(columnStatement, times(tableCount + 1)).setString(anyInt(), anyString());
        verify(primaryKeyStatement, times(tableCount + 1)).setString(anyInt(), anyString());
        verify(columnStatement, times(1)).executeQuery();
        verify(primaryKeyStatement, times(1)).executeQuery();
        verify(connection).close();
    }

    @Test
    void missingTableNamesRemainVisibleInBatchFailure() throws Exception {
        RealtimeSyncRepository repository = mock(RealtimeSyncRepository.class);
        Map<String, Object> server = Map.of(
                "address", "127.0.0.1:3306", "databaseName", "sales",
                "account", "reader", "password", "secret");
        when(repository.requiredServer(3L, true)).thenReturn(server);
        Connection connection = mock(Connection.class);
        PreparedStatement columnStatement = mock(PreparedStatement.class);
        PreparedStatement primaryKeyStatement = mock(PreparedStatement.class);
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenReturn(columnStatement, primaryKeyStatement);
        when(columnStatement.executeQuery()).thenReturn(empty);
        when(primaryKeyStatement.executeQuery()).thenReturn(empty);
        RealtimeServerService service = org.mockito.Mockito.spy(new RealtimeServerService(repository));
        doReturn(connection).when(service).connection(server);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.schemas(3L, List.of("missing_table")));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("missing_table"));
    }

    private ResultSet schemaRows(List<String> tables) throws Exception {
        ResultSet rows = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(rows.next()).thenAnswer(invocation -> index.incrementAndGet() < tables.size());
        when(rows.getString("TABLE_NAME")).thenAnswer(invocation -> tables.get(index.get()));
        when(rows.getString("COLUMN_NAME")).thenReturn("id");
        when(rows.getInt("ORDINAL_POSITION")).thenReturn(1);
        when(rows.getString("DATA_TYPE")).thenReturn("bigint");
        when(rows.getString("IS_NULLABLE")).thenReturn("NO");
        when(rows.getString("COLUMN_COMMENT")).thenReturn("primary id");
        when(rows.getObject("COLUMN_DEFAULT")).thenReturn(null);
        when(rows.getString("EXTRA")).thenReturn("");
        return rows;
    }

    private ResultSet primaryKeyRows(List<String> tables) throws Exception {
        ResultSet rows = mock(ResultSet.class);
        AtomicInteger index = new AtomicInteger(-1);
        when(rows.next()).thenAnswer(invocation -> index.incrementAndGet() < tables.size());
        when(rows.getString("TABLE_NAME")).thenAnswer(invocation -> tables.get(index.get()));
        when(rows.getString("COLUMN_NAME")).thenReturn("id");
        when(rows.getInt("SEQ_IN_INDEX")).thenReturn(1);
        return rows;
    }
}
