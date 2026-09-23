# Inventory API

Inventory 透過 Integration Event 接收取消後的訂單事實，不提供同步取消命令。
此 project 目前只公開 fulfillment read view 所需的 stock operation query contract；
寫入與流程命令仍由 Integration Event 或 Temporal Activity 驅動。
