#!/bin/sh
# Deploy the isolated mock-backed test environment. Does not touch other containers.
set -eu
BASE=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REMOTE=${1:-ubuntu@159.75.1.175}
if [ "${NOISELESS_SKIP_BUILD:-0}" != 1 ]; then "$BASE/mvnw" -q verify; fi
[ -f "$BASE/target/noiseless.jar" ] || exit 1
ssh "$REMOTE" 'sudo install -d -m 750 -o ubuntu -g ubuntu /srv/noiseless-java/releases /srv/noiseless-java/testing; sudo install -d -m 700 /srv/noiseless-java/data; sudo chown 10001:10001 /srv/noiseless-java/data'
scp -q "$BASE/target/noiseless.jar" "$REMOTE:/srv/noiseless-java/releases/noiseless.jar"
scp -q "$BASE/deploy/Dockerfile.runtime" "$REMOTE:/srv/noiseless-java/releases/Dockerfile"
scp -q "$BASE/scripts/mock_ai.py" "$BASE/scripts/remote_smoke.py" "$BASE/deploy/testing.env" "$BASE/deploy/noiseless-mock-ai.service" "$REMOTE:/srv/noiseless-java/testing/"
ssh "$REMOTE" 'set -eu
  chmod 600 /srv/noiseless-java/testing/testing.env
  sudo install -m 644 /srv/noiseless-java/testing/noiseless-mock-ai.service /etc/systemd/system/noiseless-mock-ai.service
  sudo systemctl daemon-reload
  sudo systemctl enable noiseless-mock-ai
  sudo systemctl restart noiseless-mock-ai
  sudo docker build -t noiseless-java:testing /srv/noiseless-java/releases
  if sudo docker container inspect noiseless-java-test >/dev/null 2>&1; then sudo docker stop noiseless-java-test >/dev/null; sudo docker rm noiseless-java-test >/dev/null; fi
  sudo docker run -d --name noiseless-java-test --label io.noiseless.project=noiseless-java --restart unless-stopped --network host --memory 768m --cpus 1 --cap-drop ALL --security-opt no-new-privileges --read-only --tmpfs /tmp:rw,exec,size=128m,mode=1777 --health-cmd "java -jar /app/noiseless.jar health" --health-interval 15s --health-start-period 20s --health-timeout 7s --health-retries 3 --env-file /srv/noiseless-java/testing/testing.env -v /srv/noiseless-java/data:/data noiseless-java:testing
  python3 /srv/noiseless-java/testing/remote_smoke.py --state /srv/noiseless-java/testing/smoke-account.json > /srv/noiseless-java/testing/smoke-report.json
  cat /srv/noiseless-java/testing/smoke-report.json'
