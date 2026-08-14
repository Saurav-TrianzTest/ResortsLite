# AWS Secrets Manager Setup Guide

## FIXED: cr-java-0090 - File-based Authentication

This application has been migrated from file-based/hardcoded authentication to AWS Secrets Manager and is ready for Amazon Cognito integration for user authentication.

## Database Credentials - AWS Secrets Manager

### Overview
Database credentials are now securely stored in AWS Secrets Manager instead of being hardcoded in source code or configuration files. This provides:

- **Centralized Management**: Single source of truth for credentials
- **Automatic Rotation**: Support for automatic credential rotation
- **Encryption**: Credentials encrypted at rest and in transit
- **Audit Logging**: All access to credentials is logged via CloudTrail
- **IAM Access Control**: Fine-grained access control using IAM policies
- **No Source Code Exposure**: Credentials never appear in version control

### Creating the Secret in AWS Secrets Manager

1. **Using AWS Console:**
   ```
   - Navigate to AWS Secrets Manager
   - Click "Store a new secret"
   - Select "Other type of secret"
   - Add the following key-value pairs:
     * host: your-database-host.rds.amazonaws.com
     * username: your-db-username
     * password: your-db-password
   - Name the secret: resorts-db-credentials
   - Complete the wizard
   ```

2. **Using AWS CLI:**
   ```bash
   aws secretsmanager create-secret \
     --name resorts-db-credentials \
     --description "Database credentials for ResortsLite application" \
     --secret-string '{"host":"your-database-host.rds.amazonaws.com","username":"your-db-username","password":"your-db-password"}' \
     --region us-east-1
   ```

3. **Using Terraform:**
   ```hcl
   resource "aws_secretsmanager_secret" "db_credentials" {
     name        = "resorts-db-credentials"
     description = "Database credentials for ResortsLite application"
   }

   resource "aws_secretsmanager_secret_version" "db_credentials" {
     secret_id = aws_secretsmanager_secret.db_credentials.id
     secret_string = jsonencode({
       host     = "your-database-host.rds.amazonaws.com"
       username = "your-db-username"
       password = "your-db-password"
     })
   }
   ```

### IAM Permissions Required

The application's IAM role (EC2 instance role, ECS task role, or Lambda execution role) needs the following permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret"
      ],
      "Resource": "arn:aws:secretsmanager:us-east-1:ACCOUNT_ID:secret:resorts-db-credentials-*"
    }
  ]
}
```

### Configuration

The application uses the following environment variables:

- `AWS_SECRET_NAME`: Name of the secret in Secrets Manager (default: `resorts-db-credentials`)
- `AWS_REGION`: AWS region where the secret is stored (default: `us-east-1`)

For local development, you can also use:
- `DB_HOST`: Database host (fallback if Secrets Manager is unavailable)
- `DB_USER`: Database username (fallback)
- `DB_PASS`: Database password (fallback)

### Local Development

For local development without AWS credentials:

1. Set environment variables:
   ```bash
   export DB_HOST=localhost
   export DB_USER=sa
   export DB_PASS=
   ```

2. Or use AWS credentials with access to Secrets Manager:
   ```bash
   export AWS_ACCESS_KEY_ID=your-access-key
   export AWS_SECRET_ACCESS_KEY=your-secret-key
   export AWS_REGION=us-east-1
   export AWS_SECRET_NAME=resorts-db-credentials
   ```

### Testing the Integration

1. **Verify Secret Exists:**
   ```bash
   aws secretsmanager describe-secret --secret-id resorts-db-credentials
   ```

2. **Test Secret Retrieval:**
   ```bash
   aws secretsmanager get-secret-value --secret-id resorts-db-credentials
   ```

3. **Application Startup:**
   The application will log the credential retrieval process. Check logs for:
   - Successful connection to Secrets Manager
   - Successful retrieval of credentials
   - Fallback to environment variables (if applicable)

## Amazon Cognito Integration (Future Enhancement)

For user authentication, the application is ready to integrate with Amazon Cognito:

### Benefits of Amazon Cognito:
- **User Management**: Built-in user registration, authentication, and account recovery
- **Multi-Factor Authentication (MFA)**: Enhanced security with MFA support
- **Social Identity Providers**: Integration with Google, Facebook, Amazon, etc.
- **SAML/OIDC**: Enterprise identity federation
- **Scalability**: Handles millions of users automatically
- **Security**: Built-in protection against common attacks

### Integration Steps (To Be Implemented):
1. Create a Cognito User Pool
2. Configure app client settings
3. Add Spring Security with Cognito integration
4. Update authentication endpoints
5. Implement JWT token validation

## Security Best Practices

1. **Rotate Credentials Regularly**: Enable automatic rotation in Secrets Manager
2. **Use IAM Roles**: Never use long-term access keys in production
3. **Least Privilege**: Grant only necessary permissions to access secrets
4. **Monitor Access**: Enable CloudTrail logging for secret access
5. **Encrypt in Transit**: Always use HTTPS/TLS for API calls
6. **Separate Environments**: Use different secrets for dev/staging/production

## Troubleshooting

### Common Issues:

1. **"Access Denied" Error:**
   - Verify IAM role has `secretsmanager:GetSecretValue` permission
   - Check the secret ARN in the IAM policy matches your secret

2. **"Secret Not Found" Error:**
   - Verify the secret name matches the configuration
   - Check the AWS region is correct
   - Ensure the secret exists in the specified region

3. **"Unable to Parse Secret" Error:**
   - Verify the secret is stored as JSON
   - Check the JSON contains required keys: host, username, password
   - Validate JSON syntax

4. **Application Falls Back to Environment Variables:**
   - SecretsManagerClient bean may not be initialized
   - Check AWS credentials are available
   - Verify network connectivity to AWS Secrets Manager endpoint

## Migration Checklist

- [x] Remove hardcoded credentials from source code
- [x] Add AWS Secrets Manager SDK dependency
- [x] Create SecretsManagerClient configuration
- [x] Update BookingService to retrieve credentials from Secrets Manager
- [x] Add fallback to environment variables for local development
- [x] Document setup and configuration
- [ ] Create secrets in AWS Secrets Manager for each environment
- [ ] Configure IAM roles with appropriate permissions
- [ ] Test in development environment
- [ ] Test in staging environment
- [ ] Deploy to production
- [ ] Enable automatic credential rotation
- [ ] Integrate Amazon Cognito for user authentication (future)

## References

- [AWS Secrets Manager Documentation](https://docs.aws.amazon.com/secretsmanager/)
- [AWS SDK for Java - Secrets Manager](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-secretsmanager.html)
- [Amazon Cognito Documentation](https://docs.aws.amazon.com/cognito/)
- [Spring Security with Cognito](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-integrate-apps.html)
