package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 权限等级的落盘格式。
 *
 * 这里的值直接决定旧设置/旧备份能不能读回来：读失败时 PreferencesStore 会结束进程，
 * 所以「认不出的值」必须落到兼容分支，而不是抛 [kotlinx.serialization.SerializationException]。
 */
class AgentPermissionLevelSerializationTest {
    @Test
    fun `writes the three wire names`() {
        assertEquals("\"read_only\"", JsonInstant.encodeToString(AgentPermissionLevel.READ_ONLY))
        assertEquals("\"workspace_write\"", JsonInstant.encodeToString(AgentPermissionLevel.WORKSPACE_WRITE))
        assertEquals("\"full_access\"", JsonInstant.encodeToString(AgentPermissionLevel.FULL_ACCESS))
    }

    @Test
    fun `four level values written by older builds still decode`() {
        assertEquals(
            AgentPermissionLevel.READ_ONLY,
            JsonInstant.decodeFromString<AgentPermissionLevel>("\"read_only\""),
        )
        assertEquals(
            AgentPermissionLevel.WORKSPACE_WRITE,
            JsonInstant.decodeFromString<AgentPermissionLevel>("\"config\""),
        )
        // 凭据和设备在四级版本里是独立的一档，现在收紧到完全放行
        assertEquals(
            AgentPermissionLevel.FULL_ACCESS,
            JsonInstant.decodeFromString<AgentPermissionLevel>("\"secrets\""),
        )
        assertEquals(
            AgentPermissionLevel.FULL_ACCESS,
            JsonInstant.decodeFromString<AgentPermissionLevel>("\"device\""),
        )
    }

    @Test
    fun `unknown value falls back to workspace write instead of failing the whole settings read`() {
        assertEquals(
            AgentPermissionLevel.WORKSPACE_WRITE,
            JsonInstant.decodeFromString<AgentPermissionLevel>("\"CONFIG\""),
        )
        assertEquals(
            AgentPermissionLevel.WORKSPACE_WRITE,
            JsonInstant.decodeFromString<AgentPermissionLevel>("null"),
        )
    }

    @Test
    fun `assistant json from the four level build loads`() {
        val assistant = JsonInstant.decodeFromString<Assistant>(
            """{"id":"e275ff44-8e17-414d-a9a4-93eb19cfe445","name":"Agent","permissionLevel":"config"}"""
        )
        assertEquals(AgentPermissionLevel.WORKSPACE_WRITE, assistant.permissionLevel)
    }

    @Test
    fun `conversation config keeps an unset level unset`() {
        val config = JsonInstant.decodeFromString<ConversationConfig>("""{"permissionLevel":null}""")
        assertEquals(null, config.permissionLevel)
        val bound = JsonInstant.decodeFromString<ConversationConfig>("""{"permissionLevel":"device"}""")
        assertEquals(AgentPermissionLevel.FULL_ACCESS, bound.permissionLevel)
    }
}
