package com.phoneclaw.app.notification

import com.phoneclaw.app.learner.ExplorationProgress

interface ExplorationNotifier {
    fun showProgress(progress: ExplorationProgress)
    fun showCompleted(pagesDiscovered: Int, draftsGenerated: Int)
    fun showFailed(errorMessage: String)
    fun dismiss()
}
