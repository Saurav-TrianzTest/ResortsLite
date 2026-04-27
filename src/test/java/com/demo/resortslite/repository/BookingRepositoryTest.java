package com.demo.resortslite.repository;

import com.demo.resortslite.entity.Booking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for BookingRepository.
 * Tests all repository methods and database operations using in-memory H2 database.
 */
@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "spring.datasource.driver-class-name=org.h2.Driver"
})
class BookingRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BookingRepository bookingRepository;

    private Booking testBooking1;
    private Booking testBooking2;

    @BeforeEach
    void setUp() {
        // Clear any existing data
        bookingRepository.deleteAll();
        entityManager.flush();
        entityManager.clear();

        // Create test bookings
        testBooking1 = new Booking();
        testBooking1.setId("BK-TEST-001");
        testBooking1.setGuest("John Doe");
        testBooking1.setRoom("DELUXE");
        testBooking1.setCheckin(LocalDate.of(2024, 6, 1));
        testBooking1.setCheckout(LocalDate.of(2024, 6, 5));
        testBooking1.setConfirmationCode("CONF-001");

        testBooking2 = new Booking();
        testBooking2.setId("BK-TEST-002");
        testBooking2.setGuest("Jane Smith");
        testBooking2.setRoom("SUITE");
        testBooking2.setCheckin(LocalDate.of(2024, 7, 10));
        testBooking2.setCheckout(LocalDate.of(2024, 7, 15));
        testBooking2.setConfirmationCode("CONF-002");
    }

    @Test
    void testSave_withValidBooking_savesSuccessfully() {
        // Act
        Booking saved = bookingRepository.save(testBooking1);
        entityManager.flush();

        // Assert
        assertNotNull(saved);
        assertEquals(testBooking1.getId(), saved.getId());
        assertEquals(testBooking1.getGuest(), saved.getGuest());
        assertEquals(testBooking1.getRoom(), saved.getRoom());
    }

    @Test
    void testFindById_withExistingId_returnsBooking() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        Optional<Booking> found = bookingRepository.findById("BK-TEST-001");

        // Assert
        assertTrue(found.isPresent());
        assertEquals("BK-TEST-001", found.get().getId());
        assertEquals("John Doe", found.get().getGuest());
    }

    @Test
    void testFindById_withNonExistentId_returnsEmpty() {
        // Act
        Optional<Booking> found = bookingRepository.findById("BK-NONEXISTENT");

        // Assert
        assertFalse(found.isPresent());
    }

    @Test
    void testFindById_withNullId_returnsEmpty() {
        // Act
        Optional<Booking> found = bookingRepository.findById(null);

        // Assert
        assertFalse(found.isPresent());
    }

    @Test
    void testFindByGuestName_withExactMatch_returnsBooking() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        Optional<Booking> found = bookingRepository.findByGuestName("John Doe");

        // Assert
        assertTrue(found.isPresent());
        assertEquals("John Doe", found.get().getGuest());
        assertEquals("BK-TEST-001", found.get().getId());
    }

    @Test
    void testFindByGuestName_withDifferentCase_returnsBooking() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        Optional<Booking> found = bookingRepository.findByGuestName("JOHN DOE");

        // Assert
        assertTrue(found.isPresent());
        assertEquals("John Doe", found.get().getGuest());
    }

    @Test
    void testFindByGuestName_withLowerCase_returnsBooking() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        Optional<Booking> found = bookingRepository.findByGuestName("john doe");

        // Assert
        assertTrue(found.isPresent());
        assertEquals("John Doe", found.get().getGuest());
    }

    @Test
    void testFindByGuestName_withMixedCase_returnsBooking() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        Optional<Booking> found = bookingRepository.findByGuestName("JoHn DoE");

        // Assert
        assertTrue(found.isPresent());
        assertEquals("John Doe", found.get().getGuest());
    }

    @Test
    void testFindByGuestName_withNonExistentGuest_returnsEmpty() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        Optional<Booking> found = bookingRepository.findByGuestName("Nonexistent Guest");

        // Assert
        assertFalse(found.isPresent());
    }

    @Test
    void testFindByGuestName_withNullGuest_returnsEmpty() {
        // Act
        Optional<Booking> found = bookingRepository.findByGuestName(null);

        // Assert
        assertFalse(found.isPresent());
    }

    @Test
    void testFindByGuestName_withEmptyString_returnsEmpty() {
        // Act
        Optional<Booking> found = bookingRepository.findByGuestName("");

        // Assert
        assertFalse(found.isPresent());
    }

    @Test
    void testFindAll_withMultipleBookings_returnsAllBookings() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.persist(testBooking2);
        entityManager.flush();

        // Act
        List<Booking> bookings = bookingRepository.findAll();

        // Assert
        assertEquals(2, bookings.size());
    }

    @Test
    void testFindAll_withNoBookings_returnsEmptyList() {
        // Act
        List<Booking> bookings = bookingRepository.findAll();

        // Assert
        assertTrue(bookings.isEmpty());
    }

    @Test
    void testDeleteById_withExistingId_deletesBooking() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        bookingRepository.deleteById("BK-TEST-001");
        entityManager.flush();

        // Assert
        Optional<Booking> found = bookingRepository.findById("BK-TEST-001");
        assertFalse(found.isPresent());
    }

    @Test
    void testDelete_withExistingBooking_deletesBooking() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        bookingRepository.delete(testBooking1);
        entityManager.flush();

        // Assert
        Optional<Booking> found = bookingRepository.findById("BK-TEST-001");
        assertFalse(found.isPresent());
    }

    @Test
    void testCount_withMultipleBookings_returnsCorrectCount() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.persist(testBooking2);
        entityManager.flush();

        // Act
        long count = bookingRepository.count();

        // Assert
        assertEquals(2, count);
    }

    @Test
    void testCount_withNoBookings_returnsZero() {
        // Act
        long count = bookingRepository.count();

        // Assert
        assertEquals(0, count);
    }

    @Test
    void testExistsById_withExistingId_returnsTrue() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        boolean exists = bookingRepository.existsById("BK-TEST-001");

        // Assert
        assertTrue(exists);
    }

    @Test
    void testExistsById_withNonExistentId_returnsFalse() {
        // Act
        boolean exists = bookingRepository.existsById("BK-NONEXISTENT");

        // Assert
        assertFalse(exists);
    }

    @Test
    void testSave_updatesExistingBooking() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();
        entityManager.clear();

        // Act
        Booking toUpdate = bookingRepository.findById("BK-TEST-001").orElseThrow();
        toUpdate.setGuest("Updated Guest Name");
        Booking updated = bookingRepository.save(toUpdate);
        entityManager.flush();

        // Assert
        assertEquals("Updated Guest Name", updated.getGuest());
        assertEquals("BK-TEST-001", updated.getId());
    }

    @Test
    void testSave_withNewBooking_generatesCreatedAt() {
        // Act
        Booking saved = bookingRepository.save(testBooking1);
        entityManager.flush();

        // Assert
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void testFindById_afterSave_returnsCompleteBooking() {
        // Arrange
        bookingRepository.save(testBooking1);
        entityManager.flush();
        entityManager.clear();

        // Act
        Optional<Booking> found = bookingRepository.findById("BK-TEST-001");

        // Assert
        assertTrue(found.isPresent());
        Booking booking = found.get();
        assertEquals("BK-TEST-001", booking.getId());
        assertEquals("John Doe", booking.getGuest());
        assertEquals("DELUXE", booking.getRoom());
        assertEquals(LocalDate.of(2024, 6, 1), booking.getCheckin());
        assertEquals(LocalDate.of(2024, 6, 5), booking.getCheckout());
        assertEquals("CONF-001", booking.getConfirmationCode());
        assertNotNull(booking.getCreatedAt());
    }

    @Test
    void testDeleteAll_removesAllBookings() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.persist(testBooking2);
        entityManager.flush();

        // Act
        bookingRepository.deleteAll();
        entityManager.flush();

        // Assert
        assertEquals(0, bookingRepository.count());
    }

    @Test
    void testSaveAll_withMultipleBookings_savesAllBookings() {
        // Act
        List<Booking> saved = bookingRepository.saveAll(List.of(testBooking1, testBooking2));
        entityManager.flush();

        // Assert
        assertEquals(2, saved.size());
        assertEquals(2, bookingRepository.count());
    }

    @Test
    void testFindByGuestName_withPartialMatch_returnsEmpty() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        Optional<Booking> found = bookingRepository.findByGuestName("John");

        // Assert
        assertFalse(found.isPresent());
    }

    @Test
    void testFindByGuestName_withExtraSpaces_returnsEmpty() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();

        // Act
        Optional<Booking> found = bookingRepository.findByGuestName("John  Doe");

        // Assert
        assertFalse(found.isPresent());
    }

    @Test
    void testRepositoryInterface_extendsJpaRepository() {
        // Assert
        assertTrue(org.springframework.data.jpa.repository.JpaRepository.class
            .isAssignableFrom(BookingRepository.class));
    }

    @Test
    void testRepositoryInterface_hasRepositoryAnnotation() {
        // Assert
        assertTrue(BookingRepository.class.isAnnotationPresent(Repository.class));
    }

    @Test
    void testSave_withDuplicateId_updatesExisting() {
        // Arrange
        entityManager.persist(testBooking1);
        entityManager.flush();
        entityManager.clear();

        // Act
        Booking duplicate = new Booking();
        duplicate.setId("BK-TEST-001");
        duplicate.setGuest("Different Guest");
        duplicate.setRoom("VILLA");
        duplicate.setCheckin(LocalDate.of(2024, 8, 1));
        duplicate.setCheckout(LocalDate.of(2024, 8, 5));
        
        bookingRepository.save(duplicate);
        entityManager.flush();

        // Assert
        assertEquals(1, bookingRepository.count());
        Optional<Booking> found = bookingRepository.findById("BK-TEST-001");
        assertTrue(found.isPresent());
        assertEquals("Different Guest", found.get().getGuest());
    }
}
