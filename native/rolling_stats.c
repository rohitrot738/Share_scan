#include <math.h>
#include <stddef.h>

int share_scan_rolling_mad(
    const double* values,
    int size,
    int window,
    double* output
) {
    if (!values || !output || size < 1 || window < 1) {
        return -1;
    }
    for (int i = 0; i < size; ++i) {
        if (i < window - 1) {
            output[i] = NAN;
            continue;
        }
        double mean = 0.0;
        for (int j = i - window + 1; j <= i; ++j) {
            mean += values[j];
        }
        mean /= (double)window;
        double deviation = 0.0;
        for (int j = i - window + 1; j <= i; ++j) {
            deviation += fabs(values[j] - mean);
        }
        output[i] = deviation / (double)window;
    }
    return 0;
}
