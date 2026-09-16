package shop.dear.financial.payment.application.dto;

import shop.dear.financial.payment.domain.constant.PaymentStatus;
import shop.dear.financial.payment.domain.model.Payment;

public record PaymentInfo(
        Long paymentId,
        PaymentStatus state
) {
    public static PaymentInfo from(final Payment payment) {
        return new PaymentInfo(
                payment.getId(),
                payment.getState()
        );
    }
}
