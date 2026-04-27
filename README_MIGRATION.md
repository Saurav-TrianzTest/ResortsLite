# ResortsLite - PostgreSQL Migration

## Overview
This application has been migrated from H2 in-memory database to PostgreSQL 16 for production readiness and cloud deployment compatibility.

## Migration Summary

### Database Changes
- **From**: H2 in-memory database
- **To**: PostgreSQL 16
- **ORM**: Spring Data JPA with Hibernate

### Key Improvements

#### 1. Database Migration
- ✅ Replaced H2 dependency with PostgreSQL driver
- ✅ Added Spring Data JPA for ORM support
- ✅ Created JPA Entity classes with PostgreSQL-compatible annotations
- ✅ Implemented JPA Repository for type-safe database operations
- ✅ Configured Hibernate dialect for PostgreSQL

#### 2. Security Fixes
- ✅ **SQL Injection Prevention**: Replaced raw SQL string concatenation with JPA parameterized queries
- ✅ **Credential Externalization**: Moved hardcoded database credentials to environment variables
- ✅ **Secure Hashing**: Already using SHA-256 (previously fixed from MD5)

#### 3. Cloud Compatibility
- ✅ **Configuration Externalization**: All hardcoded values moved to environment variables
- ✅ **Removed HTTP Session Storage**: Eliminated session-based state for horizontal scaling
- ✅ **Removed In-Memory Cache**: Prepared for distributed caching (Redis/ElastiCache)
- ✅ **Dynamic Port Binding**: Server port configurable via environment variable
- ✅ **Container-Ready**: Removed hardcoded file paths, added Docker support

#### 4. Code Quality
- ✅ **Reduced Cyclomatic Complexity**: Refactored `calculateRoomPrice()` method
- ✅ **Eliminated Code Duplication**: Extracted shared validation logic
- ✅ **Added Documentation**: Comprehensive JavaDoc for all public methods
- ✅ **HTTPS Recommendations**: Added comments for production security

## Configuration

### Environment Variables

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/resortdb` | Yes |
| `DB_USERNAME` | Database username | `postgres` | Yes |
| `DB_PASSWORD` | Database password | (empty) | Yes |
| `SERVER_PORT` | Application port | `8080` | No |
| `PAYMENT_ENDPOINT` | Payment service URL | `http://payment-svc.internal:9090/charge` | No |
| `INVENTORY_ENDPOINT` | Inventory service URL | `http://inventory-svc.internal:8081/rooms` | No |
| `NOTIFICATION_ENDPOINT` | Notification service URL | `http://notify.internal:7070/send` | No |

### Database Schema

The application uses the following PostgreSQL schema:

```sql
CREATE TABLE public.bookings (
    id VARCHAR(50) PRIMARY KEY,
    guest VARCHAR(255) NOT NULL,
    room VARCHAR(100) NOT NULL,
    checkin DATE NOT NULL,
    checkout DATE NOT NULL,
    confirmation_code VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

## Running the Application

### Option 1: Docker Compose (Recommended)

```bash
# Start PostgreSQL and application
docker-compose up -d

# View logs
docker-compose logs -f app

