# Installing the Parallels DevOps Jenkins Plugin

This guide takes you from an `.hpi` file to a working dynamic VM agent running a real build on a local Jenkins instance.

---

## Prerequisites

Before you begin, make sure the following are in place:

| Requirement | Minimum version | Notes |
|---|---|---|
| **Jenkins LTS** | 2.440.x or newer | [jenkins.io/download](https://www.jenkins.io/download/) |
| **JDK** | 17 (plugin default) or 21 | [Adoptium](https://adoptium.net/) / `brew install openjdk@17` |
| **`prl-devops-service`** | Latest stable | Must be running and reachable from the Jenkins host |
| **Parallels DevOps API credentials** | — | Bearer token or username / password for the service |
| **SSH credentials** | — | A keypair or username+password that Jenkins can use to reach provisioned VMs |
| **A VM source** | — | Either a base VM registered in the service, or a catalog entry |

> **Windows**: use WSL 2 and follow the Linux instructions inside the WSL shell.
>
> **macOS / Linux**: `brew install openjdk@17 maven` covers both JDK and Maven in one step.

### Verify your tools

```bash
java -version   # expect 17.x or 21.x
```

---

## Installing the .hpi

You can install the plugin in two ways. Pick whichever suits your workflow.

### Via Plugin Manager UI

1. Open Jenkins in your browser (e.g., `http://localhost:8080`).
2. Go to **Manage Jenkins → Plugins → Advanced settings**.
3. Scroll to the **Deploy Plugin** section.
4. Click **Choose File**, select your `parallels-devops-<version>.hpi`, then click **Deploy**.
5. Tick **Restart Jenkins when installation is complete and no jobs are running** and wait for the restart.

> **Tip**: If you built the plugin locally with `make package`, the `.hpi` is at `target/parallels-devops-jenkins-plugin.hpi`.

### Via Jenkins CLI

Download the CLI jar from your running instance, then run:

```bash
# Download the CLI jar (only needed once)
curl -O http://localhost:8080/jnlpJars/jenkins-cli.jar

# Install the plugin and trigger a safe restart
java -jar jenkins-cli.jar \
  -s http://localhost:8080 \
  -auth <user>:<api-token> \
  install-plugin /path/to/parallels-devops.hpi \
  -restart
```

Replace `<user>` and `<api-token>` with your Jenkins credentials. Jenkins will restart and the plugin will be active.

---

## Configuring the Cloud Provider

### Step 1: Add the Cloud

Navigate to **Manage Jenkins → Clouds → New cloud**.

Enter a name (e.g., `parallels-cloud`), choose **Parallels DevOps Cloud** from the type list, and click **Create**.

![Create a new Parallels DevOps cloud](images/new-cloud.png)

### Step 2: Configure the Service Connection

Fill in the top-level cloud fields:

| Field | Description |
|---|---|
| **Service URL** | Base URL of your `prl-devops-service`, e.g., `http://192.168.1.100:8080` |
| **API Credentials** | Select or add the Jenkins credential for the Parallels DevOps API (secret text or username/password) |
| **Connection Mode** | `HOST` — connects to a single Parallels DevOps host; `ORCHESTRATOR` — connects to an orchestrator managing a fleet |
| **Max Concurrent Agents** | Maximum number of VMs that may be alive at once for this cloud |

![Cloud service configuration](images/service-configurations.png)

### Step 3: Add an Agent Template

Scroll down to **Agent Templates** and click **Add Template**. Configure:

| Field | Description |
|---|---|
| **Template Label** | Jenkins label that jobs will request, e.g., `macos`, `ubuntu-arm64` |
| **SSH Credentials** | Credential Jenkins uses to connect to the provisioned VM over SSH |
| **Agent Workspace Directory** | Remote path on the VM where Jenkins will run builds, e.g., `/tmp/jenkins` |
| **Provisioning Mode** | `Clone existing VM` or `Create from catalog` (see below) |
| **VM Ready Timeout (s)** | Maximum seconds to wait for the VM to become usable (default: 600) |
| **VM Ready Poll Interval (s)** | How often Jenkins checks readiness (default: 10) |

![Template configuration](images/template-label.png)

#### Clone existing VM

Select **Clone existing VM** and supply:

- **Base VM Name or VM ID** — the name or ID of the golden VM registered in the Parallels DevOps service.

This is the simplest path when you manage your own golden images on a host.

#### Create from catalog

Select **Create from catalog** and supply:

| Field | Example |
|---|---|
| **Catalog ID** | `ubuntu-22-arm64` |
| **Catalog Version** | `latest` |
| **Catalog URL** | `http://192.168.1.100:8080` |
| **Architecture** | `arm64` or `x86_64` |
| **Catalog Credentials** | Credential for catalog access (if required) |

![Catalog-based provisioning template](images/catalog-based.png)

#### Advanced SSH settings

Expand **Advanced** on the template to tune bootstrap behavior:

![Advanced SSH settings](images/cutsom-ssh.png)

| Setting | When to change |
|---|---|
| **SSH Port** | Non-default SSH port on the image |
| **Java Path** | Java is not on the default `PATH` inside the VM |
| **JVM Options** | Remoting JVM needs extra flags |
| **SSH Retries** | Image needs more attempts before SSH is ready |
| **SSH Retry Delay (seconds)** | Image needs extra boot / cloud-init time |

### Step 4: Test the Configuration

Before saving, click **Test Connection** at the bottom of the cloud configuration form.

A green success banner confirms that Jenkins can reach `prl-devops-service` with the credentials you provided. If it fails, see [Test Connection fails with 401](#test-connection-fails-with-401) in the Troubleshooting section.

### Step 5: Save the Configuration

Click **Save** at the bottom of the page. The cloud now appears under **Manage Jenkins → Clouds**.

![Configured clouds in Jenkins](images/created.png)

---

## Running Your First Dynamic Build

### Create a freestyle smoke-test job

1. Go to **Dashboard → New Item**, enter a name such as `smoke-test`, select **Freestyle project**, and click **OK**.
2. Under **General**, tick **Restrict where this project can be run** and enter the label you defined in the template (e.g., `macos`).
3. Under **Build Steps**, add **Execute shell** (Linux/macOS) or **Execute Windows batch command** (Windows) with the following content:

    ```bash
    echo "=== Smoke test ==="
    hostname
    uname -a
    java -version
    echo "=== Done ==="
    ```

4. Click **Save**, then **Build Now**.

### Observe the provisioning sequence

1. Open **Dashboard → Manage Jenkins → Nodes**. Within a few seconds a new node named `prl-<uuid>` will appear with status **Launching**.
2. Click the node name, then **Log** to follow the SSH bootstrap in real time.
3. Once the node status changes to **Online**, the build will start and the console output will show the `hostname` and `uname` results.
4. After the build completes, the `prl-<uuid>` node disappears from the Nodes list and the backing VM is removed by the plugin automatically.

### Equivalent Pipeline job

For a Declarative Pipeline, create a **Pipeline** job with this script:

```groovy
pipeline {
    agent { label 'macos' }

    stages {
        stage('Smoke test') {
            steps {
                sh 'hostname'
                sh 'uname -a'
                sh 'java -version'
            }
        }
    }
}
```

---

## Troubleshooting

### Test Connection fails with 401

**Symptom**: Clicking **Test Connection** returns `HTTP 401 Unauthorized`.

**Diagnosis**: The credential stored in Jenkins does not match what `prl-devops-service` expects.

**Resolution**:
1. First check if the service is reachable at all (no auth required):
   ```bash
   curl http://<service-host>:8080/api/health/system
   ```
   A 200 response means the service is up. If this fails, the service is not reachable from the Jenkins host.

2. Then verify your token is valid (bearer auth required):
   ```bash
   curl -H "Authorization: Bearer <your-token>" http://<service-host>:8080/api/health/probe
   ```
   A 200 response confirms the token is accepted. A 401 here means the token is wrong or expired.

3. If the token is invalid, reset it in the Parallels DevOps Service admin console.
4. Update the Jenkins credential (**Manage Jenkins → Credentials**) with the new token and retest.

---

### VM stuck in PENDING state

**Symptom**: The `prl-<uuid>` node stays in **Launching** for longer than the configured timeout and the build eventually fails with `Timed out waiting for VM`.

**Diagnosis**: The VM was requested but never became reachable.

**Resolution**:
1. Check `prl-devops-service` logs for provisioning errors.
2. Verify the base VM name or catalog ID is correct — a typo causes silent provisioning failure on some service versions.
3. Increase **VM Ready Timeout (s)** if the image simply boots slowly.
4. Ensure the Jenkins host can reach the IP range that the service assigns to new VMs.

---

### SSH: Connection refused

**Symptom**: The node log shows repeated `Connection refused` or `Connection timed out` on port 22.

**Diagnosis**: SSH is not yet up on the VM, or it listens on a non-default port.

**Resolution**:
1. SSH into the VM manually from the Jenkins host to confirm reachability:
   ```bash
   ssh -p <port> <user>@<vm-ip>
   ```
2. If SSH is on a non-default port, expand **Advanced** on the template and set **SSH Port** accordingly.
3. Increase **SSH Retries** and **SSH Retry Delay (seconds)** to give cloud-init more time to start `sshd`.

---

### Agent goes offline immediately after coming online

**Symptom**: The node briefly shows **Online**, then flips to **Offline** and the build fails with `Remote call failed` or a remoting error.

**Diagnosis**: Jenkins launched the agent process but it crashed immediately, usually due to a Java issue.

**Resolution**:
1. Click the node name → **Log** and scroll to the remoting startup output.
2. If you see `java: not found`, set **Java Path** in the template's Advanced section to the full path, e.g., `/usr/lib/jvm/java-17-openjdk/bin/java`.
3. If you see `OutOfMemoryError`, add `-Xmx256m` (or larger) to **JVM Options**.
4. Confirm the SSH credential has the correct username for the image.

---

### VM not deleted after build (ONE_SHOT)

**Symptom**: After a successful build the `prl-<uuid>` node remains in Jenkins and the VM keeps running.

**Diagnosis**: The one-shot cleanup did not trigger, usually because the node was never cleanly disconnected.

**Resolution**:
1. The background reconciler runs on a periodic schedule and will eventually delete stale offline nodes. Allow a few minutes.
2. To force immediate cleanup, delete the node manually from **Manage Jenkins → Nodes** — the plugin will then remove the backing VM.
3. If VMs pile up consistently, check for exceptions in the Jenkins system log under **Manage Jenkins → System Log**.

---

### Idle timeout not triggering cleanup

**Symptom**: A VM and its agent node remain alive after a build has finished.

**Diagnosis**: The plugin uses a fixed one-shot retention strategy (`PrlDevopsRetentionStrategy`) — there is no configurable idle timeout. Cleanup is triggered automatically once the agent becomes idle after completing a build. If cleanup has not happened, the agent has either not yet become idle, or the build did not finish cleanly.

**Resolution**:
1. Wait a moment — the strategy checks agent state on a 1-minute cycle, so there can be a brief delay between build completion and VM removal.
2. If the node is stuck in a non-idle state, check the Jenkins system log under **Manage Jenkins → System Log** for errors from `PrlDevopsRetentionStrategy`.
3. If the VM is still running after several minutes, follow the steps in [VM not deleted after build (ONE_SHOT)](#vm-not-deleted-after-build-one_shot) to force cleanup manually.

---

## Further Reading

- [README — full plugin reference](../README.md)
- [CONTRIBUTING.md — building and running locally](../CONTRIBUTING.md)
- [docs/setup-guide.md — local development environment setup](setup-guide.md)
- [Parallels DevOps Service quick-start](https://parallels.github.io/prl-devops-service/quick-start/)
- [Jenkins Configuration as Code examples](../README.md#configuration-as-code)
