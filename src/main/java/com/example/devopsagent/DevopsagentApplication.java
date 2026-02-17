package com.example.devopsagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Jarvis The SRE Engineer
 *
 * A gateway-centric agent orchestration platform for production monitoring,
 * incident detection, playbook execution, and automated remediation.
 *
 * Architecture:
 * - Gateway (WebSocket/HTTP JSON-RPC) - Central control plane
 * - Agent Engine - LLM-powered reasoning loop with tool execution
 * - Tool System - 14 SRE tools with 9-layer policy engine
 * - Monitoring Pipeline - Health checks, metrics, anomaly detection
 * - Playbook Engine - DB-backed runbooks for automated remediation
 * - Plugin System - Extensible architecture for custom integrations
 * - Memory System - Incident history, embeddings, and knowledge base
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class DevopsagentApplication {

    public static void main(String[] args) {
        System.out.println("""
            ╔══════════════════════════════════════════════════╗
            ║       Jarvis The SRE Engineer v0.1.0             ║
            ║       Intelligent SRE Automation                 ║
            ║       Gateway Port: 18789                        ║
            ╚══════════════════════════════════════════════════╝
            """);
        SpringApplication.run(DevopsagentApplication.class, args);
    }
}
