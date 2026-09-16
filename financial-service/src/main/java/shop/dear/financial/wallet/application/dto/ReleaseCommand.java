package shop.dear.financial.wallet.application.dto;

import java.math.BigDecimal;

public record ReleaseCommand(
        Long memberId,
        BigDecimal amount,
        Long offerId
) {
}
