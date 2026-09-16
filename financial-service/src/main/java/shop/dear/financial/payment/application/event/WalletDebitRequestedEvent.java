package shop.dear.financial.payment.application.event;

import java.math.BigDecimal;

public record WalletDebitRequestedEvent(
        Long paymentId,
        Long memberId,
        BigDecimal amount,
        String orderType
) {
}
