package org.osmond.pulsarpublisher.router;

import org.osmond.pulsarpublisher.handler.MessageHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class MessageRouter {

    @Bean
    public RouterFunction<ServerResponse> routes(MessageHandler handler) {
        return RouterFunctions.route()
                .POST("/messages", handler::publish)
                .build();
    }

}
