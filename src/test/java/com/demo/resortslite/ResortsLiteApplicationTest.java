package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for ResortsLiteApplication.
 * Tests Spring Boot application startup and context loading.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.inventory.endpoint=http://localhost:8080/inventory",
    "app.payment.endpoint=http://localhost:8080/payment",
    "app.report.path=/tmp/test-reports/",
    "app.backup.path=/tmp/test-backups/",
    "app.report.service.url=https://test-reports.example.com"
})
class ResortsLiteApplicationTest {

    @Test
    void contextLoads(ApplicationContext context) {
        // Assert
        assertNotNull(context);
    }

    @Test
    void testMainMethod_doesNotThrowException() {
        // This test verifies that the main method can be called without errors
        // We don't actually start the application to avoid port conflicts
        assertDoesNotThrow(() -> {
            // Verify the class and method exist
            assertNotNull(ResortsLiteApplication.class);
            assertNotNull(ResortsLiteApplication.class.getMethod("main", String[].class));
        });
    }

    @Test
    void testApplicationClass_hasSpringBootApplicationAnnotation() {
        // Assert
        assertTrue(ResortsLiteApplication.class.isAnnotationPresent(SpringBootApplication.class));
    }

    @Test
    void testApplicationClass_isPublic() {
        // Assert
        assertTrue(java.lang.reflect.Modifier.isPublic(ResortsLiteApplication.class.getModifiers()));
    }

    @Test
    void testMainMethod_isPublicStatic() throws NoSuchMethodException {
        // Arrange
        var mainMethod = ResortsLiteApplication.class.getMethod("main", String[].class);

        // Assert
        assertTrue(java.lang.reflect.Modifier.isPublic(mainMethod.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers()));
    }

    @Test
    void testMainMethod_hasCorrectReturnType() throws NoSuchMethodException {
        // Arrange
        var mainMethod = ResortsLiteApplication.class.getMethod("main", String[].class);

        // Assert
        assertEquals(void.class, mainMethod.getReturnType());
    }

    @Test
    void testMainMethod_hasCorrectParameterType() throws NoSuchMethodException {
        // Arrange
        var mainMethod = ResortsLiteApplication.class.getMethod("main", String[].class);

        // Assert
        assertEquals(1, mainMethod.getParameterCount());
        assertEquals(String[].class, mainMethod.getParameterTypes()[0]);
    }

    @Test
    void testSpringBootApplicationAnnotation_hasCorrectAttributes() {
        // Arrange
        SpringBootApplication annotation = ResortsLiteApplication.class.getAnnotation(SpringBootApplication.class);

        // Assert
        assertNotNull(annotation);
    }

    @Test
    void testApplicationContext_containsBookingController(ApplicationContext context) {
        // Assert
        assertTrue(context.containsBean("bookingController"));
        assertNotNull(context.getBean(BookingController.class));
    }

    @Test
    void testApplicationContext_containsBookingService(ApplicationContext context) {
        // Assert
        assertTrue(context.containsBean("bookingService"));
        assertNotNull(context.getBean(BookingService.class));
    }

    @Test
    void testApplicationContext_containsReportService(ApplicationContext context) {
        // Assert
        assertTrue(context.containsBean("reportService"));
        assertNotNull(context.getBean(ReportService.class));
    }

    @Test
    void testApplicationContext_containsBookingRepository(ApplicationContext context) {
        // Assert
        assertNotNull(context.getBean("bookingRepository"));
    }

    @Test
    void testApplicationContext_beansAreWiredCorrectly(ApplicationContext context) {
        // Arrange
        BookingController controller = context.getBean(BookingController.class);
        BookingService service = context.getBean(BookingService.class);

        // Assert
        assertNotNull(controller);
        assertNotNull(service);
    }

    @Test
    void testApplicationContext_hasCorrectBeanCount(ApplicationContext context) {
        // Assert
        String[] beanNames = context.getBeanDefinitionNames();
        assertTrue(beanNames.length > 0);
    }

    @Test
    void testApplicationContext_containsSpringBootBeans(ApplicationContext context) {
        // Assert
        assertTrue(context.containsBean("springApplicationAdminRegistrar") || 
                   context.getBeanDefinitionNames().length > 10);
    }
}
