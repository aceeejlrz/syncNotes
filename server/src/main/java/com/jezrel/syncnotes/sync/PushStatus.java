package com.jezrel.syncnotes.sync;

/** Outcome of one change in a push (section 8.3). */
public enum PushStatus {
    APPLIED, NOOP, CONFLICT_COPY, REJECTED
}
