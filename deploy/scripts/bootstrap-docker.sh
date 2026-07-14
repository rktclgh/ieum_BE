#!/usr/bin/env bash
set -euo pipefail

if command -v docker >/dev/null 2>&1 \
  && sudo docker info >/dev/null 2>&1 \
  && sudo docker compose version >/dev/null 2>&1; then
  exit 0
fi

. /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then
  echo "This bootstrap script supports Ubuntu only." >&2
  exit 1
fi

sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

arch="$(dpkg --print-architecture)"
codename="${VERSION_CODENAME}"
echo "deb [arch=${arch} signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${codename} stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null

sudo apt-get update
sudo apt-get install -y \
  docker-ce \
  docker-ce-cli \
  containerd.io \
  docker-buildx-plugin \
  docker-compose-plugin
sudo systemctl enable --now docker
sudo docker compose version
