package com.parallels.jenkins;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JenkinsRule-based tests for {@link CredentialsHelper}.
 *
 * <p>Credentials are registered with {@link SystemCredentialsProvider} (the
 * global Jenkins credential store) so {@link CredentialsHelper}'s
 * {@code CredentialsProvider.lookupCredentials} calls can resolve them.
 */
@WithJenkins
class CredentialsHelperTest {

    // -------------------------------------------------------------------------
    // requireStringCredential
    // -------------------------------------------------------------------------

    @Test
    void requireStringCredential_resolvesValidId(JenkinsRule r) throws Exception {
        StringCredentialsImpl secret = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "api-token", "API token",
                hudson.util.Secret.fromString("super-secret"));
        SystemCredentialsProvider.getInstance().getCredentials().add(secret);

        StringCredentials resolved =
                CredentialsHelper.requireStringCredential("api-token", Jenkins.get());

        assertNotNull(resolved);
        assertEquals("api-token", resolved.getId());
        assertEquals("super-secret", resolved.getSecret().getPlainText());
    }

    @Test
    void requireStringCredential_throwsForMissingId(JenkinsRule r) {
        CredentialsNotFoundException ex = assertThrows(
                CredentialsNotFoundException.class,
                () -> CredentialsHelper.requireStringCredential("no-such-id", Jenkins.get()));

        assertEquals("no-such-id", ex.getCredentialsId());
        assertTrue(ex.getMessage().contains("no-such-id"),
                "Exception message should contain the missing credential ID");
    }

    // -------------------------------------------------------------------------
    // requireUsernamePasswordCredential
    // -------------------------------------------------------------------------

    @Test
    void requireUsernamePasswordCredential_resolvesValidId(JenkinsRule r) throws Exception {
        UsernamePasswordCredentialsImpl upc = new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "bearer-cred", "Bearer auth",
                "admin", "pass123");
        SystemCredentialsProvider.getInstance().getCredentials().add(upc);

        StandardUsernamePasswordCredentials resolved =
                CredentialsHelper.requireUsernamePasswordCredential("bearer-cred", Jenkins.get());

        assertNotNull(resolved);
        assertEquals("bearer-cred", resolved.getId());
        assertEquals("admin", resolved.getUsername());
        assertEquals("pass123", resolved.getPassword().getPlainText());
    }

    @Test
    void requireUsernamePasswordCredential_throwsForMissingId(JenkinsRule r) {
        CredentialsNotFoundException ex = assertThrows(
                CredentialsNotFoundException.class,
                () -> CredentialsHelper.requireUsernamePasswordCredential("missing-cred", Jenkins.get()));

        assertEquals("missing-cred", ex.getCredentialsId());
    }

    // -------------------------------------------------------------------------
    // Jelly dropdown filtering — doFillCredentialsIdItems
    // -------------------------------------------------------------------------

    @Test
    void doFillCredentialsIdItems_forCloud_includesOnlyStringAndUsernamePassword(JenkinsRule r)
            throws Exception {
        // Register one of each supported type
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(CredentialsScope.GLOBAL, "str-1", "String cred",
                        hudson.util.Secret.fromString("tok")));
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "upc-1", "UPC",
                        "user", "pass"));

        PrlDevopsCloud.DescriptorImpl descriptor =
                (PrlDevopsCloud.DescriptorImpl) Jenkins.get()
                        .getDescriptorOrDie(PrlDevopsCloud.class);

        ListBoxModel items = descriptor.doFillCredentialsIdItems("str-1");

        // Both credential IDs must appear; the empty value is also added
        assertTrue(items.stream().anyMatch(o -> "str-1".equals(o.value)),
                "Dropdown should contain the StringCredentials ID");
        assertTrue(items.stream().anyMatch(o -> "upc-1".equals(o.value)),
                "Dropdown should contain the UsernamePassword ID");
    }

    // SSH credentials test removed - plugin now uses inbound agents instead of SSH
}
