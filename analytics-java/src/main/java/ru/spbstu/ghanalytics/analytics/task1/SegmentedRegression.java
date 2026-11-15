package ru.spbstu.ghanalytics.analytics.task1;

import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

/**
 * Interrupted time series: {@code y = b0 + b1*t + b2*D + b3*(t - k)*D}, where {@code t} is the quarter index,
 * {@code k} the first quarter after the event and {@code D = 1} from {@code k} on. {@code b2} is the jump in level
 * at the event, {@code b3} the change of the trend (per quarter).
 * <p>
 * p-values assume independent errors; quarterly series are autocorrelated and seasonal, so read them as rough.
 */
public final class SegmentedRegression {

    /**
     * @param slopeBefore change per quarter before the event ({@code b1})
     * @param slopeAfter  change per quarter after the event ({@code b1 + b3})
     */
    public record Fit(int n, double slopeBefore, double slopeAfter, double levelChange, double slopeChange,
                      double pLevel, double pSlope, double rSquared) {
    }

    private SegmentedRegression() {
    }

    /** @param breakIndex index of the first point after the event; at least 2 points on each side */
    public static Fit fit(double[] y, int breakIndex) {
        int n = y.length;
        if (breakIndex < 2 || n - breakIndex < 2 || n < 6) {
            throw new IllegalArgumentException("Need >= 2 points on each side and >= 6 in total, got n=" + n
                    + ", break at " + breakIndex);
        }
        double[][] x = new double[n][3];
        for (int t = 0; t < n; t++) {
            double after = t >= breakIndex ? 1 : 0;
            x[t][0] = t;
            x[t][1] = after;
            x[t][2] = (t - breakIndex) * after;
        }
        OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
        ols.newSampleData(y, x);
        double[] b = ols.estimateRegressionParameters();
        double[] se = ols.estimateRegressionParametersStandardErrors();
        TDistribution t = new TDistribution(n - 4);
        return new Fit(n, b[1], b[1] + b[3], b[2], b[3],
                pValue(t, b[2], se[2]), pValue(t, b[3], se[3]), ols.calculateRSquared());
    }

    private static double pValue(TDistribution t, double estimate, double standardError) {
        if (standardError == 0) {
            return estimate == 0 ? 1 : 0;
        }
        return 2 * (1 - t.cumulativeProbability(Math.abs(estimate / standardError)));
    }
}
