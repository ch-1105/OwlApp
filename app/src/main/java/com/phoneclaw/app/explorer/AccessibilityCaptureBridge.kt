package com.phoneclaw.app.explorer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

interface AccessibilityExplorerBridge {
    val latestSnapshot: StateFlow<PageTreeSnapshot?>

    fun captureCurrentPageTree(): PageTreeSnapshot?

    fun performClick(nodeId: String): Boolean

    fun performBack(): Boolean
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
    }

    internal fun detach(service: PhoneClawAccessibilityService) {
        if (serviceRef?.get() === service) {
            serviceRef = null
        }
        _serviceConnected.value = serviceRef?.get() != null
    }

    internal fun publishSnapshot(snapshot: PageTreeSnapshot?) {
        if (snapshot != null) {
            _latestSnapshot.value = snapshot
            _serviceConnected.value = true
        }
    }

    internal fun resetForTest() {
        serviceRef = null
        _serviceConnected.value = false
        _latestSnapshot.value = null
    }

    override fun captureCurrentPageTree(): PageTreeSnapshot? {
        val snapshot = serviceRef?.get()?.captureCurrentPageTree()
        publishSnapshot(snapshot)
        return snapshot
    }

    override fun performClick(nodeId: String): Boolean {
        val performed = serviceRef?.get()?.performClick(nodeId) == true
        if (performed) {
            publishSnapshot(serviceRef?.get()?.captureCurrentPageTree())
        }
        return performed
    }

    override fun performBack(): Boolean {
        val performed = serviceRef?.get()?.performBack() == true
        if (performed) {
            publishSnapshot(serviceRef?.get()?.captureCurrentPageTree())
        }
        return performed
    }
}
