package ru.spbpu.ghanalytics.worldbank;

/** The API returned an error or a response we cannot parse. */
public class WorldBankApiException extends RuntimeException {

    public WorldBankApiException(String message) {
        super(message);
    }

    public WorldBankApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
