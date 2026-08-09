package com.flowzati.archone.messaging.jdbc;

import java.util.List;
import java.util.stream.Collectors;

/** PostgreSQL SQL shape used by the first JDBC producer and duplicate detector implementations. */
public final class PostgresMessagingSqlDialect implements MessagingSqlDialect {

  @Override
  public String insert(String qualifiedTable, List<String> columns) {
    ValidatedInsert insert = validate(qualifiedTable, columns);
    return "INSERT INTO %s (%s) VALUES (%s)".formatted(
        insert.table(),
        String.join(", ", insert.columns()),
        placeholders(insert.columns().size()));
  }

  @Override
  public String insertIgnoringDuplicate(
      String qualifiedTable,
      List<String> columns,
      List<String> conflictColumns
  ) {
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
    return insert(qualifiedTable, columns)
        + " ON CONFLICT (" + String.join(", ", validatedConflicts) + ") DO NOTHING";
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

  private String placeholders(int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(index -> "?")
        .collect(Collectors.joining(", "));
  }

  private record ValidatedInsert(String table, List<String> columns) {
  }
}
