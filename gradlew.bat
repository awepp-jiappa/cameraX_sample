@ECHO OFF
SETLOCAL
where gradle >NUL 2>&1
IF %ERRORLEVEL% NEQ 0 (
  ECHO Gradle executable not found in PATH. Please install Gradle or add Gradle Wrapper files.
  EXIT /B 1
)
CALL gradle %*
