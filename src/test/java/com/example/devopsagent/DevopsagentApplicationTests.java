package com.example.devopsagent;

import com.example.devopsagent.agent.ToolRegistry;
import com.example.devopsagent.config.AgentProperties;
import com.example.devopsagent.gateway.GatewayRpcRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DevopsagentApplicationTests {

    @Autowired
    private AgentProperties agentProperties;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private GatewayRpcRouter rpcRouter;

    @Test
    void contextLoads() {
        assertNotNull(agentProperties);
        assertNotNull(toolRegistry);
        assertNotNull(rpcRouter);
    }

    @Test
    void toolsAreRegistered() {
        assertTrue(toolRegistry.getToolCount() > 0, "At least one tool should be registered");
    }

    @Test
    void rpcMethodsAreRegistered() {
        assertTrue(rpcRouter.getMethodCount() > 0, "At least one RPC method should be registered");
    }

    @Test
    void configurationIsLoaded() {
        assertEquals("sre", agentProperties.getToolPolicy().getDefaultProfile());
        assertTrue(agentProperties.getMonitoring().isEnabled());
    }
}
