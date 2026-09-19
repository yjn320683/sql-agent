package com.yjn.sqlagent.datamap.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yjn.sqlagent.datamap.config.DataMapProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class Neo4jHttpGraphStoreClientTest {
    @Test
    void readsTransactionalApiResponseShape() {
        DataMapProperties properties=new DataMapProperties();
        properties.getNeo4j().setEnabled(true); properties.getNeo4j().setBaseUrl("http://neo4j:7474/");
        properties.getNeo4j().setDatabase("lineage"); properties.getNeo4j().setUsername("neo4j"); properties.getNeo4j().setPassword("secret");
        RestTemplate template=new RestTemplate(); MockRestServiceServer server=MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("http://neo4j:7474/db/lineage/tx/commit"))
                .andExpect(header(HttpHeaders.AUTHORIZATION,"Basic bmVvNGo6c2VjcmV0"))
                .andRespond(withSuccess("{\"results\":[{\"columns\":[\"name\",\"count\"],"
                        + "\"data\":[{\"row\":[\"orders\",2]}]}],\"errors\":[]}", MediaType.APPLICATION_JSON));

        List<Map<String,Object>> rows=new Neo4jHttpGraphStoreClient(template,new ObjectMapper(),properties)
                .query("RETURN $name AS name",Map.of("name","orders"));

        assertThat(rows).containsExactly(Map.of("name","orders","count",2));
        server.verify();
    }
}
