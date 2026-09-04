package com.yjn.sqlagent.realtime.submit;

import com.yjn.sqlagent.realtime.common.SubmissionSpec;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;

final class ExportTaskRunner implements TaskRunner {
    @Override public void validate(SubmissionSpec spec)throws Exception{build(spec,false);}
    @Override public void execute(SubmissionSpec spec)throws Exception{build(spec,true);}
    private void build(SubmissionSpec spec,boolean execute)throws Exception{
        if(spec.getServers().isEmpty())throw new IllegalArgumentException("出仓任务缺少目标 Server 快照");SubmissionSpec.ServerSnapshot server=spec.getServers().get(0);Map<String,Object>export=FlinkSqlSupport.map(spec.getTask().getTaskConfig().get("exportConfig"));List<Map<String,Object>>mappings=FlinkSqlSupport.maps(export.get("mappings"));if(mappings.isEmpty())throw new IllegalArgumentException("出仓任务没有表映射");String url=url(server);validateMysql(url,server,mappings);StreamExecutionEnvironment environment=StreamExecutionEnvironment.getExecutionEnvironment();StreamTableEnvironment table=FlinkSqlSupport.environment(spec,environment);Map<String,Object>sink=FlinkSqlSupport.map(export.get("sink"));int batch=integer(sink.get("batchSize"),500),retries=integer(sink.get("maxRetries"),3);long interval=integer(sink.get("flushIntervalMs"),2000);
        if(!execute){for(Map<String,Object>m:mappings)table.from("paimon.`"+m.get("sourceDatabase")+"`.`"+m.get("sourceTable")+"`").getResolvedSchema();return;}
        for(Map<String,Object>m:mappings){List<Map<String,Object>>columns=FlinkSqlSupport.maps(m.get("columnMappings"));List<String>source=new ArrayList<>(),target=new ArrayList<>();for(Map<String,Object>c:columns){source.add(FlinkSqlSupport.text(c.get("sourceColumn")));target.add(FlinkSqlSupport.text(c.get("targetColumn")));}String select=String.join(",",source.stream().map(v->"`"+v+"`").toArray(String[]::new));Table sourceTable=table.sqlQuery("SELECT "+select+" FROM paimon.`"+m.get("sourceDatabase")+"`.`"+m.get("sourceTable")+"`");DataStream<Row>stream=table.toChangelogStream(sourceTable);@SuppressWarnings("unchecked")List<String>keys=(List<String>)m.getOrDefault("primaryKeys",List.of());stream.sinkTo(new MysqlRowSink(new MysqlRowSink.Config(url,server.getAccount(),server.getPassword(),FlinkSqlSupport.text(m.get("targetTable")),source,target,keys,batch,interval,retries))).name("mysql-export-"+m.get("targetTable"));}
        environment.execute(spec.getJobName());
    }
    private void validateMysql(String url,SubmissionSpec.ServerSnapshot server,List<Map<String,Object>>mappings)throws Exception{try(Connection c=DriverManager.getConnection(url,server.getAccount(),server.getPassword())){DatabaseMetaData meta=c.getMetaData();for(Map<String,Object>m:mappings){String table=FlinkSqlSupport.text(m.get("targetTable"));try(ResultSet rs=meta.getTables(c.getCatalog(),null,table,new String[]{"TABLE"})){if(!rs.next())throw new IllegalArgumentException("MySQL 目标表不存在："+table);}}}}
    private String url(SubmissionSpec.ServerSnapshot server){String address=server.getAddress();String value=address.startsWith("jdbc:mysql://")?address:"jdbc:mysql://"+address;if(value.substring("jdbc:mysql://".length()).indexOf('/')<0)value+="/"+server.getDatabaseName();return value+(value.contains("?")?"&":"?")+"useUnicode=true&characterEncoding=utf8&useSSL=false";}
    private int integer(Object v,int fallback){try{return v==null?fallback:Integer.parseInt(String.valueOf(v));}catch(Exception e){return fallback;}}
}
