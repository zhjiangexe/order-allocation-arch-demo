package com.flowzati.archone.messaging.jdbc;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** PostgreSQL SQL shape used by the first JDBC producer and duplicate detector implementations. */
public final class PostgresMessagingSqlDialect implements MessagingSqlDialect {

    @Override
    public String insert(String qualifiedTable, List<String> columns, Set<String> jsonColumns) {
        ValidatedInsert insert = validate(qualifiedTable, columns);
        Set<String> validatedJsonColumns = validateJsonColumns(insert.columns(), jsonColumns);
        return "INSERT INTO %s (%s) VALUES (%s)"
                .formatted(
                        insert.table(),
                        String.join(", ", insert.columns()),
                        placeholders(insert.columns(), validatedJsonColumns));
    }

    @Override
    public String insertIgnoringDuplicate(String qualifiedTable, List<String> columns, List<String> conflictColumns) {
        ValidatedInsert insert = validate(qualifiedTable, columns);
        if (conflictColumns == null || conflictColumns.isEmpty()) {
            throw new IllegalArgumentException("Conflict columns are required");
        }
        List<String> validatedConflicts = conflictColumns.stream()
                .map(column -> SqlIdentifiers.requireValid("column", column))
                .toList();
        if (!insert.columns().containsAll(validatedConflicts)) {
            throw new IllegalArgumentException("Conflict columns must be included in insert columns");
        }
        return insert(qualifiedTable, columns) + " ON CONFLICT (" + String.join(", ", validatedConflicts)
                + ") DO NOTHING";
    }

    private ValidatedInsert validate(String qualifiedTable, List<String> columns) {
        String table = SqlIdentifiers.requireQualifiedTable(qualifiedTable);
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Insert columns are required");
        }
        List<String> validatedColumns = columns.stream()
                .map(column -> SqlIdentifiers.requireValid("column", column))
                .toList();
        if (validatedColumns.stream().distinct().count() != validatedColumns.size()) {
            throw new IllegalArgumentException("Insert columns must be unique");
        }
        return new ValidatedInsert(table, validatedColumns);
    }

    private Set<String> validateJsonColumns(List<String> columns, Set<String> jsonColumns) {
        if (jsonColumns == null) {
            throw new IllegalArgumentException("JSON columns are required");
        }
        Set<String> validated = jsonColumns.stream()
                .map(column -> SqlIdentifiers.requireValid("JSON column", column))
                .collect(Collectors.toUnmodifiableSet());
        if (!columns.containsAll(validated)) {
            throw new IllegalArgumentException("JSON columns must be included in insert columns");
        }
        return validated;
    }

    private String placeholders(List<String> columns, Set<String> jsonColumns) {
        return columns.stream()
                .map(column -> jsonColumns.contains(column) ? "CAST(? AS jsonb)" : "?")
                .collect(Collectors.joining(", "));
    }

    private record ValidatedInsert(String table, List<String> columns) {}
}
