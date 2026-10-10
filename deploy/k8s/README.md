# Local Kubernetes deployment (k3d)

Reproduces `docker-compose.full.yml` as a local Kubernetes deployment, with
**two replicas of every application service**. The manifests live in
`deploy/k8s` (kustomize `base` + `local` overlay) and the helper scripts in
`scripts/k8s`.

## Topology

| Component | Workload | Replicas | Notes |
|---|---|---|---|
| Kafka 3.8 (KRaft, single broker) | StatefulSet + headless Service `kafka` | 1 | internal listener `kafka:19092` |
| Topic bootstrap | Job `kafka-init` | run-once | 4 topics, 3 partitions, RF=1 |
| Kafka UI | Deployment + Ingress | 1 | http://kafka-ui.localhost:8081 |
| PostgreSQL 16 | StatefulSet + Service `postgres` | 1 | shared by the stateful services |
| payment-api | Deployment + Service + Ingress | **2** | http://payments.localhost:8081 |
| payment-processor | Deployment + Service | **2** | shared Postgres, outbox leasing |
| notification-service | Deployment + Service | **2** | shared Postgres, dedup |
| Loki | StatefulSet + Service `loki` | 1 | log storage, 7-day retention, 2Gi PVC |
| Alloy | DaemonSet | per node | tails container logs → Loki |

## Two replicas, always

Three mechanisms keep every application service at exactly two instances:

* `replicas: 2` in each Deployment;
* a `PodDisruptionBudget` with `minAvailable: 1`, so voluntary disruptions
  (node drains, rolling updates) never take both replicas down at once;
* a `HorizontalPodAutoscaler` with `minReplicas == maxReplicas == 2`, which
  re-reconciles the count back to 2 even if the Deployment is scaled by hand.
  (`metrics-server` ships with k3s and provides the CPU metric the HPA reads.)

## Why PostgreSQL for the two-instance services

`payment-processor` and `notification-service` keep durable state (idempotency,
transactional outbox, notification dedup). The default profile stores that in a
single-machine H2 file, which two pods cannot share. The `k8s` Spring profile
(`application-k8s.yml`) points both services at the shared PostgreSQL and loads
`schema-postgres.sql`. The outbox store detects PostgreSQL and claims rows with
`FOR UPDATE SKIP LOCKED` plus a short lease, so two relay instances never
publish the same row.

## Prerequisites

Docker (running), `k3d`, `kubectl`. No Maven or Helm required — images are
built by the repository `Dockerfile` and imported into the cluster.

## Secrets

The base manifests only reference secrets; they never contain credentials:

* `postgres-credentials` (`username`/`password`) is consumed by Postgres and
  both stateful services. The base ships no values — provision it out-of-band
  (template: `base/postgres/secret.example.yaml`):

  ```bash
  kubectl -n payments create secret generic postgres-credentials \
    --from-literal=username=payments \
    --from-literal=password="$(openssl rand -base64 24)"
  ```

  The local overlay ships a development copy in
  `overlays/local/postgres-secret.yaml`.

* The base Grafana Deployment configures no admin login (Grafana generates a
  random password on first boot). The local overlay adds a known `admin/admin`
  dev login via `overlays/local/grafana-admin-patch.yaml`.

`payment-processor` and `notification-service` read the database password from
`DB_PASSWORD` with **no default**, so a missing secret fails fast instead of
silently falling back to a weak credential.

## Usage

```bash
scripts/k8s/00-up.sh        # create the k3d cluster (1 server + 2 agents)
scripts/k8s/10-images.sh    # build + import the three service images
scripts/k8s/20-deploy.sh    # apply the overlay and wait for readiness
scripts/k8s/30-verify.sh    # assert 2/2 replicas per service
scripts/k8s/40-smoke.sh     # send a couple of payments via the ingress
scripts/k8s/50-failover.sh  # kill a processor replica; assert no double charge
scripts/k8s/70-metrics-demo.sh  # drive traffic for the metric dashboards
scripts/k8s/80-logs-demo.sh     # drive traffic for the log dashboard
scripts/k8s/90-teardown.sh  # delete the cluster
```

Handy commands:

```bash
kubectl -n payments get pods
kubectl -n payments logs -f deploy/payment-processor
kubectl -n payments get pods -o wide   # see the replicas spread across nodes
kubectl -n payments get hpa            # the pins that keep 2 replicas in place
```

## Browsing with k9s

`k9s` (installed at `/opt/homebrew/bin/k9s`) talks to the same kubeconfig, so
just launch it after `00-up.sh` switched the context to `k3d-payments`:

```bash
k9s                 # uses the current context (k3d-payments)
k9s -n payments     # start straight in the payments namespace
k9s --context k3d-payments --namespace payments
```

## Demo scenarios

Kubernetes variants of the compose scenarios live next to the operational
scripts:

* `scripts/k8s/curl/` — the seven curl scenarios from `scripts/curl-full`,
  pointing at the ingress (`API_URL`, default `http://payments.localhost:8081`).
* `scripts/k8s/kafka/` — the topic/consumer helpers from `scripts/kafka-full`,
  running inside the `kafka-0` pod through `kubectl exec`.

```bash
scripts/k8s/curl/00-send-many-payments-curl.sh
scripts/k8s/curl/20-send-duplicate-payment-curl.sh
scripts/k8s/curl/60-send-concurrent-duplicate-curl.sh
scripts/k8s/kafka/00-kafka-topics-list.sh
scripts/k8s/kafka/40-kafka-dlt-consumer-start.sh   # Ctrl-C to stop
scripts/k8s/kafka/90-full-logs.sh                  # Ctrl-C to stop
```

The duplicate scenarios (20, 60) also print the exact `psql` command to confirm
a single charge and a single outbox row per `paymentId` in the shared
PostgreSQL.

## Logs

The application pods emit **ECS JSON** (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`,
set in each Deployment). An **Alloy DaemonSet** tails every container's log,
parses the JSON and pushes it to **Loki**; Grafana ships a `Loki` datasource and
the **Payments Logs** dashboard. The `correlation_id` / `payment_id` fields
travel as Loki structured metadata, so they can be used in queries without
becoming index labels:

```logql
{app="payment-processor"} | json | correlation_id="<id>"
```

Loki has no ingress; query it through Grafana
(http://grafana.localhost:8081 → Explore → Loki) or port-forward it directly:

```bash
kubectl -n payments port-forward svc/loki 3100:3100
```

## Endpoints

* `payment-api` — http://payments.localhost:8081/payments
* Kafka UI — http://kafka-ui.localhost:8081
* Grafana (metrics + logs) — http://grafana.localhost:8081

`*.localhost` resolves to `127.0.0.1` on macOS, so no `/etc/hosts` edits are
needed. If the ingress is not reachable, fall back to port-forwarding:

```bash
kubectl -n payments port-forward svc/payment-api 8080:80
```