# Stop services
docker-compose down
```

### Option 2: Local PostgreSQL

1. **Install PostgreSQL 16**
   ```bash
   # Ubuntu/Debian
   sudo apt-get install postgresql-16
   
   # macOS
   brew install postgresql@16
   ```

2. **Create Database**
   ```bash
   psql -U postgres
   CREATE DATABASE resortdb;
   \q
   ```

3. **Initialize Schema**
   ```bash
   psql -U postgres -d resortdb -f src/main/resources/schema.sql
   ```

4. **Set Environment Variables**
   ```bash
   export DB_URL=jdbc:postgresql://localhost:5432/resortdb
   export DB_USERNAME=postgres
   export DB_PASSWORD=your_password
   ```

5. **Run Application**
   ```bash
   mvn spring-boot:run
   ```

### Option 3: AWS RDS PostgreSQL

1. **Create RDS PostgreSQL Instance**
   - Engine: PostgreSQL 16
   - Instance class: db.t3.micro (or higher)
   - Storage: 20 GB (or more)
   - Enable automatic backups

2. **Configure Security Group**
   - Allow inbound traffic on port 5432 from your application

3. **Set Environment Variables**
   ```bash
   export DB_URL=jdbc:postgresql://your-rds-endpoint:5432/resortdb
   export DB_USERNAME=postgres
   export DB_PASSWORD=your_secure_password
   ```

4. **Deploy Application**
   - Use AWS Elastic Beanstalk, ECS, or EKS
   - Configure environment variables in deployment configuration

## API Endpoints

### Create Booking
```bash
POST /api/bookings/create
Parameters:
  - guestName: string
  - roomType: string (STANDARD, DELUXE, SUITE, VILLA)
  - checkIn: string (yyyy-MM-dd)
  - checkOut: string (yyyy-MM-dd)
```

### Get Booking Status
```bash
GET /api/bookings/status/{bookingId}
```

### Check Availability
```bash
GET /api/bookings/availability?roomType=DELUXE
```

### Download Report
```bash
GET /api/bookings/report/download?month=March
```

## Testing

### Test Database Connection
```bash
curl http://localhost:8080/api/bookings/availability?roomType=DELUXE
```

### Create Test Booking
```bash
curl -X POST "http://localhost:8080/api/bookings/create" \
  -d "guestName=John Doe" \
  -d "roomType=DELUXE" \
  -d "checkIn=2024-03-15" \
  -d "checkOut=2024-03-20"
```

## Production Recommendations

### Security
1. **Use AWS Secrets Manager** for database credentials
2. **Enable SSL/TLS** for PostgreSQL connections
3. **Use HTTPS** for all service-to-service communication
4. **Implement API authentication** (OAuth2, JWT)

### Scalability
1. **Use Amazon RDS** with Multi-AZ deployment
2. **Implement connection pooling** (HikariCP already configured)
3. **Add distributed caching** (Redis/ElastiCache)
4. **Use Application Load Balancer** for horizontal scaling

### Monitoring
1. **Enable CloudWatch** for application logs
2. **Configure RDS Performance Insights**
3. **Set up health checks** and alarms
4. **Implement distributed tracing** (AWS X-Ray)

### High Availability
1. **Multi-AZ RDS deployment**
2. **Auto-scaling groups** for application instances
3. **Read replicas** for read-heavy workloads
4. **Automated backups** and point-in-time recovery

## Migration Checklist

- [x] Update Maven dependencies (PostgreSQL driver, Spring Data JPA)
- [x] Create JPA Entity classes
- [x] Create JPA Repository interfaces
- [x] Update Service layer to use JPA
- [x] Configure PostgreSQL connection in application.properties
- [x] Create database schema initialization script
- [x] Fix SQL injection vulnerabilities
- [x] Externalize all hardcoded configuration
- [x] Remove HTTP session dependencies
- [x] Remove in-memory cache
- [x] Add Docker support
- [x] Document migration process
- [ ] Test all API endpoints with PostgreSQL
- [ ] Perform load testing
- [ ] Set up monitoring and alerting
- [ ] Deploy to production environment

## Troubleshooting

### Connection Issues
```bash
# Test PostgreSQL connection
psql -h localhost -U postgres -d resortdb

# Check if PostgreSQL is running
sudo systemctl status postgresql
```

### Application Logs
```bash
# Docker Compose
docker-compose logs -f app

# Local
tail -f logs/spring.log
```

### Database Issues
```bash
# Check table structure
psql -U postgres -d resortdb -c "\d bookings"

# View data
psql -U postgres -d resortdb -c "SELECT * FROM bookings;"
```

## Support

For issues or questions, please contact the development team or refer to:
- [Spring Data JPA Documentation](https://spring.io/projects/spring-data-jpa)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/16/)
- [AWS RDS PostgreSQL Guide](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_PostgreSQL.html)
