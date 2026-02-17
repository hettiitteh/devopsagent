# Jarvis The SRE Engineer

An autonomous SRE (Site Reliability Engineering) agent that monitors production applications, detects issues early, runs playbooks, and mitigates problems automatically. Powered by LLM-driven reasoning with self-improving learning and vector embeddings.

## Architecture

Built on a **gateway-centric orchestration design**:

```
┌─────────────────────────────────────────────────────────────┐
│               Jarvis The SRE Engineer                        │
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
│  │               Tool System (14 SRE tools)              │  │
│  │                                                        │  │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────────────┐ │  │
│  │  │ Monitoring │ │Infrastructure│ │   Remediation     │ │  │
│  │  │            │ │             │ │                    │ │  │
│  │  │ health_chk │ │ kubectl    │ │ service_restart   │ │  │
│  │  │ metrics_q  │ │ docker     │ │ playbook_run      │ │  │
│  │  │ log_search │ │ ssh_exec   │ │ playbook_create   │ │  │
│  │  └────────────┘ └────────────┘ └────────────────────┘ │  │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────────────┐ │  │
│  │  │ Diagnostics│ │  Incident  │ │   Knowledge        │ │  │
│  │  │            │ │            │ │                    │ │  │
│  │  │ network_dg │ │ incident_  │ │ alert_manage      │ │  │
│  │  │ system_inf │ │ manage     │ │ learning_query    │ │  │
│  │  └────────────┘ └────────────┘ └────────────────────┘ │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐   │
│  │Monitoring│ │ Playbook │ │ Learning │ │   Memory     │   │
│  │ Service  │ │  Engine  │ │  System  │ │  Knowledge   │   │
│  │          │ │          │ │          │ │    Base      │   │
│  │ Health   │ │ DB-backed│ │ Resoln   │ │              │   │
│  │ Checks   │ │ Runbooks │ │ Records  │ │ Embeddings   │   │
│  │ Anomaly  │ │ Auto-    │ │ AI Sug-  │ │ Incident     │   │
│  │ Detection│ │ Execute  │ │ gestions │ │ History      │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Notification Channels                    │   │
│  │   Slack  │  PagerDuty  │  Email  │  WebSocket        │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Key Features

### 1. Gateway-Centric Design
- Single WebSocket/HTTP server on port **18789**
- JSON-RPC 2.0 protocol for all communication
- Session management with heartbeat monitoring
- 14 registered RPC methods organized by domain

### 2. LLM-Powered Agent Engine
- Agentic loop: Prompt → LLM → Tool Calls → Loop until done
- Dynamic system prompt with 11 sections
- Context window management with automatic compaction
- Support for OpenAI, Anthropic, and compatible providers

### 3. 9-Layer Tool Policy Engine
Tool access is controlled by 9 cascading policy layers:
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

### 4. 14 SRE Tools
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
| `playbook_create` | Remediation | Create new playbooks via LLM |
| `incident_manage` | Incident | Create/update/resolve incidents |
| `alert_manage` | Alerting | Manage alert rules |
| `network_diag` | Diagnostics | Network diagnostics (ping, traceroute, dig, etc.) |
| `system_info` | Diagnostics | System resource monitoring (top, free, df, etc.) |
| `learning_query` | Knowledge | Query resolution patterns and semantic search |

### 5. Automated Monitoring & Incident Pipeline
- Periodic health checks for registered services
- Automatic incident creation on state transitions (healthy → unhealthy)
- Auto-resolution when services recover
- Auto-trigger playbooks for known incident patterns
- LLM-generated Root Cause Analysis (RCA) reports
- Human-in-the-loop approval for RCAs

### 6. Self-Improving Learning System
- Records resolution patterns from chat, monitoring, and playbooks
- Vector embeddings for semantic similarity search across incidents
- AI-powered playbook suggestions based on frequent patterns
- Cross-service knowledge transfer via embedding similarity
- Executive summaries generated by LLM

### 7. Playbook Engine
- DB-backed remediation runbooks (migrated from YAML)
- Sequential step execution with tool calls
- Retry/continue/abort failure handling
- Dry-run support and variable parameterization
- LLM-guided playbook authoring standards
- UI-based creation, editing, and deletion

### 8. Plugin System
- Extensible architecture for custom tools, channels, and RPC methods
- Plugin API with stable interface
- Dynamic registration/deactivation

### 9. Proactive Risk Assessment
- LLM-driven identification of emerging risks
- Periodic analysis of system state
- Risk severity classification and remediation suggestions

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.9+ (or use included wrapper)
- (Optional) Docker, kubectl, Prometheus for full functionality

### 1. Configure

Create a `.env` file in the project root:
```
OPENAI_API_KEY=your-api-key-here
```

### 2. Build & Run
```bash
./mvnw spring-boot:run
```

The agent starts on port **18789**. Access the UI at `http://localhost:18789`.

### 3. Run with Docker
```bash
docker compose up --build -d
```

Access the UI at `http://localhost:8080`.

## Configuration

All configuration is in `src/main/resources/application.yml`. Key sections:

- **`devops-agent.llm`** — LLM provider, model, API key, embedding config
- **`devops-agent.monitoring`** — Health check intervals, anomaly detection
- **`devops-agent.playbooks`** — Playbook settings, approval requirements
- **`devops-agent.tool-policy`** — Tool access profiles (9-layer policy)
- **`devops-agent.notifications`** — Slack, PagerDuty, email settings
- **`devops-agent.security`** — Command allowlists/denylists, audit settings

## License

MIT
