@echo off

set HTTP_PROXY=-Dhttp.proxyHost=169.254.0.183 -Dhttp.proxyPort=8080 -Dhttps.proxyHost=169.254.0.183 -Dhttps.proxyPort=8080
set JAR_NAME=spark-test-1.0.jar

if "x%1" == "x" (
 echo ===============================================================================
 echo Usage:
 echo Setup http proxy address in the 'run.bat'.
 echo Proxy external IP must be registered in SPARK.
 echo.
 echo "run.bat <login> <password> [<requests count>]"
 echo ===============================================================================
 goto END
)

if "x%JAVA_HOME%" == "x" (
  set  JAVA=java
  echo JAVA_HOME is not set. Unexpected results may occur.
  echo Set JAVA_HOME to the directory of your local JDK to avoid this message.
) else (
  if not exist "%JAVA_HOME%" (
    echo JAVA_HOME "%JAVA_HOME%" path doesn't exist
    goto END
   ) else (
     if not exist "%JAVA_HOME%\bin\java.exe" (
       echo "%JAVA_HOME%\bin\java.exe" does not exist
       goto END_NO_PAUSE
     )
      echo Setting JAVA property to "%JAVA_HOME%\bin\java"
    set "JAVA=%JAVA_HOME%\bin\java"
  )
)

"%JAVA%" %JAVA_OPTS% %HTTP_PROXY% -jar %JAR_NAME% %1 %2 %3
goto END_NO_PAUSE

:END
if "x%NOPAUSE%" == "x" pause

:END_NO_PAUSE