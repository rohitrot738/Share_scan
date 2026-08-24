import pandas as pd


def relative_strength(stock: pd.DataFrame, benchmark: pd.DataFrame, window: int = 20):
    s = stock['close'].pct_change(window).iloc[-1]
    b = benchmark['close'].pct_change(window).iloc[-1]
    if pd.isna(s) or pd.isna(b):
        return {'stock_return':0.0,'benchmark_return':0.0,'relative_strength':0.0}
    rs = float(s-b)
    return {
        'stock_return': round(float(s)*100,2),
        'benchmark_return': round(float(b)*100,2),
        'relative_strength': round(rs*100,2),
        'outperforming': bool(rs>0)
    }
