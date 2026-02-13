# DevOps SRE Agent

An autonomous SRE (Site Reliability Engineering) agent built on the **OpenClaw architecture pattern**. This agent acts as a tireless SRE team member that monitors production applications, detects issues early, runs playbooks, and mitigates problems automatically.

## Architecture

Built on the same **gateway-centric orchestration design** as OpenClaw:

```
┌─────────────────────────────────────────────────────────────┐
│                    DevOps SRE Agent                          │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Gateway (Port 18789)                     │   │
│  │         WebSocket / HTTP JSON-RPC Server              │   │
│  │                                                       │   │
│  │  ┌─────────┐  ┌──────────┐  ┌────────────────────┐  │   │
│  │  │ Session  │  │   RPC    │  │    Heartbeat       │  │   │
│  │  │ Manager  │  │  Router  │  │    Monitor         │  │   │
│  │  └─────────┘  └──────────┘  └────────────────────┘  │   │
│  └──────────────────────────────────────────────────────┘   │
│                           │                                  │
│  ┌────────────────────────┼──────────────────────────────┐  │
│  │                  Agent Engine                          │  │
│  │                                                        │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌─────────────┐  │  │
│  │  │ LLM Client  │  │  System      │  │  9-Layer    │  │  │
│  │  │ (OpenAI/    │  │  Prompt      │  │  Tool       │  │  │
│  │  │  Anthropic) │  │  Builder     │  │  Policy     │  │  │
│  │  └─────────────┘  └──────────────┘  └─────────────┘  │  │
│  │                                                        │  │
│  │          ┌──────────────────────┐                      │  │
│  │          │    Agentic Loop      │                      │  │
│  │          │  Prompt → LLM →      │                      │  │
│  │          │  Tool Calls → Loop   │                      │  │
│  │          └──────────────────────┘                      │  │
│  └────────────────────────────────────────────────────────┘  │
│                           │                                  │
│  ┌────────────────────────┼──────────────────────────────┐  │
│  │                   Tool System (10+ tools)              │  │
│  │                                                        │  │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────────────┐ │  │
│  │  │ Monitoring │ │Infrastructure│ │   Remediation     │ │  │
│  │  │            │ │             │ │                    │ │  │
│  │  │ health_chk │ │ kubectl    │ │ service_restart   │ │  │
│  │  │ metrics_q  │ │ docker     │ │ playbook_run      │ │  │
│  │  │ log_search │ │ ssh_exec   │ │                    │ │  │
│  │  └────────────┘ └────────────┘ └────────────────────┘ │  │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────────────┐ │  │
│  │  │ Diagnostics│ │  Incident  │ │    Alerting        │ │  │
│  │  │            │ │            │ │                    │ │  │
│  │  │ network_dg │ │ incident_  │ │ alert_manage      │ │  │
│  │  │ system_inf │ │ manage     │ │                    │ │  │
│  │  └────────────┘ └────────────┘ └────────────────────┘ │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐   │
│  │Monitoring│ │ Playbook │ │  Plugin  │ │   Memory     │   │
│  │ Service  │ │  Engine  │ │  System  │ │  Knowledge   │   │
│  │          │ │          │ │          │ │    Base      │   │
│  │ Health   │ │ YAML     │ │ Plugin   │ │              │   │
│  │ Checks   │ │ Runbooks │ │ API      │ │ Incident     │   │
│  │ Anomaly  │ │ Auto-    │ │ Custom   │ │ History      │   │
│  │ Detection│ │ Execute  │ │ Tools    │ │ Search       │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Notification Channels                    │   │
│  │   Slack  │  PagerDuty  │  Email  │  WebSocket        │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Key Features

### 1. Gateway-Centric Design (OpenClaw Pattern)
- Single WebSocket/HTTP server on port **18789**
- JSON-RPC 2.0 protocol for all communication
- Session management with heartbeat monitoring
- 20+ registered RPC methods organized by domain

### 2. LLM-Powered Agent Engine
- Agentic loop: Prompt → LLM → Tool Calls → Loop until done
- Dynamic system prompt with 9 sections (identity, capabilities, tools, safety, context, monitoring, incidents, playbooks, time)
- Context window management with automatic compaction
- Support for OpenAI, Anthropic, and compatible providers

### 3. 9-Layer Tool Policy Engine
Just like OpenClaw, tool access is controlled by 9 cascading policy layers:
1. **Profile policy** — Base access level (minimal, sre, full)
2. **Provider-specific profile** — Override by LLM provider
3. **Global policy** — Project-wide tool rules
4. **Provider-specific global** — Provider overrides on global
5. **Per-agent policy** — Agent-specific access
6. **Agent provider policy** — Provider overrides per agent
7. **Group policy** — Channel/sender-based rules
8. **Sandbox policy** — Container isolation restrictions
9. **Subagent policy** — Child agent restrictions

**A deny at any layer blocks the tool. No exceptions.**

### 4. DevOps/SRE Tool Suite
| Tool | Category | Description |
|------|----------|-------------|
| `health_check` | Monitoring | HTTP/TCP health probes |
| `metrics_query` | Monitoring | Prometheus PromQL queries |
| `log_search` | Monitoring | Multi-source log search (files, journalctl, kubectl, docker) |
| `kubectl_exec` | Infrastructure | Kubernetes management |
| `docker_exec` | Infrastructure | Docker container management |
| `ssh_exec` | Infrastructure | Remote server command execution |
| `service_restart` | Remediation | Restart services (systemd/k8s/docker) |
| `playbook_run` | Remediation | Execute remediation runbooks |
| `incident_manage` | Incident | Create/update/resolve incidents |
| `alert_manage` | Alerting | Manage alert rules |
| `network_diag` | Diagnostics | Network diagnostics (ping, traceroute, dig, etc.) |
| `system_info` | Diagnostics | System resource monitoring (top, free, df, etc.) |

### 5. Automated Monitoring
- Periodic health checks for registered services
- Automatic incident creation on state transitions (healthy → unhealthy)
- Auto-resolution when services recover
- Real-time WebSocket broadcasts for health updates
- Micrometer metrics integration with Prometheus export

### 6. Anomaly Detection
- Alert rules with configurable thresholds and conditions
- Support for: GREATER_THAN, LESS_THAN, RATE_INCREASE, ABSENT, etc.
- Cooldown periods to prevent alert storms
- Automatic incident creation on alert trigger

### 7. Playbook Engine
- YAML-based remediation runbooks
- Sequential step execution with tool calls
- Retry/continue/abort failure handling
- Dry-run support
- Variable parameterization
- 3 sample playbooks included:
  - `service-restart` — Safe service restart with health verification
  - `high-cpu-investigation` — CPU/memory/disk diagnostics
  - `k8s-crashloop-fix` — Kubernetes CrashLoopBackOff remediation

### 8. Plugin System
- Extensible architecture for custom tools, channels, and RPC methods
- Plugin API with stable interface
- Dynamic registration/deactivation

### 9. Incident Knowledge Base
- Searchable incident history
- Service-level statistics
- Common root cause tracking
- Context injection into agent conversations

### 10. Multi-Channel Notifications
- Slack (webhook)
- PagerDuty (Events API v2)
- Email (SMTP)
- WebSocket (real-time gateway broadcasts)

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.9+ (or use included wrapper)
- (Optional) Docker, kubectl, Prometheus for full functionality

### 1. Configure

Set your LLM API key:
```bash
export OPENAI_API_KEY=your-api-key-here
```

Optional notification configuration:
```bash
export SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
export PAGERDUTY_API_KEY=your-pagerduty-key
```

### 2. Build & Run
```bash
./mvnw spring-boot:run
```

The agent starts on port **18789** (same as OpenClaw's gateway port).

### 3. Register a Service for Monitoring
```bash
curl -X POST http://localhost:18789/api/monitoring/services \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-api",
    "displayName": "My API Service",
    "type": "HTTP",
    "healthEndpoint": "http://localhost:8080/actuator/health",
    "checkIntervalSeconds": 60,
    "enabled": true
  }'
