# Infrastructure Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract external connections, profiles, persistence, and resource lifecycles from the largest panels while keeping the existing panels as behavior-preserving Swing adapters.

**Architecture:** Feature application services expose synchronous or asynchronous use cases; `infra` owns JDBC, Kafka, Kubernetes, JSch, Redis, HTTP, and remote-session adapters. Every adapter implements `AutoCloseable` where it owns a connection, executor, timer, listener, or temporary file.

**Tech Stack:** Java 8, JDBC, Jedis, Kafka client, ice4j, JSch, Java-WebSocket, existing configuration and vault services.

---

## Shared lifecycle contract

- [ ] **Step 1: Define `ManagedResource`**

Create `src/main/java/com/aqishi/toolbox/infra/ManagedResource.java`:

```java
public interface ManagedResource extends AutoCloseable {
    boolean isOpen();
    @Override void close();
}
```

`close()` must be idempotent and must not throw checked exceptions. Document the owning thread and shutdown behavior on each implementation.

- [ ] **Step 2: Define service error boundaries**

Create `src/main/java/com/aqishi/toolbox/infra/InfrastructureException.java` with a stable `Kind` enum (`CONFIGURATION`, `CONNECTION`, `TIMEOUT`, `PROTOCOL`, `CANCELLED`, `CLOSED`) and the original cause. UI adapters map kinds to localized messages without showing stack traces.

## Kubernetes

**Files:** `feature/cloud/ui/K8sManagerPanel.java`, `feature/cloud/ui/K8sPanel.java`, create `infra/kubernetes/KubernetesClient.java`, `infra/kubernetes/KubeconfigStore.java`, `feature/cloud/application/KubernetesService.java`, `feature/cloud/domain/KubernetesResourceRef.java`.

- [ ] **Step 3: Extract kubeconfig/profile storage**

Move profile load/save/selection code from the panel into `KubeconfigStore`, preserving existing configuration keys and imported file formats. Expose `List<ClusterProfile> list()`, `void save(ClusterProfile)`, and `void delete(String id)`.

- [ ] **Step 4: Extract REST/resource operations**

Move API version, namespace, resource listing, logs, exec, upload, and download code into `KubernetesClient`. Keep HTTP headers, TLS behavior, and path construction unchanged. `KubernetesService` composes client calls and returns immutable result models.

- [ ] **Step 5: Reduce the panels to adapters**

The panels retain Swing models, buttons, EDT updates, and localized feedback only. They receive `KubernetesService` through `ToolboxContext`; they do not instantiate HTTP clients or read kubeconfig files directly.

## Kafka

**Files:** `feature/data/ui/KafkaPanel.java`, create `infra/kafka/KafkaClient.java`, `feature/data/application/KafkaService.java`, `feature/data/application/KafkaProfileStore.java`, `feature/data/domain/KafkaSubscriptionState.java`.

- [ ] **Step 6: Extract profile and tunnel handling**

Move profile serialization and SSH tunnel lifecycle into `KafkaProfileStore` and `KafkaClient`. Preserve topic, consumer-group, bootstrap-server, and existing tunnel configuration keys.

- [ ] **Step 7: Extract topic/message operations**

Move topic listing, consumer-group lag, message fetch/publish, active-member, and partition-assignment operations into `KafkaService`. Return immutable rows or snapshots so Swing tables do not depend on Kafka client classes.

- [ ] **Step 8: Extract subscription state**

Move polling timers and subscription state transitions into `KafkaSubscriptionState`; expose `start()`, `stop()`, and `snapshot()`. Ensure `stop()` closes consumers and executors exactly once.

## Database

**Files:** `feature/data/ui/DatabasePanel.java`, create `infra/database/DatabaseConnectionFactory.java`, `infra/database/DatabaseProfileStore.java`, `feature/data/application/DatabaseMetadataService.java`, `feature/data/application/SqlExecutionService.java`, `feature/data/domain/QueryResult.java`.

- [ ] **Step 9: Extract connection/profile management**

Move driver selection, JDBC URL construction, profile persistence, and connection close handling into `DatabaseConnectionFactory` and `DatabaseProfileStore`. Keep supported drivers and profile keys unchanged.

- [ ] **Step 10: Extract metadata and SQL execution**

Move schema/table/column discovery, autocomplete data, query execution, cancellation, and result conversion into the two application services. `QueryResult` must contain column metadata and rows without Swing table models.

- [ ] **Step 11: Reduce DatabasePanel**

Keep only form state, table model binding, action listeners, and localized errors. All JDBC calls must leave the EDT as they did before; no new synchronous network call may be added.

## Other external services

- [ ] **Step 12: Extract Redis and SSH lifecycles**

Create `infra/redis/RedisClient.java` and `infra/ssh/SshSessionManager.java`; move Jedis/JSCH creation, reconnect, and close logic out of `RedisPanel` and `SshClientPanel`. Preserve existing tunnel support classes and close them from `MainFrame` through `ManagedResource`.

- [ ] **Step 13: Extract remote desktop transport**

Place ICE/STUN/TCP/UPnP/NAT-PMP and signal-client lifecycle under `infra/remote`; leave `RemoteDesktopPanel` and `RemoteControlWindow` as views over a `RemoteSessionService`. Keep protocol version constants and persisted server settings unchanged.

- [ ] **Step 14: Extract HTTP/WebSocket/MQTT clients**

Place connection objects under `infra/network`; panels receive small interfaces for request/send/subscribe and remain responsible for EDT presentation. Preserve timeout and cancellation behavior.

## Static verification and checkpoint

- [ ] **Step 15: Scan for leaked infrastructure imports**

Run:

```powershell
rg -n "java\.sql|org\.apache\.kafka|redis\.clients|com\.github\.mwiede|org\.java_websocket|org\.jitsi" src/main/java/com/aqishi/toolbox/feature
```

Expected: only explicitly documented adapter interfaces or DTO converters remain; concrete client construction appears under `infra`.

- [ ] **Step 16: Compile and commit**

```bash
mvn -Dmaven.test.skip=true package
git diff --check
git add src/main/java
git commit -m "refactor: separate infrastructure services from panels"
```

