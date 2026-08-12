package com.fieldservice.photo.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Wires the AWS SDK v2 S3 beans when S3 storage is configured.
 */
@Configuration
@ConditionalOnProperty("app.storage.s3.bucket-name")
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(PhotoStorageProperties props) {
        var builder = S3Client.builder()
                .region(Region.of(props.getS3().getRegion()));
        if (props.getS3().getEndpointOverride() != null && !props.getS3().getEndpointOverride().isBlank()) {
            builder.endpointOverride(URI.create(props.getS3().getEndpointOverride()))
                   .forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner(PhotoStorageProperties props) {
        var builder = S3Presigner.builder()
                .region(Region.of(props.getS3().getRegion()));
        if (props.getS3().getEndpointOverride() != null && !props.getS3().getEndpointOverride().isBlank()) {
            builder.endpointOverride(URI.create(props.getS3().getEndpointOverride()));
        }
        return builder.build();
    }
}
