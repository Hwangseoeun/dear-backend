package shop.dear.financial.payment.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shop.dear.financial.payment.application.dto.PgApprovalResult;
import shop.dear.financial.payment.application.event.WalletTopUpRequestedEvent;
import shop.dear.financial.payment.application.port.WalletTopUpEventPublisher;
import shop.dear.financial.payment.domain.constant.PGPaymentStatus;
import shop.dear.financial.payment.domain.constant.PaymentPurpose;
import shop.dear.financial.payment.domain.constant.PaymentStatus;
import shop.dear.financial.payment.domain.exception.PaymentErrorCode;
import shop.dear.financial.payment.domain.model.Payment;
import shop.dear.financial.payment.domain.repository.PaymentRepository;
import shop.dear.common.exception.BusinessException;

@Service
@RequiredArgsConstructor
public class TopUpApprovalCompletionService {
    private final PaymentRepository paymentRepository;
    private final WalletTopUpEventPublisher walletTopUpEventPublisher;

    @Transactional
    public void recordApproval(final PgApprovalResult approvalResult) {
        final Payment payment = paymentRepository.findByMerchantOrderId(
                        approvalResult.orderId()
                )
                .orElseThrow(() ->
                        new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND)
                );

        if (payment.getPurpose() != PaymentPurpose.TOPUP) {
            throw new BusinessException(PaymentErrorCode.INVALID_PAYMENT_PURPOSE);
        }

        if (payment.getState() != PaymentStatus.PENDING
                || payment.getPgPayment().getState() != PGPaymentStatus.READY) {
            throw new BusinessException(
                    PaymentErrorCode.INVALID_PAYMENT_STATUS_TRANSITION
            );
        }

        payment.approvePgPayment(
                approvalResult.paymentKey(),
                approvalResult.lastTransactionKey(),
                approvalResult.approvedAmount()
        );

        walletTopUpEventPublisher.publish(
                new WalletTopUpRequestedEvent(
                        payment.getId(),
                        payment.getMemberId(),
                        payment.getAmount()
                )
        );
    }
}