```

### 4. Chat with the Agent
```bash
curl -X POST http://localhost:18789/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Check the health of all monitored services and report any issues"}'
```

### 5. Create an Alert Rule
```bash
curl -X POST http://localhost:18789/api/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "name": "High Error Rate",
    "metric": "http_errors_total",
    "condition": "GREATER_THAN",
    "threshold": 100,
    "severity": "HIGH",
    "serviceName": "my-api",
    "durationSeconds": 300
  }'
```

## API Reference

### REST Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/agent/chat` | Send message to agent |
| `GET` | `/api/agent/tools` | List available tools |
| `GET` | `/api/agent/sessions` | List active sessions |
| `GET` | `/api/gateway/status` | Full system status |
| `GET` | `/api/gateway/methods` | List RPC methods |
| `GET` | `/api/monitoring/health` | Health summary |
| `POST` | `/api/monitoring/services` | Register service |
| `GET` | `/api/monitoring/services` | List services |
| `POST` | `/api/monitoring/services/{name}/check` | Manual health check |
| `GET` | `/api/incidents` | List incidents |
| `GET` | `/api/incidents/active` | Active incidents |
| `POST` | `/api/incidents` | Create incident |
| `POST` | `/api/incidents/{id}/resolve` | Resolve incident |
| `GET` | `/api/incidents/search` | Search incidents |
| `GET` | `/api/incidents/stats/{service}` | Service statistics |
| `GET` | `/api/playbooks` | List playbooks |
| `POST` | `/api/playbooks/{id}/execute` | Execute playbook |
| `GET` | `/api/alerts` | List alert rules |
| `POST` | `/api/alerts` | Create alert rule |
| `GET` | `/api/security/audit` | Run security audit |

