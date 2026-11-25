package com.flowzati.archone.architecture.fixture.violation.nolayer.sales.api;

/** api 只允許在 context 最上層，模組內的 api 套件不算。 */
public record ModuleLevelApi(String id) {}
