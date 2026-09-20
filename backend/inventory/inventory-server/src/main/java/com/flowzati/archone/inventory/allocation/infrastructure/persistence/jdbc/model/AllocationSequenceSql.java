package com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.model;

import com.flowzati.archone.inventory.allocation.domain.policy.AllocationSequencePolicy;

public final class AllocationSequenceSql {
    private AllocationSequenceSql() {}

    public static String columns(AllocationSequencePolicy policy, String alias) {
        return switch (policy) {
            case FIFO -> alias + ".enqueued_at, " + alias + ".id";
            case DISPATCH_DATE_FIRST -> alias + ".dispatch_by, " + alias + ".enqueued_at, " + alias + ".id";
        };
    }
}
