package com.demo.resortslite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * FIXED cr-java-0090: AWS Secrets Manager Configuration
 * 
 * This configuration class provides the SecretsManagerClient bean for secure
 * credential retrieval from AWS Secrets Manager. This replaces file-based
 * authentication with cloud-native secrets management.
 * 
 * Benefits:
 * - Centralized credential management
 * - Automatic credential rotation support
 * - Encryption at rest and in transit
 * - Audit logging of credential access
 * - IAM-based access control
 * - No credentials in source code or configuration files
 * 
 * The client uses DefaultCredentialsProvider which automatically discovers
 * credentials from:
 * 1. Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
 * 2. System properties
 * 3. AWS credentials file (~/.aws/credentials)
 * 4. IAM role for EC2/ECS/Lambda (recommended for production)
 */
@Configuration
public class AwsSecretsManagerConfig {

    /**
     * Creates and configures the AWS Secrets Manager client.
     * 
     * The client is configured to:
     * - Use the default AWS credentials provider chain
     * - Connect to the US-East-1 region (can be overridden via environment variable)
     * - Automatically retry failed requests
     * 
     * @return Configured SecretsManagerClient instance
     */
    @Bean
    @Profile("!test") // Don't create this bean in test profile
    public SecretsManagerClient secretsManagerClient() {
        // Get region from environment variable or default to us-east-1
        String regionName = System.getenv("AWS_REGION");
        if (regionName == null || regionName.isEmpty()) {
            regionName = "us-east-1";
        }
        
        return SecretsManagerClient.builder()
                .region(Region.of(regionName))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
