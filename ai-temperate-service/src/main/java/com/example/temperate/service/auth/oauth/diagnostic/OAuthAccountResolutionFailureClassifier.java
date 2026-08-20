package com.example.temperate.service.auth.oauth.diagnostic;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.type.TypeException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.stereotype.Component;

/**
 * 将 OAuth 账号解析异常归一化为不含消息和参数的稳定诊断分类。
 *
 * <p>异常链最多读取十六层并按对象身份防循环，只暴露异常类名和合法 SQLState；禁止调用
 * {@link Throwable#getMessage()}，防止数据库或 Provider 参数进入日志。</p>
 */
@Component
public final class OAuthAccountResolutionFailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 16;
    private static final String UNAVAILABLE = "unavailable";
    private static final Pattern SQL_STATE = Pattern.compile("^[0-9A-Z]{5}$");
    private static final Pattern SAFE_SOURCE_CLASS =
            Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$.]{0,255}$");
    private static final Pattern SAFE_SOURCE_METHOD =
            Pattern.compile("^[A-Za-z_$<][A-Za-z0-9_$<>]{0,127}$");

    /**
     * 从异常类型与 SQLState 生成固定结果，不改变或包装原异常。
     */
    public Classification classify(Throwable failure) {
        if (failure == null) {
            return new Classification(
                    "UNEXPECTED_FAILURE",
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE,
                    UNAVAILABLE);
        }
        Throwable current = failure;
        Throwable last = failure;
        SQLException sqlException = null;
        boolean cardinalityFailure = false;
        boolean mappingFailure = false;
        boolean persistenceFailure = false;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0;
                current != null && depth < MAX_CAUSE_DEPTH && visited.add(current);
                depth++) {
            last = current;
            if (current instanceof TooManyResultsException) {
                cardinalityFailure = true;
            }
            if (current instanceof ResultMapException || current instanceof TypeException) {
                mappingFailure = true;
            }
            if (current instanceof MyBatisSystemException
                    || current instanceof PersistenceException) {
                persistenceFailure = true;
            }
            if (current instanceof SQLException candidate) {
                sqlException = candidate;
            }
            current = current.getCause();
        }

        String sqlState = safeSqlState(sqlException);
        String category = category(
                cardinalityFailure,
                mappingFailure,
                persistenceFailure,
                sqlState);
        Source source = safeSource(last);
        return new Classification(
                category,
                failure.getClass().getName(),
                last.getClass().getName(),
                source.className(),
                source.methodName(),
                sqlState);
    }

    private static String category(
            boolean cardinalityFailure,
            boolean mappingFailure,
            boolean persistenceFailure,
            String sqlState) {
        if (cardinalityFailure) {
            return "RESULT_CARDINALITY_FAILURE";
        }
        if (mappingFailure) {
            return "RESULT_MAPPING_FAILURE";
        }
        if (!UNAVAILABLE.equals(sqlState)) {
            if (sqlState.startsWith("08")) {
                return "DATABASE_CONNECTIVITY_FAILURE";
            }
            if (sqlState.startsWith("22")) {
                return "DATA_CONVERSION_FAILURE";
            }
            if (sqlState.startsWith("23")) {
                return "DATABASE_CONSTRAINT_FAILURE";
            }
            if (sqlState.startsWith("40")) {
                return "TRANSACTION_FAILURE";
            }
        }
        return persistenceFailure ? "PERSISTENCE_FAILURE" : "UNEXPECTED_FAILURE";
    }

    private static String safeSqlState(SQLException failure) {
        if (failure == null) {
            return UNAVAILABLE;
        }
        String value = failure.getSQLState();
        return value != null && SQL_STATE.matcher(value).matches()
                ? value : UNAVAILABLE;
    }

    private static Source safeSource(Throwable failure) {
        StackTraceElement[] stackTrace = failure.getStackTrace();
        if (stackTrace == null || stackTrace.length == 0) {
            return new Source(UNAVAILABLE, UNAVAILABLE);
        }
        StackTraceElement source = stackTrace[0];
        String className = source.getClassName();
        String methodName = source.getMethodName();
        return new Source(
                isSafe(className, SAFE_SOURCE_CLASS) ? className : UNAVAILABLE,
                isSafe(methodName, SAFE_SOURCE_METHOD) ? methodName : UNAVAILABLE);
    }

    private static boolean isSafe(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches();
    }

    /**
     * 承载允许进入结构化日志的固定安全字段，不保存异常对象、异常消息、文件名或行号。
     */
    public record Classification(
            String failureCategory,
            String exceptionClass,
            String rootExceptionClass,
            String rootSourceClass,
            String rootSourceMethod,
            String sqlState) {
    }

    private record Source(String className, String methodName) {
    }
}
