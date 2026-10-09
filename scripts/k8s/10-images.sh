#!/usr/bin/env bash
# Builds the three service images from the repository Dockerfile and imports
# them straight into the k3d cluster, so no registry is required.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
cluster="${K3D_CLUSTER:-payments}"
tag="${IMAGE_TAG:-local}"

modules=(payment-api payment-processor notification-service)
images=()

for module in "${modules[@]}"; do
  image="payments/${module}:${tag}"
  echo "### building ${image}"
  docker build --build-arg "MODULE=${module}" -t "${image}" "$repo_root"
  images+=("${image}")
done

echo "### importing images into k3d cluster '${cluster}'"
k3d image import "${images[@]}" -c "$cluster"
