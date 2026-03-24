package com.phoneclaw.app.explorer

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

private const val ACCESSIBILITY_LOG_TAG = "PhoneClawA11y"

interface AccessibilityExplorerBridge {
    val latestSnapshot: StateFlow<PageTreeSnapshot?>

    fun captureCurrentPageTree(): PageTreeSnapshot?

    fun capturePageTreeForPackage(targetPackage: String): PageTreeSnapshot?

    fun performClick(nodeId: String): Boolean

    fun performBack(): Boolean

    fun performHome(): Boolean
}

object AccessibilityCaptureBridge : AccessibilityExplorerBridge {
    private var serviceRef: WeakReference<PhoneClawAccessibilityService>? = null

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val _latestSnapshot = MutableStateFlow<PageTreeSnapshot?>(null)
    override val latestSnapshot: StateFlow<PageTreeSnapshot?> = _latestSnapshot.asStateFlow()

    internal fun attach(service: PhoneClawAccessibilityService) {
        serviceRef = WeakReference(service)
        _serviceConnected.value = true
        Log.d(
            ACCESSIBILITY_LOG_TAG,
            "Bridge attach service=${System.identityHashCode(service)} connected=${_serviceConnected.value}",
        )
    }

    internal fun detach(service: PhoneClawAccessibilityService) {
        val activeService = serviceRef?.get()
        if (activeService !== service) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "Bridge detach ignored service=${System.identityHashCode(service)} active=${activeService?.let(System::identityHashCode)}",
            )
            return
        }

        clearDisconnectedState("detach service=${System.identityHashCode(service)}")
    }

    internal fun publishSnapshot(snapshot: PageTreeSnapshot?) {
        if (snapshot == null) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "Bridge publishSnapshot ignored null snapshot connected=${_serviceConnected.value} latestPackage=${_latestSnapshot.value?.packageName}",
            )
            return
        }

        _latestSnapshot.value = snapshot
        _serviceConnected.value = true
    }

    internal fun resetForTest() {
        clearDisconnectedState("resetForTest")
    }

    override fun captureCurrentPageTree(): PageTreeSnapshot? {
        val service = currentService(caller = "captureCurrentPageTree") ?: return null
        val snapshot = service.captureCurrentPageTree()
        publishSnapshot(snapshot)
        if (snapshot == null) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "Bridge captureCurrentPageTree returned null service=${System.identityHashCode(service)} connected=${_serviceConnected.value}",
            )
        }
        return snapshot
    }

    override fun capturePageTreeForPackage(targetPackage: String): PageTreeSnapshot? {
        val service = currentService(caller = "capturePageTreeForPackage") ?: return null
        val snapshot = service.capturePageTreeForPackage(targetPackage)
        publishSnapshot(snapshot)
        if (snapshot == null) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "Bridge capturePageTreeForPackage returned null targetPackage=$targetPackage service=${System.identityHashCode(service)} connected=${_serviceConnected.value}",
            )
        }
        return snapshot
    }

    override fun performClick(nodeId: String): Boolean {
        val service = currentService(caller = "performClick") ?: return false
        val performed = service.performClick(nodeId)
        if (!performed) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "Bridge performClick failed nodeId=$nodeId service=${System.identityHashCode(service)}",
            )
            return false
        }

        publishSnapshot(service.captureCurrentPageTree())
        return true
    }

    override fun performBack(): Boolean {
        val service = currentService(caller = "performBack") ?: return false
        val performed = service.performBack()
        if (!performed) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "Bridge performBack failed service=${System.identityHashCode(service)}",
            )
            return false
        }

        publishSnapshot(service.captureCurrentPageTree())
        return true
    }

    override fun performHome(): Boolean {
        val service = currentService(caller = "performHome") ?: return false
        val performed = service.performHome()
        if (!performed) {
            Log.d(
                ACCESSIBILITY_LOG_TAG,
                "Bridge performHome failed service=${System.identityHashCode(service)}",
            )
            return false
        }

        publishSnapshot(service.captureCurrentPageTree())
        return true
    }

    private fun currentService(caller: String): PhoneClawAccessibilityService? {
        val service = serviceRef?.get()
        if (service != null) {
            return service
        }

        clearDisconnectedState("$caller no active service")
        return null
    }

    private fun clearDisconnectedState(reason: String) {
        val previousPackage = _latestSnapshot.value?.packageName
        val previousService = serviceRef?.get()?.let(System::identityHashCode)
        serviceRef = null
        _serviceConnected.value = false
        _latestSnapshot.value = null
        Log.d(
            ACCESSIBILITY_LOG_TAG,
            "Bridge clearDisconnectedState reason=$reason previousService=$previousService previousPackage=$previousPackage",
        )
    }
}

