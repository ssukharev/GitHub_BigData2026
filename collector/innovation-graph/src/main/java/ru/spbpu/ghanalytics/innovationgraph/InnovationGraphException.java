package ru.spbpu.ghanalytics.innovationgraph;

/** Download failed or the CSV does not look like the Innovation Graph data we expect. */
public class InnovationGraphException extends RuntimeException {

    public InnovationGraphException(String message) {
        super(message);
    }

    public InnovationGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
