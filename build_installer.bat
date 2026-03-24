@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo   AudioCast Installer Builder
echo ============================================
echo.

:: Step 1 - Build Rust server
echo [1/3] Building Rust server...
cd server
cargo build --release
if %errorlevel% neq 0 (
    echo ERROR: Cargo build failed.
    pause
    exit /b 1
)
cd ..
echo       Done: server\target\release\audiocast-server.exe
echo.

:: Step 2 - Bundle Python GUI with PyInstaller
echo [2/3] Bundling GUI with PyInstaller...
pip install pystray Pillow pyinstaller --quiet 2>nul
pyinstaller audiocast.spec --noconfirm
if %errorlevel% neq 0 (
    echo ERROR: PyInstaller build failed.
    pause
    exit /b 1
)
echo       Done: dist\AudioCast.exe
echo.

:: Step 3 - Build installer with Inno Setup
echo [3/3] Building installer with Inno Setup...
where iscc >nul 2>nul
if %errorlevel% neq 0 (
    :: Try common install paths
    set "ISCC="
    if exist "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" set "ISCC=C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
    if exist "C:\Program Files\Inno Setup 6\ISCC.exe" set "ISCC=C:\Program Files\Inno Setup 6\ISCC.exe"

    if defined ISCC (
        "%ISCC%" installer.iss
    ) else (
        echo.
        echo WARNING: Inno Setup not found.
        echo Install it from https://jrsoftware.org/isdl.php
        echo Then run:  iscc installer.iss
        echo.
        echo The portable files are still available at:
        echo   dist\AudioCast.exe
        echo   server\target\release\audiocast-server.exe
        echo You can copy both to a folder and run AudioCast.exe directly.
        pause
        exit /b 0
    )
) else (
    iscc installer.iss
)

if %errorlevel% neq 0 (
    echo ERROR: Inno Setup build failed.
    pause
    exit /b 1
)

echo.
echo ============================================
echo   Build complete!
echo   Installer: installer_output\AudioCast-Setup-1.0.0.exe
echo ============================================
pause
