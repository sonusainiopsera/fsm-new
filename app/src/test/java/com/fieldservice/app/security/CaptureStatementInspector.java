package com.fieldservice.app.security;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hibernate {@link StatementInspector} that captures all SQL statements for test
 * assertion.
 *
 * <p>Registered via {@code application-test.yml}:
 * <pre>{@code
 * spring.jpa.properties.hibernate.session_factory.statement_inspector:
 *   com.fieldservice.app.security.CaptureStatementInspector
 * }</pre>
 *
 * <p>Thread-safe via synchronised list; designed for single-threaded test use where
 * {@link #clear()} is called before each assertion.
 */
public class CaptureStatementInspector implements StatementInspector {

    private static final List<String> CAPTURED = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String inspect(String sql) {
        CAPTURED.add(sql);
        return sql;
    }

    /** Returns a snapshot of all SQL statements captured since the last {@link #clear()}. */
    public static List<String> getCaptured() {
        synchronized (CAPTURED) {
            return new ArrayList<>(CAPTURED);
        }
    }

    /** Clears the capture buffer before a new test assertion. */
    public static void clear() {
        CAPTURED.clear();
    }
}
