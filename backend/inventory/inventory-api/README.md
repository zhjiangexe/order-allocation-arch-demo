# Inventory API

Inventory 目前由 Integration Event 接收取消後的訂單事實；`fulfillment-process` 沒有
同步呼叫 Inventory 的需求，因此此 project 暫時沒有 Java RPC 介面。保留獨立的
API project 作為 Inventory 的模組邊界，不為了與 Ordering／WMS 對稱而加入假操作。
