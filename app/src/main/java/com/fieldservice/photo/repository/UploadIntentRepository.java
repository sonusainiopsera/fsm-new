package com.fieldservice.photo.repository;

import com.fieldservice.photo.domain.UploadIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UploadIntentRepository extends JpaRepository<UploadIntent, UUID> {
    Optional<UploadIntent> findByStorageKey(String storageKey);
}
