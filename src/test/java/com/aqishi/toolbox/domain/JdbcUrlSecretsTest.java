package com.aqishi.toolbox.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcUrlSecretsTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "mysql query|jdbc:mysql://db:3306/app?user=root&password=S3cr%26t&useSSL=false"
                    + "|jdbc:mysql://db:3306/app?user=root&password={{vault:password}}&useSSL=false|url:password|S3cr%26t",
            "sqlserver semicolon|jdbc:sqlserver://db:1433;databaseName=app;user=sa;password=P@ss;encrypt=true"
                    + "|jdbc:sqlserver://db:1433;databaseName=app;user=sa;password={{vault:password}};encrypt=true|url:password|P@ss",
            "sqlserver braces|jdbc:sqlserver://db;password={a;b=c};user=sa"
                    + "|jdbc:sqlserver://db;password={{vault:password}};user=sa|url:password|{a;b=c}",
            "postgres ssl key|jdbc:postgresql://db/app?sslmode=verify-full&sslpassword=k3y"
                    + "|jdbc:postgresql://db/app?sslmode=verify-full&sslpassword={{vault:sslpassword}}|url:sslpassword|k3y",
            "truststore|jdbc:mysql://db/app?trustCertificateKeyStorePassword=ts1"
                    + "|jdbc:mysql://db/app?trustCertificateKeyStorePassword={{vault:trustCertificateKeyStorePassword}}"
                    + "|url:trustCertificateKeyStorePassword|ts1",
            "userinfo|jdbc:mysql://root:tiger@db:3306/app"
                    + "|jdbc:mysql://root:{{vault:userinfo}}@db:3306/app|url:userinfo|tiger",
            "oracle thin|jdbc:oracle:thin:scott/tiger@db:1521:orcl"
                    + "|jdbc:oracle:thin:scott/{{vault:oracle}}@db:1521:orcl|url:oracle|tiger",
            "db2 colon|jdbc:db2://db:50000/app:user=u;password=pw;"
                    + "|jdbc:db2://db:50000/app:user=u;password={{vault:password}};|url:password|pw",
    })
    void extractsEmbeddedPasswords(String label, String url, String masked, String key, String value) {
        JdbcUrlSecrets.Split split = JdbcUrlSecrets.split(url);

        assertEquals(masked, split.maskedUrl());
        assertEquals(Map.of(key, value), split.secrets());
        assertEquals(url, JdbcUrlSecrets.restore(split.maskedUrl(), split.secrets()), "round trip must be exact");
    }

    @Test
    void urlsWithoutSecretsAreUntouched() {
        for (String url : new String[]{
                "jdbc:mysql://127.0.0.1:3306/test?useSSL=false&serverTimezone=UTC&characterEncoding=utf-8",
                "jdbc:oracle:thin:@//db:1521/svc",
                "jdbc:sqlserver://db:1433;databaseName=app;user=sa@corp.com;encrypt=true",
                "jdbc:postgresql://db:5432/app?user=me@corp&password=",
                "jdbc:sqlite:C:/data/app.db"}) {
            JdbcUrlSecrets.Split split = JdbcUrlSecrets.split(url);
            assertEquals(url, split.maskedUrl(), url);
            assertTrue(split.secrets().isEmpty(), url);
        }
    }

    @Test
    void splittingAMaskedUrlIsIdempotent() {
        String url = "jdbc:sqlserver://db;password=pw;sslpassword=k;user=sa";
        String masked = JdbcUrlSecrets.split(url).maskedUrl();

        JdbcUrlSecrets.Split again = JdbcUrlSecrets.split(masked);

        assertEquals(masked, again.maskedUrl());
        assertTrue(again.secrets().isEmpty());
    }

    @Test
    void repeatedParametersKeepEachValue() {
        String url = "jdbc:mysql://db/app?password=one&password=two";
        JdbcUrlSecrets.Split split = JdbcUrlSecrets.split(url);

        assertEquals("jdbc:mysql://db/app?password={{vault:password}}&password={{vault:password#2}}", split.maskedUrl());
        assertEquals(url, JdbcUrlSecrets.restore(split.maskedUrl(), split.secrets()));
    }

    @Test
    void missingSecretsLeavePlaceholdersForTheCallerToDetect() {
        String masked = JdbcUrlSecrets.split("jdbc:mysql://db/app?password=pw").maskedUrl();

        assertTrue(JdbcUrlSecrets.hasPlaceholders(JdbcUrlSecrets.restore(masked, Map.of())));
        assertFalse(JdbcUrlSecrets.hasPlaceholders(
                JdbcUrlSecrets.restore(masked, Map.of("url:password", "pw"))));
    }

    @Test
    void redactsPlainAndMaskedSecretsForLogs() {
        assertEquals("jdbc:mysql://root:******@db/app?password=******&useSSL=false",
                JdbcUrlSecrets.redact("jdbc:mysql://root:tiger@db/app?password=pw&useSSL=false"));
        assertEquals("jdbc:mysql://db/app?password=******",
                JdbcUrlSecrets.redact("jdbc:mysql://db/app?password={{vault:password}}"));
    }

    /** 配置层：URL 中的密码随连接密码一起进保险库，剥离后偏好里只剩占位符。 */
    @Test
    void databaseProfileTreatsUrlPasswordsAsSecretFields() {
        DatabaseProfile profile = new DatabaseProfile("p", "MySQL", "db", "3306", "app", "root", "main",
                "com.mysql.cj.jdbc.Driver", "jdbc:mysql://db:3306/app?password=inurl", null);

        Map<String, String> fields = profile.secretFields();
        assertEquals(Map.of("password", "main", "url:password", "inurl"), fields);

        profile.applySecretFields(null);
        assertEquals("jdbc:mysql://db:3306/app?password={{vault:password}}", profile.url);
        assertEquals(null, profile.password);
        assertTrue(profile.secretFields().isEmpty(), "a stripped profile has nothing left to migrate");

        profile.applySecretFields(fields);
        assertEquals("jdbc:mysql://db:3306/app?password=inurl", profile.url);
        assertEquals("main", profile.password);
    }
}
