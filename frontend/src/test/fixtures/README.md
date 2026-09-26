# Fulfillment fixtures

`fulfillment.json` 是 Events／Temporal 履約查詢的固定前端測試樣本，包含 `orchestrationMode` 與
`workflowQueryStatus`。

測試以 `structuredClone` 修改各情境，保留正常完成案例的訂單／作業／Shipment 關聯。
這是測試 fixture，不代表本次重新執行過真實後端流程；API 相容性仍以後端 contract tests 與 E2E scenarios 為準。
