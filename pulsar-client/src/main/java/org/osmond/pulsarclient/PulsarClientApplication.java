package org.osmond.pulsarclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.pulsar.annotation.PulsarListener;

@SpringBootApplication
public class PulsarClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(PulsarClientApplication.class, args);
    }

    @PulsarListener(subscriptionName = "hello-pulsar-sub", topics = "hello-pulsar-topic")
    void listen(String message) {
        System.out.println("Message Received: " + message);
    }

}
