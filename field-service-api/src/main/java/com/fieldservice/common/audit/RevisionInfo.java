package com.fieldservice.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

/**
 * Custom Envers revision entity mapping to the {@code revinfo} table.
 *
 * <p>The sequence {@code revinfo_seq} is created by the V1 Flyway migration with
 * {@code allocationSize = 50} to match the sequence increment.
 */
@Entity
@RevisionEntity
@Table(name = "revinfo")
@Getter
@Setter
public class RevisionInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "revinfo_seq_gen")
    @SequenceGenerator(
            name = "revinfo_seq_gen",
            sequenceName = "revinfo_seq",
            allocationSize = 50
    )
    @RevisionNumber
    @Column(name = "rev", nullable = false)
    private int rev;

    @RevisionTimestamp
    @Column(name = "revtstmp", nullable = false)
    private long revtstmp;
}
