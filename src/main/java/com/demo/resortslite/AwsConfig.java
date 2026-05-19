package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AWS SDK configuration for cloud-native services integration.
 * Provides centralized AWS client beans for S3, Secrets Manager, and Systems Manager.
 */
@Configuration
public class AwsConfig {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates S3 client for Amazon S3 object storage operations.
     * Used for storing reports and files in cloud-native manner.
     * 
     * @return Configured S3Client
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Creates Secrets Manager client for secure credential management.
     * Used for retrieving database credentials and API keys.
     * 
     * @return Configured SecretsManagerClient
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Creates Systems Manager client for parameter store access.
     * Used for retrieving configuration parameters and service endpoints.
     * 
     * @return Configured SsmClient
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
