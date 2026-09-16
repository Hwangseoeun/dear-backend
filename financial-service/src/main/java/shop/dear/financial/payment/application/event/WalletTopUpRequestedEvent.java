package shop.dear.financial.payment.application.event;

import java.math.BigDecimal;

public record WalletTopUpRequestedEvent(
        Long paymentId,
        Long memberId,
        BigDecimal amount
) {
}
