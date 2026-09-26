# Industry Positioning Reference

狀態：2026-09-24 外部公開資料摘要

本文件比較產品公開的 business objects、processes 與 product boundaries，不推定廠商未公開的程式架構或 bounded
context 實作。

## 觀察摘要

主流做法通常是「同一平台、不同業務文件、共同監控」：

- WMS 可以同時處理 inbound、outbound、returns，但三者保留不同文件、狀態與規則。
- OMS／DOM 的中央 fulfillment orchestration 聚焦 customer order lifecycle，並擁有 sourcing、promise、split 與
  replanning 權力。
- Control Tower／warehouse monitor 提供跨流程可視性、告警與操作入口，不表示所有來源流程共享同一 aggregate。

## 公開產品模型

| 廠商／產品 | 公開模型 | 對 Archone 的意義 |
| --- | --- | --- |
| [Microsoft D365 WMS-only](https://learn.microsoft.com/en-us/dynamics365/supply-chain/warehousing/wms-only-mode-overview) | 外部 ERP／OMS 保留訂單與財務；WMS 使用分立 inbound／outbound shipment orders，支援 Owner inventory dimension | 接近目前 warehouse-centric A；不需要中央 OMS |
| [SAP EWM Warehouse Monitor](https://help.sap.com/docs/SAP_EXTENDED_WAREHOUSE_MANAGEMENT/3d97bec9bf1649099384bb8167df3cf2/51cdcb53ad377114e10000000a174cb4.html) | Inbound Delivery、Outbound Delivery Order 與 warehouse work 分立，由 Monitor 統一監控與例外操作 | 統一 Control Tower 不要求合併 domain model |
| [Infor WMS](https://docs.infor.com/wms/2026.x/en-us/wmsolh/default.html?helpcontent=inventoryholdcodes_list.html) | Owner-specific policy、ASN／Receipt、Shipment Order、Return Receipt 分立；3PL Billing 為整合模組 | 支持 Owner policy、分立流程與獨立 Billing |
| [Oracle Order Management](https://docs.oracle.com/en/cloud/saas/supply-chain-and-manufacturing/26b/faiom/orchestration-processes.html) | Fulfillment Line orchestration 包含 schedule、reserve、ship、invoice 與 change management | 屬於 OMS／DOM（B）；需有中央履約決策權 |
| [Manhattan Unified Control](https://www.manh.com/solutions/supply-chain-management-software/warehouse-management/unified-control) | 跨設施、資源、ASN 與 outbound order 的控制面；Order Management 另負責 sourcing 與 promise | 支持「WMS execution＋Control Tower」，並區分 OMS 權責 |
| [Blue Yonder](https://blueyonder.com/solutions/returns-management/industry/logistics-service-providers) | WMS、Order Management、Returns Management 是分立能力並透過平台整合 | 支持長期分立 context＋共同控制面 |

## 對 Archone 的路線判斷

1. **現在**：建立正式 Outbound Fulfillment Operations，補上 submission review、FulfillmentCase、SLA、取消與例外。
2. **下一步**：建立 Partner Integration、Operational Service Policy、3PL Billing 與跨 context Control Tower projection。
3. **按需求升格**：Inbound Coordination 與 Returns Management 只有在各自規則成熟後成為 bounded context。
4. **保留 OMS 接縫**：未來取得選倉、拆單、promise 與成本最佳化權時，才新增權威 FulfillmentPlan。

因此目標不是 generic Operations Control domain，而是由 warehouse-centric A 演進成 A+C，必要時再增加 B。
