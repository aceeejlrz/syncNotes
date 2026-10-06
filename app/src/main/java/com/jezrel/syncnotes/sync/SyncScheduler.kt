package com.jezrel.syncnotes.sync

import javax.inject.Inject

/**
 * Asks for a background sync. The real implementation (WorkManager) arrives in Phase 4;
 * Phase 1 uses the no-op below so the repository can call it without any network code.
 */
interface SyncScheduler {
    fun requestSync()
}

class NoOpSyncScheduler @Inject constructor() : SyncScheduler {
    override fun requestSync() { /* wired up in Phase 4 */ }
}
