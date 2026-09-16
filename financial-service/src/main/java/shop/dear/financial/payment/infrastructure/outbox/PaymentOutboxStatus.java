package shop.dear.financial.payment.infrastructure.outbox;

public enum PaymentOutboxStatus {
    PENDING,
    SENT,
    FAILED,
    EXHAUSTED
}
