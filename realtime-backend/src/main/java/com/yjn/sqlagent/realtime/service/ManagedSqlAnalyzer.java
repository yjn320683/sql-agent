package com.yjn.sqlagent.realtime.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 受管 Flink SQL 的安全策略与语句边界检查；表依赖和执行计划由 Flink Parser/Planner 解析。 */
@Service
public class ManagedSqlAnalyzer {
    private static final Set<String> BLOCKED = Set.of("ALTER", "DROP", "TRUNCATE", "DELETE", "UPDATE", "MERGE", "CALL", "USE");
    private static final Set<String> SET_PREFIXES = Set.of("table.", "execution.", "pipeline.");

    public Analysis analyze(String sql, String defaultDatabase) {
        if (text(sql).isEmpty()) throw new IllegalArgumentException("计算 SQL 不能为空");
        List<List<String>> statements = statements(sql);
        if (statements.isEmpty()) throw new IllegalArgumentException("计算 SQL 不能为空");
        Set<String> temporaryViews = new LinkedHashSet<>();
        Set<String> inputs = new LinkedHashSet<>(), outputs = new LinkedHashSet<>();
        int inserts = 0;
        for (List<String> tokens : statements) {
            if (tokens.isEmpty()) continue;
            String first = upper(tokens.get(0));
            if ("END".equals(first) && tokens.size() == 1) continue;
            if (BLOCKED.contains(first)) throw new IllegalArgumentException("受管 SQL 不允许执行 " + first);
            if ("SET".equals(first)) { validateSet(tokens); continue; }
            if ("CREATE".equals(first)) {
                if (tokens.size() < 4 || !"TEMPORARY".equals(upper(tokens.get(1))) || !"VIEW".equals(upper(tokens.get(2)))) {
                    throw new IllegalArgumentException("受管 SQL 只允许 CREATE TEMPORARY VIEW");
                }
                temporaryViews.add(clean(tokens.get(3)).toLowerCase(Locale.ROOT));
            } else if (!"INSERT".equals(first) && !isStatementSet(tokens)) {
                throw new IllegalArgumentException("受管 SQL 只允许 SET、CREATE TEMPORARY VIEW、INSERT 或 EXECUTE STATEMENT SET");
            }
            for (int i = 0; i < tokens.size(); i++) {
                String token = upper(tokens.get(i));
                if ("INSERT".equals(token) && i + 2 < tokens.size() && ("INTO".equals(upper(tokens.get(i + 1))) || "OVERWRITE".equals(upper(tokens.get(i + 1))))) {
                    outputs.add(qualified(tokens.get(i + 2), defaultDatabase)); inserts++;
                }
                if (("FROM".equals(token) || "JOIN".equals(token)) && i + 1 < tokens.size() && !"(".equals(tokens.get(i + 1))) {
                    inputs.add(qualified(tokens.get(i + 1), defaultDatabase));
                }
            }
        }
        inputs.removeIf(value -> temporaryViews.contains(last(value).toLowerCase(Locale.ROOT)));
        if (inserts == 0 || outputs.isEmpty()) throw new IllegalArgumentException("计算 SQL 至少需要一个 INSERT 输出");
        return new Analysis(new ArrayList<>(inputs), new ArrayList<>(outputs), inserts, statements.size());
    }

    public Map<String, Object> explain(String sql, String defaultDatabase) {
        Analysis analysis = analyze(sql, defaultDatabase);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true); result.put("inputs", analysis.inputs); result.put("outputs", analysis.outputs);
        result.put("statementCount", analysis.statementCount); result.put("insertCount", analysis.insertCount);
        result.put("plan", "受管流式 SQL：" + analysis.inputs.size() + " 个输入，" + analysis.outputs.size() + " 个输出，提交时由 Flink Planner 生成物理计划");
        return result;
    }

    private void validateSet(List<String> tokens) {
        if (tokens.size() < 2) throw new IllegalArgumentException("SET 语句缺少配置项");
        String key = clean(tokens.get(1)).replace("'", "").replace("\"", "");
        if (SET_PREFIXES.stream().noneMatch(key::startsWith)) throw new IllegalArgumentException("SET 参数不在白名单中：" + key);
    }

    private boolean isStatementSet(List<String> tokens) {
        return tokens.size() >= 4 && "EXECUTE".equals(upper(tokens.get(0)))
                && "STATEMENT".equals(upper(tokens.get(1))) && "SET".equals(upper(tokens.get(2)))
                && "BEGIN".equals(upper(tokens.get(3)));
    }

    private List<List<String>> statements(String sql) {
        List<List<String>> result = new ArrayList<>(); List<String> current = new ArrayList<>(); StringBuilder token = new StringBuilder();
        boolean single=false, quoted=false, backtick=false, lineComment=false, blockComment=false;
        for (int i=0;i<sql.length();i++) {
            char c=sql.charAt(i), next=i+1<sql.length()?sql.charAt(i+1):'\0';
            if (lineComment) { if (c=='\n') lineComment=false; continue; }
            if (blockComment) { if (c=='*'&&next=='/') { blockComment=false; i++; } continue; }
            if (!single&&!quoted&&!backtick&&c=='-'&&next=='-') { flush(current,token); lineComment=true;i++;continue; }
            if (!single&&!quoted&&!backtick&&c=='/'&&next=='*') { flush(current,token); blockComment=true;i++;continue; }
            if (c=='\''&&!quoted&&!backtick) { single=!single; token.append(c); continue; }
            if (c=='\"'&&!single&&!backtick) { quoted=!quoted; token.append(c); continue; }
            if (c=='`'&&!single&&!quoted) { backtick=!backtick; token.append(c); continue; }
            if (!single&&!quoted&&!backtick && (Character.isWhitespace(c)||",();=".indexOf(c)>=0)) {
                flush(current,token); if (c==';') { if(!current.isEmpty()) result.add(current); current=new ArrayList<>(); }
                else if (c=='('||c==')'||c=='=') current.add(String.valueOf(c));
            } else token.append(c);
        }
        if (single||quoted||backtick||blockComment) throw new IllegalArgumentException("SQL 字符串、标识符或注释没有闭合");
        flush(current,token); if(!current.isEmpty()) result.add(current); return result;
    }
    private void flush(List<String> tokens,StringBuilder token){ if(token.length()>0){tokens.add(token.toString());token.setLength(0);} }
    private String qualified(String raw,String defaultDatabase){ String value=clean(raw); String[] parts=value.split("\\."); if(parts.length==1)return "paimon."+defaultDatabase+"."+parts[0]; if(parts.length==2)return "paimon."+parts[0]+"."+parts[1]; if(parts.length==3)return String.join(".",parts); throw new IllegalArgumentException("表标识不正确："+raw); }
    private String clean(String value){ return value.replace("`","").trim(); }
    private String last(String value){ int index=value.lastIndexOf('.'); return index<0?value:value.substring(index+1); }
    private String upper(String value){ return clean(value).toUpperCase(Locale.ROOT); }
    private String text(Object value){return value==null?"":String.valueOf(value).trim();}

    public static final class Analysis {
        private final List<String> inputs, outputs; private final int insertCount, statementCount;
        Analysis(List<String> inputs,List<String> outputs,int insertCount,int statementCount){this.inputs=inputs;this.outputs=outputs;this.insertCount=insertCount;this.statementCount=statementCount;}
        public List<String> getInputs(){return inputs;} public List<String> getOutputs(){return outputs;}
        public int getInsertCount(){return insertCount;} public int getStatementCount(){return statementCount;}
    }
}
