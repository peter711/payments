#!/usr/bin/env bash
# Deletes the local k3d cluster and everything running in it.
set -euo pipefail

cluster="${K3D_CLUSTER:-payments}"
k3d cluster delete "$cluster"
