package shop.dear.financial.settlement.infrastructure.client.dto;

public record WalletApiResponse(
	Long walletId,
	Long memberId
) {
}
