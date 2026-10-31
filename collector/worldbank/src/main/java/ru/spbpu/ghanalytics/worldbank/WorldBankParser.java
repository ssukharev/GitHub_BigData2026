package ru.spbpu.ghanalytics.worldbank;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Parses World Bank API v2 JSON responses.
 * <p>
 * A successful response is {@code [meta, data]}; an error is {@code [{"message": [...]}]} with HTTP 200.
 */
final class WorldBankParser {

    /** {@code region.id} that the World Bank uses for aggregates. */
    private static final String AGGREGATE_REGION_ID = "NA";

    private final ObjectMapper mapper = new ObjectMapper();

    Page<Country> parseCountries(String json) {
        return parsePage(json, item -> {
            JsonNode region = item.path("region");
            boolean aggregate = AGGREGATE_REGION_ID.equals(region.path("id").asText());
            return new Country(
                    item.path("iso2Code").asText(),
                    item.path("id").asText(),
                    item.path("name").asText().strip(),
                    aggregate ? null : region.path("value").asText().strip(),
                    aggregate);
        });
    }

    /** Rows without a value (e.g. "Not classified") are skipped. */
    Page<Population> parsePopulation(String json) {
        return parsePage(json, item -> {
            JsonNode value = item.path("value");
            if (value.isNull() || value.isMissingNode()) {
                return null;
            }
            return new Population(
                    item.path("country").path("id").asText(),
                    Integer.parseInt(item.path("date").asText()),
                    value.asLong());
        });
    }

    private <T> Page<T> parsePage(String json, Function<JsonNode, T> mapItem) {
        JsonNode root = readTree(json);
        if (!root.isArray() || root.isEmpty()) {
            throw new WorldBankApiException("Unexpected response: " + abbreviate(json));
        }
        JsonNode meta = root.get(0);
        if (meta.has("message")) {
            throw new WorldBankApiException("API error: " + meta.get("message"));
        }

        List<T> items = new ArrayList<>();
        JsonNode data = root.path(1);
        // "data" is null when the query matches nothing
        if (data.isArray()) {
            for (JsonNode item : data) {
                T mapped = mapItem.apply(item);
                if (mapped != null) {
                    items.add(mapped);
                }
            }
        }
        return new Page<>(meta.path("page").asInt(), meta.path("pages").asInt(), items);
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new WorldBankApiException("Malformed JSON: " + abbreviate(json), e);
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
