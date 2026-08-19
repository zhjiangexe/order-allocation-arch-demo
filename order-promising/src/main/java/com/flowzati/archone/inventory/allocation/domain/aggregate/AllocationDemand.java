package com.flowzati.archone.inventory.allocation.domain.aggregate;

import com.flowzati.archone.inventory.allocation.domain.entity.AllocationDemandLine;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandLineRequest;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Allocation context 自己擁有的持久化需求根。
 *
 * <p>只保存所有 stock-consuming source 共通的 identity、scope、scheduling snapshot、lines 與
 * allocation lifecycle。來源 aggregate status、外部識別碼映射、execution intent、picking 與
 * fulfillment lifecycle 都不屬於這個 aggregate。
 */
public final class AllocationDemand {

  public static final int ACCEPTED_CONTENT_VERSION = 1;

  private final UUID id;
  private final SourceAllocationUnit source;
  private final UUID ownerId;
  private final UUID facilityId;
  private final UUID locationId;
  private final Instant requiredBy;
  private final int releasePriority;
  private final Instant enqueuedAt;
  private final int acceptedContentVersion;
  private final List<AllocationDemandLine> lines;
  private AllocationDemandStatus status;
  private final Long version;

  private AllocationDemand(
      UUID id,
      SourceAllocationUnit source,
      UUID ownerId,
      UUID facilityId,
      UUID locationId,
      Instant requiredBy,
      int releasePriority,
      Instant enqueuedAt,
      int acceptedContentVersion,
      List<AllocationDemandLine> lines,
      AllocationDemandStatus status,
      Long version
  ) {
    requireState(id, source, ownerId, facilityId, locationId, requiredBy, releasePriority,
        enqueuedAt, acceptedContentVersion, lines, status);
    this.id = id;
    this.source = source;
    this.ownerId = ownerId;
    this.facilityId = facilityId;
    this.locationId = locationId;
    this.requiredBy = requiredBy;
    this.releasePriority = releasePriority;
    this.enqueuedAt = enqueuedAt;
    this.acceptedContentVersion = acceptedContentVersion;
    this.lines = List.copyOf(lines);
    this.status = status;
    this.version = version;
  }

  /**
   * 首次 acceptance：transport line ordering 不具語意，先依 stable source-line id canonicalize，
   * 再產生 allocation-owned id 與 immutable sequence。
   */
  public static AllocationDemand accept(
      UUID id,
      SourceAllocationUnit source,
      UUID ownerId,
      UUID facilityId,
      UUID locationId,
      Instant requiredBy,
      int releasePriority,
      Instant enqueuedAt,
      List<AllocationDemandLineRequest> requestedLines,
      Supplier<UUID> lineIdSupplier
  ) {
    if (requestedLines == null || requestedLines.isEmpty()) {
      throw new IllegalArgumentException("Allocation demand requires at least one line");
    }
    if (lineIdSupplier == null) {
      throw new IllegalArgumentException("Allocation demand line ID supplier is required");
    }

    List<AllocationDemandLineRequest> canonical = requestedLines.stream()
        .sorted(Comparator.comparing(AllocationDemandLineRequest::sourceLineId))
        .toList();
    rejectDuplicateSourceLines(canonical);
    requireCheckedSkuTotals(canonical);

    List<AllocationDemandLine> lines = new ArrayList<>(canonical.size());
    for (int index = 0; index < canonical.size(); index++) {
      AllocationDemandLineRequest line = canonical.get(index);
      lines.add(new AllocationDemandLine(
          lineIdSupplier.get(), id, line.sourceLineId(), line.skuCode(), line.quantity(), index + 1));
    }
    return new AllocationDemand(
        id, source, ownerId, facilityId, locationId, requiredBy, releasePriority, enqueuedAt,
        ACCEPTED_CONTENT_VERSION, lines, AllocationDemandStatus.PENDING, null);
  }

  public static AllocationDemand rehydrate(
      UUID id,
      SourceAllocationUnit source,
      UUID ownerId,
      UUID facilityId,
      UUID locationId,
      Instant requiredBy,
      int releasePriority,
      Instant enqueuedAt,
      int acceptedContentVersion,
      List<AllocationDemandLine> lines,
      AllocationDemandStatus status,
      Long version
  ) {
    return new AllocationDemand(
        id, source, ownerId, facilityId, locationId, requiredBy, releasePriority, enqueuedAt,
        acceptedContentVersion, lines, status, version);
  }

  public boolean markAllocated() {
    if (status == AllocationDemandStatus.ALLOCATED) {
      return false;
    }
    if (status != AllocationDemandStatus.PENDING) {
      throw new IllegalStateException("Only a pending allocation demand can be allocated");
    }
    status = AllocationDemandStatus.ALLOCATED;
    return true;
  }

