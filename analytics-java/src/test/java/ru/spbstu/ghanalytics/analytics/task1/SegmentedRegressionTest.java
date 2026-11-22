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

    /** y = t + residual pattern; returns the design matrix with intercept and the residuals of a straight-line fit. */
    private static double[] pattern(int n, int period) {
        double[] e = new double[n];
        for (int t = 0; t < n; t++) {
            e[t] = (t / period) % 2 == 0 ? 1 : -1;
        }
        return e;
    }

    private static double[][] design(int n) {
        double[][] x = new double[n][2];
        for (int t = 0; t < n; t++) {
            x[t] = new double[]{1, t};
        }
        return x;
    }

    @Test
    void neweyWestWidensErrorsForPositiveAutocorrelation() {
        // runs of three equal residuals: strong positive autocorrelation
        double[] e = pattern(24, 3);

        double[] white = SegmentedRegression.neweyWestStandardErrors(design(24), e, 0);
        double[] hac = SegmentedRegression.neweyWestStandardErrors(design(24), e, 2);

        assertTrue(hac[1] > white[1], "HAC " + hac[1] + " vs White " + white[1]);
        assertTrue(SegmentedRegression.durbinWatson(e) < 1.5, "DW " + SegmentedRegression.durbinWatson(e));
    }

    @Test
    void durbinWatsonOfAlternatingResidualsIsAboveTwo() {
        assertTrue(SegmentedRegression.durbinWatson(pattern(20, 1)) > 3);
    }

    @Test
    void lagsFollowTheRuleOfThumb() {
        assertEquals(2, SegmentedRegression.lags(14));
        assertEquals(2, SegmentedRegression.lags(26));
        assertEquals(4, SegmentedRegression.lags(100));
    }

    @Test
    void rejectsTooFewPoints() {
        assertThrows(IllegalArgumentException.class, () -> SegmentedRegression.fit(new double[]{1, 2, 3, 4, 5, 6}, 1));
        assertThrows(IllegalArgumentException.class, () -> SegmentedRegression.fit(new double[]{1, 2, 3, 4, 5}, 2));
    }
}
