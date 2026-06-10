package com.vng.kyc.domain;

public class SubmissionNotFoundException extends RuntimeException {
    public SubmissionNotFoundException(String submissionId) {
        super("Submission not found: " + submissionId);
    }
}
