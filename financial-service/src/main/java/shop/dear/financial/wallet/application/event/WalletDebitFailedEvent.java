package shop.dear.financial.wallet.application.event;

public record WalletDebitFailedEvent(
        Long paymentId
) {
}
