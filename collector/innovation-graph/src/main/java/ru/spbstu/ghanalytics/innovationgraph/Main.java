package ru.spbstu.ghanalytics.innovationgraph;

/**
 * Loads GitHub Innovation Graph data into the database configured in {@code .env}.
 * <pre>
 * docker compose up -d                       # PostgreSQL + Flyway migrations
 * cd collector/innovation-graph
 * mvn -q compile exec:java                   # branch main of github/innovationgraph
 * mvn -q compile exec:java -Dexec.args="&lt;commit sha&gt;"
 * </pre>
 * Load World Bank first: country codes are checked against {@code core.countries}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String ref = args.length > 0 ? args[0] : InnovationGraphClient.DEFAULT_REF;

        DbConfig db = DbConfig.fromEnvironment();
        System.out.println("Innovation Graph (" + ref + ") -> " + db.url());

        InnovationGraphLoader.Result result =
                new InnovationGraphLoader(new InnovationGraphClient(ref), ref, db).load();
        System.out.printf("Done (collection_runs.id = %d): %d country-quarter rows, %d topic rows%n",
                result.runId(), result.stats(), result.topics());
        System.out.println(CountryCodeReport.format(result.unknownCodes()));
    }
}
