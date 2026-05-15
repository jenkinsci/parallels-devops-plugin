# Manual GUI Testing Guide — Parallels DevOps Jenkins Plugin

This guide walks through testing the `provision()` engine end-to-end in a local Jenkins instance started via `make run`.

---

## Part 0 — Concepts: How Templates and VMs Relate

### The Mental Model

```
Parallels DevOps Service                    Jenkins Plugin
─────────────────────────────────────────   ──────────────────────────────────────
  [ Base VM: "macOS-Sonoma-base" ]   ←───   AgentTemplate.baseVmName = "macOS-Sonoma-base"
        (exists, powered OFF)                AgentTemplate.templateLabel = "macos-sonoma"
              │
              │  PUT /api/v1/machines/{baseVmName}/clone
              ▼
  [ Clone: "jenkins-macos-sonoma-1713..." ]  ← new UUID returned as vmId
        (booting up)
              │
              │  GET /api/v1/machines/{vmId}/status  (polled every 10s)
              ▼
  [ Clone: running, ip_configured = "192.168.x.x" ]
              │
              │  SSHLauncher connects to 192.168.x.x:22
              ▼
  Jenkins agent "prl-{vmId}" comes ONLINE
```

### Key Mapping

| Jenkins Plugin concept | What it means in Parallels DevOps |
|---|---|
| `AgentTemplate.baseVmName` | The **exact name** of a VM that already exists on the host. This is the source being cloned. It does **not** have to be running — stopped is fine and preferred. |
| `AgentTemplate.templateLabel` | A Jenkins label string. Jobs that declare `label 'macos-sonoma'` get routed here. Has **no meaning** on the Parallels side. |
| Clone request | Plugin calls `PUT /api/v1/machines/{baseVmName}/clone`. DevOps Service duplicates the VM disk, assigns it a new UUID, and starts it. |
| `vmId` in `CloneResponse` | The UUID of the **newly created clone** — not the base VM. Jenkins tracks this clone for its entire lifetime. |
| `ip_configured` in `VmStatusResponse` | The IP address the plugin SSHes into. The clone must have the SSH daemon running and the OS must have reported its IP to prl-devops-service. |

### What you must prepare on the Parallels DevOps side

You need **exactly one base VM** per `AgentTemplate`. That base VM is:

- Created and configured once, manually.
- Kept **stopped** (the clone API works on a stopped VM and starts the clone fresh each time).
- Named exactly what you put in `baseVmName` — e.g. `macOS-Sonoma-base`.
- Has SSH enabled, a known user account, and Java pre-installed.

The plugin never touches the base VM itself. Every build gets a **fresh clone**.

---

## Part 1 — Prepare the Base macOS VM

### 1.1 Create the VM in Parallels DevOps Service

Use the prl-devops-service UI or API to create a new macOS virtual machine. A minimal configuration:

| Setting | Recommended value |
|---|---|
| Name | `macOS-Sonoma-base` ← this must exactly match `baseVmName` in the template |
| macOS version | Sonoma 14.x (or whichever you target) |
| vCPUs | 2 |
| RAM | 4 GB |
| Disk | 40 GB (thin provisioned) |

Boot the VM and complete the macOS first-run setup (language, account, etc.).

### 1.2 Create a local user account

During macOS setup, create an account. Recommended for CI:

| Field | Value |
|---|---|
| Full name | `Jenkins Agent` |
| Account name (short) | `parallels` |
| Password | something strong, stored in Jenkins credentials |

> **Tip**: Use the same username+password or SSH key across all your base VMs — it simplifies credential management in Jenkins.

### 1.3 Enable Remote Login (SSH)

This is the single most important step. Without SSH, Jenkins cannot connect to the agent.

1. Open **System Settings → General → Sharing**.
2. Turn on **Remote Login**.
3. Set **Allow access for**: `All users` (or specifically the `parallels` user).
4. Note the SSH address shown — e.g. `ssh parallels@192.168.x.x`.

Verify from inside the VM's terminal:

```bash
sudo systemsetup -getremotelogin
# Expected: Remote Login: On

sudo launchctl list | grep ssh
# Expected: com.openssh.sshd should appear
```

### 1.4 Install Java (required for Jenkins agent)

The Jenkins JNLP/SSH agent needs Java on the VM. Install a headless JDK:

```bash
# Option A — Homebrew (easiest)
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
brew install openjdk@21

# Make it the system default
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
    /Library/Java/JavaVirtualMachines/openjdk-21.jdk
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zprofile
source ~/.zprofile

# Verify
java -version
# Expected: openjdk version "21.x.x" ...
```

