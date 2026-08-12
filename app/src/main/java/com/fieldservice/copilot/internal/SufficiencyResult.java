package com.fieldservice.copilot.internal;

record SufficiencyResult(SufficiencyVerdict verdict, String reasonCode) {

    static SufficiencyResult sufficient() {
        return new SufficiencyResult(SufficiencyVerdict.SUFFICIENT, null);
    }

    static SufficiencyResult insufficient(String reasonCode) {
        return new SufficiencyResult(SufficiencyVerdict.INSUFFICIENT, reasonCode);
    }

    boolean isSufficient() {
        return verdict == SufficiencyVerdict.SUFFICIENT;
    }
}
