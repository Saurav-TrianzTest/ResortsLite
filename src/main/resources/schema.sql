-- PostgreSQL Database Initialization Script for ResortsLite
-- This script creates the necessary schema and tables for the application

-- Create schema if not exists (default is 'public')
CREATE SCHEMA IF NOT EXISTS public;

-- Create bookings table
CREATE TABLE IF NOT EXISTS public.bookings (
    id VARCHAR(50) PRIMARY KEY,
    guest VARCHAR(255) NOT NULL,
    room VARCHAR(100) NOT NULL,
    checkin DATE NOT NULL,
    checkout DATE NOT NULL,
    confirmation_code VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for better query performance
CREATE INDEX IF NOT EXISTS idx_bookings_guest ON public.bookings(guest);
CREATE INDEX IF NOT EXISTS idx_bookings_checkin ON public.bookings(checkin);
CREATE INDEX IF NOT EXISTS idx_bookings_checkout ON public.bookings(checkout);

-- Insert sample data for testing
INSERT INTO public.bookings (id, guest, room, checkin, checkout, confirmation_code, created_at)
VALUES 
    ('BK-SAMPLE01', 'John Doe', 'DELUXE', '2024-03-15', '2024-03-20', 'CONF123456', CURRENT_TIMESTAMP),
    ('BK-SAMPLE02', 'Jane Smith', 'SUITE', '2024-03-18', '2024-03-22', 'CONF789012', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- Grant permissions (adjust as needed for your environment)
-- GRANT ALL PRIVILEGES ON SCHEMA public TO postgres;
-- GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO postgres;
