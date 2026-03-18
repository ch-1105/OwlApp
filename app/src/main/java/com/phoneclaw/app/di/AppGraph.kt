package com.phoneclaw.app.di

import android.content.Context
import com.phoneclaw.app.audit.FileAuditTrail
import com.phoneclaw.app.executor.ActionExecutor
import com.phoneclaw.app.executor.IntentActionExecutor
import com.phoneclaw.app.gateway.DefaultGateway
import com.phoneclaw.app.gateway.Gateway
import com.phoneclaw.app.gateway.ports.AuditPort
import com.phoneclaw.app.gateway.ports.PlannerPort
import com.phoneclaw.app.gateway.ports.SessionPort
import com.phoneclaw.app.gateway.ports.TelemetryPort
import com.phoneclaw.app.model.BuildConfigCloudModelConfig
import com.phoneclaw.app.model.CloudModelAdapter
import com.phoneclaw.app.model.DefaultPlanningService
import com.phoneclaw.app.model.FallbackCloudModelAdapter
import com.phoneclaw.app.model.HttpCloudModelAdapter
import com.phoneclaw.app.model.StubCloudModelAdapter
import com.phoneclaw.app.policy.DefaultPolicyEngine
import com.phoneclaw.app.policy.PolicyEngine
import com.phoneclaw.app.session.InMemorySessionStore
import com.phoneclaw.app.skills.JsonSkillLoader
import com.phoneclaw.app.skills.SkillRegistry
import com.phoneclaw.app.skills.StaticSkillRegistry
import com.phoneclaw.app.telemetry.LogcatTelemetry
import java.io.File

class AppGraph(
    appContext: Context,
) {
    val skillRegistry: SkillRegistry = JsonSkillLoader
        .fromAssets(appContext.assets)
        .loadRegisteredActions()
        .takeIf { it.isNotEmpty() }
        ?.let { loadedActions -> StaticSkillRegistry(loadedActions) }
        ?: StaticSkillRegistry()

    private val cloudConfig = BuildConfigCloudModelConfig.fromBuildConfig()
    private val remoteModelAdapter = HttpCloudModelAdapter(cloudConfig, skillRegistry)
    private val stubModelAdapter = StubCloudModelAdapter(skillRegistry)

    val cloudModelAdapter: CloudModelAdapter = FallbackCloudModelAdapter(
        remoteAdapter = remoteModelAdapter,
        fallbackAdapter = stubModelAdapter,
        useRemote = cloudConfig.remoteEnabled,
    )
    val plannerPort: PlannerPort = DefaultPlanningService(
        cloudModelAdapter = cloudModelAdapter,
        allowCloud = cloudConfig.remoteEnabled,
        preferredProvider = cloudConfig.provider,
    )
    val sessionPort: SessionPort = InMemorySessionStore()
    val telemetryPort: TelemetryPort = LogcatTelemetry()
    val auditPort: AuditPort = FileAuditTrail(File(appContext.filesDir, "audit"))
    val policyEngine: PolicyEngine = DefaultPolicyEngine(skillRegistry)
    val actionExecutor: ActionExecutor = IntentActionExecutor(appContext, skillRegistry)
    val gateway: Gateway = DefaultGateway(
        plannerPort = plannerPort,
        policyEngine = policyEngine,
        actionExecutor = actionExecutor,
        sessionPort = sessionPort,
        telemetryPort = telemetryPort,
        auditPort = auditPort,
    )
}
