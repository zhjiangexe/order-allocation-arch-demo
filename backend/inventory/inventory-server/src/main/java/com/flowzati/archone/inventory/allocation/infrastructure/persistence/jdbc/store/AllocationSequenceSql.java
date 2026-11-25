package com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store;

import com.flowzati.archone.inventory.allocation.domain.enums.AllocationSequencePolicy;

final class AllocationSequenceSql {
    private AllocationSequenceSql() {}

    static String columns(AllocationSequencePolicy policy, String alias) {
        return switch (policy) {
            case FIFO -> alias + ".enqueued_at, " + alias + ".id";
            case DISPATCH_DATE_FIRST -> alias + ".dispatch_by, " + alias + ".enqueued_at, " + alias + ".id";
        };
    }
}
