@echo off
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

java -cp "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
