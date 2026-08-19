package com.flowzati.archone.messaging.jdbc;

import java.util.Objects;
import java.util.Optional;

/** Optional validated database schema used to qualify messaging tables. */
public final class MessagingSchema {

    private final String name;

    private MessagingSchema(String name) {
        this.name = name;
    }

    public static MessagingSchema defaultSchema() {
        return new MessagingSchema(null);
    }

    public static MessagingSchema named(String name) {
        return new MessagingSchema(SqlIdentifiers.requireValid("schema", name));
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public String qualify(String table) {
        String validatedTable = SqlIdentifiers.requireValid("table", table);
        return name == null ? validatedTable : name + "." + validatedTable;
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof MessagingSchema other && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        return name == null ? "MessagingSchema[default]" : "MessagingSchema[" + name + "]";
    }
}
