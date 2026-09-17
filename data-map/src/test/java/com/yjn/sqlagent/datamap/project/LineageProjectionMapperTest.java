package com.yjn.sqlagent.datamap.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LineageProjectionMapperTest {
    @Test
    void mapsTablesColumnsUsagesAndJoinsWithoutInventingBlankColumns() {
        Map<String,Object> source=Map.of("catalog","hive","db","ods","table","orders","column","id","direct",true);
        Map<String,Object> target=Map.of("catalog","paimon","db","dwd","table","orders","qualifiedName","paimon.dwd.orders");
        Map<String,Object> statement=Map.of(
                "statementIndex",1,
                "columnLineages",List.of(Map.of("targetTable",target,"targetColumn","order_id","ordinal",0,"expression","o.id","sources",List.of(source,Map.of("catalog","hive","db","ods","table","orders","column","")))),
                "columnUsages",List.of(Map.of("type","FILTER","expression","o.id > 0","columns",List.of(source))),
                "joins",List.of(Map.of("joinType","INNER","condition","o.id=i.order_id","leftColumns",List.of(source),"rightColumns",List.of(Map.of("catalog","hive","db","ods","table","items","column","order_id")))));
        Map<String,Object> facts=Map.of("complete",true,"diagnostics",List.of(),"inputs",List.of(Map.of("catalog","hive","db","ods","table","orders")),"outputs",List.of(target),"statements",List.of(statement));

        LineageProjection value=new LineageProjectionMapper().map(facts);

        assertThat(value.inputs).extracting(item->item.get("assetKey")).containsExactly("hive|ods|orders");
        assertThat(value.outputs).extracting(item->item.get("assetKey")).containsExactly("paimon|dwd|orders");
        assertThat(value.derivations).hasSize(1);
        assertThat(value.usages).hasSize(1);
        assertThat(value.joins).hasSize(1);
        assertThat(value.complete).isTrue();
    }
}

