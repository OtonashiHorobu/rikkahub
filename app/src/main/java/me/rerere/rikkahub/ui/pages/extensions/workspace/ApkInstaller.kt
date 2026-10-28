package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

/** 待安装 APK 的静态信息，供安装前展示 */
data class ApkInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String?,
    val icon: Drawable?,
    val minSdk: Int,
    val targetSdk: Int,
    val sizeBytes: Long,
    /** 签名证书的 SHA-256 前 16 位，用来区分是不是同一个签名打的包 */
    val signatureDigest: String?,
    /** 设备上是否已装同包名应用、已装版本号，用于提示升级还是新装 */
    val installedVersionCode: Long?,
)

/**
 * APK 解析与安装。
 *
 * 安装走 [PackageInstaller] 会话：能拿到明确的成功/失败回调，也能在会话里设置
 * 显示名（系统安装器会显示 "RikkaHub (debug)" 而不是文件名）。
 * 用户没给"安装未知应用"权限时，先引导到系统设置页，而不是让会话直接失败。
 */
object ApkInstaller {
    /** 系统安装器广播回传的会话 id → 回调，避免把回调塞进 PendingIntent（跨进程不可行） */
    private val callbacks = mutableMapOf<Int, (Boolean, String?) -> Unit>()

    fun inspect(context: Context, file: File): ApkInfo? {
        if (!file.isFile) return null
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, flags)
        } ?: return null

        // 归档里的资源没加载过，必须把 sourceDir 指过去才能读标签和图标
        info.applicationInfo?.apply {
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(info.packageName, 0)
            }
        }.getOrNull()

        return ApkInfo(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
            label = runCatching { pm.getApplicationLabel(info.applicationInfo!!).toString() }.getOrNull(),
            icon = runCatching { pm.getApplicationIcon(info.applicationInfo!!) }.getOrNull(),
            minSdk = info.applicationInfo?.minSdkVersion ?: 0,
            targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
            sizeBytes = file.length(),
            signatureDigest = signatureDigestOf(info, pm),
            installedVersionCode = installed?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    it.versionCode.toLong()
                }
            },
        )
    }

    /** 当前应用是否有权安装未知应用；Android 8 起这是每应用开关 */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * 发起安装。回调在安装器确认/取消后触发，[onResult] 第一个参数是成功与否。
     *
     * 注意：即使回调成功，实际安装也可能被系统当作"需要用户确认"而仍在等待，
     * 这里只反映 PackageInstaller 会话的结果。
     */
    fun install(context: Context, file: File, onResult: (Boolean, String?) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(file.length())
            // 让系统安装器显示应用名而不是文件名
            setAppPackageName(file.name.removeSuffix(".apk"))
        }
        val sessionId = runCatching { installer.createSession(params) }
            .getOrElse { error ->
                onResult(false, error.message)
                return
            }
        callbacks[sessionId] = onResult
        runCatching {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, ApkInstallReceiver::class.java)
                    .putExtra(ApkInstallReceiver.EXTRA_SESSION_ID, sessionId)
                // 不指定包名，PendingIntent 自己带着发送方身份，接收方是我们自己
                val pending = android.app.PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        }.onFailure { error ->
            callbacks.remove(sessionId)
            runCatching { installer.abandonSession(sessionId) }
            onResult(false, error.message)
        }
    }

    /**
     * 发起安装并等系统安装器的结果。
     *
     * 超时不代表失败：有些系统会在安装器界面等用户点确认，这时会话还没结束。
     * 返回 [Result.success] 表示会话成功；[Result.failure] 带系统给的原因。
     */
    suspend fun awaitInstall(
        context: Context,
        file: File,
        timeoutMillis: Long = 120_000,
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        val deferred = kotlinx.coroutines.CompletableDeferred<Pair<Boolean, String?>>()
        install(context, file) { success, message -> deferred.complete(success to message) }
        val result = withTimeoutOrNull(timeoutMillis) { deferred.await() }
            ?: return@withContext Result.failure(
                IllegalStateException("安装器没有在 ${timeoutMillis / 1000}s 内返回结果，可能仍在等待用户确认")
            )
        if (result.first) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(result.second ?: "系统拒绝安装")
            )
        }
    }

    /** 给 [ApkInstallReceiver] 用：把系统回传的结果分发给对应的回调 */
    internal fun dispatch(sessionId: Int, success: Boolean, message: String?) {
        callbacks.remove(sessionId)?.invoke(success, message)
    }

    /** 系统安装器读取的 content:// 地址；临时授权给安装器进程 */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    private fun signatureDigestOf(
        info: android.content.pm.PackageInfo,
        pm: PackageManager,
    ): String? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return null
        val first = signatures.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(first.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }
}

/** 接收 PackageInstaller 的会话结果。必须在清单里声明，否则进程被杀后收不到广播 */
class ApkInstallReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        ApkInstaller.dispatch(
            sessionId = sessionId,
            success = status == PackageInstaller.STATUS_SUCCESS,
            message = message,
        )
    }
}
