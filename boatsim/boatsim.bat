@echo off
REM Launch the MyNavvy boat simulator / helm.
REM Uses Python 3.14 (py -3.14) because the map widget (tkintermapview) is
REM installed there. The emulator (or a tablet on adb) must be running a DEBUG
REM MyNavvy build. If the map is missing, install the dep:
REM     py -3.14 -m pip install --user tkintermapview
cd /d "%~dp0"
py -3.14 boatsim.py %*
