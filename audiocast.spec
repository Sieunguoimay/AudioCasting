# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec file for AudioCast GUI

import os

block_cipher = None
script_dir = os.path.dirname(os.path.abspath(SPEC))

a = Analysis(
    [os.path.join(script_dir, 'AudioCast GUI.py')],
    pathex=[script_dir],
    binaries=[],
    datas=[],
    hiddenimports=['pystray._win32', 'PIL._tkinter_finder'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['matplotlib', 'numpy', 'scipy', 'pandas'],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='AudioCast',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=os.path.join(script_dir, 'audiocast.ico'),
)
