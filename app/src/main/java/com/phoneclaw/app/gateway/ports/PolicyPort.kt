package com.phoneclaw.app.gateway.ports

import com.phoneclaw.app.contracts.ActionSpec

data class PolicyDecision(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val reason: String? = null,
)

interface PolicyPort {
    fun review(actionSpec: ActionSpec): PolicyDecision
}
