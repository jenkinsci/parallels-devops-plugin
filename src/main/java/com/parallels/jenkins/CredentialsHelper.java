package com.parallels.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import java.util.Collections;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

/**
 * Centralised credential lookup for the Parallels DevOps plugin.
 *
 * <p>All calls to {@link CredentialsProvider#lookupCredentials} are funnelled
 * through this class so that no inline lookups are scattered across
 * {@link PrlDevopsCloud} or {@link PrlDevopsComputerLauncher}.
 *
 * <p>Methods prefixed {@code find} return {@code null} when the credential is
 * absent; methods prefixed {@code require} throw {@link CredentialsNotFoundException}
 * instead of returning {@code null}, preventing silent {@link NullPointerException}s.
 */
public final class CredentialsHelper {

    private CredentialsHelper() {
        // utility class — no instances
    }

    // -------------------------------------------------------------------------
    // StringCredentials (Secret Text / API key)
    // -------------------------------------------------------------------------

    /**
     * Looks up a {@link StringCredentials} by ID.
     *
     * @return the credential, or {@code null} if not found
     */
    @CheckForNull
    public static StringCredentials findStringCredential(
            @NonNull String credentialsId,
            @NonNull ItemGroup<?> context) {
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StringCredentials.class, context, ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
    }

    /**
     * Looks up a {@link StringCredentials} by ID, throwing if absent.
     *
     * @throws CredentialsNotFoundException if the credential cannot be resolved
     */
    @NonNull
    public static StringCredentials requireStringCredential(
            @NonNull String credentialsId,
            @NonNull ItemGroup<?> context) throws CredentialsNotFoundException {
        StringCredentials cred = findStringCredential(credentialsId, context);
        if (cred == null) {
            throw new CredentialsNotFoundException(credentialsId);
        }
        return cred;
    }

    // -------------------------------------------------------------------------
    // StandardUsernamePasswordCredentials (Username + Password)
    // -------------------------------------------------------------------------

    /**
     * Looks up a {@link StandardUsernamePasswordCredentials} by ID.
     *
     * @return the credential, or {@code null} if not found
     */
    @CheckForNull
    public static StandardUsernamePasswordCredentials findUsernamePasswordCredential(
            @NonNull String credentialsId,
            @NonNull ItemGroup<?> context) {
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class, context, ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
    }

    /**
     * Looks up a {@link StandardUsernamePasswordCredentials} by ID, throwing if absent.
     *
     * @throws CredentialsNotFoundException if the credential cannot be resolved
     */
    @NonNull
    public static StandardUsernamePasswordCredentials requireUsernamePasswordCredential(
            @NonNull String credentialsId,
            @NonNull ItemGroup<?> context) throws CredentialsNotFoundException {
        StandardUsernamePasswordCredentials cred = findUsernamePasswordCredential(credentialsId, context);
        if (cred == null) {
            throw new CredentialsNotFoundException(credentialsId);
        }
        return cred;
    }

    // -------------------------------------------------------------------------
    // SSH credentials (StandardUsernameCredentials covers both
    // StandardUsernamePasswordCredentials and BasicSSHUserPrivateKey)
    // -------------------------------------------------------------------------

    /**
     * Looks up an SSH credential ({@link StandardUsernameCredentials}) by ID,
     * throwing if absent.
     *
     * <p>{@link StandardUsernameCredentials} is the common supertype for both
     * {@link StandardUsernamePasswordCredentials} (username + password) and
     * {@code BasicSSHUserPrivateKey} (username + private key), so a single lookup
     * covers all SSH authentication modes.
     *
     * @throws CredentialsNotFoundException if the credential cannot be resolved
     */
    @NonNull
    public static StandardUsernameCredentials requireSshCredential(
            @NonNull String credentialsId,
            @NonNull ItemGroup<?> context) throws CredentialsNotFoundException {
        StandardUsernameCredentials cred = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardUsernameCredentials.class, context, ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));
        if (cred == null) {
            throw new CredentialsNotFoundException(credentialsId);
        }
        return cred;
    }
}
