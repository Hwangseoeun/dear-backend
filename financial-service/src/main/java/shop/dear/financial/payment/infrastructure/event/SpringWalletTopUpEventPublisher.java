package shop.dear.financial.payment.infrastructure.event;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import shop.dear.financial.payment.application.event.WalletTopUpRequestedEvent;
import shop.dear.financial.payment.application.port.WalletTopUpEventPublisher;

@Component
@RequiredArgsConstructor
public class SpringWalletTopUpEventPublisher implements WalletTopUpEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(final WalletTopUpRequestedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
