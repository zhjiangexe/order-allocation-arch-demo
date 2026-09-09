# Fulfillment fixtures

`fulfillment.json` 衍生自 `docs/plans/allocation-console/t1-contract-samples.json` 的真實 Events／Temporal
回應，只補上 T2 的 `orchestrationMode` 與 `workflowQueryStatus`。原始基線檔未修改。

測試以 `structuredClone` 修改各情境，保留正常完成案例的訂單／作業／Shipment 關聯。
這是測試 fixture，不代表本次重新執行過真實後端流程。