```bash
# Option B — download from Adoptium if no internet on VM
# Install the .pkg from https://adoptium.net manually
```

### 1.5 Create the Jenkins agent working directory

```bash
mkdir -p /Users/parallels/jenkins-agent
chmod 755 /Users/parallels/jenkins-agent
```

This path must match `remoteFs` in the `AgentTemplate` config.

### 1.6 Enable automatic login (optional but useful for CI VMs)

Prevents the VM from sitting at the login screen after a reboot:

1. **System Settings → General → Login Options** (or **Users & Groups → Login Options** on older macOS).
2. Set **Automatic login** to the `parallels` user.

### 1.7 Disable sleep / screen saver

CI VMs should never sleep:

```bash
sudo pmset -a sleep 0 disksleep 0 displaysleep 0 autopoweroff 0
```

### 1.8 (Optional) Add an SSH authorized key

If you want key-based auth instead of password (recommended for production):

```bash
# On your dev machine — generate a key pair if needed
ssh-keygen -t ed25519 -f ~/.ssh/prl_jenkins_agent -N ""

# Copy the public key to the VM
ssh-copy-id -i ~/.ssh/prl_jenkins_agent.pub parallels@<VM-IP>

# Verify
ssh -i ~/.ssh/prl_jenkins_agent parallels@<VM-IP> "echo connected"
```

You'll store the **private key** (`prl_jenkins_agent`) as an SSH credential in Jenkins (Step 2 of the testing guide below).

### 1.9 Verify SSH from your Mac host before cloning

```bash
ssh parallels@<VM-IP> "java -version && echo OK"
# Both outputs should appear with no errors
```

### 1.10 Shut down the VM and rename it correctly

```bash
# Inside the VM terminal
sudo shutdown -h now
```

In the prl-devops-service interface, confirm the VM name is **exactly** `macOS-Sonoma-base` (or whatever you plan to put in `baseVmName`). The clone API uses this name as the source ID, so spelling must be exact.

The base VM is now ready. You should never manually start it again — the plugin will always clone it.

---

## Prerequisites (for the Jenkins side)

- Java 21 and Maven installed (`java -version`, `mvn -version`)
- A running **Parallels DevOps Service** instance (or a mock server — see Step 0 below)
- The base VM you intend to clone must already exist on the host/orchestrator
- SSH credentials for the cloned VM are available (username + private key or password)

---

## Step 0 — Start Jenkins locally

```bash
make run
```

This runs `mvn hpi:run` and boots Jenkins on `http://localhost:8080`.  
Wait for the line:

```
INFO: Jenkins is fully up and running
```

Open `http://localhost:8080` in your browser. Jenkins starts with no security configured in dev mode.

---

## Step 1 — Add a Bearer Token credential

The cloud plugin authenticates to the Parallels DevOps Service API using a **Secret Text** credential.

