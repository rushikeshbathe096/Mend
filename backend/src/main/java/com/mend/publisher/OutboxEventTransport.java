package com.mend.publisher;

import com.mend.domain.entity.OutboxEvent;

/** Redis transport boundary. Implementations must not mutate database state. */
public interface OutboxEventTransport {
    void publishOutbox(OutboxEvent event);
}
