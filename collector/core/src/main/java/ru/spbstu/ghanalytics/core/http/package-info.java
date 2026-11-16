/**
 * HTTP for every source: the shared {@link java.net.http.HttpClient} bean, classification of
 * HTTP errors and {@link ru.spbstu.ghanalytics.core.http.RetryPolicy}.
 * <table>
 *   <caption>What is repeated</caption>
 *   <tr><th>Answer</th><th>Exception</th><th>Repeated</th></tr>
 *   <tr><td>2xx</td><td>none</td><td>-</td></tr>
 *   <tr><td>3xx that reached us, 400, 401, 403, 404, 422, ...</td>
 *       <td>{@link ru.spbstu.ghanalytics.core.http.HttpStatusException}</td><td>no: same request, same answer</td></tr>
 *   <tr><td>429, 5xx</td><td>{@link ru.spbstu.ghanalytics.core.http.RetryableHttpException}</td>
 *       <td>yes, after {@code Retry-After} or backoff</td></tr>
 *   <tr><td>connection reset, timeout, DNS</td><td>{@link java.io.IOException}</td><td>yes</td></tr>
 *   <tr><td>bad JSON, bugs</td><td>Jackson exceptions, {@code NullPointerException}, ...</td><td>no</td></tr>
 * </table>
 * Usage in a client (inject {@code HttpClient}, {@code RetryPolicy} and {@code RunStats} beans):
 * <pre>{@code
 * HttpRequest request = HttpRequest.newBuilder(uri)
 *         .timeout(Duration.ofSeconds(30))            // without it one hanging answer blocks the run
 *         .header("Accept", "application/json")
 *         .GET()
 *         .build();
 * String body = retry.execute(() -> {
 *     HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
 *     HttpStatusException.throwIfError(response);   // 429/5xx: repeat, other 4xx: out at once
 *     return response.body();
 * });
 * }</pre>
 * A client that needs its own rules (a rate limit sent as 403, an error inside a 200 body)
 * throws {@code RetryableHttpException} or {@code HttpStatusException} itself; the policy
 * decides by the exception type only.
 */
package ru.spbstu.ghanalytics.core.http;
