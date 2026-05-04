# ResortsLite - Cloud-Ready Application

## Overview
This application has been transformed to be fully cloud-ready and compatible with AWS cloud environments. All cloud compatibility blockers have been resolved.

## Cloud Readiness Fixes Applied

### 1. File System Dependencies (cr-java-0061, cr-java-0062, cr-java-0063)
**Issue**: Hard-coded file paths and local file system operations
**Fix**: Migrated to Amazon S3 for all file storage operations
- Reports are now generated and stored in S3 buckets
- No local file system dependencies
- Files: `ReportService.java`

### 2. Hard-coded Database Credentials (cr-java-0069)
**Issue**: Database credentials embedded in source code
**Fix**: Migrated to AWS Secrets Manager
- Credentials retrieved dynamically from Secrets Manager
- Supports automatic credential rotation
- Fallback to environment variables for local development
- Files: `BookingService.java`

### 3. Hard-coded Environment URLs (cr-java-0071)
**Issue**: Environment-specific URLs hard-coded in application
**Fix**: Externalized to AWS Systems Manager Parameter Store
- Service endpoints retrieved from Parameter Store
- Environment-agnostic deployments
- Files: `BookingController.java`, `ReportService.java`

### 4. Hard-coded Ports (cr-java-0077)
**Issue**: Fixed port numbers preventing dynamic assignment
**Fix**: Externalized to environment variables
- Ports configurable via environment variables
- Compatible with ECS/EKS dynamic port assignment
- Files: `application.properties`, `ReportService.java`

### 5. HTTP Session State Storage (cr-java-0065)
**Issue**: Session data stored in local memory, breaking horizontal scaling
**Fix**: Migrated to Amazon ElastiCache for Redis with Spring Session
- Session data stored in distributed Redis cache
- Accessible across all application instances
- Supports horizontal scaling and auto-scaling
- Files: `BookingController.java`, `RedisConfig.java`

### 6. In-Memory Caching Without TTL (cr-java-0067)
**Issue**: Unbounded in-memory cache causing memory issues
**Fix**: Migrated to Amazon ElastiCache for Redis with TTL
- Distributed caching with proper expiration policies
- Consistent cache across all instances
- Files: `BookingController.java`, `RedisConfig.java`

### 7. File-based Authentication (cr-java-0090)
**Issue**: Authentication credentials stored in local files
**Fix**: Migrated to AWS Secrets Manager
- Centralized credential management
- Encrypted storage with audit trails
- Files: `BookingService.java`

### 8. Clock/Time Dependencies (cr-java-0111)
**Issue**: Using java.util.Date with local timezone
**Fix**: Migrated to java.time API with UTC standardization
- All timestamps use UTC timezone
- Consistent time handling across distributed services
- Files: `ReportService.java`

## AWS Services Used

### Amazon S3
- **Purpose**: Durable file storage for reports
- **Configuration**: `aws.s3.bucket` in application.properties
- **Usage**: ReportService for report generation and storage

### AWS Secrets Manager
- **Purpose**: Secure credential storage and rotation
- **Configuration**: `aws.secrets.db-credentials`, `aws.secrets.api-keys`
- **Usage**: BookingService for database and API credentials

### AWS Systems Manager Parameter Store
- **Purpose**: Centralized configuration management
- **Configuration**: Retrieved dynamically at runtime
- **Usage**: Service endpoint URLs and configuration values

### Amazon ElastiCache for Redis
- **Purpose**: Distributed session management and caching
- **Configuration**: `spring.redis.*` properties
- **Usage**: Session storage and application caching

## Environment Variables

### Required for AWS Deployment
```bash
# AWS Configuration
AWS_REGION=us-east-1
AWS_S3_BUCKET=resorts-lite-reports
AWS_SECRET_DB_CREDENTIALS=resorts/db/credentials
AWS_SECRET_API_KEYS=resorts/api/keys

# Database Configuration
DB_URL=jdbc:postgresql://db-host:5432/resorts
DB_USERNAME=app_user
DB_PASSWORD=<from-secrets-manager>
DB_DRIVER=org.postgresql.Driver

# Redis Configuration (ElastiCache)
REDIS_HOST=resorts-cache.abc123.ng.0001.use1.cache.amazonaws.com
REDIS_PORT=6379
REDIS_PASSWORD=<optional>
REDIS_SSL=true

# Service Endpoints
PAYMENT_ENDPOINT=https://payment-svc.internal:9090/charge
INVENTORY_ENDPOINT=https://inventory-svc.internal:8081/rooms
NOTIFICATION_ENDPOINT=https://notify-svc.internal:7070/send

# Server Configuration
SERVER_PORT=8080
```

