@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\bootstrap-maven.ps1"
if errorlevel 1 exit /b 1
call "%~dp0.cache\apache-maven-3.9.11\bin\mvn.cmd" -f "%~dp0pom.xml" %*
exit /b %errorlevel%
