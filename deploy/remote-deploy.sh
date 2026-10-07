#!/usr/bin/env bash
set -euo pipefail

new_tag="${1:?Image tag is required}"
old_tag=""
ghcr_username="${GHCR_USERNAME:?GHCR_USERNAME is required}"
IFS= read -r ghcr_token || true
export DOCKER_CONFIG="$(mktemp -d)"
trap 'rm -rf "$DOCKER_CONFIG"' EXIT
printf '%s' "$ghcr_token" | docker login ghcr.io -u "$ghcr_username" --password-stdin
unset ghcr_token

if [[ -f .deployed-image-tag ]]; then
	old_tag="$(cat .deployed-image-tag)"
fi

if ! IMAGE_TAG="$new_tag" docker compose up -d --remove-orphans; then
	if [[ -n "$old_tag" ]]; then IMAGE_TAG="$old_tag" docker compose up -d; fi
	exit 1
fi

container_id="$(docker compose ps -q api)"
for attempt in $(seq 1 36); do
	status="$(docker inspect --format='{{.State.Health.Status}}' "$container_id" 2>/dev/null || true)"
	if [[ "$status" == "healthy" ]]; then
		printf '%s\n' "$new_tag" > .deployed-image-tag
		exit 0
	fi
	if [[ "$status" == "unhealthy" || "$status" == "" ]]; then break; fi
	sleep 5
done

if [[ -n "$old_tag" ]]; then
	IMAGE_TAG="$old_tag" docker compose up -d --remove-orphans
else
	docker compose down
fi
echo "New API container did not become healthy; restored the previous version." >&2
exit 1
