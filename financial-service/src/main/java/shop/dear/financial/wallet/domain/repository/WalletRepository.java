package shop.dear.financial.wallet.domain.repository;

import shop.dear.financial.wallet.domain.model.Wallet;
import java.util.Optional;

public interface WalletRepository {
    Optional<Wallet> findById(Long walletId);

    Optional<Wallet> findByMemberId(Long memberId);

    Wallet save(Wallet wallet);
}
