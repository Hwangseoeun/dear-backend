package shop.dear.financial.payment.application.port;

import shop.dear.financial.payment.application.dto.PgApprovalResult;

import java.math.BigDecimal;

public interface PgPaymentApprovalPort {

    PgApprovalResult approve(
            String paymentKey,
            String orderId,
            BigDecimal amount,
            String idempotencyKey
    );
}