1. Go to **Manage Jenkins → Credentials → System → Global credentials (unrestricted)**.
2. Click **Add Credentials**.
3. Set **Kind** to `Secret text`.
4. Paste your Parallels DevOps Service bearer token into the **Secret** field.
5. Set **ID** to `prl-devops-token` (you'll reference this ID later).
6. Click **Create**.

---

## Step 2 — Add SSH credentials for the cloned VM

Jenkins will SSH into the cloned VM to launch the agent. It needs OS-level credentials.

1. Still in **Global credentials**, click **Add Credentials** again.
2. Set **Kind** to `SSH Username with private key` (or `Username with password`).
3. Enter the username that exists inside the VM (e.g., `parallels`).
4. Enter the private key or password.
5. Set **ID** to `prl-vm-ssh-cred`.
6. Click **Create**.

---

## Step 3 — Configure the Parallels DevOps Cloud

1. Go to **Manage Jenkins → Clouds**.
2. Click **New cloud**.
3. Enter a name (e.g., `parallels-local`) and select **Parallels Devops Cloud**.
4. Click **Create**.

You are now in the cloud configuration form. Fill in the fields:

| Field | Value |
|---|---|
| **Service URL** | URL of your prl-devops-service, e.g. `http://192.168.1.10:8090` |
| **API Credentials** | Select `prl-devops-token` (created in Step 1) |
| **Connection Mode** | `HOST` (talking to a single host) or `ORCHESTRATOR` |
| **Max Concurrent Agents** | `3` (start small for testing) |

5. Click **Test Connection** — you should see a green **"Connected"** message. If not, verify the URL and token before continuing.

---

## Step 4 — Add an Agent Template

The template tells the plugin which VM to clone and what label to assign.

Scroll down to the **VM Template** section and click **Add**.  
Fill in the template fields:

| Field | Value |
|---|---|
| **Template Label** | `macos-sonoma` (must match the job's label expression exactly) |
| **Base VM Name** | Exact name of the source VM in DevOps Service, e.g. `macOS-Sonoma-base` |
| **SSH Credentials** | Select `prl-vm-ssh-cred` (created in Step 2) |
| **Remote FS Root** | `/Users/parallels/jenkins-agent` (must exist on the cloned VM) |
| **Number of Executors** | `1` |

6. Click **Save**.

---

## Step 5 — Verify `canProvision` in the cloud log

1. Go to **Manage Jenkins → System Log → All Jenkins Logs**.
2. Filter for `PrlDevops` — you should see no errors.
3. The cloud is now registered and Jenkins will call `canProvision()` when a job with label `macos-sonoma` enters the queue.

---

## Step 6 — Create a test Freestyle job

1. Click **New Item** on the Jenkins dashboard.
2. Enter name `provision-test`, select **Freestyle project**, click **OK**.
3. In **General**, check **Restrict where this project can be run**.
4. Enter `macos-sonoma` in the **Label Expression** field.
5. Under **Build Steps**, click **Add build step → Execute shell** and enter:
   ```bash
   echo "Running on: $(hostname)"
   echo "IP: $(ipconfig getifaddr en0 2>/dev/null || ifconfig | grep 'inet ' | grep -v '127.0.0.1' | head -1)"
   echo "Agent node name: $NODE_NAME"
   echo "Agent labels: $NODE_LABELS"
   sleep 5  # Keep the agent busy for a bit to test retention strategy
   ```
6. Click **Save**.

---

## Step 7 — Trigger provisioning

1. On the job page, click **Build Now**.
2. The job enters the **Build Queue** because no agent with label `macos-sonoma` is available yet.
3. Jenkins polls all registered clouds — your `PrlDevopsCloud.canProvision()` returns `true`, so Jenkins calls `provision()`.

---

## Step 8 — Watch the provision logs

Open **Manage Jenkins → System Log → All Jenkins Logs** and filter by `PrlDevops`.

You should see a sequence like:

```
[PrlDevops] Requesting clone of 'macOS-Sonoma-base' for label 'macos-sonoma'
[PrlDevops] Clone requested; VM ID=<vm-uuid>. Submitting planned node future.
[PrlDevops] Waiting for VM <vm-uuid> to become ready (timeout=PT5M, interval=PT10S)
[PrlDevops] VM <vm-uuid> is ready at IP 192.168.x.x
```

If you see an error followed by `Successfully deleted orphaned VM`, the VM booted but failed to become ready within the timeout — increase `vmReadyTimeoutSeconds` on the template.

---

## Step 9 — Confirm the agent comes online

1. Go to the Jenkins dashboard.
2. You should see a new node named `prl-<vm-uuid>` appear under **Build Executor Status**.
3. Its status will transition from **Offline** → **Connecting** → **Online**.
4. Once online, Jenkins dispatches the queued `provision-test` job to it automatically.

---

## Step 10 — Verify the job runs and succeeds

1. Click **provision-test** → **#1** (or whichever build number).
2. Open **Console Output**.
3. Confirm it shows output like:
   ```
   Running on: prl-<vm-uuid>
   IP: 192.168.x.x
   Finished: SUCCESS
   ```
4. The build node should match `prl-<vm-uuid>` in the header line: `Building remotely on prl-<vm-uuid>`.

---

## Step 11 — Verify maxAgents cap

1. Edit the cloud config and set **Max Concurrent Agents** to `1`.
2. Queue **two** builds simultaneously (click **Build Now** twice quickly).
3. The first build should trigger provisioning; the second should remain queued.
4. In the logs you should see:
   ```
   [PrlDevops] maxAgents cap reached (active=1, max=1). Skipping provisioning.
   ```
5. Once the first build completes and its agent is released, the second build provisions a new VM.

---

## Step 12 — Test no-matching-label behaviour

1. Create a second Freestyle job with label `windows-11` (no template exists for this label).
2. Queue a build.
3. Confirm in the logs that `canProvision` returns false for this label and no clone is requested.
4. The job should remain permanently queued with the message **"waiting for next available executor"** — this is correct behaviour.

---

## Cleanup

To stop the local Jenkins server, press `Ctrl+C` in the terminal running `make run`.  
The `work/` directory holds all Jenkins data. To start completely fresh:

```bash
make clean
```
