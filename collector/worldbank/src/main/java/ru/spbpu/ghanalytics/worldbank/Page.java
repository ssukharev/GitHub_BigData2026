package ru.spbpu.ghanalytics.worldbank;

import java.util.List;

/** One page of a paginated World Bank API response. */
record Page<T>(int page, int pages, List<T> items) {
}
