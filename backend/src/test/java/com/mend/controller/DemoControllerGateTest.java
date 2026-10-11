package com.mend.controller;

import com.mend.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pre-M3 hygiene: without {@code mend.demo.enabled=true} (the default) the SIMULATED demo endpoints do not exist. */
@DisplayName("Demo controller is off by default")
class DemoControllerGateTest extends AbstractIntegrationTest {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("No DemoController bean (so /api/v1/demo has no handler) unless mend.demo.enabled=true")
    void demoControllerIsAbsentByDefault() {
        assertEquals(0, context.getBeanNamesForType(DemoController.class).length);
    }
}
