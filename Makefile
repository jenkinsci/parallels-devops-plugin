.PHONY: all build test verify run clean package

# Force Maven to use JDK 21 (fixes 'Unsupported class file major version 69 / Java 25' bugs)
export JAVA_HOME := $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo "/opt/homebrew/opt/openjdk@21")

# Configurable environment variables
JENKINS_PORT     ?= 8080
JENKINS_VERSION  ?=

# Build extra args: inject jenkins.version only when the variable is set
_VERSION_ARG := $(if $(JENKINS_VERSION),-Djenkins.version=$(JENKINS_VERSION),)

# Default target
all: build

# Compile the plugin source (no packaging overhead)
build:
	mvn clean compile

# Run unit tests
test:
	mvn test

# Boot the local Jenkins development server
# Override port:    make run JENKINS_PORT=9090
# Override version: make run JENKINS_VERSION=2.479.1
# Binds to 0.0.0.0 so VMs on the local network can reach Jenkins at the Mac's LAN IP.
run:
	mvn hpi:run -DskipTests -Dport=$(JENKINS_PORT) -Dhpi.host=0.0.0.0 $(_VERSION_ARG)

# Package the final .hpi file under target/
package:
	mvn clean package -DskipTests

# Run the full CI check locally: tests + SpotBugs (mirrors what buildPlugin() does in CI)
verify:
	mvn verify

# Wipe all compiled Java artifacts and purge the Jenkins cache
clean:
	mvn clean
	rm -rf work/
