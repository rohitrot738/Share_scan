# -*- mode: python ; coding: utf-8 -*-
from PyInstaller.utils.hooks import collect_submodules, collect_data_files

hiddenimports = []
for pkg in ("pandas", "numpy", "yfinance", "requests", "lxml", "growwapi"):
    try:
        hiddenimports += collect_submodules(pkg)
    except Exception:
        pass

datas = []
for pkg in ("yfinance", "lxml"):
    try:
        datas += collect_data_files(pkg)
    except Exception:
        pass

block_cipher = None

a = Analysis(
    ["live_scan.py"],
    pathex=["."],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="ShareScan",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
)