### WebSocket Gateway (JSON-RPC)

Connect to `ws://localhost:18789/ws/gateway`

**RPC Methods:**
| Method | Description |
|--------|-------------|
| `gateway.status` | System status |
| `gateway.methods` | List RPC methods |
| `agent.run` | Start agent session |
| `agent.sessions` | List sessions |
| `agent.abort` | Abort session |
| `monitor.health` | Health summary |
| `monitor.services` | List services |
| `incident.list` | Active incidents |
| `incident.get` | Get incident |
| `incident.search` | Search incidents |
| `playbook.list` | List playbooks |
| `playbook.run` | Execute playbook |
| `tool.list` | List tools |
| `plugin.list` | List plugins |

### WebSocket Events (Broadcasts)
| Event | Description |
|-------|-------------|
| `monitor.health_update` | Service health change |
| `monitor.service_recovered` | Service recovered |
| `incident.created` | New incident created |
| `alert.triggered` | Alert rule triggered |
| `playbook.step_started` | Playbook step executing |
| `playbook.completed` | Playbook finished |

## Configuration

All configuration is in `src/main/resources/application.yml`. Key sections:

- **`devops-agent.llm`** — LLM provider, model, API key
- **`devops-agent.monitoring`** — Health check intervals, anomaly detection
- **`devops-agent.playbooks`** — Playbook directory, approval requirements
- **`devops-agent.tool-policy`** — Tool access profiles (9-layer policy)
- **`devops-agent.notifications`** — Slack, PagerDuty, email settings
- **`devops-agent.security`** — Command allowlists/denylists, audit settings

## Project Structure

