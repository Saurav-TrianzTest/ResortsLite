package com.demo.resortslite.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for Booking entity.
 * Tests all constructors, getters, setters, and JPA lifecycle callbacks.
 */
class BookingTest {

    private Booking booking;

    @BeforeEach
    void setUp() {
        booking = new Booking();
    }

    @Test
    void testDefaultConstructor_createsEmptyBooking() {
        // Assert
        assertNotNull(booking);
        assertNull(booking.getId());
        assertNull(booking.getGuest());
        assertNull(booking.getRoom());
        assertNull(booking.getCheckin());
        assertNull(booking.getCheckout());
        assertNull(booking.getConfirmationCode());
        assertNull(booking.getCreatedAt());
    }

    @Test
    void testParameterizedConstructor_setsAllFields() {
        // Arrange
        String id = "BK-12345";
        String guest = "John Doe";
        String room = "DELUXE";
        LocalDate checkin = LocalDate.of(2024, 6, 1);
        LocalDate checkout = LocalDate.of(2024, 6, 5);

        // Act
        Booking booking = new Booking(id, guest, room, checkin, checkout);

        // Assert
        assertEquals(id, booking.getId());
        assertEquals(guest, booking.getGuest());
        assertEquals(room, booking.getRoom());
        assertEquals(checkin, booking.getCheckin());
        assertEquals(checkout, booking.getCheckout());
    }

    @Test
    void testSetId_andGetId() {
        // Arrange
        String id = "BK-TEST-001";

        // Act
        booking.setId(id);

        // Assert
        assertEquals(id, booking.getId());
    }

    @Test
    void testSetGuest_andGetGuest() {
        // Arrange
        String guest = "Jane Smith";

        // Act
        booking.setGuest(guest);

        // Assert
        assertEquals(guest, booking.getGuest());
    }

    @Test
    void testSetRoom_andGetRoom() {
        // Arrange
        String room = "SUITE";

        // Act
        booking.setRoom(room);

        // Assert
        assertEquals(room, booking.getRoom());
    }

    @Test
    void testSetCheckin_andGetCheckin() {
        // Arrange
        LocalDate checkin = LocalDate.of(2024, 7, 15);

        // Act
        booking.setCheckin(checkin);

        // Assert
        assertEquals(checkin, booking.getCheckin());
    }

    @Test
    void testSetCheckout_andGetCheckout() {
        // Arrange
        LocalDate checkout = LocalDate.of(2024, 7, 20);

        // Act
        booking.setCheckout(checkout);

        // Assert
        assertEquals(checkout, booking.getCheckout());
    }

    @Test
    void testSetConfirmationCode_andGetConfirmationCode() {
        // Arrange
        String confirmationCode = "CONF-ABC123";

        // Act
        booking.setConfirmationCode(confirmationCode);

        // Assert
        assertEquals(confirmationCode, booking.getConfirmationCode());
    }

    @Test
    void testSetCreatedAt_andGetCreatedAt() {
        // Arrange
        LocalDateTime createdAt = LocalDateTime.of(2024, 5, 1, 10, 30, 0);

        // Act
        booking.setCreatedAt(createdAt);

        // Assert
        assertEquals(createdAt, booking.getCreatedAt());
    }

