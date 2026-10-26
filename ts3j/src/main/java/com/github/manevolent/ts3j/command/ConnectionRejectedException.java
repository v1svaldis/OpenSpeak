package com.github.manevolent.ts3j.command;

/**
 * The server answered the connection attempt with an error (wrong password, banned, identity
 * security level too low, ...). Keeps the TeamSpeak error id and extra message for callers.
 */
public class ConnectionRejectedException extends CommandProcessException {
    public static final int CLIENT_COULD_NOT_VALIDATE_IDENTITY = 0x0207;

    private final int errorId;
    private final String extraMessage;

    public ConnectionRejectedException(int errorId, String message, String extraMessage) {
        super(message);
        this.errorId = errorId;
        this.extraMessage = extraMessage;
    }

    public int getErrorId() {
        return errorId;
    }

    /** For CLIENT_COULD_NOT_VALIDATE_IDENTITY this is the security level the server needs. */
    public String getExtraMessage() {
        return extraMessage;
    }
}
