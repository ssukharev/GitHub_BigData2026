package ru.spbstu.ghanalytics.worldbank;

import java.time.Year;

/**
 * Loads World Bank countries and population into the database configured in {@code .env}.
 * <pre>
 * docker compose up -d                 # PostgreSQL + Flyway migrations
 * cd collector/worldbank
 * mvn -q compile exec:java             # years 2018..current
 * mvn -q compile exec:java -Dexec.args="2010"
 * </pre>
 */
public final class Main {

    private static final int DEFAULT_FROM_YEAR = 2018;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int fromYear = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_FROM_YEAR;
        int toYear = Year.now().getValue();

        DbConfig db = DbConfig.fromEnvironment();
        System.out.println("World Bank -> " + db.url() + ", years " + fromYear + ".." + toYear);

        WorldBankLoader.Result result = new WorldBankLoader(new WorldBankClient(), db).load(fromYear, toYear);
        System.out.printf("Done (collection_runs.id = %d): %d countries and aggregates, %d population rows%n",
                result.runId(), result.countries(), result.population());
    }
}
