package com.example.temperate.service.auth.oauth.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;

/**
 * 验证 OAuth 账号解析失败分类只从异常类型和受控 SQLState 提取安全诊断信息。
 */
class OAuthAccountResolutionFailureClassifierTest {

    private final OAuthAccountResolutionFailureClassifier classifier =
            new OAuthAccountResolutionFailureClassifier();

    @Test
    void classifiesNestedSqlConversionWithoutReadingSensitiveMessage() {
        SQLException sqlFailure = new SQLException(
                "email=member@example.com token=secret-token",
                "22007");
        MyBatisSystemException failure = new MyBatisSystemException(
                new PersistenceException(sqlFailure));

        OAuthAccountResolutionFailureClassifier.Classification result =
                classifier.classify(failure);

        assertThat(result.failureCategory()).isEqualTo("DATA_CONVERSION_FAILURE");
        assertThat(result.exceptionClass())
                .isEqualTo(MyBatisSystemException.class.getName());
        assertThat(result.rootExceptionClass()).isEqualTo(SQLException.class.getName());
        assertThat(result.sqlState()).isEqualTo("22007");
        assertThat(result.toString()).doesNotContain(
                "member@example.com", "secret-token");
    }

    @Test
    void classifiesCardinalityFailureWithoutSqlState() {
        TooManyResultsException failure = new TooManyResultsException(
                "subject=secret-subject");

        OAuthAccountResolutionFailureClassifier.Classification result =
                classifier.classify(failure);

        assertThat(result.failureCategory()).isEqualTo("RESULT_CARDINALITY_FAILURE");
        assertThat(result.rootExceptionClass())
                .isEqualTo(TooManyResultsException.class.getName());
        assertThat(result.sqlState()).isEqualTo("unavailable");
        assertThat(result.toString()).doesNotContain("secret-subject");
    }

    @Test
    void extractsOnlySafeRootSourceClassAndMethod() {
        IllegalArgumentException root = new IllegalArgumentException(
                "email=member@example.com subject=secret-subject");
        root.setStackTrace(new StackTraceElement[] {
            new StackTraceElement(
                    "org.apache.ibatis.type.EnumTypeHandler",
                    "getNullableResult",
                    "EnumTypeHandler.java",
                    53)
        });
        MyBatisSystemException failure = new MyBatisSystemException(
                new PersistenceException(root));

        OAuthAccountResolutionFailureClassifier.Classification result =
                classifier.classify(failure);

        assertThat(result.rootSourceClass())
                .isEqualTo("org.apache.ibatis.type.EnumTypeHandler");
        assertThat(result.rootSourceMethod()).isEqualTo("getNullableResult");
        assertThat(result.toString()).doesNotContain(
                "EnumTypeHandler.java", "member@example.com", "secret-subject", "53");
    }

    @Test
    void boundsCauseTraversalAndRejectsMalformedSqlState() {
        Throwable failure = new SQLException("do-not-log", "unsafe-state");
        for (int index = 0; index < 20; index++) {
            failure = new IllegalStateException("layer-" + index, failure);
        }

        OAuthAccountResolutionFailureClassifier.Classification result =
                classifier.classify(failure);

        assertThat(result.failureCategory()).isEqualTo("UNEXPECTED_FAILURE");
        assertThat(result.sqlState()).isEqualTo("unavailable");
        assertThat(result.toString()).doesNotContain("do-not-log", "layer-");
    }
}
