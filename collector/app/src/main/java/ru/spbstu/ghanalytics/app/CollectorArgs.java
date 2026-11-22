package ru.spbstu.ghanalytics.app;

import org.springframework.boot.ApplicationArguments;
import ru.spbstu.ghanalytics.core.CollectionContext;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * Parsed command line of one run:
 * {@code --source=<name> [--from=YYYY-MM-DD] [--to=YYYY-MM-DD] [--min-stars=N] [--limit=N] [--resume]}.
 * <p>
 * Options with a dot ({@code --spring.datasource.url=...}) are Spring property overrides
 * and are left to Spring; any other unknown option is an error, so typos do not pass silently.
 */
public record CollectorArgs(String source, CollectionContext context) {

    /** Default start of the period: the first year of the study. */
    public static final LocalDate DEFAULT_FROM = LocalDate.of(2018, 1, 1);

    static final Set<String> KNOWN_OPTIONS = Set.of("source", "from", "to", "min-stars", "limit", "resume");

    public static final String USAGE = """
            Usage: java -jar collector.jar --source=<name> [options]
              --source=<name>       collector to run (see the list of sources below)
              --from=YYYY-MM-DD     first day of the period, default 2018-01-01
              --to=YYYY-MM-DD       last day of the period, default today
              --min-stars=N         minimal stars for repository sources, default 0
              --limit=N             maximal number of items, 0 = no limit (default)
              --resume              continue from saved checkpoints
            Exit codes: 0 success, 1 collector failed, 2 invalid arguments or unknown source.""";

    /** Whether a source was given at all; without it the runner prints usage. */
    public static boolean hasSource(ApplicationArguments args) {
        return args.containsOption("source");
    }

    /**
     * Parses and validates the arguments.
     *
     * @param today used as the default end of the period
     * @throws InvalidArgumentsException with a message for the user
     */
    public static CollectorArgs parse(ApplicationArguments args, LocalDate today) {
        if (!args.getNonOptionArgs().isEmpty()) {
            throw new InvalidArgumentsException("unexpected arguments " + args.getNonOptionArgs()
                    + ": options must look like --name=value");
        }
        for (String name : args.getOptionNames()) {
            if (!name.contains(".") && !KNOWN_OPTIONS.contains(name)) {
                throw new InvalidArgumentsException("unknown option --" + name);
            }
        }

        String source = single(args, "source");
        if (source == null || source.isBlank()) {
            throw new InvalidArgumentsException("--source is required");
        }
        LocalDate from = date(args, "from", DEFAULT_FROM);
        LocalDate to = date(args, "to", today);
        int minStars = number(args, "min-stars", 0);
        int limit = number(args, "limit", 0);
        boolean resume = flag(args, "resume");

        try {
            return new CollectorArgs(source.strip(), new CollectionContext(from, to, minStars, limit, resume));
        } catch (IllegalArgumentException e) {
            throw new InvalidArgumentsException(e.getMessage(), e);
        }
    }

    private static String single(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {
            return null;
        }
        if (values.isEmpty()) {
            throw new InvalidArgumentsException("--" + name + " requires a value: use --" + name + "=value");
        }
        if (values.size() > 1) {
            throw new InvalidArgumentsException("--" + name + " is given " + values.size() + " times");
        }
        return values.get(0);
    }

    private static LocalDate date(ApplicationArguments args, String name, LocalDate defaultValue) {
        String value = single(args, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidArgumentsException("--" + name + " must be a date YYYY-MM-DD, got '" + value + "'", e);
        }
    }

    private static int number(ApplicationArguments args, String name, int defaultValue) {
        String value = single(args, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new InvalidArgumentsException("--" + name + " must be an integer, got '" + value + "'", e);
        }
    }

    private static boolean flag(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null) {
            return false;
        }
        if (!values.isEmpty()) {
            throw new InvalidArgumentsException("--" + name + " is a flag and takes no value");
        }
        return true;
    }
}
