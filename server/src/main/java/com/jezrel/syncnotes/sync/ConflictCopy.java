package com.jezrel.syncnotes.sync;

/** Title and body for the conflicted copy created by rule M4. */
public record ConflictCopy(String title, String body) {

    /**
     * {@code "<title> (conflicted copy from <deviceName>)"}, with the original title shortened by
     * whole code points so the result fits {@link NoteLimits#TITLE_MAX}; a longer title would
     * violate the database constraint, roll back the push and stall the device's sync forever.
     */
    public static String titleFor(String title, String deviceName) {
        String suffix = " (conflicted copy from " + deviceName + ")";
        int keep = Math.max(0, NoteLimits.TITLE_MAX - NoteLimits.length(suffix));
        if (NoteLimits.length(title) > keep) {
            title = title.substring(0, title.offsetByCodePoints(0, keep));
        }
        return title + suffix;
    }
}
