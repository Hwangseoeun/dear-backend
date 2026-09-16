package shop.dear.financial.settlement.application.port;

import shop.dear.financial.settlement.application.dto.WalletInfo;

public interface WalletPort {

	WalletInfo getWalletId(Long memberId);
}
