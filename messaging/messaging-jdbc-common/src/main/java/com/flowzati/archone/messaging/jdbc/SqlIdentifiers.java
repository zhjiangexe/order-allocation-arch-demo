package com.flowzati.archone.messaging.jdbc;

import java.util.regex.Pattern;

final class SqlIdentifiers {

  private static final Pattern VALID_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private SqlIdentifiers() {
  }

  static String requireValid(String role, String identifier) {
    if (identifier == null || !VALID_IDENTIFIER.matcher(identifier).matches()) {
      throw new IllegalArgumentException("Invalid " + role + " SQL identifier: " + identifier);
    }
    return identifier;
  }

  static String requireQualifiedTable(String table) {
    if (table == null) {
      throw new IllegalArgumentException("Invalid table SQL identifier: null");
    }
    String[] segments = table.split("\\.", -1);
    if (segments.length < 1 || segments.length > 2) {
      throw new IllegalArgumentException("Invalid table SQL identifier: " + table);
    }
    for (String segment : segments) {
      requireValid("table", segment);
    }
    return table;
  }
}
