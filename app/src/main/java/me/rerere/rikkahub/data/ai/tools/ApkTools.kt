package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.extensions.workspace.ApkInfo
import me.rerere.rikkahub.ui.pages.extensions.workspace.ApkInstaller
import java.io.File

/**
 * 把工作区里的 APK 装到这台手机上。
 *
 * 面向远程迭代：在机器上 `assembleDebug` 出来的产物传到工作区，直接让 agent 装，
 * 不用在手机上手动翻文件管理器。审批由工具的 `needsApproval` 单独把关（默认必审）。
 */
internal fun createApkTools(
    context: Context,
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    paths: WorkspacePaths,
) = listOf(
    Tool(
        name = "workspace_install_apk",
        description = """
            Install an APK file from the assistant's bound workspace onto this device.
            Paths may be absolute inside the workspace, or relative to the current working directory.
            Requires the user to allow RikkaHub to install unknown apps; when not allowed, the call fails
            and reports the reason instead of opening settings by itself.
            Installing replaces the app with the same package name, so debug builds install alongside release builds.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "APK path inside the workspace, absolute or relative to the current directory."
                        )
                    })
                },
                required = listOf("path"),
            )
        },
        needsApproval = { true },
        execute = {
            val raw = it.jsonObject.let { args ->
                args["path"]?.let { value ->
                    (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }
            } ?: error("path is required")
            val absolute = paths.resolve(raw)
            require(absolute.endsWith(".apk", ignoreCase = true)) { "not an APK file: $absolute" }

            if (!ApkInstaller.canInstall(context)) {
                return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "install_blocked")
                            put(
                                "message",
                                "RikkaHub is not allowed to install unknown apps. Ask the user to enable it in system settings.",
                            )
                            put("path", absolute)
                        }.toString()
                    )
                )
            }

            val target = exportToCache(context, workspaceRepository, workspaceId, absolute)
            val info = ApkInstaller.inspect(context, target)
            val result = ApkInstaller.awaitInstall(context, target)
            result.fold(
                onSuccess = {
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("installed", true)
                                info?.let { put("packageName", it.packageName) }
                                info?.versionName?.let { put("versionName", it) }
                                info?.let { put("versionCode", it.versionCode) }
                                put("path", absolute)
                            }.toString()
                        )
                    )
                },
                onFailure = { error ->
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("installed", false)
                                put("error", error.message ?: "install failed")
                                info?.let { put("packageName", it.packageName) }
                                put("path", absolute)
                            }.toString()
                        )
                    )
                },
            )
        },
    ),
    Tool(
        name = "workspace_read_apk",
        description = """
            Inspect an APK in the assistant's bound workspace without installing it:
            package name, version, min/target SDK, signature digest, and whether a build is already installed.
            Use this to check which build is on the device before installing.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "APK path inside the workspace, absolute or relative to the current directory."
                        )
                    })
                },
                required = listOf("path"),
            )
        },
        needsApproval = { false },
        execute = {
            val raw = it.jsonObject.let { args ->
                args["path"]?.let { value ->
                    (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }
            } ?: error("path is required")
            val absolute = paths.resolve(raw)
            val target = exportToCache(context, workspaceRepository, workspaceId, absolute)
            val info = ApkInstaller.inspect(context, target)
                ?: return@Tool listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "not_an_apk")
                            put("path", absolute)
                        }.toString()
                    )
                )
            listOf(UIMessagePart.Text(info.toJson().toString()))
        },
    ),
)

private fun ApkInfo.toJson() = buildJsonObject {
    put("packageName", packageName)
    versionName?.let { put("versionName", it) }
    put("versionCode", versionCode)
    label?.let { put("label", it) }
    put("minSdk", minSdk)
    put("targetSdk", targetSdk)
    put("sizeBytes", sizeBytes)
    signatureDigest?.let { put("signatureDigest", it) }
    installedVersionCode?.let { put("installedVersionCode", it) }
    put("installed", installedVersionCode != null)
}

/** 把工作区里的文件导出到 cacheDir，供 PackageInstaller / PackageManager 按真实路径读取 */
private suspend fun exportToCache(
    context: Context,
    repository: WorkspaceRepository,
    workspaceId: String,
    absolutePath: String,
): File {
    val dir = File(context.cacheDir, "apk_install").apply { mkdirs() }
    val name = absolutePath.substringAfterLast('/')
    val target = File(dir, name)
    target.outputStream().use { output ->
        repository.exportAbsoluteFile(workspaceId, absolutePath, output)
    }
    return target
}

private val kotlinx.serialization.json.JsonElement.contentOrNull: String?
    get() = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
