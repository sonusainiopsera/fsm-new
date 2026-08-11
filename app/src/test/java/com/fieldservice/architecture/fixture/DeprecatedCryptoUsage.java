package com.fieldservice.architecture.fixture;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * ⚠️ ARCHITECTURE TEST FIXTURE ONLY — DELIBERATELY NON-COMPLIANT ⚠️
 *
 * <p>This class exists solely to prove that the ArchUnit crypto-safety rule in
 * {@link com.fieldservice.architecture.InjectionAndCryptoRulesTest} fires when code
 * uses a deprecated/weak hashing algorithm.
 *
 * <p>This class must never be used in production code.
 */
@SuppressWarnings({"unused", "java:S4790"})
public class DeprecatedCryptoUsage {

    /**
     * Uses MD5 — a deliberately deprecated algorithm to trigger the crypto rule.
     * MD5 is broken for security use; SHA-256 is the approved minimum.
     */
    public byte[] hashWithMd5(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return md.digest(input.getBytes());
    }
}
