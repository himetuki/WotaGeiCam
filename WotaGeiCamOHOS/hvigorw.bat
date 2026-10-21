@echo off
rem Hvigor launcher: delegates to the DevEco Studio bundled wrapper.
rem See WotaGeiCamOHOS/README.md for why hvigorw.js is not kept in-project.
setlocal

set "WRAPPER_MODULE_PATH="
if defined HVIGOR_WRAPPER_MODULE if exist "%HVIGOR_WRAPPER_MODULE%" set "WRAPPER_MODULE_PATH=%HVIGOR_WRAPPER_MODULE%"
if not defined WRAPPER_MODULE_PATH if defined DEVECO_HOME if exist "%DEVECO_HOME%\tools\hvigor\bin\hvigorw.js" set "WRAPPER_MODULE_PATH=%DEVECO_HOME%\tools\hvigor\bin\hvigorw.js"

if defined WRAPPER_MODULE_PATH node "%WRAPPER_MODULE_PATH%" %*
if not defined WRAPPER_MODULE_PATH echo ERROR: set DEVECO_HOME or HVIGOR_WRAPPER_MODULE, see README.md

endlocal
