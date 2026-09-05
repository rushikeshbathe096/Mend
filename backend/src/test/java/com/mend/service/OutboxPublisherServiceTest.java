package com.mend.service;

import com.mend.domain.entity.OutboxEvent;
import com.mend.domain.repository.OutboxEventRepository;
import com.mend.publisher.OutboxEventTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherServiceTest {

    private OutboxEventRepository repository;
    private OutboxEventTransport transport;
    private OutboxEventStateService stateService;
    private OutboxPublisherService service;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        transport = mock(OutboxEventTransport.class);
        stateService = mock(OutboxEventStateService.class);
        service = new OutboxPublisherService(repository, transport, stateService, 10, 1, 60, false);
    }

    @Test
    void successfulPublicationMarksPublishedOnlyAfterRedisAccepts() {
        OutboxEvent event = pendingEvent();
        when(repository.findDueForPublication(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(event));

        assertEquals(1, service.publishDueEvents());

        InOrder order = inOrder(transport, stateService);
        order.verify(transport).publishOutbox(event);
        order.verify(stateService).markPublished(any(UUID.class), any(Instant.class));
        verify(stateService, never()).markFailed(any(), any(), any(), any());
    }

    @Test
    void redisFailureLeavesEventRetryableAndRecordsBackoff() {
        OutboxEvent event = pendingEvent();
        when(repository.findDueForPublication(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(event));
        doThrow(new RuntimeException("redis unavailable")).when(transport).publishOutbox(event);

        assertEquals(0, service.publishDueEvents());

        verify(stateService, never()).markPublished(any(), any());
        verify(stateService).markFailed(any(), any(), any(), any());
    }

    @Test
    void redisRecoveryPublishesPreviouslyFailedOutboxEvent() {
        OutboxEvent event = pendingEvent();
        when(repository.findDueForPublication(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(event), List.of(event));
        doThrow(new RuntimeException("redis unavailable"))
                .doNothing()
                .when(transport).publishOutbox(event);

        assertEquals(0, service.publishDueEvents());
        assertEquals(1, service.publishDueEvents());

        verify(transport, times(2)).publishOutbox(event);
        verify(stateService).markFailed(any(), any(), any(), any());
        verify(stateService).markPublished(any(), any());
    }

    @Test
    void crashAfterRedisAcceptsCausesSafeAtLeastOnceRepublish() {
        OutboxEvent event = pendingEvent();
        when(repository.findDueForPublication(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(event), List.of(event));
        // Models a DB/process failure after Redis accepted the record but before
        // the PUBLISHED state could be durably recorded.
        doThrow(new RuntimeException("database unavailable after redis accept"))
                .when(stateService).markPublished(any(), any());

        assertEquals(0, service.publishDueEvents());
        assertEquals(0, service.publishDueEvents());

        verify(transport, times(2)).publishOutbox(event);
        verify(stateService, times(2)).markPublished(any(), any());
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.pendingWebhook(
                UUID.randomUUID(), UUID.randomUUID(), "{}",
                "trace-test", "correlation-test", Instant.now());
    }
}
