#!/usr/bin/env bash
# Applies the local kustomize overlay and waits for every workload to be ready.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
overlay="$repo_root/deploy/k8s/overlays/local"
namespace="${NAMESPACE:-payments}"

kubectl apply -k "$overlay"

echo "### waiting for kafka"
kubectl -n "$namespace" rollout status statefulset/kafka --timeout=300s
kubectl -n "$namespace" wait --for=condition=complete job/kafka-init --timeout=300s

echo "### waiting for postgres"
kubectl -n "$namespace" rollout status statefulset/postgres --timeout=300s

echo "### waiting for the applications and kafka-ui"
kubectl -n "$namespace" rollout status deployment/kafka-ui --timeout=300s
kubectl -n "$namespace" rollout status deployment/payment-api --timeout=300s
kubectl -n "$namespace" rollout status deployment/payment-processor --timeout=300s
kubectl -n "$namespace" rollout status deployment/notification-service --timeout=300s

echo "### waiting for the monitoring stack"
kubectl -n "$namespace" rollout status deployment/prometheus --timeout=300s
kubectl -n "$namespace" rollout status deployment/grafana --timeout=300s

echo
echo "Deployment ready. Run scripts/k8s/30-verify.sh to assert 2 ready replicas per service."
echo "Grafana: http://grafana.localhost:8081 (admin/admin)"
echo "Generate dashboard traffic with: scripts/k8s/70-metrics-demo.sh"
