package com.fieldservice.photo.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for photo object storage.
 *
 * <p>When {@code app.storage.s3.bucket-name} is set the S3 adapter is activated;
 * otherwise the stub adapter is used (development and tests).
 */
@Component
@ConfigurationProperties(prefix = "app.storage")
public class PhotoStorageProperties {

    private S3Props s3 = new S3Props();
    private PhotoProps photo = new PhotoProps();

    public S3Props getS3() { return s3; }
    public void setS3(S3Props s3) { this.s3 = s3; }

    public PhotoProps getPhoto() { return photo; }
    public void setPhoto(PhotoProps photo) { this.photo = photo; }

    public static class S3Props {
        private String bucketName;
        private String region = "us-east-1";
        private String endpointOverride;

        public String getBucketName() { return bucketName; }
        public void setBucketName(String v) { this.bucketName = v; }

        public String getRegion() { return region; }
        public void setRegion(String v) { this.region = v; }

        /** Optional override for LocalStack / MinIO endpoints in test environments. */
        public String getEndpointOverride() { return endpointOverride; }
        public void setEndpointOverride(String v) { this.endpointOverride = v; }
    }

    public static class PhotoProps {
        /** Presigned PUT validity in seconds (default: 300 per AC-1). */
        private int putExpirySeconds = 300;
        /** Presigned GET validity in seconds (default: 60 per AC-7). */
        private int getExpirySeconds = 60;
        /** Maximum accepted object size in bytes (default: 5 MB per AC-3). */
        private long maxBytes = 5_242_880L;
        /** Orphan cleanup: hours after URL expiry before an intent is considered orphaned. */
        private int orphanGraceHours = 24;
        /** Photo retention in days (drives retain_until for the purge job). */
        private int retentionDays = 2557; // ~7 years default

        public int getPutExpirySeconds() { return putExpirySeconds; }
        public void setPutExpirySeconds(int v) { this.putExpirySeconds = v; }

        public int getGetExpirySeconds() { return getExpirySeconds; }
        public void setGetExpirySeconds(int v) { this.getExpirySeconds = v; }

        public long getMaxBytes() { return maxBytes; }
        public void setMaxBytes(long v) { this.maxBytes = v; }

        public int getOrphanGraceHours() { return orphanGraceHours; }
        public void setOrphanGraceHours(int v) { this.orphanGraceHours = v; }

        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int v) { this.retentionDays = v; }
    }
}
