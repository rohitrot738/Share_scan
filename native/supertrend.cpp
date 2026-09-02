#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>

extern "C" int share_scan_supertrend(
    const double* high,
    const double* low,
    const double* close,
    int size,
    int window,
    double multiplier,
    double* trend,
    double* direction
) {
    if (!high || !low || !close || !trend || !direction || size < 1 || window < 1) {
        return -1;
    }

    const double missing = std::numeric_limits<double>::quiet_NaN();
    std::vector<double> upper(size, missing);
    std::vector<double> lower(size, missing);
    std::vector<double> ranges(size, missing);
    double rolling_sum = 0.0;

    for (int i = 0; i < size; ++i) {
        const double intraday = high[i] - low[i];
        double true_range = intraday;
        if (i > 0) {
            true_range = std::max({intraday, std::abs(high[i] - close[i - 1]), std::abs(low[i] - close[i - 1])});
        }
        ranges[i] = true_range;
        rolling_sum += true_range;
        if (i >= window) {
            rolling_sum -= ranges[i - window];
        }
        if (i >= window - 1) {
            const double atr = rolling_sum / static_cast<double>(window);
            const double midpoint = (high[i] + low[i]) / 2.0;
            upper[i] = midpoint + multiplier * atr;
            lower[i] = midpoint - multiplier * atr;
        }
    }

    direction[0] = 1.0;
    trend[0] = lower[0];
    for (int i = 1; i < size; ++i) {
        if (close[i] > upper[i - 1]) {
            direction[i] = 1.0;
        } else if (close[i] < lower[i - 1]) {
            direction[i] = -1.0;
        } else {
            direction[i] = direction[i - 1];
        }
        trend[i] = direction[i] > 0.0 ? lower[i] : upper[i];
    }
    return 0;
}
