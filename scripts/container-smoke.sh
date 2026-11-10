#!/usr/bin/env bash
set -euo pipefail
image=${1:?Pass the locally built image reference}
container=recomate-build-smoke
origin=http://localhost:5173
headers=
cleanup() {
  result=$?
  trap - EXIT
  if [ "$result" -ne 0 ]; then
    docker logs "$container" 2>&1 | sed 's/Using generated security password:.*/Using generated security password: [REDACTED]/' || true
  fi
  [ -z "$headers" ] || rm -f "$headers"
  docker rm --force "$container" >/dev/null 2>&1 || true
  exit "$result"
}
trap cleanup EXIT
docker run --detach --name "$container" --read-only \
  --tmpfs /tmp:rw,nosuid,size=256m,mode=1777 --cap-drop ALL \
  --security-opt no-new-privileges:true --env AWS_REGION=ap-southeast-2 \
  --env AWS_EC2_METADATA_DISABLED=true --publish 127.0.0.1:18080:8080 "$image" >/dev/null
ready=false
for attempt in {1..60}; do
  if [ "$(docker inspect --format '{{.State.Health.Status}}' "$container")" = healthy ]; then
    ready=true
    break
  fi
  [ "$(docker inspect --format '{{.State.Running}}' "$container")" = true ] || break
  sleep 2
done
[ "$ready" = true ]
status=$(curl --silent --output /dev/null --write-out '%{http_code}' http://localhost:18080/api/users/me)
[ "$status" = 401 ]
headers=$(mktemp)
status=$(curl --silent --output /dev/null --dump-header "$headers" --write-out '%{http_code}' \
  --request OPTIONS --header "Origin: $origin" --header 'Access-Control-Request-Method: PATCH' \
  --header 'Access-Control-Request-Headers: authorization,content-type' http://localhost:18080/api/users/me)
[ "$status" = 200 ]
grep -iq "^Access-Control-Allow-Origin: $origin" "$headers"
status=$(curl --silent --output /dev/null --write-out '%{http_code}' --request POST \
  --header 'Content-Type: application/json' --data '{}' http://localhost:18080/api/sessions)
[ "$status" = 401 ]
status=$(curl --silent --output /dev/null --dump-header "$headers" --write-out '%{http_code}' \
  --request OPTIONS --header "Origin: $origin" --header 'Access-Control-Request-Method: POST' \
  --header 'Access-Control-Request-Headers: authorization,content-type' http://localhost:18080/api/sessions)
[ "$status" = 200 ]
grep -iq "^Access-Control-Allow-Origin: $origin" "$headers"
# Guests can read the catalog and reach the guest route; a bad body is rejected before any TMDB call.
status=$(curl --silent --output /dev/null --write-out '%{http_code}' http://localhost:18080/api/genres)
[ "$status" = 200 ]
status=$(curl --silent --output /dev/null --write-out '%{http_code}' --request POST \
  --header 'Content-Type: application/json' --data '{"participantCount":3,"participants":[{"index":1,"genres":["SF"]},{"index":2,"genres":["SF"]}]}' \
  http://localhost:18080/api/guest/recommendations)
[ "$status" = 400 ]
status=$(curl --silent --output /dev/null --dump-header "$headers" --write-out '%{http_code}' \
  --request OPTIONS --header "Origin: $origin" --header 'Access-Control-Request-Method: POST' \
  --header 'Access-Control-Request-Headers: content-type' http://localhost:18080/api/guest/recommendations)
[ "$status" = 200 ]
grep -iq "^Access-Control-Allow-Origin: $origin" "$headers"
status=$(curl --silent --output /dev/null --dump-header "$headers" --write-out '%{http_code}' \
  --request OPTIONS --header "Origin: $origin" --header 'Access-Control-Request-Method: GET' \
  --header 'Access-Control-Request-Headers: authorization' http://localhost:18080/api/genres)
[ "$status" = 200 ]
grep -iq "^Access-Control-Allow-Origin: $origin" "$headers"
# The popular list needs a login: no token is 401 and the browser preflight is allowed. A real list needs a TMDB token.
status=$(curl --silent --output /dev/null --write-out '%{http_code}' 'http://localhost:18080/api/popular?genre=ACTION&country=KR')
[ "$status" = 401 ]
status=$(curl --silent --output /dev/null --dump-header "$headers" --write-out '%{http_code}' \
  --request OPTIONS --header "Origin: $origin" --header 'Access-Control-Request-Method: GET' \
  --header 'Access-Control-Request-Headers: authorization' http://localhost:18080/api/popular)
[ "$status" = 200 ]
grep -iq "^Access-Control-Allow-Origin: $origin" "$headers"
for path in /api/sessions /api/sessions/01234567-89ab-4cde-8fab-0123456789ab; do
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' "http://localhost:18080$path")
  [ "$status" = 401 ]
  status=$(curl --silent --output /dev/null --dump-header "$headers" --write-out '%{http_code}' \
    --request OPTIONS --header "Origin: $origin" --header 'Access-Control-Request-Method: GET' \
    --header 'Access-Control-Request-Headers: authorization' "http://localhost:18080$path")
  [ "$status" = 200 ]
  grep -iq "^Access-Control-Allow-Origin: $origin" "$headers"
done
session=/api/sessions/01234567-89ab-4cde-8fab-0123456789ab
for route in "DELETE $session" "POST $session/recommendations" "POST $session/exclusions"; do
  method=${route% *}
  path=${route#* }
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' --request "$method" \
    --header 'Content-Type: application/json' --data '{}' "http://localhost:18080$path")
  [ "$status" = 401 ]
  status=$(curl --silent --output /dev/null --dump-header "$headers" --write-out '%{http_code}' \
    --request OPTIONS --header "Origin: $origin" --header "Access-Control-Request-Method: $method" \
    --header 'Access-Control-Request-Headers: authorization,content-type' "http://localhost:18080$path")
  [ "$status" = 200 ]
  grep -iq "^Access-Control-Allow-Origin: $origin" "$headers"
done
echo 'Read-only container started; user/session/recommendation/exclusion/popular authentication=401, public genres=200, guest validation=400, CORS preflights=200.'
