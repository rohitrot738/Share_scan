from __future__ import annotations
import subprocess
from pathlib import Path

STAGES = [
    'macd,vwap,bollinger',
    'macd,vwap,bollinger,stochastic',
    'macd,vwap,bollinger,stochastic,adx-dmi',
    'macd,vwap,bollinger,stochastic,adx-dmi,cci',
    'macd,vwap,bollinger,stochastic,adx-dmi,cci,mfi',
    'macd,vwap,bollinger,stochastic,adx-dmi,cci,mfi,obv',
    'macd,vwap,bollinger,stochastic,adx-dmi,cci,mfi,obv,roc',
    'macd,vwap,bollinger,stochastic,adx-dmi,cci,mfi,obv,roc,atr',
    'macd,vwap,bollinger,stochastic,adx-dmi,cci,mfi,obv,roc,atr,supertrend',
    'macd,vwap,bollinger,stochastic,adx-dmi,cci,mfi,obv,roc,atr,supertrend,ichimoku',
    'macd,vwap,bollinger,stochastic,adx-dmi,cci,mfi,obv,roc,atr,supertrend,ichimoku,pivots',
    'macd,vwap,bollinger,stochastic,adx-dmi,cci,mfi,obv,roc,atr,supertrend,ichimoku,pivots,support-resistance',
]

ACTIVE = Path('active_indicator.txt')

for index, stage in enumerate(STAGES, start=3):
    ACTIVE.write_text(stage + '\n', encoding='utf-8')
    print(f'\n===== CUMULATIVE STAGE {index}: {stage} =====', flush=True)
    subprocess.run(['python', 'live_scan.py', '--top', '100', '--shortlist', '500'], check=True)
    print(f'===== STAGE {index} PASS =====', flush=True)

print('\nALL TECHNICAL CUMULATIVE STAGES PASS', flush=True)
