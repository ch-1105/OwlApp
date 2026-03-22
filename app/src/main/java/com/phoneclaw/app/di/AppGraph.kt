package com.phoneclaw.app.di

import android.content.Context
import android.content.Intent
import androidx.room.Room
import com.phoneclaw.app.audit.FileAuditTrail
import com.phoneclaw.app.data.db.PHONECLAW_DATABASE_NAME
import com.phoneclaw.app.data.db.PHONECLAW_DB_MIGRATION_1_2
import com.phoneclaw.app.data.db.PHONECLAW_DB_MIGRATION_2_3
import com.phoneclaw.app.data.db.PhoneClawDatabase
import com.phoneclaw.app.executor.IntentActionExecutor
import com.phoneclaw.app.explorer.AccessibilityAppExplorer
import com.phoneclaw.app.explorer.AppExplorer
import com.phoneclaw.app.gateway.DefaultGateway
import com.phoneclaw.app.gateway.Gateway
import com.phoneclaw.app.gateway.ports.AuditPort
import com.phoneclaw.app.gateway.ports.ExecutorPort
import com.phoneclaw.app.gateway.ports.ModelPort
import com.phoneclaw.app.gateway.ports.PageAnalysisPort
import com.phoneclaw.app.gateway.ports.PlannerPort
import com.phoneclaw.app.gateway.ports.PolicyPort
import com.phoneclaw.app.gateway.ports.SessionPort
import com.phoneclaw.app.gateway.ports.SkillRegistryPort
import com.phoneclaw.app.gateway.ports.SummaryPort
import com.phoneclaw.app.gateway.ports.TelemetryPort
import com.phoneclaw.app.learner.AiExplorationStrategy
import com.phoneclaw.app.learner.AiPageAnalyzer
import com.phoneclaw.app.learner.AiSkillLearner
import com.phoneclaw.app.learner.AppLauncher
import com.phoneclaw.app.learner.DefaultExplorationAgent
import com.phoneclaw.app.learner.DefaultLearningSessionManager
import com.phoneclaw.app.learner.ExplorationAgent
import com.phoneclaw.app.learner.ExplorationStrategy
import com.phoneclaw.app.learner.HeuristicExplorationStrategy
import com.phoneclaw.app.learner.LearningSessionManager
import com.phoneclaw.app.learner.SkillLearner
import com.phoneclaw.app.model.BuildConfigCloudModelConfig
import com.phoneclaw.app.model.DefaultModelService
import com.phoneclaw.app.model.DefaultPlanningService
import com.phoneclaw.app.model.DefaultSummaryService
import com.phoneclaw.app.model.FallbackCloudModelAdapter
import com.phoneclaw.app.model.HttpCloudModelAdapter
import com.phoneclaw.app.model.StubCloudModelAdapter
import com.phoneclaw.app.notification.AndroidExplorationNotifier
import com.phoneclaw.app.notification.ExplorationNotifier
import com.phoneclaw.app.policy.DefaultPolicyEngine
import com.phoneclaw.app.scanner.AppScanner
import com.phoneclaw.app.scanner.AuthorizationManager
import com.phoneclaw.app.scanner.PackageManagerAppScanner
import com.phoneclaw.app.scanner.RoomAuthorizationManager
import com.phoneclaw.app.session.RoomSessionStore
import com.phoneclaw.app.skills.JsonSkillLoader
import com.phoneclaw.app.skills.StoreBackedSkillRegistry
import com.phoneclaw.app.store.RoomSkillStore
import com.phoneclaw.app.store.SkillStore
import com.phoneclaw.app.telemetry.LogcatTelemetry
import java.io.File

class AppGraph(
    appContext: Context,
) {
    val database: PhoneClawDatabase = Room.databaseBuilder(
        appContext,
        PhoneClawDatabase::class.java,
        PHONECLAW_DATABASE_NAME,
    )
        .addMigrations(PHONECLAW_DB_MIGRATION_1_2, PHONECLAW_DB_MIGRATION_2_3)
        .build()

    private val builtinSkillLoader = JsonSkillLoader.fromAssets(appContext.assets)
    private val bundledSkillPackages = builtinSkillLoader.loadSkillPackages()
        .also { loadedSkills ->
            require(loadedSkills.isNotEmpty()) {
                "No bundled skills were loaded from assets/skills."
            }
        }

    val skillStore: SkillStore = RoomSkillStore(
        skillDao = database.skillDao(),
        builtinLoader = builtinSkillLoader,
    )
    val skillRegistry: SkillRegistryPort = StoreBackedSkillRegistry(skillStore)

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
    val pageAnalysisPort: PageAnalysisPort = AiPageAnalyzer(
        modelPort = modelPort,
        allowCloud = cloudConfig.remoteEnabled,
        preferredProvider = cloudConfig.provider,
    )
    val skillLearner: SkillLearner = AiSkillLearner(
        modelPort = modelPort,
        allowCloud = cloudConfig.remoteEnabled,
        preferredProvider = cloudConfig.provider,
    )
    val sessionPort: SessionPort = RoomSessionStore(database.taskDao())
    val appScanner: AppScanner = PackageManagerAppScanner(appContext)
    val authorizationManager: AuthorizationManager = RoomAuthorizationManager(database.authorizedAppDao())
    val appExplorer: AppExplorer = AccessibilityAppExplorer()
    val learningSessionManager: LearningSessionManager = DefaultLearningSessionManager(
        appExplorer = appExplorer,
        pageAnalysisPort = pageAnalysisPort,
        skillLearner = skillLearner,
    )
    val appLauncher: AppLauncher = AppLauncher { packageName ->
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName) ?: return@AppLauncher false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }.isSuccess
    }
    val explorationNotifier: ExplorationNotifier = AndroidExplorationNotifier(appContext)
    val explorationStrategy: ExplorationStrategy = AiExplorationStrategy(
        modelPort = modelPort,
        allowCloud = cloudConfig.remoteEnabled,
        preferredProvider = cloudConfig.provider,
    )
    val explorationAgent: ExplorationAgent = DefaultExplorationAgent(
        appLauncher = appLauncher,
        appExplorer = appExplorer,
        pageAnalysisPort = pageAnalysisPort,
        skillLearner = skillLearner,
        strategy = explorationStrategy,
    )
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