### Optional Configuration
```bash
# Cache Configuration
CACHE_TTL=3600
SESSION_TIMEOUT=1800

# Database Pool Configuration
DB_POOL_SIZE=10
DB_POOL_MIN_IDLE=5
DB_CONNECTION_TIMEOUT=30000
```

## AWS Secrets Manager Setup

### Database Credentials Secret
```json
{
  "host": "db-prod.resorts-internal.com",
  "username": "admin",
  "password": "SecurePassword123!",
  "port": 5432,
  "database": "resorts"
}
```

### API Keys Secret
```json
{
  "payment_api_endpoint": "https://payment-api.internal:9090/payments/charge",
  "payment_api_key": "pk_live_abc123...",
  "notification_api_key": "nk_live_xyz789..."
}
```

## AWS Systems Manager Parameter Store Setup

### Required Parameters
- `/resorts/config/report-base-url`: Base URL for report downloads
- `/resorts/config/inventory-service-url`: Inventory service endpoint

## Deployment Considerations

### ECS/Fargate
- Application is stateless and can scale horizontally
- Session data persists in Redis across container restarts
- No local file system dependencies

### EKS (Kubernetes)
- Compatible with dynamic port assignment
- Supports rolling updates without session loss
- Redis provides distributed state management

### Elastic Beanstalk
- Environment variables can be configured in EB console
- Auto-scaling supported with Redis session management
- Health checks compatible

## Security Improvements
- No credentials in source code or version control
- All secrets managed through AWS Secrets Manager
- Support for automatic credential rotation
- HTTPS enforced for all external communications
- SHA-256 hashing instead of MD5

## 12-Factor App Compliance
✅ Codebase: Single codebase tracked in version control
✅ Dependencies: Explicitly declared in pom.xml
✅ Config: Externalized to environment variables
✅ Backing Services: Treated as attached resources (Redis, S3, RDS)
✅ Build, Release, Run: Strictly separated
✅ Processes: Stateless with shared-nothing architecture
✅ Port Binding: Self-contained with configurable ports
✅ Concurrency: Horizontally scalable
✅ Disposability: Fast startup and graceful shutdown
✅ Dev/Prod Parity: Same backing services across environments
✅ Logs: Structured logging to stdout
✅ Admin Processes: Run as one-off processes

## Testing Locally

### Prerequisites
- Docker and Docker Compose for Redis
- AWS CLI configured with appropriate credentials
- Java 8+ and Maven

### Start Redis Locally
```bash
docker run -d -p 6379:6379 redis:latest
```

### Run Application
```bash
mvn spring-boot:run
```

### Environment Variables for Local Testing
```bash
export AWS_REGION=us-east-1
export REDIS_HOST=localhost
export REDIS_PORT=6379
export DB_URL=jdbc:h2:mem:resortdb
export DB_USERNAME=sa
export DB_PASSWORD=
```

## Migration Checklist

- [x] Replace hard-coded file paths with S3
- [x] Replace hard-coded credentials with Secrets Manager
- [x] Externalize environment URLs to Parameter Store
- [x] Replace hard-coded ports with environment variables
- [x] Migrate HTTP session to Redis
- [x] Replace in-memory cache with Redis
- [x] Fix timezone dependencies (UTC standardization)
- [x] Update dependencies (AWS SDK, Spring Session, Redis)
- [x] Create configuration classes (RedisConfig, AwsConfig)
- [x] Update application.properties with cloud-ready configuration

## Next Steps

1. **Create AWS Resources**
   - S3 bucket for reports
   - ElastiCache Redis cluster
   - Secrets Manager secrets
   - Parameter Store parameters

2. **Configure IAM Roles**
   - S3 read/write permissions
   - Secrets Manager read permissions
   - Parameter Store read permissions

3. **Deploy to AWS**
   - Build Docker image (separate workflow)
   - Deploy to ECS/EKS/Elastic Beanstalk
   - Configure environment variables
   - Test horizontal scaling

## Support
For issues or questions, contact the cloud migration team.
