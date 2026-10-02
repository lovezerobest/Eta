package io.github.mangi.eta

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.astraisland.client.IslandClient
import com.astraisland.protocol.ActivityBundle
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var islandClient: IslandClient
        private set
    private var islandStatusLastSentAt = 0L
    @Volatile private var lastAgentStatus: String? = null
    @Volatile private var agentRunActive = false
    @Volatile private var islandReady = false

    private var islandStatusPending: IslandStatus? = null
    private val islandHandler = Handler(Looper.getMainLooper())
    private val islandStatusFlush = Runnable { flushIslandStatus() }

    internal data class IslandStatus(val title: String, val detail: String)

    @Synchronized
    internal fun onAgentEvent(event: AgentEvent) {
        val status = when (event) {
            is AgentEvent.RunStarted -> { agentRunActive = true; IslandStatus("准备任务", "Agent 已开始运行") }
            is AgentEvent.RoundStarted -> IslandStatus("正在思考", "第 ${event.round} 轮，已准备 ${event.messageCount} 条上下文")
            is AgentEvent.ContextCompaction -> if (event.phase == AgentEvent.ContextCompaction.PHASE_STARTED) IslandStatus("压缩上下", "正在整理上下文") else IslandStatus("继续处理", "上下文整理完成")
            is AgentEvent.ModelRetryScheduled -> IslandStatus("请求重试", "第 ${event.attempt}/${event.maxAttempts} 次，${(event.delayMs + 999) / 1000} 秒后重试")
            is AgentEvent.ProviderRequestStarted -> IslandStatus("连接模型", "正在发送模型请求")
            is AgentEvent.ProviderResponseStarted -> IslandStatus("读取响应", "模型已响应，正在读取数据")
            is AgentEvent.AssistantBlockStart -> when (event.kind) {
                AgentEvent.AssistantBlockKind.THINKING -> IslandStatus("正在思考", "模型正在分析任务")
                AgentEvent.AssistantBlockKind.TEXT -> IslandStatus("生成回复", "正在生成文本回复")
                AgentEvent.AssistantBlockKind.TOOL_CALL -> IslandStatus("规划工具", "正在准备工具参数")
            }
            is AgentEvent.AssistantBlockDelta -> when (event.kind) {
                AgentEvent.AssistantBlockKind.THINKING -> IslandStatus("正在思考", "模型正在分析任务")
                AgentEvent.AssistantBlockKind.TEXT -> IslandStatus("生成回复", "正在生成文本回复")
                AgentEvent.AssistantBlockKind.TOOL_CALL -> IslandStatus("规划工具", "正在准备工具参数")
            }
            is AgentEvent.AssistantReceived -> if (event.toolNames.isNotEmpty()) IslandStatus("调用工具", "准备调用 ${event.toolNames.size} 项工具") else IslandStatus("整理回复", "正在整理模型输出")
            is AgentEvent.ToolStarted -> IslandStatus("调用工具", "正在${toolStatusLabel(event.name)}")
            is AgentEvent.HostedToolStarted -> IslandStatus("调用工具", "正在${toolStatusLabel(event.name)}")
            is AgentEvent.ToolFinished -> IslandStatus("工具完成", "${toolStatusLabel(event.name)}已完成，继续处理")
            is AgentEvent.HostedToolFinished -> if (event.success) IslandStatus("工具完成", "${toolStatusLabel(event.name)}已完成，继续处理") else IslandStatus("工具失败", "${toolStatusLabel(event.name)}未成功，正在处理")
            is AgentEvent.ToolImagesAttached -> IslandStatus("读取结果", "已取得 ${event.imageCount} 张结果图片")
            is AgentEvent.UserSupplementReceived -> IslandStatus("收到补充", "已收到补充指令，继续处理")
            is AgentEvent.RunFinished -> { agentRunActive = false; null }
            is AgentEvent.RunFailed -> { agentRunActive = false; IslandStatus("任务失败", "任务执行失败，${event.reason.take(48)}") }
            else -> return
        }
        if (status != null) lastAgentStatus = status.title
        val displayStatus = if (islandReady) status else if (agentRunActive) IslandStatus("岛线重连", "星河岛连接中断，Agent 仍在后台运行") else null
        islandStatusPending = displayStatus
        islandHandler.removeCallbacks(islandStatusFlush)
        val wait = (1000L - (SystemClock.elapsedRealtime() - islandStatusLastSentAt)).coerceAtLeast(0L)
        if (displayStatus == null || wait == 0L) islandStatusFlush.run() else islandHandler.postDelayed(islandStatusFlush, wait)
    }

    @Synchronized
    private fun onIslandReadyChanged(ready: Boolean) {
        islandReady = ready
        islandStatusPending = when {
            !ready && agentRunActive -> IslandStatus("岛线重连", "星河岛连接中断，Agent 仍在后台运行")
            ready && agentRunActive -> IslandStatus(lastAgentStatus ?: "继续处理", "Agent 任务正在继续")
            else -> null
        }
        islandHandler.removeCallbacks(islandStatusFlush)
        islandStatusFlush.run()
    }

    private fun toolStatusLabel(name: String): String = when (name) {
        "browser_use" -> "浏览网页"
        "terminal", "terminal_command", "terminal_session" -> "执行终端命令"
        "launch_app", "search_apps" -> "查找或打开应用"
        "open_uri" -> "打开链接"
        "tap", "tap_area", "tap_element", "long_press", "long_press_element" -> "操作屏幕"
        "swipe", "scroll", "scroll_element" -> "滚动页面"
        "input_text", "replace_text", "paste_text", "clear_text" -> "输入文本"
        "read_image", "search_media", "search_audio", "search_files" -> "查看文件或图片"
        "set_alarm", "set_timer" -> "设置提醒"
        "device_status", "network_info", "get_device_environment" -> "查看设备状态"
        else -> "执行操作"
    }

    @Synchronized
    private fun flushIslandStatus() {
        val status = islandStatusPending
        islandStatusPending = null
        updateIslandStatus(status)
    }

    @Synchronized
    internal fun updateIslandStatus(status: IslandStatus?) {
        val client = if (::islandClient.isInitialized) islandClient else return
        if (status == null) { client.end("eta-agent-task"); return }
        islandStatusLastSentAt = SystemClock.elapsedRealtime()
        client.start(ActivityBundle.encodeActivity(
            id = "eta-agent-task", kind = "LIVE_UPDATE",
            compactLeading = ActivityBundle.encodeSlot("icon", icon = ActivityBundle.encodeIcon("builtin", builtin = "INFO")),
            compactTrailing = ActivityBundle.encodeSlot("text", text = status.title),
            expanded = ActivityBundle.encodeExpanded(
                template = "STATUS", title = status.title, subtitle = status.detail,
                body = "Eta 智能任务进度",
            ),
            hideWhenSourceForeground = false, lockScreenVisibility = "REDACTED",
            contentDescription = "Eta 当前状态：${status.title}，${status.detail}",
        ))
    }

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        if (AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            islandClient = IslandClient(this) { activityId, actionId ->
                AndroidAgentLogger.info("Astraland action: activity=$activityId action=$actionId")
            }
            islandClient.onReadyChanged = { ready -> onIslandReadyChanged(ready) }
            islandClient.connect()
        }
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        SettingsDataStore.init(this)
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
