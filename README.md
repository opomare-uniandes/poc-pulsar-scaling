# Pulsar KEDA Scaling POC

Demonstrates autoscaling of a Spring Boot consumer using KEDA driven by Apache Pulsar message backlog.

## Structure

```
pulsar/
├── k8s/                        # Kubernetes manifests
│   ├── 00-pulsar.yaml          # Pulsar standalone Deployment + Service
│   ├── 01-publisher.yaml       # Publisher Deployment + Service (port 8080)
│   ├── 02-consumer.yaml        # Consumer Deployment (scaled by KEDA)
│   ├── 03-scaled-object.yaml   # KEDA ScaledObject (0–10 replicas, 10 msg/replica)
│   └── 04-gateway.yaml         # Envoy Gateway — GatewayClass, Gateway, HTTPRoute
├── kind-config.yaml            # Kind cluster config (port 30080 → localhost:9080)
├── pulsar-publisher/           # Spring Boot WebFlux app — POST /messages to publish
└── pulsar-client/              # Spring Boot app — @PulsarListener on hello-pulsar-topic
```

## Prerequisites

- Docker
- Kind (`kind get clusters` should list `pulsar`)
- Helm (`brew install helm`)

## 1. Create the Kind cluster

```bash
kind create cluster --config kind-config.yaml
```

This maps the cluster's NodePort `30080` to `localhost:9080` on your machine, which is how the Gateway API is exposed without needing sudo or a cloud load balancer.

## 2. Install cluster dependencies

Run these in parallel — they are independent:

```bash
# KEDA — event-driven autoscaler
helm repo add kedacore https://kedacore.github.io/charts
helm repo update
helm install keda kedacore/keda --namespace keda --create-namespace

# Envoy Gateway — Gateway API implementation
helm install eg oci://docker.io/envoyproxy/gateway-helm \
  --version v1.4.0 \
  --namespace envoy-gateway-system \
  --create-namespace \
  --timeout 3m
```

Wait for Envoy Gateway to be ready:

```bash
kubectl wait --for=condition=available deployment/envoy-gateway -n envoy-gateway-system --timeout=90s
```

## 3. Build and load images

Run from the `pulsar/` directory:

```bash
docker build -t pulsar-publisher:latest ./pulsar-publisher
docker build -t pulsar-consumer:latest ./pulsar-client

kind load docker-image pulsar-publisher:latest --name pulsar
kind load docker-image pulsar-consumer:latest --name pulsar
```

> The Dockerfiles use a 3-stage build: Gradle compilation → layer extraction → JVM AOT cache training.
> First build is slow (dependency downloads); subsequent builds use Docker layer cache.

## 4. Deploy everything

```bash
kubectl apply -f k8s/
```

Wait for Pulsar to be ready (takes ~60–90s on first start):

```bash
kubectl wait --for=condition=ready pod -l app=pulsar --timeout=120s
```

## 5. Pin the Gateway NodePort

Envoy Gateway creates the proxy service with a random NodePort. Patch it to `30080` so it matches the Kind cluster mapping:

```bash
kubectl patch svc -n envoy-gateway-system \
  $(kubectl get svc -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=publisher-gateway -o name) \
  --type='json' \
  -p='[{"op": "replace", "path": "/spec/ports/0/nodePort", "value": 30080}]'
```

## 6. Verify everything is running

```bash
kubectl get gateway        # should show PROGRAMMED=True with an ADDRESS
kubectl get pods           # all pods Running
kubectl get scaledobject   # READY=True
```

Expected:

```
NAME                CLASS   ADDRESS      PROGRAMMED
publisher-gateway   eg      172.22.0.x   True

NAME                  READY   STATUS    RESTARTS
pulsar-xxx            1/1     Running
pulsar-publisher-xxx  1/1     Running
pulsar-consumer-xxx   1/1     Running
```

> **Why does the consumer start at 1 replica?**
> Spring creates the topic (`hello-pulsar-topic`) automatically on first use — no manual step needed there.
> However, KEDA tracks message backlog per *subscription* (`hello-pulsar-sub`), not per topic.
> The subscription is only created in Pulsar when the consumer first connects via `@PulsarListener`.
> If the consumer starts at 0 replicas, the subscription never exists, KEDA gets a 404 from the
> Pulsar admin API, and scaling never triggers — a chicken-and-egg deadlock.
> Starting at 1 lets `@PulsarListener` register the subscription once. After that, KEDA takes over:
> it scales to 0 when the backlog is empty (after the cooldown period), and back up as messages arrive.
> The subscription persists in Pulsar even with zero consumers connected, so KEDA can always query it.

## 7. Trigger load

The publisher is exposed via Envoy Gateway on `localhost:9080`. No port-forward needed.

```bash
# Single message
curl -X POST http://localhost:9080/messages \
  -H "Content-Type: application/json" \
  -d '{"content": "hello pulsar"}'

# Flood 200 messages to trigger scaling
for i in $(seq 1 200); do
  curl -s -X POST http://localhost:9080/messages \
    -H "Content-Type: application/json" \
    -d "{\"content\": \"message $i\"}" &
done
wait
```

## 8. Watch KEDA scale the consumer

```bash
# Watch replicas change in real time
kubectl get pods -l app=pulsar-consumer -w

# Watch the HPA metric (current backlog vs threshold)
kubectl get hpa -w

# Check backlog directly via Pulsar admin
kubectl exec -it deploy/pulsar -- bin/pulsar-admin topics stats \
  persistent://public/default/hello-pulsar-topic
```

## How scaling works

| Backlog | Consumer replicas |
|---------|-------------------|
| 0       | 0 (after 30s cooldown) |
| 1–10    | 1 |
| 11–20   | 2 |
| 51–100  | up to 10 |

KEDA polls the Pulsar admin API every 30s and adjusts replicas so each consumer handles ~10 messages
(`msgBacklogThreshold: 10` in `03-scaled-object.yaml`).

## Traffic flow

```
curl localhost:9080
  → Kind extraPortMapping (hostPort 9080 → containerPort 30080)
  → Kind node iptables (nodePort 30080 → Envoy proxy pod)
  → Envoy Gateway (HTTPRoute matches /messages)
  → pulsar-publisher ClusterIP :8080
  → PulsarAdapter → hello-pulsar-topic
```

## Teardown

```bash
kubectl delete -f k8s/
kind delete cluster --name pulsar
```
