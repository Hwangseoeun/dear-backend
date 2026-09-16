package shop.dear.financial.payment.application.dto;

import java.math.BigDecimal;

public record PgApprovalResult(
        String paymentKey,
        String orderId,
        String lastTransactionKey,
        BigDecimal approvedAmount
) {
}
