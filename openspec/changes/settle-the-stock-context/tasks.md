## 0. 前置

- [x] 0.1 動手前跑一次 `test` 與 `sit`，記下支數（288 / 139）。**這個 change 不新增也不移除測試**——收工時要一模一樣。

  唯一的例外是 `OrderLine` 那三個消失的方法會帶走它們的單元測試，那些要列在 4.4。

- [x] 0.2 移除 Postgres volume（`V2` 會被改寫）。作法同前四個 change：`docker compose -f e2e/perf/docker-compose.yml rm -sfv postgres` 後 `up -d postgres`。

## 1. package `allocation` → `stock`

- [x] 1.1 依 design 的決策「`stock` 而不是 `inventory`」，把 `com.flowzati.archone.allocation` 整個移到 `com.flowzati.archone.stock`（`main`、`test`、`sit` 三個 source set）。

  **只改 package 宣告與 import 行，不做全文字串替換。** 上一次同類的機械改動（`node_id` → `location_id`）差點改壞 `fulfillment_node_id`，教訓是範圍要收得比想像中更窄。

- [x] 1.2 依 design 的決策「改的是 context 的名字，不是配貨這個概念」，**`Allocation*` 的型別一律不改名**。

  留下的清單：`AllocationService`、`AllocationOutcome`、`AllocationResult`、`AllocationPlan`、`AllocationRequest`、`AllocationSelector`、`AllocationPolicy`、`AllocationContext`、`AllocationRetryExecutor`、`AllocationDomainEventTranslator`、`AllocationBoundaryArchitectureTest`、`OrderAllocationCompleted`、`OrderAllocated`。

  判準：**這個名字說的是「配貨」還是「這個 context」？** 說前者就留。任何一個被改成 `Stock*` 都是走偏的訊號。

- [x] 1.3 `AllocationBoundaryArchitectureTest` 的兩個路徑常數跟著改（`ALLOCATION_ROOT`）。**規則本身一個字不動**——它守的界線沒有變。

## 2. `ReleaseReservation*` → `CancelMovements*`

- [x] 2.1 `ReleaseReservationUsecase` → `CancelMovementsUsecase`，`ReleaseReservationCommand` → `CancelMovementsCommand`，連同 `OrderCancelledIntegrationEventHandler` 裡的組裝與兩支測試。

  型別的 javadoc 自己寫著這件事欠著：「類別名還叫 `ReleaseReservation`，而預留已經不是一個獨立的東西了」。新名字與它委派的 `MovementCanceller` 對得起來。

- [x] 2.2 **對外事件 `OrderCancelledIntegrationEvent` 不動。** 那是 ordering 發的，說的是訂單被取消，與這一側怎麼稱呼它的處置無關。

## 3. 兩個明確不改

- [x] 3.1 Kafka topic `promising.allocation-events` 與常數 `PromisingEventTopics.ALLOCATION_EVENTS` **不動**。驗證階段以 `grep` 確認它們原樣存在。

  與 `/stock-pool` 端點同一個判準：名字站得住就沒有債要償，改它只會製造協調成本。

- [x] 3.2 設定鍵前綴 `archone.allocation.*` **整組不動**。

  執行時發現不只一個：`partition-key-strategy`（前端逐字讀它，`AppHeader.tsx:11`；`e2e/perf/run.sh` 也以命令列傳它）與 `replenishment-wake-limit`（只有內部讀）。

  **整組留，不拆一半。** 只改內部那個會讓同一個前綴下的兩把鑰匙分家——那比兩個都舊更難讀。判準與 topic 相同：它是運維契約，而運維契約沒有債。

## 4. `order_lines.status` 移除

- [x] 4.1 依 `order-intake` 的 **A line's status mirrors its header, and no timestamp is stored on it**，以及 design 的決策「`order_lines.status`：欄位刪掉，REST 欄位留著」，改寫 `V2__create_ordering_tables.sql` 移除 `order_lines.status` 欄位與相關約束。檔頭要記下「它為什麼曾經存在、以及那個理由為什麼不成立」。

- [x] 4.2 `OrderLine` 移除 `status` 欄位與 `markAllocated` / `markBackOrdered` / `markCancelled`。**它因此完全沒有可變狀態。**

- [x] 4.3 `Order` 的三個狀態方法不再逐行呼叫（`lines.forEach(OrderLine::markAllocated)` 那幾行消失）。

- [x] 4.4 依同一 requirement 的第一個 scenario，`OrderStatusResponse` 的逐行 `status` **保留**，改由 header 導出。

  **REST 契約一個欄位都不變**，前端因此完全不動——前端的 `OrderLineView` 註解本來就寫著「`status` 隨整張單走（ship-complete）」。

  `OrderLine` 那三個方法帶走的測試：`OrderTest` 的「配置後所有行一起成為 ALLOCATED」與「取消後所有行一起成為 CANCELLED」（各驗一個已不存在的欄位），「缺貨後⋯⋯」那支保留但拿掉逐行斷言。

  **性質沒有消失，是搬家了**：新增 `OrderControllerTest`「逐行的狀態由 header 導出」，刻意用 `BACKORDERED` 而不是 `PENDING`——後者是新建的行本來就會有的值，拿它驗導出等於什麼都沒驗。

  淨變化 −2 +1 = **−1**（288 → 287）。

- [x] 4.5 依 design 的決策「`-er` 明確不改」，`MovementRecorder` / `Assigner` / `Completer` / `Canceller` **不改名**，理由已寫進 design。

  這一項不寫程式，但它要在 tasks 裡出現——否則下一個做命名收斂的人會以為那四個是漏掉的。

## 5. 驗證

- [x] 5.1 `test` 與 `sit` 的支數與 0.1 記下的相同（扣掉 4.4 列出的那些）。三支護欄測試**斷言一行未改**。

- [x] 5.2 前端不動：`frontend` 既有測試全綠，且 `git diff -- frontend` 為空。

- [x] 5.3 `grep` 確認三件事原樣存在：topic 常數、設定鍵、以及 1.2 清單裡的每一個 `Allocation*` 型別。

- [x] 5.4 `grep -r "archone.allocation"` 在 `src/main/java` 底下**只剩設定鍵**——package 已無殘留。

- [x] 5.4a 文件裡指向舊 package 路徑的連結要修——但**只修活的文件**。

  `docs/done/`、`.spectra/snapshots/`、`.superpowers/sdd/` 是歷史紀錄，裡面本來就有已不存在的型別（`OrderAllocationCoordinator`、`StockReservation`、`ReleaseReservationListener`）。改寫它們是修改歷史，而快照還關係到 unarchive 的正確性。

  執行時我先改過頭再還原了 20 個歷史檔——教訓寫在這裡：**這類全域替換要先分「活的」與「歷史的」再動手**。

  活的：`e2e/perf/README.md`、`openspec/specs/*`。

- [x] 5.5 更新 `docs/dom-stock-movement-scope.md`：把「五個 change 的順序」表中第 4 列標記為已交付，並把文中所有 `allocation` package 的引用改為 `stock`（**只改 package 的引用**，指配貨概念的地方不動）。

- [x] 5.6 這是最後一個 change：scope 文件開頭已記下「五個 change 全部交付」，並指向 roadmap 的「已識別但未排程」。

  確認仍在 roadmap 裡的：`AllocatableStock` / `AllocationAttempt`（觸發點 R8 之前）、釋放後喚醒佇列（與取消按鈕同一個 change）、取消的非同步窗口。
