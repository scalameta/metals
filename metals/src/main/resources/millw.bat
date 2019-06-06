@echo off

rem This is a wrapper script, that automatically download mill from GitHub release pages
rem You can give the required mill version with --mill-version parameter
rem If no version is given, it falls back to the value of DEFAULT_MILL_VERSION

rem setlocal seems to be unavailable on Windows 95/98/ME
rem but I don't think we need to support them in 2019
setlocal enabledelayedexpansion

set DEFAULT_MILL_VERSION=0.3.6

if exist .mill-version (
    set /p MILL_VERSION= < .mill-version
)

rem %~1% removes surrounding quotes
if "%~1%"=="--mill-version" (
    rem shift command doesn't work within parentheses
    if not "%~2%"=="" (
        set MILL_VERSION=%~2%
    ) else (
        echo You specified --mill-version without a version.
        echo Please provide a version that matches one provided on
        echo https://github.com/lihaoyi/mill/releases
        exit /b 1
    )
)

if "%MILL_VERSION%"=="" (
    set MILL_VERSION=%DEFAULT_MILL_VERSION%
)

set MILL_DOWNLOAD_PATH=%USERPROFILE%\.mill\download

rem without bat file extension, cmd doesn't seem to be able to run it
set MILL=%MILL_DOWNLOAD_PATH%\%MILL_VERSION%.bat

if not exist "%MILL%" (
    rem there seems to be no way to generate a unique temporary file path (on native Windows)
    set DOWNLOAD_FILE=%MILL%.tmp

    rem curl is bundled with recent Windows 10
    rem but I don't think we can expect all the users to have it in 2019
    rem bitadmin seems to be available on Windows 7
    rem without /dynamic, github returns 403
    rem bitadmin is sometimes needlessly slow but it looks better with /priority foreground
    if not exist "%MILL_DOWNLOAD_PATH%" mkdir "%MILL_DOWNLOAD_PATH%"
    bitsadmin /transfer millDownloadJob /dynamic /priority foreground "https://github.com/lihaoyi/mill/releases/download/%MILL_VERSION%/%MILL_VERSION%" "!DOWNLOAD_FILE!"

    move /y "!DOWNLOAD_FILE!" "%MILL%"
    set DOWNLOAD_FILE=
)

set MILL_DOWNLOAD_PATH=
set MILL_VERSION=

%MILL% %*