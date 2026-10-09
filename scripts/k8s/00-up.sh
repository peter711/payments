#!/usr/bin/env bash
# Creates the local k3d cluster used to run the full stack in Kubernetes.
#
# 1 server + 2 agents so the two replicas of every service can be spread across
# separate nodes. Traefik (bundled with k3d) publishes the ingress on the host
# ports below.
set -euo pipefail

cluster="${K3D_CLUSTER:-payments}"
http_port="${K3D_HTTP_PORT:-8081}"
https_port="${K3D_HTTPS_PORT:-8443}"

if k3d cluster list "$cluster" 2>/dev/null | grep -q "^${cluster} "; then
  echo "k3d cluster '$cluster' already exists"
else
  k3d cluster create "$cluster" \
    --servers 1 \
    --agents 2 \
    --port "${http_port}:80@loadbalancer" \
    --port "${https_port}:443@loadbalancer"
fi

kubectl config use-context "k3d-${cluster}" >/dev/null
kubectl get nodes
