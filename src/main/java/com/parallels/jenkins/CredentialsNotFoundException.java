package com.parallels.jenkins;

/**
 * Thrown by {@link CredentialsHelper} when a credential ID cannot be resolved
 * to an actual credential in the Jenkins Credentials store.
 *
 * <p>Using a typed exception instead of a generic {@link NullPointerException}
 * makes the failure diagnostic — callers see exactly which ID was missing and
 * can surface a meaningful error in the Jenkins UI or log.
 */
public class CredentialsNotFoundException extends Exception {

    private final String credentialsId;

    public CredentialsNotFoundException(String credentialsId) {
        super("Credentials '" + credentialsId + "' not found or not accessible. "
                + "Ensure the credential exists in the Jenkins Credentials store and "
                + "is accessible to the SYSTEM acl.");
        this.credentialsId = credentialsId;
    }

    /** The credential ID that could not be resolved. */
    public String getCredentialsId() {
        return credentialsId;
    }
}
