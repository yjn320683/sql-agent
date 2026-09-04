package com.yjn.sqlagent.realtime.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FlinkSqlSupportTest {

    @Test
    void splitsStatementSetAndPreservesQuotedSemicolon() {
        List<String> statements = FlinkSqlSupport.statements(
                "-- managed compute\n"
                + "SET 'pipeline.name' = 'a;b';\n"
                + "EXECUTE STATEMENT SET BEGIN\n"
                + "  INSERT INTO d.out_a SELECT * FROM d.in_a;\n"
                + "  INSERT INTO d.out_b SELECT * FROM d.in_b;\n"
                + "END;");

        assertEquals(4, statements.size());
        assertEquals("SET 'pipeline.name' = 'a;b'", statements.get(0));
        assertEquals("EXECUTE STATEMENT SET BEGIN\n  INSERT INTO d.out_a SELECT * FROM d.in_a", statements.get(1));
        assertEquals("INSERT INTO d.out_b SELECT * FROM d.in_b", statements.get(2));
        assertEquals("END", statements.get(3));
    }
}
