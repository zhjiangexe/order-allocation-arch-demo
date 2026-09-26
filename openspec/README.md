# OpenSpec 文件治理

`specs/` 描述現行必要行為；`changes/archive/` 保存變更理由、實作過程與當時的驗證證據。

主規格中的 `@trace` 只保留 `source` 與 `updated`。實作 class、caller 與依賴關係由當前程式碼和 Graphify 衍生，
不在每條 requirement 中維護重複的 `code:`／`tests:` 清單。

只有在特定 contract、migration、configuration 或 automated test 本身構成 requirement 時，才於 requirement
正文清楚引用該路徑及理由。

完整規則見 [文件與程式碼關聯政策](../docs/documentation-reference-policy.md)。
