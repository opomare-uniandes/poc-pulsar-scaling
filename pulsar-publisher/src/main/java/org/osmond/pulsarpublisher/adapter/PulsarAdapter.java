package org.osmond.pulsarpublisher.adapter;

import org.osmond.pulsarpublisher.domain.PublishRequest;
import org.osmond.pulsarpublisher.domain.port.MessagePort;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

@Component
public class PulsarAdapter implements MessagePort {

    private static final String TOPIC = "hello-pulsar-topic";

    private final PulsarTemplate<String> pulsarTemplate;

    public PulsarAdapter(PulsarTemplate<String> pulsarTemplate) {
        this.pulsarTemplate = pulsarTemplate;
    }

    @Override
    public void publish(PublishRequest request) {
        pulsarTemplate.sendAsync(TOPIC, request.content());
    }

}
