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

/** Allocation demand 的 Spring Data repository；複雜查詢只回傳配置流程需要的最小資料。 */
public interface JpaAllocationDemandRepository extends JpaRepository<AllocationDemandEntity, UUID> {

    /** backlog 排程使用的 queue 識別資料。 */
    interface AllocationDemandQueueKeyView {
        UUID getOwnerId();

        UUID getFacilityId();

        UUID getLocationId();

        String getSkuCode();
    }

    /** 某個 SKU queue 目前最前面的 demand。 */
    interface AllocationQueueHeadView {
        String getSkuCode();

        UUID getAllocationDemandId();

        String getSourceType();

        java.time.Instant getEnqueuedAt();
    }

    Optional<AllocationDemandEntity> findBySourceTypeAndSourceIdAndAllocationUnitKey(
            AllocationSourceType sourceType, String sourceId, String allocationUnitKey);

    /** 依入列時間讀取 demands，並一次載入 lines，避免逐筆查詢。 */
    @EntityGraph(attributePaths = "lines")
    List<AllocationDemandEntity> findByStatusOrderByEnqueuedAtAscIdAsc(
            com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus status, Pageable pageable);

    /** 找出比 candidate 更早、且競爭任一相同 SKU 的第一筆 PENDING demand。 */
    @Query(value = """
      SELECT predecessor.id
        FROM allocation_demands predecessor
        JOIN allocation_demand_lines line ON line.allocation_demand_id = predecessor.id
       WHERE predecessor.status = 'PENDING'
         AND predecessor.owner_id = :ownerId
         AND predecessor.facility_id = :facilityId
         AND predecessor.location_id = :locationId
         AND line.sku_code IN (:skuCodes)
         AND (predecessor.enqueued_at, predecessor.id) < (:candidateEnqueuedAt, :candidateId)
       ORDER BY predecessor.enqueued_at, predecessor.id
       LIMIT 1
      """, nativeQuery = true)
    Optional<UUID> findBlockingDemandId(
            @Param("ownerId") UUID ownerId,
            @Param("facilityId") UUID facilityId,
            @Param("locationId") UUID locationId,
            @Param("candidateEnqueuedAt") java.time.Instant candidateEnqueuedAt,
            @Param("candidateId") UUID candidateId,
            @Param("skuCodes") Collection<String> skuCodes);

    /** 取得指定 queue 中 FIFO 最前面且 execution references 完整的 demand ID。 */
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
       LIMIT 1
      """, nativeQuery = true)
    Optional<UUID> findPendingQueueHeadId(
            @Param("ownerId") UUID ownerId,
            @Param("facilityId") UUID facilityId,
            @Param("locationId") UUID locationId,
            @Param("skuCode") String skuCode);

    /** 對 candidate 需要的每個 SKU，各取一筆不晚於 candidate 的 FIFO queue head。 */
    @Query(value = """
      SELECT DISTINCT ON (line.sku_code)
             line.sku_code AS skuCode,
             demand.id AS allocationDemandId,
             demand.source_type AS sourceType,
             demand.enqueued_at AS enqueuedAt
        FROM allocation_demands demand
        JOIN allocation_demand_lines line ON line.allocation_demand_id = demand.id
       WHERE demand.status = 'PENDING'
         AND demand.owner_id = :ownerId
         AND demand.facility_id = :facilityId
         AND demand.location_id = :locationId
         AND line.sku_code IN (:skuCodes)
         AND (demand.enqueued_at, demand.id) <= (:candidateEnqueuedAt, :candidateId)
       ORDER BY line.sku_code, demand.enqueued_at, demand.id
      """, nativeQuery = true)
    List<AllocationQueueHeadView> findRequiredQueueHeads(
            @Param("ownerId") UUID ownerId,
            @Param("facilityId") UUID facilityId,
            @Param("locationId") UUID locationId,
            @Param("candidateEnqueuedAt") java.time.Instant candidateEnqueuedAt,
            @Param("candidateId") UUID candidateId,
            @Param("skuCodes") Collection<String> skuCodes);

    /** 找出目前有可用庫存、值得本輪排程嘗試的 distinct queue keys。 */
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
    List<AllocationDemandQueueKeyView> findPendingQueueKeysWithAvailableStock(
            @Param("today") java.time.LocalDate today, @Param("queueKeyLimit") int queueKeyLimit);

    /** 取樣因 execution references 不完整而不應進入配置的 PENDING demands，供 health check 使用。 */
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
