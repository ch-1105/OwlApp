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

class AppGraph(
    appContext: Context,
) {
    private val cloudConfig = BuildConfigCloudModelConfig.fromBuildConfig()
    private val remoteModelAdapter = HttpCloudModelAdapter(cloudConfig)
    private val stubModelAdapter = StubCloudModelAdapter()

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
    val policyEngine: PolicyEngine = DefaultPolicyEngine()
    val actionExecutor: ActionExecutor = IntentActionExecutor(appContext)
    val gateway: Gateway = DefaultGateway(planningService, policyEngine, actionExecutor)
}
