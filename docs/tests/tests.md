Based on everything built so far, here's the full manual test checklist:

---

## Core Provisioning

**1. Happy path — single build**
- Queue a job restricted to label `macos-sonoma`
- Verify in logs: clone → start → wait → ready → SSH → `SUCCESS`
- Verify build ran *on the clone* (`$NODE_NAME` = `prl-<uuid>`, not Built-In Node)

**2. Multiple simultaneous builds**
- Queue 3 builds at once
- Verify 3 separate clones are created (3 distinct `prl-<uuid>` nodes appear)
- All 3 should complete independently

**3. `maxAgents` cap**
- Set `Max Concurrent Agents = 2`
- Queue 4 builds simultaneously
- Verify only 2 nodes are provisioned; 2 builds wait in queue
- After first 2 finish, next 2 should provision

**4. Label mismatch**
- Queue a job with label `windows-11` (no template for this)
- Verify no clone is requested — job stays queued
- Verify log: `No matching template for label`

---

## Node Reconciler (60-second sync)

**5. External VM deletion — offline node**
- Let a build finish; node shows offline (or delete the VM manually via prl-devops UI)
- Wait up to 60 seconds
- Verify log: `Removing node prl-<uuid> — VM no longer exists: error 404`
- Verify the stale node disappears from Build Executor Status

**6. External VM deletion — while launching**
- Trigger a build, immediately delete the cloned VM from prl-devops UI before SSH connects
- Node should be stuck at `launching...`
- Within 60 seconds the reconciler should remove it and re-queue the build

**7. Next build after reconciler cleanup**
- After stale nodes are removed, queue a new build
- Verify a fresh clone is provisioned (budget is freed up)

---

## Credentials

**8. Username + password credential**
- Configure cloud with a `Username with password` credential
- Trigger a build — verify it provisions successfully (auth token exchange happens transparently)

**9. Invalid credentials**
- Change `credentialsId` to a non-existent ID
- Queue a build — verify log: `Cannot build API client: Credentials '...' not found`
- No VM should be cloned

---

## Timeout / Failure Handling

**10. VM ready timeout**
- Set `VM Ready Timeout` to `30` seconds on the template
- Trigger a build (VM will likely not be SSH-ready in 30s)
- Verify log: `failed to become ready`, `Successfully deleted orphaned VM`
- Verify the partially-booted clone is deleted in prl-devops

**11. `startVm` failure**
- If you can simulate a start failure (e.g. pause the host), verify:
  - Log shows `startVm() failed`
  - VM is deleted
  - No `prl-<uuid>` node lingers in Jenkins

---

## Configuration

**12. Config round-trip**
- Configure cloud + template, click Save
- Reload page — verify all fields (URL, credentials, mode, maxAgents, template label, base VM name, timeout values) survived the round-trip

**13. Test Connection button**
- Click **Test Connection** with valid credentials → green `Connected`
- Click with wrong URL → error message (not a stacktrace)

---

## What to check in logs for each test

`Manage Jenkins → System Log → All Jenkins Logs`, filter by `PrlDevops`:

| Expected line | Means |
|---|---|
| `Requesting clone of '...'` | `provision()` fired |
| `Clone requested; VM ID=...` | API returned a vmId |
| `Starting VM ...` | `startVm()` called |
| `Waiting for VM ...` | Polling loop started |
| `VM ... is ready at IP ...` | SSH can now connect |
| `Removing node ... no longer exists` | Reconciler cleaned up a deleted VM |
| `maxAgents cap reached` | Budget enforcement working |