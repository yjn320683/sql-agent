package com.yjn.sqlagent.realtime.submit;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.api.connector.sink2.WriterInitContext;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;

/** Flink 2.2 尚无官方 JDBC Connector，使用主键幂等的批量 MySQL Sink。 */
final class MysqlRowSink implements Sink<Row> {
    private final Config config;
    MysqlRowSink(Config config){this.config=config;}
    @Override public SinkWriter<Row> createWriter(WriterInitContext context) throws IOException{return new Writer(config);}

    static final class Config implements java.io.Serializable {
        final String url,user,password,table;final List<String>sourceColumns,targetColumns,primaryKeys;final int batchSize,maxRetries;final long flushIntervalMs;
        Config(String url,String user,String password,String table,List<String>sourceColumns,List<String>targetColumns,List<String>primaryKeys,int batchSize,long flushIntervalMs,int maxRetries){this.url=url;this.user=user;this.password=password;this.table=table;this.sourceColumns=sourceColumns;this.targetColumns=targetColumns;this.primaryKeys=primaryKeys;this.batchSize=batchSize;this.flushIntervalMs=flushIntervalMs;this.maxRetries=maxRetries;}
    }
    static final class Writer implements SinkWriter<Row>{
        private final Config config;private transient Connection connection;private transient PreparedStatement upsert,delete;private final List<Mutation> pending=new ArrayList<>();private long lastFlush=System.currentTimeMillis();
        Writer(Config config)throws IOException{this.config=config;connect();}
        @Override public void write(Row row,Context context)throws IOException{if(row.getKind()==RowKind.UPDATE_BEFORE)return;boolean deleting=row.getKind()==RowKind.DELETE;if(deleting&&delete==null)throw new IOException("DELETE 需要完整主键");List<Object>values=new ArrayList<>();for(int i=0;i<config.sourceColumns.size();i++)values.add(row.getField(i));pending.add(new Mutation(deleting,values));if(pending.size()>=config.batchSize||System.currentTimeMillis()-lastFlush>=config.flushIntervalMs)flush(false);}
        @Override public void flush(boolean endOfInput)throws IOException{if(pending.isEmpty())return;SQLException last=null;for(int attempt=0;attempt<=config.maxRetries;attempt++){try{replay();connection.commit();pending.clear();lastFlush=System.currentTimeMillis();return;}catch(SQLException ex){last=ex;rollback();if(attempt<config.maxRetries)reconnect();}}throw new IOException("批量提交 MySQL 失败："+safe(last),last);}
        @Override public void close()throws Exception{flush(true);closeQuietly(upsert);closeQuietly(delete);if(connection!=null)connection.close();}
        private void connect()throws IOException{try{connection=DriverManager.getConnection(config.url,config.user,config.password);connection.setAutoCommit(false);String columns=join(config.targetColumns);String values=String.join(",",java.util.Collections.nCopies(config.targetColumns.size(),"?"));List<String>updates=new ArrayList<>();for(String c:config.targetColumns)if(!config.primaryKeys.contains(c))updates.add(q(c)+"=VALUES("+q(c)+")");if(updates.isEmpty())updates.add(q(config.primaryKeys.get(0))+"="+q(config.primaryKeys.get(0)));upsert=connection.prepareStatement("INSERT INTO "+q(config.table)+" ("+columns+") VALUES ("+values+") ON DUPLICATE KEY UPDATE "+String.join(",",updates));if(!config.primaryKeys.isEmpty()){List<String>where=new ArrayList<>();for(String key:config.primaryKeys)where.add(q(key)+"=?");delete=connection.prepareStatement("DELETE FROM "+q(config.table)+" WHERE "+String.join(" AND ",where));}}catch(SQLException ex){throw new IOException("连接 MySQL 失败："+safe(ex),ex);}}
        private String sourceForTarget(String target){for(int i=0;i<config.targetColumns.size();i++)if(config.targetColumns.get(i).equalsIgnoreCase(target))return config.sourceColumns.get(i);return "";}
        private void replay()throws SQLException{PreparedStatement current=null;Boolean deleting=null;for(Mutation mutation:pending){if(deleting!=null&&deleting!=mutation.deleting){current.executeBatch();current.clearBatch();}deleting=mutation.deleting;current=mutation.deleting?delete:upsert;bind(current,mutation);current.addBatch();}if(current!=null){current.executeBatch();current.clearBatch();}}
        private void bind(PreparedStatement statement,Mutation mutation)throws SQLException{if(mutation.deleting){for(int i=0;i<config.primaryKeys.size();i++){int sourceIndex=config.sourceColumns.indexOf(sourceForTarget(config.primaryKeys.get(i)));if(sourceIndex<0)throw new SQLException("主键缺少源字段映射："+config.primaryKeys.get(i));statement.setObject(i+1,mutation.values.get(sourceIndex));}}else for(int i=0;i<mutation.values.size();i++)statement.setObject(i+1,mutation.values.get(i));}
        private void reconnect()throws IOException{closeQuietly(upsert);closeQuietly(delete);closeQuietly(connection);connect();}
        private void rollback(){try{if(connection!=null)connection.rollback();}catch(SQLException ignored){}}
        private void closeQuietly(AutoCloseable value){try{if(value!=null)value.close();}catch(Exception ignored){}}
        private static String join(List<String>values){List<String>r=new ArrayList<>();for(String v:values)r.add(q(v));return String.join(",",r);}private static String q(String v){if(!v.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("非法 MySQL 标识符："+v);return "`"+v+"`";}private static String safe(Throwable e){String v=e==null?"unknown":String.valueOf(e.getMessage());return v.replaceAll("(?i)(password=)[^&\\s]+","$1******");}
        private static final class Mutation{final boolean deleting;final List<Object>values;Mutation(boolean deleting,List<Object>values){this.deleting=deleting;this.values=values;}}
    }
}
