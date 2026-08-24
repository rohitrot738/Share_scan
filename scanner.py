import argparse
import json
from pathlib import Path
import pandas as pd
from config import ScannerConfig
from multi_timeframe import analyse_timeframes

REQUIRED = ['timestamp', 'open', 'high', 'low', 'close', 'volume']


def load_csv(path):
    df = pd.read_csv(path)
    missing = [c for c in REQUIRED if c not in df.columns]
    if missing:
        raise ValueError(f'{path}: missing columns {missing}')
    df['timestamp'] = pd.to_datetime(df['timestamp'])
    return df.sort_values('timestamp').reset_index(drop=True)


def main():
    parser = argparse.ArgumentParser(description='Ghost Move Pro pre-breakout scanner')
    parser.add_argument('--symbol', required=True)
    parser.add_argument('--5m')
    parser.add_argument('--15m')
    parser.add_argument('--30m')
    parser.add_argument('--1h')
    parser.add_argument('--json-out')
    args = parser.parse_args()

    paths = {
        '5m': getattr(args, '5m'),
        '15m': getattr(args, '15m'),
        '30m': getattr(args, '30m'),
        '1h': getattr(args, '1h'),
    }
    data = {tf: load_csv(path) for tf, path in paths.items() if path}
    if not data:
        raise SystemExit('Provide at least one timeframe CSV.')

    result = {'symbol': args.symbol, **analyse_timeframes(data, ScannerConfig())}
    text = json.dumps(result, indent=2)
    print(text)

    if args.json_out:
        Path(args.json_out).write_text(text, encoding='utf-8')


if __name__ == '__main__':
    main()
