package com.parallels.jenkins;

/**
 * Controls how a VM is provisioned when Jenkins needs a new agent.
 *
 * <ul>
 *   <li>{@link #CLONE} — Clone an existing VM by name (current behaviour).
 *       Uses {@code PUT /api/v1/machines/{sourceId}/clone}.</li>
 *   <li>{@link #CATALOG} — Create a new VM from a Parallels DevOps catalog entry.
 *       Uses {@code POST /api/v1/machines} with a {@code catalog_manifest} body.</li>
 * </ul>
 */
public enum VmProvisioningMode {
    CLONE,
    CATALOG
}
