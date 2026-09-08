package com.flowzati.archone.inventory.allocation.infrastructure.persistence.jdbc.store;

import com.flowzati.archone.inventory.allocation.application.store.OwnerAllocationPolicyStore;
import com.flowzati.archone.inventory.allocation.domain.policy.AllocationSequencePolicy;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOwnerAllocationPolicyStoreAdapter implements OwnerAllocationPolicyStore {
    private final JdbcClient jdbcClient;

    public JdbcOwnerAllocationPolicyStoreAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public AllocationSequencePolicy find(UUID ownerId) {
        Objects.requireNonNull(ownerId);
        return jdbcClient
                .sql("""
                SELECT COALESCE(setting.sequence_policy, 'DISPATCH_DATE_FIRST')
                FROM owners owner LEFT JOIN owner_allocation_policies setting ON setting.owner_id = owner.id
                WHERE owner.id = ?
                """)
                .param(ownerId)
                .query(String.class)
                .optional()
                .map(AllocationSequencePolicy::valueOf)
                .orElseThrow(() -> new NoSuchElementException("Owner not found: " + ownerId));
    }

    @Override
    @Transactional
    public void save(UUID ownerId, AllocationSequencePolicy policy) {
        Objects.requireNonNull(policy);
        requireOwnerExists(ownerId);
        jdbcClient.sql("""
                INSERT INTO owner_allocation_policies (owner_id, sequence_policy) VALUES (?, ?)
                ON CONFLICT (owner_id) DO UPDATE SET sequence_policy = EXCLUDED.sequence_policy
                """).param(ownerId).param(policy.name()).update();
    }

    private void requireOwnerExists(UUID ownerId) {
        Objects.requireNonNull(ownerId);
        jdbcClient
                .sql("SELECT id FROM owners WHERE id = ?")
                .param(ownerId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new NoSuchElementException("Owner not found: " + ownerId));
    }
}
