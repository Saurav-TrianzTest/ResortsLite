package com.demo.resortslite.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * Booking entity for PostgreSQL database.
 * Uses JPA annotations for ORM mapping with PostgreSQL-specific configurations.
 */
@Entity
@Table(name = "bookings", schema = "public")
public class Booking {

    @Id
    @Column(name = "id", length = 50)
    private String id;

    @Column(name = "guest", nullable = false, length = 255)
    private String guest;

    @Column(name = "room", nullable = false, length = 100)
    private String room;

    @Column(name = "checkin", nullable = false)
    private LocalDate checkin;

    @Column(name = "checkout", nullable = false)
    private LocalDate checkout;

    @Column(name = "confirmation_code", length = 255)
    private String confirmationCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = java.time.LocalDateTime.now();
        }
    }

    // Constructors
    public Booking() {
    }

    public Booking(String id, String guest, String room, LocalDate checkin, LocalDate checkout) {
        this.id = id;
        this.guest = guest;
        this.room = room;
        this.checkin = checkin;
        this.checkout = checkout;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGuest() {
        return guest;
    }

    public void setGuest(String guest) {
        this.guest = guest;
    }

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    public LocalDate getCheckin() {
        return checkin;
    }

    public void setCheckin(LocalDate checkin) {
        this.checkin = checkin;
    }

    public LocalDate getCheckout() {
        return checkout;
    }

    public void setCheckout(LocalDate checkout) {
        this.checkout = checkout;
    }

    public String getConfirmationCode() {
        return confirmationCode;
    }

    public void setConfirmationCode(String confirmationCode) {
        this.confirmationCode = confirmationCode;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
