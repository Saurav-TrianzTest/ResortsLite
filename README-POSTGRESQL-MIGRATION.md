# ResortsLite - PostgreSQL Migration Guide

## Overview
This application has been migrated from H2 in-memory database to PostgreSQL 16 for production-ready deployment.

## Migration Summary

### Database Changes
- **From**: H2 in-memory database
- **To**: PostgreSQL 16
- **Driver**: PostgreSQL JDBC Driver 42.7.1

### Key Changes Made

#### 1. Package Dependencies (pom.xml)
- ✅ Removed H2 database dependency
- ✅ Added PostgreSQL JDBC driver (version 42.7.1)
- ✅ Maintained Spring Boot 3.2.0 compatibility

#### 2. Connection Configuration (application.properties)
- ✅ Updated JDBC URL from H2 to PostgreSQL format
- ✅ Configured HikariCP connection pool for PostgreSQL
- ✅ Added PostgreSQL-specific Hibernate properties
- ✅ Externalized all configuration to environment variables

#### 3. SQL Query Updates (BookingService.java)
- ✅ Fixed SQL injection vulnerabilities using parameterized queries
- ✅ Replaced string concatenation with PreparedStatement parameters
- ✅ Updated all raw SQL queries for PostgreSQL compatibility
- ✅ Added comprehensive JavaDoc documentation

#### 4. Code Quality Improvements
- ✅ Reduced cyclomatic complexity by extracting methods
- ✅ Removed duplicated validation logic
- ✅ Externalized hardcoded credentials and paths
- ✅ Added proper error handling and logging

#### 5. Cloud Compatibility
- ✅ Externalized all infrastructure endpoints to environment variables
- ✅ Replaced HTTP with HTTPS for external service calls
- ✅ Removed hardcoded file paths for container compatibility
- ✅ Added Docker and Docker Compose support

## Prerequisites

### Local Development
- Java 17 or higher
- Maven 3.9+
- PostgreSQL 16 (or use Docker Compose)

### Docker Deployment
- Docker 20.10+
- Docker Compose 2.0+

## Quick Start

### Option 1: Using Docker Compose (Recommended)

1. **Start PostgreSQL and Application**:
   ```bash
   docker-compose up -d
   ```

2. **Check Application Health**:
   ```bash
   curl http://localhost:8080/actuator/health
   ```

3. **View Logs**:
   ```bash
   docker-compose logs -f app
   ```

4. **Stop Services**:
   ```bash
   docker-compose down
   ```

### Option 2: Local PostgreSQL Installation

1. **Install PostgreSQL 16**:
   ```bash
   # Ubuntu/Debian
   sudo apt-get install postgresql-16
   
   # macOS
   brew install postgresql@16
   ```

2. **Create Database**:
   ```bash
   psql -U postgres
   CREATE DATABASE resortdb;
   \q
   ```

3. **Initialize Schema**:
   ```bash
   psql -U postgres -d resortdb -f src/main/resources/schema.sql
   ```

4. **Set Environment Variables**:
   ```bash
   export DB_HOST=localhost
   export DB_PORT=5432
   export DB_NAME=resortdb
   export DB_USERNAME=postgres
   export DB_PASSWORD=your_password
   export SPRING_PROFILES_ACTIVE=postgresql
   ```

