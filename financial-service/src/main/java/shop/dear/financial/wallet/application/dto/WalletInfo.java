package shop.dear.financial.wallet.application.dto;

import shop.dear.financial.wallet.domain.model.Wallet;

import java.math.BigDecimal;

public record WalletInfo(
        Long memberId,
        BigDecimal availableBalance,
        BigDecimal heldBalance
) {
    public static WalletInfo from(final Wallet wallet) {
        return new WalletInfo(
                wallet.getMemberId(),
                wallet.getAvailableBalance(),
                wallet.getHeldBalance()
        );
    }
}
