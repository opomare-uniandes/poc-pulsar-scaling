package org.osmond.pulsarpublisher.domain.port;

import org.osmond.pulsarpublisher.domain.PublishRequest;

public interface MessagePort {
    void publish(PublishRequest request);
}
