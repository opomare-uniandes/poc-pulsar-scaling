package org.osmond.pulsarpublisher.handler;

import org.osmond.pulsarpublisher.domain.PublishRequest;
import org.osmond.pulsarpublisher.domain.port.MessagePort;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Component
public class MessageHandler {

    private final MessagePort messagePort;

    public MessageHandler(MessagePort messagePort) {
        this.messagePort = messagePort;
    }

    public Mono<ServerResponse> publish(ServerRequest request) {
        return request.bodyToMono(PublishRequest.class)
                .doOnNext(messagePort::publish)
                .then(ServerResponse.accepted().build());
    }

}
