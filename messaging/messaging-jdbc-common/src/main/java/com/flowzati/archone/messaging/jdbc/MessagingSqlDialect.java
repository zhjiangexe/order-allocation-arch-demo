package com.flowzati.archone.messaging.jdbc;

import java.util.List;
import java.util.Set;

/** Generates vendor-specific parameterized SQL from prevalidated identifiers. */
public interface MessagingSqlDialect {

    default String insert(String qualifiedTable, List<String> columns) {
        return insert(qualifiedTable, columns, Set.of());
    }

    /**
     * Generates an insert whose listed JSON columns use the database-native JSON parameter shape.
     * Argument ordering remains identical to {@code columns}.
     */
    String insert(String qualifiedTable, List<String> columns, Set<String> jsonColumns);

    String insertIgnoringDuplicate(String qualifiedTable, List<String> columns, List<String> conflictColumns);
}
