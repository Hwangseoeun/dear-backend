package shop.dear.financial.wallet.presentation.dto.request;

import jakarta.validation.constraints.NotNull;

public record WalletRequest(
        @NotNull Long memberId
) {
}
