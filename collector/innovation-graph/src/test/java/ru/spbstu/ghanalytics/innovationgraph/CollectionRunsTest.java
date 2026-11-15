package ru.spbstu.ghanalytics.innovationgraph;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CollectionRunsTest {

    /** A connection whose every call fails, like one to a database that just went down. */
    private static Connection brokenConnection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    throw new SQLException("connection lost");
                });
    }

    @Test
    void bookkeepingErrorDoesNotHideTheOriginalOne() {
        InnovationGraphException original = new InnovationGraphException("HTTP 500 for data/topics.csv");

        CollectionRuns.recordFailure(brokenConnection(), 1, original);

        assertEquals(1, original.getSuppressed().length);
        assertEquals("connection lost", original.getSuppressed()[0].getMessage());
    }

    @Test
    void recordsErrorsToo() {
        OutOfMemoryError original = new OutOfMemoryError("test");

        CollectionRuns.recordFailure(brokenConnection(), 1, original);

        assertSame(SQLException.class, original.getSuppressed()[0].getClass());
    }
}
