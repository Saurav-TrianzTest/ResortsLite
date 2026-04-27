-- PostgreSQL Database Schema for ResortsLite Application
-- This script creates the necessary tables for the booking system

-- Drop existing tables if they exist (for clean setup)
DROP TABLE IF EXISTS bookings CASCADE;

-- Create bookings table with PostgreSQL-specific features
CREATE TABLE bookings (
    id VARCHAR(50) PRIMARY KEY,
    guest VARCHAR(255) NOT NULL,
    room VARCHAR(50) NOT NULL,
    checkin DATE NOT NULL,
    checkout DATE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create index on guest name for faster lookups
CREATE INDEX idx_bookings_guest ON bookings(guest);

-- Create index on check-in date for date range queries
CREATE INDEX idx_bookings_checkin ON bookings(checkin);

-- Create index on room type for availability queries
CREATE INDEX idx_bookings_room ON bookings(room);

-- Insert sample data for testing
INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES
    ('BK-SAMPLE01', 'John Doe', 'DELUXE', '2024-03-15', '2024-03-18'),
    ('BK-SAMPLE02', 'Jane Smith', 'SUITE', '2024-03-20', '2024-03-25'),
    ('BK-SAMPLE03', 'Bob Johnson', 'STANDARD', '2024-03-22', '2024-03-24');

-- Create a function to automatically update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to automatically update updated_at on row updates
CREATE TRIGGER update_bookings_updated_at
    BEFORE UPDATE ON bookings
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Grant necessary permissions (adjust as needed for your environment)
-- GRANT SELECT, INSERT, UPDATE, DELETE ON bookings TO your_app_user;
