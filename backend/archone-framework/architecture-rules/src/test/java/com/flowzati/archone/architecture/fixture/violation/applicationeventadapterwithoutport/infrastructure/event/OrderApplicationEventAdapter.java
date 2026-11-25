package com.flowzati.archone.architecture.fixture.violation.applicationeventadapterwithoutport.infrastructure.event;

/** 名稱正確，但沒有實作任何 application.port 的 Publisher。 */
public class OrderApplicationEventAdapter {
    public void publish(String orderId) {}
}
