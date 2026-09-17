package com.yjn.sqlagent.datamap.graph;

import java.util.List;
import java.util.Map;

/** 图数据库最小访问面，便于测试和未来替换存储实现。 */
public interface GraphStoreClient {
    boolean isConfigured();
    boolean ping();
    List<Map<String, Object>> query(String cypher, Map<String, Object> parameters);
}
