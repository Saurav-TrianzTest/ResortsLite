package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AwsClientConfig provides Spring-managed AWS SDK v2 client beans.
 *
 * <p>These beans are used across the application to interact with:
 * <ul>
 *   <li>Amazon S3 — durable object storage replacing local file system (blockers 1-7)</li>
 *   <li>AWS Secrets Manager — externalized database credentials (blockers 8,9,18)</li>
 *   <li>AWS Systems Manager Parameter Store — externalized URLs and ports (blockers 10-12)</li>
 * </ul>
 *
 * <p>The AWS region is injected from the {@code AWS_REGION} environment variable
 * (defaulting to {@code us-east-1}). Credentials are resolved automatically by the
 * AWS SDK default credential provider chain (IAM role, environment variables, etc.).
 */
@Configuration
public class AwsClientConfig {

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * Amazon S3 client bean.
     * Used by {@link ReportService} to store and retrieve report objects
     * instead of writing to the local file system.
     *
     * @return configured {@link S3Client}
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Secrets Manager client bean.
     * Used by {@link BookingService} to retrieve database credentials at runtime,
     * eliminating hard-coded DB_HOST, DB_USER, and DB_PASS constants.
     *
     * @return configured {@link SecretsManagerClient}
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * AWS Systems Manager (SSM) client bean.
     * Used by {@link ReportService} and {@link BookingService} to retrieve
     * environment-specific URLs and port configuration from Parameter Store,
     * replacing all hard-coded endpoint strings.
     *
     * @return configured {@link SsmClient}
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