5. **Build and Run Application**:
   ```bash
   mvn clean package
   java -jar target/resortsLite-1.0.0.jar
   ```

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_HOST` | PostgreSQL host | localhost |
| `DB_PORT` | PostgreSQL port | 5432 |
| `DB_NAME` | Database name | resortdb |
| `DB_USERNAME` | Database user | postgres |
| `DB_PASSWORD` | Database password | postgres |
| `DB_SSL` | Enable SSL connection | false |
| `DB_POOL_MAX` | Max connection pool size | 20 |
| `DB_POOL_MIN` | Min idle connections | 5 |
| `DDL_AUTO` | Hibernate DDL mode | validate |
| `INIT_MODE` | Schema initialization | never |
| `SPRING_PROFILES_ACTIVE` | Active profile | postgresql |

### Application Profiles

- **default**: Uses settings from application.properties
- **postgresql**: Uses PostgreSQL-specific settings from application-postgresql.properties

## Database Schema

### Tables

#### bookings
| Column | Type | Constraints |
|--------|------|-------------|
| id | VARCHAR(50) | PRIMARY KEY |
| guest | VARCHAR(255) | NOT NULL |
| room | VARCHAR(50) | NOT NULL |
| checkin | DATE | NOT NULL |
| checkout | DATE | NOT NULL |
| created_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP |
| updated_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP |

### Indexes
- `idx_bookings_guest`: Index on guest name
- `idx_bookings_checkin`: Index on check-in date
- `idx_bookings_room`: Index on room type

## API Endpoints

### Create Booking
```bash
POST /api/bookings/create
Content-Type: application/x-www-form-urlencoded

guestName=John Doe&roomType=DELUXE&checkIn=2024-03-15&checkOut=2024-03-18
```

### Get Booking Status
```bash
GET /api/bookings/status/{bookingId}
```

### Check Room Availability
```bash
GET /api/bookings/availability?roomType=SUITE
```

### Download Report
```bash
GET /api/bookings/report/download?month=March
```

## Testing

### Test Database Connection
```bash
psql -h localhost -U postgres -d resortdb -c "SELECT version();"
```

### Test Application Endpoints
```bash
# Health check
curl http://localhost:8080/actuator/health

# Create booking
curl -X POST "http://localhost:8080/api/bookings/create" \
  -d "guestName=Test User" \
  -d "roomType=DELUXE" \
  -d "checkIn=2024-03-20" \
  -d "checkOut=2024-03-22"
```

## Security Improvements

### Fixed Vulnerabilities
1. **SQL Injection**: All queries now use parameterized statements
2. **Hardcoded Credentials**: Externalized to environment variables
3. **HTTP Usage**: Replaced with HTTPS for external services
4. **Weak Hashing**: Upgraded from MD5 to SHA-256

### Production Recommendations
1. Use AWS Secrets Manager or HashiCorp Vault for credentials
2. Enable SSL/TLS for PostgreSQL connections
3. Implement JWT-based authentication instead of HTTP sessions
4. Use Redis or ElastiCache for distributed caching
5. Store reports in S3 or Azure Blob Storage

## Troubleshooting

### Connection Issues
```bash
# Check PostgreSQL is running
docker-compose ps

# Check PostgreSQL logs
docker-compose logs postgres

# Test connection manually
psql -h localhost -U postgres -d resortdb
```

### Application Issues
```bash
# Check application logs
docker-compose logs app

# Restart application
docker-compose restart app
```

## Migration Checklist

- [x] Updated pom.xml with PostgreSQL driver
- [x] Removed H2 database dependency
- [x] Updated connection strings to PostgreSQL format
- [x] Fixed SQL injection vulnerabilities
- [x] Converted all SQL queries to parameterized statements
- [x] Created PostgreSQL schema initialization script
- [x] Externalized all hardcoded configurations
- [x] Added Docker and Docker Compose support
- [x] Updated documentation with migration details
- [x] Added comprehensive JavaDoc comments
- [x] Improved code quality and reduced complexity

## Next Steps

1. **Performance Tuning**: Adjust connection pool settings based on load testing
2. **Monitoring**: Integrate with Prometheus and Grafana for metrics
3. **Backup Strategy**: Implement automated PostgreSQL backups
4. **High Availability**: Configure PostgreSQL replication for production
5. **Security Hardening**: Enable SSL, implement row-level security

## Support

For issues or questions, please refer to:
- PostgreSQL Documentation: https://www.postgresql.org/docs/16/
- Spring Boot Documentation: https://docs.spring.io/spring-boot/docs/3.2.0/reference/html/
- HikariCP Documentation: https://github.com/brettwooldridge/HikariCP

## License

This project is licensed under the MIT License.
