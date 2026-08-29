package com.yjn.sqlagent.config;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query", args = {
                MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class,
                org.apache.ibatis.session.ResultHandler.class
        }),
        @Signature(type = Executor.class, method = "update", args = {
                MappedStatement.class, Object.class
        })
})
public class SqlLogInterceptor implements Interceptor {

    private static final Logger SQL_LOG = LoggerFactory.getLogger("SQL_LOG");
    private final Environment environment;

    public SqlLogInterceptor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!isLoggingEnabled()) {
            return invocation.proceed();
        }
        long start = System.nanoTime();
        MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
        Object parameter = invocation.getArgs().length > 1 ? invocation.getArgs()[1] : null;
        BoundSql boundSql = mappedStatement.getBoundSql(parameter);
        String sql = normalizeSql(boundSql.getSql());

        try {
            Object result = invocation.proceed();
            long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (shouldLogAll() || isSlowSql(costMs)) {
                SQL_LOG.info("sqlId={}, cost={}ms, sql={}", mappedStatement.getId(), costMs, sql);
            }
            return result;
        } catch (Throwable ex) {
            long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            SQL_LOG.error("sqlId={}, cost={}ms", mappedStatement.getId(), costMs, ex);
            throw ex;
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }

    private boolean shouldLogAll() {
        return Boolean.parseBoolean(environment.getProperty("app.sql-log.log-all", "false"));
    }

    private boolean isLoggingEnabled() {
        return Boolean.parseBoolean(environment.getProperty("app.sql-log.enabled", "false"));
    }

    private boolean isSlowSql(long costMs) {
        long threshold = Long.parseLong(environment.getProperty("app.sql-log.slow-threshold-ms", "1000"));
        return costMs >= threshold;
    }

    private String normalizeSql(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

}
