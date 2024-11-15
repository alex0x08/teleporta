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

IF exist %UNPACKED_JRE% >NUL (goto :RunJavaUnpacked)

where java 2 >NUL
if "%ERRORLEVEL%"=="0" (call :JavaFound1) else (call :CheckJavaHome)
goto :EOF
:CheckJavaHome
if defined JAVA_HOME (
    echo JAVA_HOME="%JAVA_HOME%"
	set JRE=%JAVA_HOME%\bin\java.exe
	goto :JavaFound2
) else (
    echo JAVA_HOME is not defined
	goto :CheckJavaReg	
)


:CheckJavaReg
setlocal enableextensions disabledelayedexpansion

    :: possible locations under HKLM\SOFTWARE of JavaSoft registry data
    set "javaNativeVersion="
    set "java32ON64=Wow6432Node\"

    :: for variables
    ::    %%k = HKLM\SOFTWARE subkeys where to search for JavaSoft key
    ::    %%j = full path of "Java Runtime Environment" key under %%k
    ::    %%v = current java version
    ::    %%e = path to java

   set "javaDir="
    set "javaVersion="
    for %%k in ( "%javaNativeVersion%" "%java32ON64%") do if not defined javaDir (
        for %%j in (
            "HKLM\SOFTWARE\%%~kJavaSoft\Java Runtime Environment"
        ) do for /f "tokens=3" %%v in (
            'reg query "%%~j" /v "CurrentVersion" 2^>nul ^| find /i "CurrentVersion"'
        ) do for /f "tokens=2,*" %%d in (
            'reg query "%%~j\%%v" /v "JavaHome"   2^>nul ^| find /i "JavaHome"'
        ) do ( set "javaDir=%%~e" & set "javaVersion=%%v" )
    )

    if not defined javaDir (
        echo Java not found
		goto :DownloadJava
    ) else (
        ::echo JAVA_HOME="%javaDir%"
        ::echo JAVA_VERSION="%javaVersion%"
		set "JRE=%javaDir%\bin\java.exe"
		goto :JavaFound2
	)

    endlocal

:JavaFound1
set JRE=java

:JavaFound2
:: Java found in PATH, checking version..
set JAVA_VERSION=0
for /f "tokens=3" %%g in ('^""%JRE%" -version 2^>^&1 ^| findstr /i "version"^"') do (
  set JAVA_VERSION=%%g
)
echo ver1: %JAVA_VERSION%
set JAVA_VERSION=%JAVA_VERSION:"=%
for /f "delims=.-_ tokens=1-2" %%v in ("%JAVA_VERSION%") do (
  if /I "%%v" EQU "1" (
    set JAVA_VERSION=%%w
  ) else (
    set JAVA_VERSION=%%v
  )
)
if %JAVA_VERSION% LSS 8 (goto :DownloadJava) else (goto :RunJava)

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
::echo %JRE% 
::pause
::chcp 65001 >NUL
::-Dfile.encoding=UTF-8
"%JRE%" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dconsole.encoding=UTF-8 -jar "%SELF_SCRIPT%" %*
goto :EOF

:ExitError
echo Found Error: %0
pause
:EOF
exit

