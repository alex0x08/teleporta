:b
@echo off
:: self script name
set SELF_SCRIPT=%0
:: do we have quotes in self name?
set q=n
:: on Windows you can run application without extension, so we need to detect full self path to run Java later
if [^%SELF_SCRIPT:~-1%] == [^"] ( 
	set ext=%SELF_SCRIPT:~-4,3%
	set q=y
 ) else ( 
	set ext=%SELF_SCRIPT:~-3%
 )
:: add .cmd extension to self name, if user started us without it 
if /i not "%ext%" == "cmd" ( 
	:: deal with quotes
	if "%q%" == "y" ( 
		set SELF_SCRIPT=%SELF_SCRIPT:~0,-1%.cmd"
	) else ( 
		set SELF_SCRIPT=%SELF_SCRIPT%.cmd
	) 
)

:: path to unpacked JRE
set UNPACKED_JRE_DIR=%UserProfile%\.jre
:: path to unpacked JRE binary
set UNPACKED_JRE=%UNPACKED_JRE_DIR%\jre\bin\java.exe

IF exist %UNPACKED_JRE% (goto :RunJavaUnpacked)

where java 2>NUL
if "%ERRORLEVEL%"=="0" (call :JavaFound) else (call :DownloadJava)
goto :EOF
:JavaFound
set JRE=java
echo Java found in PATH, checking version..
set JAVA_VERSION=0
for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do (
  set JAVA_VERSION=%%g
)
set JAVA_VERSION=%JAVA_VERSION:"=%
for /f "delims=.-_ tokens=1-2" %%v in ("%JAVA_VERSION%") do (
  if /I "%%v" EQU "1" (
    set JAVA_VERSION=%%w
  ) else (
    set JAVA_VERSION=%%v
  )
)

if %JAVA_VERSION% LSS 11 (goto :DownloadJava) else (goto :RunJava)

:DownloadJava
echo JRE not found in PATH, trying to download..

WHERE curl
IF %ERRORLEVEL% NEQ 0 (call :ExitError "curl wasn't found in PATH, cannot download JRE")  

WHERE tar
IF %ERRORLEVEL% NEQ 0 (call :ExitError "tar wasn't found in PATH, cannot download JRE")  

curl.exe -o %TEMP%\jre.zip  -C - https://nexus.nuiton.org/nexus/content/repositories/jvm/com/oracle/jre/1.8.121/jre-1.8.121-windows-i586.zip

IF not exist %UNPACKED_JRE_DIR% (mkdir %UNPACKED_JRE_DIR%)

tar -xf %TEMP%\jre.zip -C %UNPACKED_JRE_DIR%

:RunJavaUnpacked
set JRE=%UNPACKED_JRE_DIR%\jre\bin\java.exe

:RunJava
echo %JRE% 
:: note: there should be exact 2 spaces between start and %JRE% !
::pause
chcp 65001
%JRE% -Dfile.encoding=UTF-8 -jar %SELF_SCRIPT% %*
goto :EOF

:ExitError
echo Found Error: %0
pause
:EOF
exit

