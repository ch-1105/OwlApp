package com.phoneclaw.app.gateway.ports

import com.phoneclaw.app.contracts.ExecutionRequest
import com.phoneclaw.app.contracts.ExecutionResult

interface ExecutorPort {
    suspend fun execute(request: ExecutionRequest): ExecutionResult
}
