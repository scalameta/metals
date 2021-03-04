@echo off

rem This is a wrapper script, that automatically download mill from GitHub release pages
rem You can give the required mill version with --mill-version parameter
rem If no version is given, it falls back to the value of DEFAULT_MILL_VERSION
rem
rem Project page: https://github.com/lefou/millw
rem
rem If you want to improve this script, please also contribute your changes back!
rem
rem Licensed under the Apache License, Version 2.0

rem setlocal seems to be unavailable on Windows 95/98/ME
rem but I don't think we need to support them in 2019
setlocal enabledelayedexpansion

set "DEFAULT_MILL_VERSION=0.9.5"

set "MILL_REPO_URL=https://github.com/com-lihaoyi/mill"

rem %~1% removes surrounding quotes
if [%~1%]==[--mill-version] (
    rem shift command doesn't work within parentheses
    if not [%~2%]==[] (
        set MILL_VERSION=%~2%
        set "STRIP_VERSION_PARAMS=true"
    ) else (
        echo You specified --mill-version without a version.
        echo Please provide a version that matches one provided on
        echo %MILL_REPO_URL%/releases
        exit /b 1
    )
)

if [!MILL_VERSION!]==[] (
  if exist .mill-version (
      set /p MILL_VERSION=<.mill-version
  )
)

if [!MILL_VERSION!]==[] (
    set MILL_VERSION=%DEFAULT_MILL_VERSION%
)

set MILL_DOWNLOAD_PATH=%USERPROFILE%\.mill\download

rem without bat file extension, cmd doesn't seem to be able to run it
set MILL=%MILL_DOWNLOAD_PATH%\!MILL_VERSION!.bat

if not exist "%MILL%" (
    set VERSION_PREFIX=%MILL_VERSION:~0,4%
    set DOWNLOAD_SUFFIX=-assembly
    if [!VERSION_PREFIX!]==[0.0.] set DOWNLOAD_SUFFIX=
    if [!VERSION_PREFIX!]==[0.1.] set DOWNLOAD_SUFFIX=
    if [!VERSION_PREFIX!]==[0.2.] set DOWNLOAD_SUFFIX=
    if [!VERSION_PREFIX!]==[0.3.] set DOWNLOAD_SUFFIX=
    if [!VERSION_PREFIX!]==[0.4.] set DOWNLOAD_SUFFIX=
    set VERSION_PREFIX=

    for /F "delims=-" %%A in ("!MILL_VERSION!") do (
        set MILL_BASE_VERSION=%%A
    )

    rem there seems to be no way to generate a unique temporary file path (on native Windows)
    set DOWNLOAD_FILE=%MILL%.tmp

    set DOWNLOAD_URL=%MILL_REPO_URL%/releases/download/!MILL_BASE_VERSION!/!MILL_VERSION!!DOWNLOAD_SUFFIX!

    echo Downloading mill %MILL_VERSION% from %MILL_REPO_URL%/releases ...

    if not exist "%MILL_DOWNLOAD_PATH%" mkdir "%MILL_DOWNLOAD_PATH%"
    rem curl is bundled with recent Windows 10
    rem but I don't think we can expect all the users to have it in 2019
    where /Q curl
    if %ERRORLEVEL% EQU 0 (
        curl -L "!DOWNLOAD_URL!" -o "!DOWNLOAD_FILE!"
    ) else (
        rem bitsadmin seems to be available on Windows 7
        rem without /dynamic, github returns 403
        rem bitsadmin is sometimes needlessly slow but it looks better with /priority foreground
        bitsadmin /transfer millDownloadJob /dynamic /priority foreground "!DOWNLOAD_URL!" "!DOWNLOAD_FILE!"
    )
    if not exist "!DOWNLOAD_FILE!" (
        echo Could not download mill %MILL_VERSION%
        exit /b 1
    )

    move /y "!DOWNLOAD_FILE!" "%MILL%"

    set DOWNLOAD_FILE=
    set DOWNLOAD_SUFFIX=
)

set MILL_DOWNLOAD_PATH=
set MILL_VERSION=
set MILL_REPO_URL=

set MILL_PARAMS=%*

if defined STRIP_VERSION_PARAMS (
    for /f "tokens=1-2*" %%a in ("%*") do (
        rem strip %%a - It's the "--mill-version" option.
        rem strip %%b - it's the version number that comes after the option.
        rem keep  %%c - It's the remaining options.
        set MILL_PARAMS=%%c
    )
)

"%MILL%" %MILL_PARAMS%
