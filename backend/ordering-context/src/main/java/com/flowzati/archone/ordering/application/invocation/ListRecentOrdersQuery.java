package com.flowzati.archone.ordering.application.invocation;

import java.util.Optional;
import java.util.UUID;

public record ListRecentOrdersQuery(Optional<UUID> ownerId, int limit) {

    public ListRecentOrdersQuery {
        ownerId = ownerId == null ? Optional.empty() : ownerId;
    }

    public static ListRecentOrdersQuery forAllOwners(int limit) {
        return new ListRecentOrdersQuery(Optional.empty(), limit);
    }

    public static ListRecentOrdersQuery forOwner(UUID ownerId, int limit) {
        return new ListRecentOrdersQuery(Optional.of(ownerId), limit);
    }
}
