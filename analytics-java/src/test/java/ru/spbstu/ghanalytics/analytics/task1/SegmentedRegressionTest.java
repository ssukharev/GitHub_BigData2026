package ru.spbstu.ghanalytics.analytics.task1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentedRegressionTest {

    @Test
    void recoversExactBreak() {
        // y = 10 + 1*t before t = 6; from t = 6 on: jump +5 and slope 3
        double[] y = new double[12];
        for (int t = 0; t < y.length; t++) {
            y[t] = t < 6 ? 10 + t : 10 + t + 5 + 2 * (t - 6);
        }

        SegmentedRegression.Fit fit = SegmentedRegression.fit(y, 6);

        assertEquals(1.0, fit.slopeBefore(), 1e-9);
        assertEquals(3.0, fit.slopeAfter(), 1e-9);
        assertEquals(5.0, fit.levelChange(), 1e-9);
        assertEquals(2.0, fit.slopeChange(), 1e-9);
        assertEquals(1.0, fit.rSquared(), 1e-9);
        assertEquals(12, fit.n());
    }

    @Test
    void noBreakGivesHighPValues() {
        // a straight line with alternating noise: no change at the break
        double[] y = new double[16];
        for (int t = 0; t < y.length; t++) {
            y[t] = 2 * t + (t % 2 == 0 ? 0.5 : -0.5);
        }

        SegmentedRegression.Fit fit = SegmentedRegression.fit(y, 8);

        assertEquals(2.0, fit.slopeBefore(), 0.2);
        assertTrue(fit.pLevel() > 0.05, "p_level " + fit.pLevel());
        assertTrue(fit.pSlope() > 0.05, "p_slope " + fit.pSlope());
    }

    @Test
    void rejectsTooFewPoints() {
        assertThrows(IllegalArgumentException.class, () -> SegmentedRegression.fit(new double[]{1, 2, 3, 4, 5, 6}, 1));
        assertThrows(IllegalArgumentException.class, () -> SegmentedRegression.fit(new double[]{1, 2, 3, 4, 5}, 2));
    }
}