    @Test
    void testOnCreate_setsCreatedAtWhenNull() throws Exception {
        // Arrange
        Booking booking = new Booking();
        assertNull(booking.getCreatedAt());

        // Act - Simulate @PrePersist callback
        var method = Booking.class.getDeclaredMethod("onCreate");
        method.setAccessible(true);
        method.invoke(booking);

        // Assert
        assertNotNull(booking.getCreatedAt());
        assertTrue(booking.getCreatedAt().isBefore(LocalDateTime.now().plusSeconds(1)));
        assertTrue(booking.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(5)));
    }

    @Test
    void testOnCreate_doesNotOverrideExistingCreatedAt() throws Exception {
        // Arrange
        LocalDateTime existingCreatedAt = LocalDateTime.of(2024, 1, 1, 12, 0, 0);
        booking.setCreatedAt(existingCreatedAt);

        // Act - Simulate @PrePersist callback
        var method = Booking.class.getDeclaredMethod("onCreate");
        method.setAccessible(true);
        method.invoke(booking);

        // Assert
        assertEquals(existingCreatedAt, booking.getCreatedAt());
    }

    @Test
    void testSetId_withNull_acceptsNull() {
        // Act
        booking.setId(null);

        // Assert
        assertNull(booking.getId());
    }

    @Test
    void testSetGuest_withNull_acceptsNull() {
        // Act
        booking.setGuest(null);

        // Assert
        assertNull(booking.getGuest());
    }

    @Test
    void testSetRoom_withNull_acceptsNull() {
        // Act
        booking.setRoom(null);

        // Assert
        assertNull(booking.getRoom());
    }

    @Test
    void testSetCheckin_withNull_acceptsNull() {
        // Act
        booking.setCheckin(null);

        // Assert
        assertNull(booking.getCheckin());
    }

    @Test
    void testSetCheckout_withNull_acceptsNull() {
        // Act
        booking.setCheckout(null);

        // Assert
        assertNull(booking.getCheckout());
    }

    @Test
    void testSetConfirmationCode_withNull_acceptsNull() {
        // Act
        booking.setConfirmationCode(null);

        // Assert
        assertNull(booking.getConfirmationCode());
    }

    @Test
    void testSetCreatedAt_withNull_acceptsNull() {
        // Act
        booking.setCreatedAt(null);

        // Assert
        assertNull(booking.getCreatedAt());
    }

    @Test
    void testSetId_withEmptyString_acceptsEmptyString() {
        // Act
        booking.setId("");

        // Assert
        assertEquals("", booking.getId());
    }

    @Test
    void testSetGuest_withEmptyString_acceptsEmptyString() {
        // Act
        booking.setGuest("");

        // Assert
        assertEquals("", booking.getGuest());
    }

    @Test
    void testSetRoom_withEmptyString_acceptsEmptyString() {
        // Act
        booking.setRoom("");

        // Assert
        assertEquals("", booking.getRoom());
    }

    @Test
    void testSetConfirmationCode_withEmptyString_acceptsEmptyString() {
        // Act
        booking.setConfirmationCode("");

        // Assert
        assertEquals("", booking.getConfirmationCode());
    }

    @Test
    void testSetId_withLongString_acceptsLongString() {
        // Arrange
        String longId = "BK-" + "A".repeat(100);

        // Act
        booking.setId(longId);

        // Assert
        assertEquals(longId, booking.getId());
    }

    @Test
    void testSetGuest_withLongString_acceptsLongString() {
        // Arrange
        String longGuest = "Guest Name " + "X".repeat(250);

        // Act
        booking.setGuest(longGuest);

        // Assert
        assertEquals(longGuest, booking.getGuest());
    }

    @Test
    void testSetRoom_withSpecialCharacters_acceptsSpecialCharacters() {
        // Arrange
        String specialRoom = "SUITE-@#$%";

        // Act
        booking.setRoom(specialRoom);

        // Assert
        assertEquals(specialRoom, booking.getRoom());
    }

    @Test
    void testCheckinCheckout_withSameDate_acceptsSameDate() {
        // Arrange
        LocalDate date = LocalDate.of(2024, 8, 1);

        // Act
        booking.setCheckin(date);
        booking.setCheckout(date);

        // Assert
        assertEquals(date, booking.getCheckin());
        assertEquals(date, booking.getCheckout());
    }

    @Test
    void testCheckinCheckout_withCheckoutBeforeCheckin_acceptsAnyOrder() {
        // Arrange
        LocalDate checkin = LocalDate.of(2024, 8, 10);
        LocalDate checkout = LocalDate.of(2024, 8, 5);

        // Act
        booking.setCheckin(checkin);
        booking.setCheckout(checkout);

        // Assert
        assertEquals(checkin, booking.getCheckin());
        assertEquals(checkout, booking.getCheckout());
        assertTrue(booking.getCheckout().isBefore(booking.getCheckin()));
    }

    @Test
    void testParameterizedConstructor_withNullValues_acceptsNullValues() {
        // Act
        Booking booking = new Booking(null, null, null, null, null);

        // Assert
        assertNull(booking.getId());
        assertNull(booking.getGuest());
        assertNull(booking.getRoom());
        assertNull(booking.getCheckin());
        assertNull(booking.getCheckout());
    }

    @Test
    void testMultipleSettersOnSameInstance_maintainsState() {
        // Act
        booking.setId("BK-001");
        booking.setGuest("Alice");
        booking.setRoom("VILLA");
        booking.setCheckin(LocalDate.of(2024, 9, 1));
        booking.setCheckout(LocalDate.of(2024, 9, 10));
        booking.setConfirmationCode("CONF-XYZ");
        booking.setCreatedAt(LocalDateTime.now());

        // Assert
        assertEquals("BK-001", booking.getId());
        assertEquals("Alice", booking.getGuest());
        assertEquals("VILLA", booking.getRoom());
        assertEquals(LocalDate.of(2024, 9, 1), booking.getCheckin());
        assertEquals(LocalDate.of(2024, 9, 10), booking.getCheckout());
        assertEquals("CONF-XYZ", booking.getConfirmationCode());
        assertNotNull(booking.getCreatedAt());
    }

    @Test
    void testEntityAnnotation_classHasEntityAnnotation() {
        // Assert
        assertTrue(Booking.class.isAnnotationPresent(jakarta.persistence.Entity.class));
    }

    @Test
    void testTableAnnotation_classHasTableAnnotation() {
        // Assert
        assertTrue(Booking.class.isAnnotationPresent(jakarta.persistence.Table.class));
    }

    @Test
    void testIdField_hasIdAnnotation() throws NoSuchFieldException {
        // Arrange
        var idField = Booking.class.getDeclaredField("id");

        // Assert
        assertTrue(idField.isAnnotationPresent(jakarta.persistence.Id.class));
    }

    @Test
    void testIdField_hasColumnAnnotation() throws NoSuchFieldException {
        // Arrange
        var idField = Booking.class.getDeclaredField("id");

        // Assert
        assertTrue(idField.isAnnotationPresent(jakarta.persistence.Column.class));
    }

    @Test
    void testOnCreateMethod_isProtected() throws NoSuchMethodException {
        // Arrange
        var method = Booking.class.getDeclaredMethod("onCreate");

        // Assert
        assertTrue(java.lang.reflect.Modifier.isProtected(method.getModifiers()));
    }

    @Test
    void testOnCreateMethod_hasPrePersistAnnotation() throws NoSuchMethodException {
        // Arrange
        var method = Booking.class.getDeclaredMethod("onCreate");

        // Assert
        assertTrue(method.isAnnotationPresent(jakarta.persistence.PrePersist.class));
    }
}