```
src/main/java/com/example/devopsagent/
├── DevopsagentApplication.java      # Entry point
├── config/                          # Configuration
│   ├── AgentProperties.java         # Central config (maps to application.yml)
│   ├── WebSocketConfig.java         # WebSocket gateway config
│   ├── AsyncConfig.java             # Thread pool executors
│   └── AppConfig.java               # Beans (ObjectMapper, OkHttp, WebClient)
├── gateway/                         # Gateway (OpenClaw core)
│   ├── GatewayWebSocketHandler.java # WebSocket handler
│   ├── GatewayRpcRouter.java        # JSON-RPC method router
│   ├── GatewayRpcRegistration.java  # RPC method registration
│   ├── GatewaySession.java          # Session tracking
│   └── JsonRpcMessage.java          # JSON-RPC 2.0 protocol
├── agent/                           # Agent Engine
│   ├── AgentEngine.java             # Core agentic loop
│   ├── LlmClient.java              # LLM API client
│   ├── SystemPromptBuilder.java     # Dynamic prompt builder
│   ├── ToolPolicyEngine.java        # 9-layer policy engine
│   ├── ToolRegistry.java            # Tool registration
│   ├── AgentTool.java               # Tool interface
│   ├── ToolResult.java              # Tool result type
│   ├── ToolContext.java             # Execution context
│   └── AgentMessage.java            # Conversation messages
├── tools/                           # Built-in SRE tools
│   ├── HealthCheckTool.java         # HTTP/TCP health checks
│   ├── MetricsQueryTool.java        # Prometheus queries
│   ├── LogSearchTool.java           # Multi-source log search
│   ├── KubectlTool.java            # Kubernetes management
│   ├── DockerTool.java             # Docker management
│   ├── SshExecTool.java            # SSH command execution
│   ├── ServiceRestartTool.java      # Service restart
│   ├── PlaybookRunTool.java         # Playbook execution
│   ├── IncidentManageTool.java      # Incident management
│   ├── AlertManageTool.java         # Alert rule management
│   ├── NetworkDiagTool.java         # Network diagnostics
│   ├── SystemInfoTool.java          # System information
│   └── ToolRegistrationConfig.java  # Auto-registration
├── monitoring/                      # Monitoring subsystem
│   ├── MonitoringService.java       # Health check orchestrator
│   └── AnomalyDetector.java        # Alert rule evaluation
├── playbook/                        # Playbook engine
│   ├── Playbook.java               # Playbook model (YAML)
│   └── PlaybookEngine.java         # Execution engine
├── plugin/                          # Plugin system
│   ├── Plugin.java                  # Plugin interface
│   ├── PluginApi.java              # Plugin API (stable surface)
│   └── PluginManager.java          # Plugin lifecycle
├── notification/                    # Notification channels
│   └── NotificationService.java     # Slack, PagerDuty, email
├── memory/                          # Knowledge base
│   └── IncidentKnowledgeBase.java  # Incident search & stats
├── security/                        # Security audit
│   └── SecurityAuditService.java   # 30+ security checks
├── domain/                          # Domain models (JPA entities)
│   ├── Incident.java               # Incident entity
│   ├── MonitoredService.java       # Service entity
│   ├── AlertRule.java              # Alert rule entity
│   └── PlaybookExecution.java      # Execution record
├── repository/                      # Data access
│   ├── IncidentRepository.java
│   ├── MonitoredServiceRepository.java
│   ├── AlertRuleRepository.java
│   └── PlaybookExecutionRepository.java
└── controller/                      # REST API
    ├── AgentController.java         # Agent chat API
    ├── MonitoringController.java    # Monitoring API
    ├── IncidentController.java      # Incident API
    ├── PlaybookController.java      # Playbook API
    ├── AlertController.java         # Alert & security API
    └── GatewayController.java       # Gateway status API
```

## OpenClaw Architecture Mapping

| OpenClaw Concept | DevOps Agent Implementation |
|-----------------|---------------------------|
| Gateway (port 18789) | `GatewayWebSocketHandler` + JSON-RPC |
| Pi Agent Framework | `AgentEngine` (agentic loop) |
| 60+ Built-in Tools | 12 SRE-specific tools |
| 9-Layer Tool Policy | `ToolPolicyEngine` |
| Dynamic System Prompt | `SystemPromptBuilder` (9 sections) |
| Plugin SDK | `Plugin` interface + `PluginApi` |
| Auto-Reply Pipeline | Monitoring → Alert → Incident → Playbook |
| Memory System | `IncidentKnowledgeBase` |
| Channel Abstraction | `NotificationService` (Slack, PD, Email) |
| Security Audit | `SecurityAuditService` |
| Config System | `AgentProperties` (YAML + hot reload) |

## License

MIT
