package com.phoneclaw.app.di

import android.content.Context
import com.phoneclaw.app.executor.ActionExecutor
import com.phoneclaw.app.executor.IntentActionExecutor
import com.phoneclaw.app.gateway.DefaultGateway
import com.phoneclaw.app.gateway.Gateway
import com.phoneclaw.app.model.BuildConfigCloudModelConfig
import com.phoneclaw.app.model.CloudModelAdapter
import com.phoneclaw.app.model.DefaultPlanningService
import com.phoneclaw.app.model.FallbackCloudModelAdapter
import com.phoneclaw.app.model.HttpCloudModelAdapter
import com.phoneclaw.app.model.PlanningService
import com.phoneclaw.app.model.StubCloudModelAdapter
import com.phoneclaw.app.policy.DefaultPolicyEngine
import com.phoneclaw.app.policy.PolicyEngine
import com.phoneclaw.app.skills.SkillRegistry
import com.phoneclaw.app.skills.StaticSkillRegistry

class AppGraph(
    appContext: Context,
) {
    val skillRegistry: SkillRegistry = StaticSkillRegistry()

    private val cloudConfig = BuildConfigCloudModelConfig.fromBuildConfig()
    private val remoteModelAdapter = HttpCloudModelAdapter(cloudConfig, skillRegistry)
    private val stubModelAdapter = StubCloudModelAdapter(skillRegistry)

    val cloudModelAdapter: CloudModelAdapter = FallbackCloudModelAdapter(
        remoteAdapter = remoteModelAdapter,
        fallbackAdapter = stubModelAdapter,
        useRemote = cloudConfig.remoteEnabled,
    )
    val planningService: PlanningService = DefaultPlanningService(
        cloudModelAdapter = cloudModelAdapter,
        allowCloud = cloudConfig.remoteEnabled,
        preferredProvider = cloudConfig.provider,
    )
    val policyEngine: PolicyEngine = DefaultPolicyEngine(skillRegistry)
    val actionExecutor: ActionExecutor = IntentActionExecutor(appContext, skillRegistry)
    val gateway: Gateway = DefaultGateway(planningService, policyEngine, actionExecutor)
}
