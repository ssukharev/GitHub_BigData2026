package ru.spbstu.ghanalytics.app;

/** Command line arguments are wrong: the run is not started, exit code 2. */
public class InvalidArgumentsException extends RuntimeException {

    public InvalidArgumentsException(String message) {
        super(message);
    }

    public InvalidArgumentsException(String message, Throwable cause) {
        super(message, cause);
    }
}
