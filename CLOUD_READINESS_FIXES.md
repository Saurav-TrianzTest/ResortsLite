# ModResorts Basic - Cloud-Ready Application

## Overview
This application has been transformed to be fully cloud-ready and compatible with AWS cloud environments. All cloud compatibility blockers have been resolved.

## Cloud Readiness Fixes Applied

### 1. File System & Storage (Blockers 1-7)
**Issues Fixed:**
- Hard-coded file paths (`/var/legacy/reports/`, `C:\ResortBackups\`)
- Local file system write operations
- Java.io.File usage for data storage

**Solution:**
- Migrated all file operations to **Amazon S3** using AWS SDK for Java v2
- Reports are now stored in S3 bucket (configurable via `aws.s3.bucket` property)
- Eliminated all local file system dependencies

**Files Modified:**
- `ReportService.java` - Complete rewrite to use S3Client for file operations

### 2. Configuration Management (Blockers 8-11)
**Issues Fixed:**
- Hard-coded database credentials in source code
- Hard-coded environment URLs
- Hard-coded ports

**Solution:**
- Database credentials now retrieved from **AWS Secrets Manager**
- Environment URLs externalized to **AWS Systems Manager Parameter Store**
- All ports configurable via environment variables
- Configuration externalized to `application.properties` with environment variable support

**Files Modified:**
- `BookingService.java` - Integrated AWS Secrets Manager for credentials
- `BookingController.java` - Uses Parameter Store for service URLs
- `ReportService.java` - Port configuration from environment variables
- `application.properties` - All values externalized with environment variable fallbacks

### 3. State Management & Session (Blockers 13-17, 20)
**Issues Fixed:**
- HTTP session state storage (prevents horizontal scaling)
- In-memory caching without TTL (causes memory issues)

**Solution:**
- Migrated session storage to **Amazon ElastiCache for Redis** using Spring Session
- Replaced in-memory cache with Redis cache with proper TTL (1 hour)
- Application is now stateless and can scale horizontally

**Files Modified:**
- `BookingController.java` - Uses RedisTemplate for session and cache management
- `RedisConfig.java` - New configuration class for Redis/ElastiCache integration
- `application.properties` - Redis connection configuration

### 4. Security & Authentication (Blocker 18)
**Issues Fixed:**
- File-based authentication

**Solution:**
- Replaced file-based authentication with **AWS Secrets Manager**
- Added `authenticateUser()` method in BookingService that retrieves credentials from Secrets Manager
- Supports integration with **Amazon Cognito** for user identity management

**Files Modified:**
- `BookingService.java` - New authentication method using Secrets Manager

### 5. Time & Clock Dependencies (Blocker 19)
**Issues Fixed:**
- Use of java.util.Date and local timezone dependencies

**Solution:**
- Migrated to **java.time API** (Instant, ZonedDateTime)
- All timestamps standardized to **UTC** timezone
- Ensures consistency across multiple regions and containers

**Files Modified:**
- `ReportService.java` - Uses Instant and DateTimeFormatter with UTC

## AWS Services Integration

### Required AWS Services
1. **Amazon S3** - File storage for reports and documents
2. **AWS Secrets Manager** - Secure credential storage
3. **AWS Systems Manager Parameter Store** - Configuration management
4. **Amazon ElastiCache for Redis** - Distributed session and cache management

### Environment Variables

```bash
# Server Configuration
SERVER_PORT=8080

# Database Configuration
DB_URL=jdbc:h2:mem:resortdb
DB_USERNAME=sa
DB_PASSWORD=

# AWS Configuration
AWS_REGION=us-east-1
AWS_S3_BUCKET=resorts-reports-bucket
AWS_SECRET_DB_CREDENTIALS=resorts/db/credentials

# Service Endpoints
PAYMENT_ENDPOINT=https://payment-svc:9090/charge
INVENTORY_ENDPOINT=https://inventory-svc:8081/rooms
NOTIFICATION_ENDPOINT=https://notify-svc:7070/send

# Redis Configuration (ElastiCache)
REDIS_HOST=resorts-cache.abc123.ng.0001.use1.cache.amazonaws.com
REDIS_PORT=6379
REDIS_PASSWORD=
```

### AWS Secrets Manager Secret Format

**Database Credentials Secret** (`resorts/db/credentials`):
```json
{
  "host": "resorts-db.cluster-abc123.us-east-1.rds.amazonaws.com",
  "username": "admin",
  "password": "SecurePassword123!"
}
```

**User Authentication Secret** (`resorts/users/{username}`):
```json
{
  "username": "john.doe",
  "password": "hashed_password",
  "roles": "ADMIN,USER"
}
```

### AWS Parameter Store Parameters

- `/resorts/report-service/base-url` - Report service base URL
- `/resorts/inventory-service/url` - Inventory service URL

## Deployment Architecture

### Cloud-Native Features
✅ **Stateless** - No local session storage, fully horizontally scalable
✅ **12-Factor App** - Externalized configuration, environment-based settings
✅ **Cloud Storage** - S3 for durable file storage
✅ **Distributed Cache** - Redis for shared cache across instances
✅ **Secret Management** - AWS Secrets Manager for credentials
✅ **Configuration Management** - Parameter Store for dynamic configuration
✅ **UTC Timezone** - Consistent time handling across regions

### Recommended AWS Deployment Options
1. **Amazon ECS (Elastic Container Service)** - Container orchestration
2. **Amazon EKS (Elastic Kubernetes Service)** - Kubernetes-based deployment
3. **AWS Elastic Beanstalk** - Platform-as-a-Service deployment
4. **AWS App Runner** - Fully managed container service

## Dependencies Added

### AWS SDK for Java v2
- `software.amazon.awssdk:s3` - S3 client
- `software.amazon.awssdk:secretsmanager` - Secrets Manager client
- `software.amazon.awssdk:ssm` - Systems Manager client

### Spring Session & Redis
- `spring-session-data-redis` - Distributed session management
- `spring-boot-starter-data-redis` - Redis integration
- `lettuce-core` - Redis client

### JSON Processing
- `jackson-databind` - JSON serialization for Redis and AWS SDK

## Build & Run

### Local Development
```bash
# Build the application
mvn clean package

# Run with local Redis
docker run -d -p 6379:6379 redis:latest
mvn spring-boot:run
```

### AWS Deployment
```bash
# Build Docker image
docker build -t resorts-lite:latest .

# Push to Amazon ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com
docker tag resorts-lite:latest <account-id>.dkr.ecr.us-east-1.amazonaws.com/resorts-lite:latest
docker push <account-id>.dkr.ecr.us-east-1.amazonaws.com/resorts-lite:latest
```

## Testing Cloud Readiness

### Verify S3 Integration
```bash
curl -X GET "http://localhost:8080/api/bookings/report/download?month=2024-03"
```

### Verify Redis Session Management
```bash
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-03-01&checkOut=2024-03-05" \
  -H "X-Session-Id: test-session-123"
```

### Verify Distributed Cache
```bash
curl -X GET "http://localhost:8080/api/bookings/status/BK-12345678" \
  -H "X-Session-Id: test-session-123"
```

## Migration Checklist

- [x] Replace hard-coded file paths with S3
- [x] Replace local file writes with S3
- [x] Migrate java.io.File to AWS SDK
- [x] Externalize database credentials to Secrets Manager
- [x] Externalize environment URLs to Parameter Store
- [x] Replace hard-coded ports with environment variables
- [x] Replace HTTP session with Redis
- [x] Replace in-memory cache with Redis + TTL
- [x] Replace file-based auth with Secrets Manager
- [x] Migrate to java.time API with UTC
- [x] Add AWS SDK dependencies
- [x] Add Spring Session Redis dependencies
- [x] Create Redis configuration
- [x] Create AWS SDK configuration

## Summary

All 20 cloud readiness blockers have been successfully resolved. The application is now:
- Fully cloud-native and AWS-ready
- Stateless and horizontally scalable
- Secure with externalized credentials
- Resilient with distributed session and cache management
- Portable across cloud environments
