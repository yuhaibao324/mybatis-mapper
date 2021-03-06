package cn.sirenia.mybatis.plugin;

import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import cn.sirenia.mybatis.util.ReflectHelper;
import cn.sirenia.mybatis.util.XMLMapperConf;
import cn.sirenia.mybatis.util.groovy.GroovyScriptHelper;
import groovy.lang.GroovyObject;

@Intercepts({
		@Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class,
				RowBounds.class, ResultHandler.class }),
		@Signature(type = Executor.class, method = "update", args = { MappedStatement.class, Object.class }) })
public class BaseMapperPlugin implements Interceptor {

	// private static final Logger logger =
	// Logger.getLogger(ExecutorPlugin.class);
	private String dialect = "postgresql"; // 数据库方言

	public Object intercept(Invocation invocation) throws Throwable {
		Object[] args = invocation.getArgs();
		MappedStatement statement = (MappedStatement) args[0];
		Configuration configuration = statement.getConfiguration();
		Object parameterObject = args[1];
		// statement.getSqlSource().getBoundSql(parameterObject);
		String sql = statement.getBoundSql(parameterObject).getSql();
		String statementId = statement.getId();
		int index = statementId.lastIndexOf(".");
		String methodName = statementId.substring(index + 1);
		if (("sirenia-" + methodName).equals(sql)) {// 只有第一次会执行，因为执行一次后sql语句已经被改变。
			synchronized (this) {
				if (("sirenia-" + methodName).equals(sql)) {
					//动态创建sql
					String mapperClazzName = statementId.substring(0, index);
					Class<?> paramClazz = parameterObject == null ? null : parameterObject.getClass();
					String script = null;
					try{
						XMLMapperConf conf = XMLMapperConf.of(configuration, mapperClazzName,dialect);
						GroovyObject go = GroovyScriptHelper.loadGroovyScript("cn.sirenia.mybatis.sql.provider.DynSqlProvider",true);
						script = go.invokeMethod(methodName,conf).toString();
					}catch(Exception e){
						throw new RuntimeException(e);
					}
					//构建MappedStatement
					SqlSource sqlSource = new XMLLanguageDriver().createSqlSource(configuration, script,	paramClazz);
					SqlCommandType sqlCommandType = statement.getSqlCommandType();
					MappedStatement ms = new MappedStatement.Builder(configuration, statementId, sqlSource, sqlCommandType )
							.resultMaps(statement.getResultMaps() ).build();
					Map<String, MappedStatement> mappedStatements = (Map<String, MappedStatement>) ReflectHelper.getValueByFieldName(configuration, "mappedStatements");
					//以后执行使用新的MappedStatement
					mappedStatements.remove(statementId);
					mappedStatements.put(statementId, ms);
					//本次执行也要使用新的MappedStatement
					args[0] = ms;
				}
			}
		}
		Object ret = invocation.proceed();
		return ret;
	}

	public Object plugin(Object target) {
		return Plugin.wrap(target, this);
	}
	public void setProperties(Properties p) {
		String dialect = p.getProperty("dialect");
		if (dialect != null && !dialect.trim().isEmpty()) {
			this.dialect = dialect;
		}
	}
}
