package com.phoneclaw.app.di

import android.content.Context
import com.phoneclaw.app.audit.FileAuditTrail
import com.phoneclaw.app.executor.IntentActionExecutor
import com.phoneclaw.app.gateway.DefaultGateway
import com.phoneclaw.app.gateway.Gateway
import com.phoneclaw.app.gateway.ports.AuditPort
import com.phoneclaw.app.gateway.ports.ExecutorPort
import com.phoneclaw.app.gateway.ports.ModelPort
import com.phoneclaw.app.gateway.ports.PlannerPort
import com.phoneclaw.app.gateway.ports.PolicyPort
import com.phoneclaw.app.gateway.ports.SkillRegistryPort
import com.phoneclaw.app.gateway.ports.SessionPort
import com.phoneclaw.app.gateway.ports.SummaryPort
import com.phoneclaw.app.gateway.ports.TelemetryPort
import com.phoneclaw.app.model.BuildConfigCloudModelConfig
import com.phoneclaw.app.model.DefaultModelService
import com.phoneclaw.app.model.DefaultPlanningService
import com.phoneclaw.app.model.DefaultSummaryService
import com.phoneclaw.app.model.FallbackCloudModelAdapter
import com.phoneclaw.app.model.HttpCloudModelAdapter
import com.phoneclaw.app.model.StubCloudModelAdapter
import com.phoneclaw.app.policy.DefaultPolicyEngine
import com.phoneclaw.app.session.InMemorySessionStore
import com.phoneclaw.app.skills.JsonSkillLoader
import com.phoneclaw.app.skills.StaticSkillRegistry
import com.phoneclaw.app.telemetry.LogcatTelemetry
import java.io.File

class AppGraph(
    appContext: Context,
) {
    private val bundledRegisteredActions = JsonSkillLoader
        .fromAssets(appContext.assets)
        .loadRegisteredActions()
        .also { loadedActions ->
            require(loadedActions.isNotEmpty()) {
                "No bundled skills were loaded from assets/skills."
            }
        }

    val skillRegistry: SkillRegistryPort = StaticSkillRegistry(bundledRegisteredActions)

    private val cloudConfig = BuildConfigCloudModelConfig.fromBuildConfig()
    private val remoteModelAdapter = HttpCloudModelAdapter(cloudConfig, skillRegistry)
    private val stubModelAdapter = StubCloudModelAdapter(skillRegistry)
    private val fallbackModelAdapter = FallbackCloudModelAdapter(
        remoteAdapter = remoteModelAdapter,
        fallbackAdapter = stubModelAdapter,
        useRemote = cloudConfig.remoteEnabled,
    )

    val modelPort: ModelPort = DefaultModelService(fallbackModelAdapter)
    val plannerPort: PlannerPort = DefaultPlanningService(
        modelPort = modelPort,
        allowCloud = cloudConfig.remoteEnabled,
        preferredProvider = cloudConfig.provider,
    )
    val summaryPort: SummaryPort = DefaultSummaryService(
        modelPort = modelPort,
        allowCloud = cloudConfig.remoteEnabled,
        preferredProvider = cloudConfig.provider,
    )
    val sessionPort: SessionPort = InMemorySessionStore()
    val telemetryPort: TelemetryPort = LogcatTelemetry()
    val auditPort: AuditPort = FileAuditTrail(File(appContext.filesDir, "audit"))
    val policyPort: PolicyPort = DefaultPolicyEngine(skillRegistry)
    val executorPort: ExecutorPort = IntentActionExecutor(appContext, skillRegistry)
    val gateway: Gateway = DefaultGateway(
        plannerPort = plannerPort,
        policyPort = policyPort,
        executorPort = executorPort,
        summaryPort = summaryPort,
        sessionPort = sessionPort,
        telemetryPort = telemetryPort,
        auditPort = auditPort,
    )
}
