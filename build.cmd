@echo off
setlocal
call "%~dp0mvnw.cmd" clean package %*
set "exitCode=%ERRORLEVEL%"
endlocal & exit /b %exitCode%
