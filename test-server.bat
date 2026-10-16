@echo off
REM ---------------------------------------------------------------------------
REM  Hycon local test server
REM
REM  Builds the plugin and starts a Hytale server with it loaded. The server
REM  runs in .\run (ignored by git). Type commands into this window, e.g.
REM  "/auth login" once, then join from Hytale via Servers -> localhost.
REM  Stop with "stop" or Ctrl+C.
REM ---------------------------------------------------------------------------
cd /d "%~dp0"
call gradlew.bat runServer --console=plain
pause