  public boolean cancelPending() {
    if (status == AllocationDemandStatus.CANCELLED) {
      return false;
    }
    if (status != AllocationDemandStatus.PENDING) {
      throw new IllegalStateException(
          "An allocated demand requires confirmed reversible execution cancellation");
    }
    status = AllocationDemandStatus.CANCELLED;
    return true;
  }

  /** 呼叫端已在 transaction 外取得 external confirmation，並確認本地 execution 仍可逆。 */
  public boolean cancelAllocatedAfterExecutionStopped() {
    if (status == AllocationDemandStatus.CANCELLED) {
      return false;
    }
    if (status != AllocationDemandStatus.ALLOCATED) {
      throw new IllegalStateException("Only an allocated demand uses confirmed cancellation");
    }
    status = AllocationDemandStatus.CANCELLED;
    return true;
  }

  public Map<String, Integer> totalsBySku() {
    Map<String, Integer> totals = new LinkedHashMap<>();
    for (AllocationDemandLine line : lines) {
      totals.merge(line.skuCode(), line.quantity(), Math::addExact);
    }
    return Map.copyOf(totals);
  }

  private static void requireState(
      UUID id,
      SourceAllocationUnit source,
      UUID ownerId,
      UUID facilityId,
      UUID locationId,
      Instant requiredBy,
      int releasePriority,
      Instant enqueuedAt,
      int acceptedContentVersion,
      List<AllocationDemandLine> lines,
      AllocationDemandStatus status
  ) {
    if (id == null || source == null || ownerId == null || facilityId == null || locationId == null) {
      throw new IllegalArgumentException("Allocation demand requires identity and one inventory scope");
    }
    if (requiredBy == null || enqueuedAt == null) {
      throw new IllegalArgumentException("Allocation demand requires scheduling and enqueue times");
    }
    if (releasePriority < 0 || releasePriority > 100) {
      throw new IllegalArgumentException("Release priority must be between 0 and 100");
    }
    if (acceptedContentVersion <= 0) {
      throw new IllegalArgumentException("Accepted content version must be positive");
    }
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("Allocation demand requires at least one line");
    }
    if (status == null) {
      throw new IllegalArgumentException("Allocation demand status is required");
    }

    Set<String> sourceLineIds = new HashSet<>();
    Set<Integer> sequences = new HashSet<>();
    for (AllocationDemandLine line : lines) {
      if (!id.equals(line.allocationDemandId())) {
        throw new IllegalArgumentException("Allocation demand line belongs to another demand");
      }
      if (!sourceLineIds.add(line.sourceLineId())) {
        throw new IllegalArgumentException("Duplicate source line ID " + line.sourceLineId());
      }
      if (!sequences.add(line.lineSequence())) {
        throw new IllegalArgumentException("Duplicate allocation line sequence " + line.lineSequence());
      }
    }
    requireContiguousSequence(sequences, lines.size());
    requireCheckedSkuTotals(lines.stream()
        .map(line -> new AllocationDemandLineRequest(
            line.sourceLineId(), line.skuCode(), line.quantity()))
        .toList());
  }

  private static void rejectDuplicateSourceLines(List<AllocationDemandLineRequest> lines) {
    for (int index = 1; index < lines.size(); index++) {
      if (lines.get(index - 1).sourceLineId().equals(lines.get(index).sourceLineId())) {
        throw new IllegalArgumentException(
            "Duplicate source line ID " + lines.get(index).sourceLineId());
      }
    }
  }

  private static void requireCheckedSkuTotals(List<AllocationDemandLineRequest> lines) {
    Map<String, Integer> totals = new LinkedHashMap<>();
    try {
      for (AllocationDemandLineRequest line : lines) {
        totals.merge(line.skuCode(), line.quantity(), Math::addExact);
      }
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("Aggregated allocation demand quantity exceeds integer range", overflow);
    }
  }

  private static void requireContiguousSequence(Set<Integer> sequences, int lineCount) {
    for (int sequence = 1; sequence <= lineCount; sequence++) {
      if (!sequences.contains(sequence)) {
        throw new IllegalArgumentException("Allocation demand line sequence must be contiguous");
      }
    }
  }

  public UUID id() {
    return id;
  }

  public SourceAllocationUnit source() {
    return source;
  }

  public UUID ownerId() {
    return ownerId;
  }

  public UUID facilityId() {
    return facilityId;
  }

  public UUID locationId() {
    return locationId;
  }

  public Instant requiredBy() {
    return requiredBy;
  }

  public int releasePriority() {
    return releasePriority;
  }

  public Instant enqueuedAt() {
    return enqueuedAt;
  }

  public int acceptedContentVersion() {
    return acceptedContentVersion;
  }

  public List<AllocationDemandLine> lines() {
    return lines;
  }

  public AllocationDemandStatus status() {
    return status;
  }

  public Long version() {
    return version;
  }
}
