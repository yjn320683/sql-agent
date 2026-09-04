package com.yjn.sqlagent.realtime.submit;

import com.yjn.sqlagent.realtime.common.SubmissionSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

final class FlinkSqlSupport {
    private FlinkSqlSupport() { }
    static StreamTableEnvironment environment(SubmissionSpec spec) {
        StreamExecutionEnvironment stream = StreamExecutionEnvironment.getExecutionEnvironment();
        return environment(spec, stream);
    }
    static StreamTableEnvironment environment(SubmissionSpec spec, StreamExecutionEnvironment stream) {
        Integer parallelism=spec.getTask().getParallelism(); if(parallelism!=null&&parallelism>0)stream.setParallelism(parallelism);
        StreamTableEnvironment table=StreamTableEnvironment.create(stream, EnvironmentSettings.newInstance().inStreamingMode().build());
        String warehouse=spec.getRuntimeConfig().getPaimonWarehouse();
        StringBuilder ddl=new StringBuilder("CREATE CATALOG paimon WITH ('type'='paimon','warehouse'='").append(escape(warehouse)).append("'");
        for(Map.Entry<String,String>entry:spec.getRuntimeConfig().getCatalogConf().entrySet())ddl.append(",'" ).append(escape(entry.getKey())).append("'='").append(escape(entry.getValue())).append("'");
        ddl.append(")"); table.executeSql(ddl.toString()); table.useCatalog("paimon"); return table;
    }
    static List<String> statements(String sql){
        List<String>result=new ArrayList<>();StringBuilder current=new StringBuilder();boolean single=false,quoted=false,backtick=false,line=false,block=false;
        for(int i=0;i<sql.length();i++){char c=sql.charAt(i),n=i+1<sql.length()?sql.charAt(i+1):'\0';if(line){if(c=='\n'){line=false;current.append(c);}continue;}if(block){if(c=='*'&&n=='/'){block=false;i++;}continue;}if(!single&&!quoted&&!backtick&&c=='-'&&n=='-'){line=true;i++;continue;}if(!single&&!quoted&&!backtick&&c=='/'&&n=='*'){block=true;i++;continue;}if(c=='\''&&!quoted&&!backtick)single=!single;if(c=='\"'&&!single&&!backtick)quoted=!quoted;if(c=='`'&&!single&&!quoted)backtick=!backtick;if(c==';'&&!single&&!quoted&&!backtick){add(result,current);current=new StringBuilder();}else current.append(c);}add(result,current);return result;
    }
    static void add(List<String>result,StringBuilder value){String text=value.toString().trim();if(!text.isEmpty())result.add(text);}
    static String escape(String value){return value==null?"":value.replace("'","''");}
    static String text(Object value){return value==null?"":String.valueOf(value).trim();}
    @SuppressWarnings("unchecked")static Map<String,Object>map(Object value){return value instanceof Map?(Map<String,Object>)value:Map.of();}
    @SuppressWarnings("unchecked")static List<Map<String,Object>>maps(Object value){return value instanceof List?(List<Map<String,Object>>)value:List.of();}
}
