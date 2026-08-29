package com.yjn.sqlagent.datacompare.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SqlParameterRendererTest {
    private final SqlParameterRenderer renderer = new SqlParameterRenderer(new ObjectMapper());

    @Test
    void rendersTypedDefaultsAndEscapesStrings() {
        String schema = "["
                + "{\"name\":\"id\",\"type\":\"INTEGER\",\"defaultValue\":1},"
                + "{\"name\":\"label\",\"type\":\"STRING\",\"defaultValue\":\"O'Reilly\"},"
                + "{\"name\":\"enabled\",\"type\":\"BOOLEAN\",\"defaultValue\":true}]";

        assertEquals("SELECT 1, 'O''Reilly', TRUE",
                renderer.renderDefaults("SELECT ${id}, ${label}, ${enabled}", schema));
    }

    @Test
    void rejectsReferencedParameterWithoutDefault() {
        String schema = "[{\"name\":\"id\",\"type\":\"INTEGER\",\"required\":true}]";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> renderer.renderDefaults("SELECT ${id}", schema));
        assertEquals("版本验数需要为运行参数配置默认值：id", error.getMessage());
    }

    @Test
    void leavesSqlWithoutPlaceholdersUntouched() {
        assertEquals("SELECT 1", renderer.renderDefaults("SELECT 1", "[]"));
    }
}
