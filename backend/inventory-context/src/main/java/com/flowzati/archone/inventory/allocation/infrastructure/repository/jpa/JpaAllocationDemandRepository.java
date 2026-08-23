package com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa;

import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import com.flowzati.archone.inventory.allocation.infrastructure.entity.AllocationDemandEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaAllocationDemandRepository extends JpaRepository<AllocationDemandEntity, UUID> {

    interface AllocationDemandQueueKeyView {
        UUID getOwnerId();

        UUID getFacilityId();

        UUID getLocationId();

        String getSkuCode();
    }

    Optional<AllocationDemandEntity> findBySourceTypeAndSourceIdAndAllocationUnitKey(
            AllocationSourceType sourceType, String sourceId, String allocationUnitKey);

    @EntityGraph(attributePaths = "lines")
    List<AllocationDemandEntity> findByStatusOrderByEnqueuedAtAscIdAsc(
            com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus status, Pageable pageable);

    @Query(value = """
      SELECT d.id
        FROM allocation_demands d
       WHERE d.status = 'PENDING'
         AND (d.enqueued_at, d.id) <= (:enqueuedAt, :allocationDemandId)
       ORDER BY d.enqueued_at, d.id
      """, nativeQuery = true)
    List<UUID> findPendingIdsThrough(
            @Param("enqueuedAt") java.time.Instant enqueuedAt, @Param("allocationDemandId") UUID allocationDemandId);

    @Query(value = """
      SELECT d.id
        FROM allocation_demands d
       WHERE d.status = 'PENDING'
         AND d.owner_id = :ownerId
         AND d.facility_id = :facilityId
         AND d.location_id = :locationId
         AND EXISTS (
               SELECT 1
                 FROM allocation_demand_lines line
                WHERE line.allocation_demand_id = d.id
                  AND line.sku_code = :skuCode
             )
         AND NOT EXISTS (
               SELECT 1
                 FROM allocation_demand_lines line
                WHERE line.allocation_demand_id = d.id
                  AND (SELECT COUNT(*)
                         FROM stock_moves move
                        WHERE move.allocation_demand_id = d.id
                          AND move.allocation_demand_line_id = line.id) <> 1
             )
         AND NOT EXISTS (
               SELECT 1
                 FROM stock_moves move
                 LEFT JOIN allocation_demand_lines line
                   ON line.allocation_demand_id = d.id
                  AND line.id = move.allocation_demand_line_id
                 LEFT JOIN stock_pickings picking ON picking.id = move.picking_id
                WHERE move.allocation_demand_id = d.id
                  AND (line.id IS NULL
                    OR move.state <> 'CONFIRMED'
                    OR move.owner_id <> d.owner_id
                    OR move.from_location_id <> d.location_id
                    OR move.sku_code <> line.sku_code
                    OR move.demand_quantity <> line.quantity
                    OR move.source_line_id IS DISTINCT FROM line.source_line_id
                    OR (move.picking_id IS NOT NULL
                        AND (picking.id IS NULL OR picking.state IN ('CANCELLED', 'DONE'))))
             )
       ORDER BY d.enqueued_at, d.id
       LIMIT :candidateLimit
      """, nativeQuery = true)
    List<UUID> findPendingQueueCandidateIds(
            @Param("ownerId") UUID ownerId,
            @Param("facilityId") UUID facilityId,
            @Param("locationId") UUID locationId,
            @Param("skuCode") String skuCode,
            @Param("candidateLimit") int candidateLimit);

    @Query(value = """
      SELECT DISTINCT predecessor.id
        FROM allocation_demands predecessor
        JOIN allocation_demand_lines predecessor_line
          ON predecessor_line.allocation_demand_id = predecessor.id
        JOIN allocation_demands candidate
          ON candidate.id IN (:candidateIds)
        JOIN allocation_demand_lines candidate_line
          ON candidate_line.allocation_demand_id = candidate.id
         AND candidate_line.sku_code = predecessor_line.sku_code
       WHERE predecessor.status = 'PENDING'
         AND predecessor.owner_id = :ownerId
         AND predecessor.facility_id = :facilityId
         AND predecessor.location_id = :locationId
         AND (predecessor.enqueued_at, predecessor.id)
             <= (candidate.enqueued_at, candidate.id)
       ORDER BY predecessor.id
      """, nativeQuery = true)
    List<UUID> findFifoContextIds(
            @Param("ownerId") UUID ownerId,
            @Param("facilityId") UUID facilityId,
            @Param("locationId") UUID locationId,
            @Param("candidateIds") Collection<UUID> candidateIds);

    @EntityGraph(attributePaths = "lines")
    List<AllocationDemandEntity> findByIdIn(Collection<UUID> ids);

    @Query(value = """
      SELECT d.owner_id AS ownerId,
             d.facility_id AS facilityId,
             d.location_id AS locationId,
             line.sku_code AS skuCode
        FROM allocation_demands d
        JOIN allocation_demand_lines line ON line.allocation_demand_id = d.id
       WHERE d.status = 'PENDING'
         AND EXISTS (
               SELECT 1
                 FROM stock_pools pool
                WHERE pool.owner_id = d.owner_id
                  AND pool.location_id = d.location_id
                  AND pool.sku_code = line.sku_code
                  AND pool.expiry_date >= :today
                  AND pool.on_hand_quantity > pool.reserved_quantity
             )
         AND NOT EXISTS (
               SELECT 1
                 FROM allocation_demand_lines execution_line
                WHERE execution_line.allocation_demand_id = d.id
                  AND (SELECT COUNT(*)
                         FROM stock_moves move
                        WHERE move.allocation_demand_id = d.id
                          AND move.allocation_demand_line_id = execution_line.id) <> 1
             )
         AND NOT EXISTS (
               SELECT 1
                 FROM stock_moves move
                 LEFT JOIN allocation_demand_lines execution_line
                   ON execution_line.allocation_demand_id = d.id
                  AND execution_line.id = move.allocation_demand_line_id
                 LEFT JOIN stock_pickings picking ON picking.id = move.picking_id
                WHERE move.allocation_demand_id = d.id
                  AND (execution_line.id IS NULL
                    OR move.state <> 'CONFIRMED'
                    OR move.owner_id <> d.owner_id
                    OR move.from_location_id <> d.location_id
                    OR move.sku_code <> execution_line.sku_code
                    OR move.demand_quantity <> execution_line.quantity
                    OR move.source_line_id IS DISTINCT FROM execution_line.source_line_id
                    OR (move.picking_id IS NOT NULL
                        AND (picking.id IS NULL OR picking.state IN ('CANCELLED', 'DONE'))))
             )
       GROUP BY d.owner_id, d.facility_id, d.location_id, line.sku_code
       ORDER BY MIN(d.enqueued_at), MIN(d.id::text), line.sku_code
       LIMIT :queueKeyLimit
      """, nativeQuery = true)
    List<AllocationDemandQueueKeyView> findAllocatablePendingQueueKeys(
            @Param("today") java.time.LocalDate today, @Param("queueKeyLimit") int queueKeyLimit);

    /** Pending demands excluded from allocation because execution references are unsafe. */
    @Query(value = """
      SELECT d.id
        FROM allocation_demands d
       WHERE d.status = 'PENDING'
         AND (
           EXISTS (
             SELECT 1
               FROM allocation_demand_lines line
              WHERE line.allocation_demand_id = d.id
                AND (SELECT COUNT(*)
                       FROM stock_moves move
                      WHERE move.allocation_demand_id = d.id
                        AND move.allocation_demand_line_id = line.id) <> 1
           )
           OR EXISTS (
             SELECT 1
               FROM stock_moves move
               LEFT JOIN allocation_demand_lines line
                 ON line.allocation_demand_id = d.id
                AND line.id = move.allocation_demand_line_id
               LEFT JOIN stock_pickings picking ON picking.id = move.picking_id
              WHERE move.allocation_demand_id = d.id
                AND (line.id IS NULL
                  OR move.state <> 'CONFIRMED'
                  OR move.owner_id <> d.owner_id
                  OR move.from_location_id <> d.location_id
                  OR move.sku_code <> line.sku_code
                  OR move.demand_quantity <> line.quantity
                  OR move.source_line_id IS DISTINCT FROM line.source_line_id
                  OR (move.picking_id IS NOT NULL
                      AND (picking.id IS NULL OR picking.state IN ('CANCELLED', 'DONE'))))
           )
         )
       ORDER BY d.enqueued_at, d.id
       LIMIT :limit
      """, nativeQuery = true)
    List<UUID> findPendingExecutionAnomalyIds(@Param("limit") int limit);
}
