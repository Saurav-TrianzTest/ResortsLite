# Cloud Readiness Fix Report: cr-java-0077 - Hard-coded Ports

## Fix Summary
**Rule ID**: cr-java-0077  
**Rule Name**: Hard-coded Ports  
**Severity**: CRITICAL  
**Category**: networking-&-communication  
**Status**: ✅ FIXED  

## Problem Description
The application contained hard-coded port numbers (8080) that prevented dynamic port assignment required by container orchestration platforms (ECS, EKS) and cloud service discovery mechanisms. This caused deployment failures and service conflicts in cloud environments.

## Remediation Strategy Applied
Eliminated hard-coded port numbers by:
1. Externalizing port configuration to environment variables
2. Supporting AWS Systems Manager Parameter Store integration
3. Enabling dynamic port assignment by ECS, EKS, and Elastic Beanstalk
4. Implementing fallback mechanism for local development

## Files Modified

### 1. ReportService.java
**Path**: `src/main/java/com/demo/resortslite/ReportService.java`

**Changes**:
- **Line 38**: Replaced `@Value("${server.port:8080}")` with `@Value("${SERVER_PORT:${server.port:0}}")`
  - Now reads from `SERVER_PORT` environment variable first
  - Falls back to `server.port` property if not set
  - Defaults to `0` (random port) for cloud-native dynamic assignment

- **Line 113**: Replaced hard-coded port in URL
  - Before: `"http://reports.resorts-internal.com:8080/download/" + reportName`
  - After: `"http://reports.resorts-internal.com:" + serverPort + "/download/" + reportName`
  - Port is now dynamically injected from configuration

### 2. application.properties
**Path**: `src/main/resources/application.properties`

**Changes**:
- **Line 9**: Updated server.port configuration
  - Before: `server.port=8080`
  - After: `server.port=${SERVER_PORT:0}`
  - Added comprehensive documentation for AWS deployment scenarios

## AWS Deployment Configuration

### ECS (Elastic Container Service)
Set the `SERVER_PORT` environment variable in your ECS Task Definition:
```json
{
  "containerDefinitions": [{
    "environment": [
      {
        "name": "SERVER_PORT",
        "value": "8080"
      }
    ]
  }]
}
```

Or use AWS Systems Manager Parameter Store:
```json
{
  "containerDefinitions": [{
    "secrets": [
      {
        "name": "SERVER_PORT",
        "valueFrom": "arn:aws:ssm:region:account:parameter/app/server/port"
      }
    ]
  }]
}
```

### EKS (Elastic Kubernetes Service)
Set the environment variable in your Deployment manifest:
```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
      - name: resortslite
        env:
        - name: SERVER_PORT
          value: "8080"
```

Or use ConfigMap:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  SERVER_PORT: "8080"
---
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
      - name: resortslite
        envFrom:
        - configMapRef:
            name: app-config
```

### Elastic Beanstalk
Set the environment property in `.ebextensions/environment.config`:
```yaml
option_settings:
  - namespace: aws:elasticbeanstalk:application:environment
    option_name: SERVER_PORT
    value: 8080
```

Or via AWS Console:
1. Navigate to Elastic Beanstalk Console
2. Select your environment
3. Go to Configuration → Software
4. Add environment property: `SERVER_PORT=8080`

## Benefits of This Fix

1. **Dynamic Port Assignment**: Supports container orchestration platforms that assign ports dynamically
2. **Cloud-Native**: Follows 12-factor app principles for configuration management
3. **Flexible Deployment**: Works across ECS, EKS, and Elastic Beanstalk
4. **Secure Configuration**: Supports AWS Parameter Store for centralized configuration
5. **Development Friendly**: Falls back to sensible defaults for local development
6. **No Service Conflicts**: Eliminates port conflicts in multi-container environments

## Testing Recommendations

### Local Testing
```bash
# Test with environment variable
export SERVER_PORT=8080
mvn spring-boot:run

# Test with default (random port)
unset SERVER_PORT
mvn spring-boot:run
```

### AWS Testing
1. Deploy to ECS/EKS with SERVER_PORT set to 8080
2. Verify application starts on configured port
3. Test service discovery and load balancing
4. Verify no port conflicts with other services

## Compliance Status
✅ **FIXED**: Application now supports dynamic port assignment  
✅ **AWS Compatible**: Integrates with AWS Parameter Store  
✅ **Container Ready**: Works with ECS, EKS, and Elastic Beanstalk  
✅ **12-Factor Compliant**: Configuration externalized to environment  

## Next Steps
1. Update deployment scripts to set SERVER_PORT environment variable
2. Configure AWS Parameter Store for production environments
3. Update load balancer target group to use dynamic port mapping
4. Test service discovery with new port configuration
