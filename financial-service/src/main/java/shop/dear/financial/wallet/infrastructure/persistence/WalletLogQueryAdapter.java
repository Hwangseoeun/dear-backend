package shop.dear.financial.wallet.infrastructure.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import shop.dear.financial.wallet.application.port.WalletLogQueryPort;
import shop.dear.financial.wallet.domain.constant.WalletLogType;
import shop.dear.financial.wallet.infrastructure.persistence.jpa.WalletJpaRepository;

import java.math.BigDecimal;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class WalletLogQueryAdapter implements WalletLogQueryPort {

    private final WalletJpaRepository walletJpaRepository;

    @Override
    public Optional<BigDecimal> findLogAmount(
            final Long walletId,
            final WalletLogType type,
            final Long referenceId
    ) {
        return walletJpaRepository.findLogAmount(
                walletId,
                type,
                referenceId
        );
    }
}
