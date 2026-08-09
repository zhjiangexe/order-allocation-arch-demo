package com.flowzati.archone.messaging.jdbc;

import java.util.List;

/** Generates vendor-specific parameterized SQL from prevalidated identifiers. */
public interface MessagingSqlDialect {

  String insert(String qualifiedTable, List<String> columns);

  String insertIgnoringDuplicate(
      String qualifiedTable,
      List<String> columns,
      List<String> conflictColumns
  );
}
