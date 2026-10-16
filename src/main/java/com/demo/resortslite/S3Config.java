package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

/**
 * S3Config – provides a singleton {@link S3Client} bean for the application.
 *
 * <p>The AWS region is externalised via the {@code AWS_REGION} environment variable
 * (or the Spring property {@code aws.region}) so that no region is hard-coded in
 * source code.  Credentials are resolved automatically by the AWS Default Credential
 * Provider Chain (IAM role, environment variables, ~/.aws/credentials, etc.).</p>
 *
 * <p>This configuration was introduced as part of the cr-java-0061 remediation to
 * support replacing hard-coded file-system paths with Amazon S3 object storage.</p>
 */
@Configuration
public class S3Config {

    /**
     * AWS region for the S3 client.
     * Resolved from the environment variable {@code AWS_REGION}
     * or the Spring property {@code aws.region}.
     */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates and configures the {@link S3Client} bean.
     *
     * <p>Uses the URL-connection HTTP client (no Netty dependency) and relies on
     * the AWS Default Credential Provider Chain for authentication.</p>
     *
     * @return a configured {@link S3Client} instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
