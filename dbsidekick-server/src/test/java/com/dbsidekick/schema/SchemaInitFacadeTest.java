package com.dbsidekick.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证一键初始化 DAG：SYNC → (INFER∥ALIAS) → INDEX，以及失败跳过 INDEX。
 */
@ExtendWith(MockitoExtension.class)
class SchemaInitFacadeTest {

    @Mock
    private SchemaSyncService schemaSyncService;
    @Mock
    private RelationInferService relationInferService;
    @Mock
    private TableAliasGenerator tableAliasGenerator;
    @Mock
    private SchemaVectorService schemaVectorService;

    private SchemaInitFacade facade;
    private List<SchemaInitSseEvent> events;

    @BeforeEach
    void setUp() {
        facade = new SchemaInitFacade(schemaSyncService, relationInferService,
                tableAliasGenerator, schemaVectorService);
        events = new ArrayList<>();
    }

    @Test
    void singleDatabase_success_runsAllSteps() throws Exception {
        when(schemaSyncService.sync(eq("ds1"), eq("db1"), any(SyncOptions.class)))
                .thenReturn(new SchemaSyncService.SyncResult(2, 10, 5));
        when(relationInferService.infer(eq("ds1"), eq("db1"), any()))
                .thenReturn(new InferReport(1, 0, 3));
        when(tableAliasGenerator.generateAll(eq("ds1"), eq(false), eq("db1"), any()))
                .thenReturn(new TableAliasGenerator.GenReport(2, 0, 0, 8));
        when(schemaVectorService.indexDatasource(eq("ds1"), eq("db1"), any()))
                .thenReturn(new SchemaVectorService.IndexResult(2, 0, 9));

        facade.run("ds1", "db1", false, events::add, new AtomicBoolean(false));

        verify(schemaSyncService).sync(eq("ds1"), eq("db1"), any(SyncOptions.class));
        verify(relationInferService).infer(eq("ds1"), eq("db1"), any());
        verify(tableAliasGenerator).generateAll(eq("ds1"), eq(false), eq("db1"), any());
        verify(schemaVectorService).indexDatasource(eq("ds1"), eq("db1"), any());

        assertTrue(events.stream().anyMatch(e -> "init_start".equals(e.getEvent())));
        assertTrue(events.stream().anyMatch(e -> "init_done".equals(e.getEvent())
                && "SUCCEEDED".equals(String.valueOf(((java.util.Map<?, ?>) e.getData()).get("overall")))));
        assertTrue(events.stream().anyMatch(e -> "done".equals(e.getEvent())));
    }

    @Test
    void syncFails_skipsIndex() throws Exception {
        when(schemaSyncService.sync(eq("ds1"), eq("db1"), any(SyncOptions.class)))
                .thenThrow(new IllegalArgumentException("同步失败"));

        facade.run("ds1", "db1", false, events::add, new AtomicBoolean(false));

        verify(relationInferService, never()).infer(anyString(), anyString(), any());
        verify(tableAliasGenerator, never()).generateAll(anyString(), anyBoolean(), anyString(), any());
        verify(schemaVectorService, never()).indexDatasource(anyString(), anyString(), any());

        assertTrue(events.stream().anyMatch(e -> "init_done".equals(e.getEvent())
                && "FAILED".equals(String.valueOf(((java.util.Map<?, ?>) e.getData()).get("overall")))));
    }

    @Test
    void inferFails_skipsIndex() throws Exception {
        when(schemaSyncService.sync(eq("ds1"), eq("db1"), any(SyncOptions.class)))
                .thenReturn(new SchemaSyncService.SyncResult(1, 3, 2));
        when(relationInferService.infer(eq("ds1"), eq("db1"), any()))
                .thenThrow(new IllegalStateException("推断炸掉"));
        when(tableAliasGenerator.generateAll(eq("ds1"), eq(false), eq("db1"), any()))
                .thenReturn(new TableAliasGenerator.GenReport(1, 0, 0, 4));

        facade.run("ds1", "db1", false, events::add, new AtomicBoolean(false));

        verify(schemaVectorService, never()).indexDatasource(anyString(), anyString(), any());
        assertTrue(events.stream().anyMatch(e -> "init_done".equals(e.getEvent())
                && "FAILED".equals(String.valueOf(((java.util.Map<?, ?>) e.getData()).get("overall")))));
    }

