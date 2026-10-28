package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.model.AgentPermissionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPermissionLevelTest {
    @Test
    fun `three levels form a ladder`() {
        assertTrue(AgentPermissionLevel.WORKSPACE_WRITE.grants(AgentPermissionLevel.READ_ONLY))
        assertTrue(AgentPermissionLevel.FULL_ACCESS.grants(AgentPermissionLevel.WORKSPACE_WRITE))
        assertFalse(AgentPermissionLevel.READ_ONLY.grants(AgentPermissionLevel.WORKSPACE_WRITE))
        assertFalse(AgentPermissionLevel.WORKSPACE_WRITE.grants(AgentPermissionLevel.FULL_ACCESS))
    }

    @Test
    fun `read only keeps read tools and drops write tools`() {
        assertEquals(AgentPermissionLevel.READ_ONLY, AgentToolLevels.requiredLevel("workspace_read_file"))
        assertEquals(AgentPermissionLevel.READ_ONLY, AgentToolLevels.requiredLevel("workspace_grep"))
        assertEquals(AgentPermissionLevel.WORKSPACE_WRITE, AgentToolLevels.requiredLevel("workspace_write_file"))
        assertEquals(AgentPermissionLevel.WORKSPACE_WRITE, AgentToolLevels.requiredLevel("workspace_shell"))
        assertEquals(AgentPermissionLevel.WORKSPACE_WRITE, AgentToolLevels.requiredLevel("workspace_cd"))
    }

    @Test
    fun `credentials and device tools need full access`() {
        assertEquals(AgentPermissionLevel.FULL_ACCESS, AgentToolLevels.requiredLevel("provider_api_key_set"))
        assertEquals(AgentPermissionLevel.FULL_ACCESS, AgentToolLevels.requiredLevel("webdav_config_set"))
    }

    @Test
    fun `unregistered tools fall back to read only`() {
        assertEquals(
            AgentToolLevels.DEFAULT_TOOL_LEVEL,
            AgentToolLevels.requiredLevel("mcp__server__tool"),
        )
        assertEquals(AgentPermissionLevel.READ_ONLY, AgentToolLevels.DEFAULT_TOOL_LEVEL)
    }

    @Test
    fun `old four level values migrate to three`() {
        assertEquals(AgentPermissionLevel.READ_ONLY, AgentPermissionLevel.parseOrWorkspaceWrite("read_only"))
        assertEquals(AgentPermissionLevel.WORKSPACE_WRITE, AgentPermissionLevel.parseOrWorkspaceWrite("config"))
        assertEquals(
            AgentPermissionLevel.WORKSPACE_WRITE,
            AgentPermissionLevel.parseOrWorkspaceWrite("workspace_write")
        )
        // secrets 原来是"写凭据"，收紧到完全放行才给
        assertEquals(AgentPermissionLevel.FULL_ACCESS, AgentPermissionLevel.parseOrWorkspaceWrite("secrets"))
        assertEquals(AgentPermissionLevel.FULL_ACCESS, AgentPermissionLevel.parseOrWorkspaceWrite("device"))
        assertEquals(AgentPermissionLevel.WORKSPACE_WRITE, AgentPermissionLevel.parseOrWorkspaceWrite(null))
    }
}
