package shop.dear.financial.wallet.application.port;

import shop.dear.financial.wallet.domain.constant.WalletLogType;
import java.math.BigDecimal;
import java.util.Optional;

public interface WalletLogQueryPort {

    Optional<BigDecimal> findLogAmount(
            Long walletId,
            WalletLogType type,
            Long referenceId
    );
}
