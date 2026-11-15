package ru.spbstu.ghanalytics.analytics.task1;

import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

/**
 * Interrupted time series: {@code y = b0 + b1*t + b2*D + b3*(t - k)*D}, where {@code t} is the quarter index,
 * {@code k} the first quarter after the event and {@code D = 1} from {@code k} on. {@code b2} is the jump in level
 * at the event, {@code b3} the change of the trend (per quarter).
 * <p>
 * Quarterly series are autocorrelated, which makes plain OLS standard errors too small. The p-values therefore use
 * Newey-West (HAC) standard errors with a Bartlett kernel; the Durbin-Watson statistic of the residuals is reported
 * alongside (about 2 = no autocorrelation, well below 2 = positive autocorrelation).
 */
public final class SegmentedRegression {

    private static final int PARAMETERS = 4;

    /**
     * @param slopeBefore  change per quarter before the event ({@code b1})
     * @param slopeAfter   change per quarter after the event ({@code b1 + b3})
     * @param pLevel       p-value of the jump {@code b2}, Newey-West standard error
     * @param pSlope       p-value of the trend change {@code b3}, Newey-West standard error
     * @param durbinWatson Durbin-Watson statistic of the OLS residuals
     */
    public record Fit(int n, double slopeBefore, double slopeAfter, double levelChange, double slopeChange,
                      double pLevel, double pSlope, double rSquared, double durbinWatson) {
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
        double[][] design = new double[n][PARAMETERS];
        for (int t = 0; t < n; t++) {
            double after = t >= breakIndex ? 1 : 0;
            x[t][0] = t;
            x[t][1] = after;
            x[t][2] = (t - breakIndex) * after;
            design[t] = new double[]{1, x[t][0], x[t][1], x[t][2]};
        }
        OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
        ols.newSampleData(y, x);
        double[] b = ols.estimateRegressionParameters();
        double[] residuals = ols.estimateResiduals();
        double[] se = neweyWestStandardErrors(design, residuals, lags(n));
        TDistribution t = new TDistribution(n - PARAMETERS);
        return new Fit(n, b[1], b[1] + b[3], b[2], b[3],
                pValue(t, b[2], se[2]), pValue(t, b[3], se[3]), ols.calculateRSquared(), durbinWatson(residuals));
    }

    /** Usual rule of thumb {@code floor(4 * (n / 100)^(2/9))}: 2 lags for 14-26 quarters. */
    static int lags(int n) {
        return (int) Math.floor(4 * Math.pow(n / 100.0, 2.0 / 9));
    }

    /**
     * Newey-West covariance {@code (X'X)^-1 S (X'X)^-1 * n / (n - k)}, where {@code S} sums {@code e_t^2 x_t x_t'}
     * plus Bartlett-weighted cross terms of residuals up to {@code lags} quarters apart.
     */
    static double[] neweyWestStandardErrors(double[][] x, double[] residuals, int lags) {
        int n = x.length;
        int k = x[0].length;
        RealMatrix design = MatrixUtils.createRealMatrix(x);
        RealMatrix xtxInverse = new LUDecomposition(design.transpose().multiply(design)).getSolver().getInverse();

        double[][] s = new double[k][k];
        for (int lag = 0; lag <= lags; lag++) {
            double weight = 1 - lag / (lags + 1.0);
            for (int t = lag; t < n; t++) {
                double ee = residuals[t] * residuals[t - lag];
                for (int i = 0; i < k; i++) {
                    for (int j = 0; j < k; j++) {
                        s[i][j] += lag == 0
                                ? ee * x[t][i] * x[t][j]
                                : weight * ee * (x[t][i] * x[t - lag][j] + x[t - lag][i] * x[t][j]);
                    }
                }
            }
        }
        RealMatrix covariance = xtxInverse.multiply(MatrixUtils.createRealMatrix(s)).multiply(xtxInverse)
                .scalarMultiply((double) n / (n - k));
        double[] se = new double[k];
        for (int i = 0; i < k; i++) {
            se[i] = Math.sqrt(Math.max(covariance.getEntry(i, i), 0));
        }
        return se;
    }

    static double durbinWatson(double[] residuals) {
        double num = 0;
        double den = residuals[0] * residuals[0];
        for (int t = 1; t < residuals.length; t++) {
            double d = residuals[t] - residuals[t - 1];
            num += d * d;
            den += residuals[t] * residuals[t];
        }
        return den == 0 ? Double.NaN : num / den;
    }

    private static double pValue(TDistribution t, double estimate, double standardError) {
        if (standardError == 0) {
            return estimate == 0 ? 1 : 0;
        }
        return 2 * (1 - t.cumulativeProbability(Math.abs(estimate / standardError)));
    }
}
