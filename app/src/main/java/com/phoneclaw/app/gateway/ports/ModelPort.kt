package com.phoneclaw.app.gateway.ports

import com.phoneclaw.app.contracts.ModelRequest
import com.phoneclaw.app.contracts.ModelResponse

interface ModelPort {
    suspend fun infer(request: ModelRequest): ModelResponse
}
