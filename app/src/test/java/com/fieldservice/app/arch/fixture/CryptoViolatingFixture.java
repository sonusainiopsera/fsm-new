package com.fieldservice.app.arch.fixture;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deliberately non-compliant fixture used by {@code InjectionAndCryptoRulesTest}
 * to prove the cryptography rule fires when a class uses a deprecated algorithm.
 *
 * <p>This class calls {@code MessageDigest.getInstance("MD5")} — an algorithm
 * that is explicitly forbidden. The ArchUnit rule must detect this and raise an
 * {@link AssertionError}.
 *
 * <p>This class lives in the test-only {@code arch.fixture} sub-package and
 * must never appear in production code.
 */
@SuppressWarnings({"unused", "java:S4790"})
public class CryptoViolatingFixture {

    /**
     * Uses MD5 — intentionally forbidden. The crypto ArchUnit rule must flag this.
     */
    public byte[] computeInsecureHash(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("MD5").digest(data);
    }
}
