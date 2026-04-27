package com.demo.resortslite.repository;

import com.demo.resortslite.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA Repository for Booking entity.
 * Provides PostgreSQL-compatible database operations using Spring Data JPA.
 */
@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {

    /**
     * Find booking by ID using parameterized query (SQL injection safe).
     * 
     * @param id the booking ID
     * @return Optional containing the booking if found
     */
    Optional<Booking> findById(String id);

    /**
     * Find booking by guest name using parameterized query.
     * PostgreSQL is case-sensitive by default, use ILIKE for case-insensitive search.
     * 
     * @param guestName the guest name
     * @return Optional containing the booking if found
     */
    @Query("SELECT b FROM Booking b WHERE LOWER(b.guest) = LOWER(:guestName)")
    Optional<Booking> findByGuestName(@Param("guestName") String guestName);
}
