#!/bin/bash
set -e

# Fix kubeconfig for Docker-in-Docker:
# The host kubeconfig points to 127.0.0.1 which doesn't work inside a container.
# Replace with host.docker.internal so kubectl can reach the K8s API server.
# Also remove certificate-authority-data and add insecure-skip-tls-verify
# (the CA cert is issued for 127.0.0.1/localhost, not host.docker.internal).
if [ -f /root/.kube/config ]; then
    mkdir -p /root/.kube
    sed \
        -e 's|https://127.0.0.1:|https://host.docker.internal:|g' \
        -e '/certificate-authority-data:/d' \
        -e '/^\s*server:/a\    insecure-skip-tls-verify: true' \
        /root/.kube/config > /root/.kube/config.patched
    export KUBECONFIG=/root/.kube/config.patched
fi

exec java -jar app.jar "$@"
