package com.parallels.jenkins.api;

import com.parallels.jenkins.api.dto.CloneRequest;
import com.parallels.jenkins.api.dto.CloneResponse;
import com.parallels.jenkins.api.dto.CreateVmRequest;
import com.parallels.jenkins.api.dto.CreateVmResponse;
import com.parallels.jenkins.api.dto.ExecuteRequest;
import com.parallels.jenkins.api.dto.ExecuteResponse;
import com.parallels.jenkins.api.dto.VmStatusResponse;
import com.parallels.jenkins.api.exception.PrlApiException;
import com.parallels.jenkins.api.exception.PrlApiTimeoutException;

import java.time.Duration;

/**
 * Thin abstraction over the prl-devops-service REST API.
 *
 * <p>All three core operations needed by the Jenkins plugin are declared here.
 * Callers should depend on this interface, not on the concrete HTTP implementation,
 * so the client can be mocked in upstream unit tests.
 *
 * <p>Every method throws {@link PrlApiException} for:
 * <ul>
 *   <li>non-2xx HTTP responses (carries the status code and parsed error body), or</li>
 *   <li>low-level network failures.</li>
 * </ul>
 */
public interface PrlDevopsApiClient {

    /**
     * Clones the VM identified by {@code sourceVmId}.
     *
     * <p>Maps to {@code PUT /api/v1/machines/{sourceVmId}/clone} (host mode) or
     * {@code PUT /api/v1/orchestrator/hosts/{hostId}/machines/{sourceVmId}/clone}
     * (orchestrator mode).
     *
     * @param sourceVmId ID of the VM to clone.
     * @param request    Clone options (clone name, destination path); fields are optional.
     * @return {@link CloneResponse} containing the new VM's ID.
     * @throws PrlApiException on HTTP error or network failure.
     */
    CloneResponse cloneVm(String sourceVmId, CloneRequest request) throws PrlApiException;

    /**
     * Creates a new VM from a Parallels DevOps catalog entry.
     *
     * <p>Maps to {@code POST /api/v1/machines}.
     *
     * <p>The request includes {@code startOnCreate: true} so the VM boots
     * immediately after creation. Callers must still poll {@link #waitForVmReady}
     * because the VM will be in {@code stopped} state momentarily before transitioning
     * to {@code running}.
     *
     * @param request Catalog VM creation parameters.
     * @return {@link CreateVmResponse} containing the new VM's ID.
     * @throws PrlApiException on HTTP error or network failure.
     */
    CreateVmResponse createVmFromCatalog(CreateVmRequest request) throws PrlApiException;

    /**
     * Returns the lightweight status of a VM.
     *
     * <p>Maps to {@code GET /api/v1/machines/{vmId}/status} (host mode) or
     * {@code GET /api/v1/orchestrator/hosts/{hostId}/machines/{vmId}/status}
     * (orchestrator mode).
     *
     * @param vmId ID of the VM to query.
     * @return {@link VmStatusResponse} with {@code id}, {@code status}, and {@code ip_configured}.
     * @throws PrlApiException on HTTP error or network failure.
     */
    VmStatusResponse getVmStatus(String vmId) throws PrlApiException;

    /**
     * Starts a VM that is in the {@code stopped} state.
     *
     * <p>Maps to {@code GET /api/v1/machines/{vmId}/start} (host mode) or
     * {@code GET /api/v1/orchestrator/hosts/{hostId}/machines/{vmId}/start}
     * (orchestrator mode).
     *
     * <p>The API accepts the request and begins booting; the VM transitions
     * through {@code stopped → starting → running}. Callers must subsequently
     * poll {@link #waitForVmReady} to know when the VM is ready.
     *
     * @param vmId ID of the VM to start.
     * @throws PrlApiException on HTTP error or network failure.
     */
    void startVm(String vmId) throws PrlApiException;

    /**
     * Deletes a VM.
     *
     * <p>Maps to {@code DELETE /api/v1/machines/{vmId}?force=true} (host mode) or
     * {@code DELETE /api/v1/orchestrator/hosts/{hostId}/machines/{vmId}?force=true}
     * (orchestrator mode). The {@code force=true} parameter allows deletion of a
     * running VM without stopping it first. The API returns {@code 202 Accepted} with no body.
     *
     * @param vmId ID of the VM to delete.
     * @throws PrlApiException on HTTP error or network failure.
     */
    void deleteVm(String vmId) throws PrlApiException;

    /**
     * Polls {@link #getVmStatus} until the VM reaches the {@code running} state or
     * {@code timeout} is exceeded.
     *
     * <p>State machine:
     * <pre>
     *   stopped  → keep polling (VM may briefly show stopped immediately after start)
     *   pending  → keep polling
     *   starting → keep polling
     *   running  → return (success)
     *   error    → throw {@link PrlApiException}
     *   (timeout)→ throw {@link PrlApiTimeoutException}
     * </pre>
     *
     * @param vmId     ID of the VM to wait for.
     * @param timeout  Maximum time to wait before giving up.
     * @param interval Time to sleep between polling attempts.
     * @return Final {@link VmStatusResponse} once the VM is running.
     * @throws PrlApiException        if the VM enters an error state or a network error occurs.
     * @throws PrlApiTimeoutException if the VM does not reach running within {@code timeout}.
     */
    /**
     * @param vmUser OS user used for the execute-API readiness probe.
     */
    VmStatusResponse waitForVmReady(String vmId, String vmUser, Duration timeout, Duration interval)
            throws PrlApiException, PrlApiTimeoutException;

    /**
     * Executes a command on a running VM.
     *
     * <p>Maps to {@code PUT /api/v1/machines/{vmId}/execute} (host mode) or
     * {@code PUT /api/v1/orchestrator/hosts/{hostId}/machines/{vmId}/execute}
     * (orchestrator mode).
     *
     * @param vmId    ID of the VM to run the command on.
     * @param request Command, user, and environment variables.
     * @return {@link ExecuteResponse} with stdout and exit code.
     * @throws PrlApiException on HTTP error or network failure.
     */
    ExecuteResponse executeCommand(String vmId, ExecuteRequest request) throws PrlApiException;
}
