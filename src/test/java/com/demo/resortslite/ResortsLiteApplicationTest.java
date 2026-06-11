package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResortsLiteApplication entry point.
 * Uses @SpringBootTest to verify the application context loads successfully.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Context load test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void contextLoads() {
        // Verifies that the Spring application context starts without errors.
        // This is the primary smoke test for the application entry point.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // main() method test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void main_doesNotThrowException() {
        // Arrange & Act & Assert
        // Calling main() with an empty args array should not throw any exception.
        // Spring Boot will attempt to start; we rely on the test context already
        // being up, so this is a lightweight invocation check.
        assertDoesNotThrow(() ->
                ResortsLiteApplication.main(new String[]{}),
                "main() should not throw an exception when called with empty args"
        );
    }
}
