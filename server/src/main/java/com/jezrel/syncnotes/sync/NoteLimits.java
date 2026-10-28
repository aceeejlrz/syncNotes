package com.jezrel.syncnotes.sync;

/**
 * Size limits (section 8.4). Lengths are in characters as PostgreSQL's char_length counts them,
 * i.e. Unicode code points, not Java UTF-16 units.
 */
public final class NoteLimits {
    public static final int TITLE_MAX = 300;
    public static final int BODY_MAX = 100_000;
    public static final int DEVICE_NAME_MAX = 60;

    private NoteLimits() {
    }

    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