    @Test
    void aliasPartialFail_stillSucceededThenIndex() throws Exception {
        when(schemaSyncService.sync(eq("ds1"), eq("db1"), any(SyncOptions.class)))
                .thenReturn(new SchemaSyncService.SyncResult(3, 9, 2));
        when(relationInferService.infer(eq("ds1"), eq("db1"), any()))
                .thenReturn(new InferReport(2, 1, 3));
        when(tableAliasGenerator.generateAll(eq("ds1"), eq(false), eq("db1"), any()))
                .thenReturn(new TableAliasGenerator.GenReport(2, 1, 1, 10));
        when(schemaVectorService.indexDatasource(eq("ds1"), eq("db1"), any()))
                .thenReturn(new SchemaVectorService.IndexResult(3, 0, 5));

        facade.run("ds1", "db1", false, events::add, new AtomicBoolean(false));

        verify(schemaVectorService).indexDatasource(eq("ds1"), eq("db1"), any());
        boolean aliasSucceeded = events.stream().anyMatch(e -> {
            if (!"step".equals(e.getEvent())) {
                return false;
            }
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> data = (java.util.Map<String, Object>) e.getData();
            return "ALIAS".equals(data.get("step"))
                    && "SUCCEEDED".equals(data.get("status"))
                    && String.valueOf(data.get("message")).contains("成功 2 张");
        });
        assertTrue(aliasSucceeded);
    }

    @Test
    void instanceLevel_runsPerDatabase() throws Exception {
        when(schemaSyncService.listDatabases("ds1")).thenReturn(List.of("a", "b"));
        when(schemaSyncService.sync(eq("ds1"), anyString(), any(SyncOptions.class)))
                .thenReturn(new SchemaSyncService.SyncResult(1, 1, 1));
        when(relationInferService.infer(eq("ds1"), anyString(), any()))
                .thenReturn(new InferReport(0, 0, 1));
        when(tableAliasGenerator.generateAll(eq("ds1"), eq(false), anyString(), any()))
                .thenReturn(new TableAliasGenerator.GenReport(1, 0, 0, 1));
        when(schemaVectorService.indexDatasource(eq("ds1"), anyString(), any()))
                .thenReturn(new SchemaVectorService.IndexResult(1, 0, 1));

        facade.run("ds1", null, false, events::add, new AtomicBoolean(false));

        verify(schemaSyncService, times(2)).sync(eq("ds1"), anyString(), any(SyncOptions.class));
        verify(schemaVectorService, times(2)).indexDatasource(eq("ds1"), anyString(), any());
        long dbStarts = events.stream().filter(e -> "db_start".equals(e.getEvent())).count();
        assertEquals(2, dbStarts);
    }

    @Test
    void syncUsesSkipAutoInfer() throws Exception {
        when(schemaSyncService.sync(eq("ds1"), eq("db1"), any(SyncOptions.class)))
                .thenAnswer(inv -> {
                    SyncOptions opts = inv.getArgument(2);
                    assertTrue(opts.skipAutoInfer());
                    return new SchemaSyncService.SyncResult(0, 0, 1);
                });
        when(relationInferService.infer(eq("ds1"), eq("db1"), any()))
                .thenReturn(new InferReport(0, 0, 1));
        when(tableAliasGenerator.generateAll(eq("ds1"), eq(false), eq("db1"), any()))
                .thenReturn(new TableAliasGenerator.GenReport(0, 0, 0, 1));
        when(schemaVectorService.indexDatasource(eq("ds1"), eq("db1"), any()))
                .thenReturn(new SchemaVectorService.IndexResult(0, 0, 1));

        facade.run("ds1", "db1", false, events::add, new AtomicBoolean(false));

        verify(schemaSyncService).sync(eq("ds1"), eq("db1"), any(SyncOptions.class));
    }

    @Test
    void emptyDatasourceId_emitsError() {
        facade.run("  ", null, false, events::add, new AtomicBoolean(false));
        assertTrue(events.stream().anyMatch(e -> "error".equals(e.getEvent())));
        assertFalse(events.isEmpty());
    }
}
