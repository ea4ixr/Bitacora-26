@echo off
setlocal
cd /d "%~dp0"

rem ============================================================
rem  Genera la carpeta "salida\Bitacora 26" con "Bitacora 26.exe"
rem  (no necesita instalar nada ni tener Java en el otro PC).
rem  ANTES hay que crear el JAR con Maven (ver LEEME.txt, paso 1).
rem ============================================================

set "APP_NAME=Bitacora 26"
rem Version del programa: se lee SOLA de LibroGuardiaUI.java (VERSION_APP). Ya no se escribe aqui,
rem asi el programa y el paquete siempre dicen la misma version.
set "APP_VERSION="
for /f "tokens=2 delims==" %%V in ('findstr /c:"VERSION_APP = " "src\main\java\LibroGuardiaUI.java"') do set "APP_VERSION=%%V"
if defined APP_VERSION set "APP_VERSION=%APP_VERSION:"=%"
if defined APP_VERSION set "APP_VERSION=%APP_VERSION:;=%"
if defined APP_VERSION set "APP_VERSION=%APP_VERSION: =%"
if "%APP_VERSION%"=="" (
  echo [ERROR] No encuentro VERSION_APP en src\main\java\LibroGuardiaUI.java
  pause
  exit /b 1
)
echo Version: %APP_VERSION%
set MAIN_JAR=generador_PDF-1.0-SNAPSHOT-jar-with-dependencies.jar
set OUT_DIR=salida
set TMP_DIR=_tmp_jpackage

rem Si el script no encuentra solo el JDK 25, escribe aqui su carpeta.
rem Ejemplo:  set JDK_HOME=C:\Users\TuUsuario\.jdks\openjdk-25
set JDK_HOME=

rem Pon --win-console para que se abra una ventana negra con los mensajes de error.
set CONSOLE_OPT=

rem --- Buscar un JDK 25 automaticamente (IntelliJ los guarda en .jdks) ---
if "%JDK_HOME%"=="" for /d %%D in ("%USERPROFILE%\.jdks\*25*") do if exist "%%D\bin\jpackage.exe" set "JDK_HOME=%%D"
if "%JDK_HOME%"=="" for /d %%D in ("%ProgramFiles%\Java\jdk-25*") do if exist "%%D\bin\jpackage.exe" set "JDK_HOME=%%D"
if "%JDK_HOME%"=="" for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-25*") do if exist "%%D\bin\jpackage.exe" set "JDK_HOME=%%D"
if "%JDK_HOME%"=="" for /d %%D in ("%ProgramFiles%\Microsoft\jdk-25*") do if exist "%%D\bin\jpackage.exe" set "JDK_HOME=%%D"

if not "%JDK_HOME%"=="" set "PATH=%JDK_HOME%\bin;%PATH%"

where jpackage >nul 2>&1
if errorlevel 1 (
  echo.
  echo [ERROR] No encuentro jpackage.
  echo Edita este archivo y escribe en JDK_HOME la carpeta de tu JDK 25.
  echo En IntelliJ: File - Project Structure - SDKs te muestra donde esta.
  echo.
  pause
  exit /b 1
)

set "JPV="
for /f "tokens=1 delims=." %%V in ('jpackage --version') do set "JPV=%%V"
echo Usando jpackage de la version %JPV%:
where jpackage
if not "%JPV%"=="25" (
  echo.
  echo [ERROR] El jpackage encontrado es de la version %JPV% y hace falta la 25.
  echo Edita este archivo y escribe en JDK_HOME la carpeta de tu JDK 25.
  echo En IntelliJ: File - Project Structure - SDKs te muestra donde esta.
  echo.
  pause
  exit /b 1
)

if not exist "target\%MAIN_JAR%" (
  echo.
  echo [ERROR] No existe target\%MAIN_JAR%
  echo Primero genera el JAR en IntelliJ: panel Maven - Lifecycle - clean, y luego package.
  echo.
  pause
  exit /b 1
)

rem --- El JAR tiene que ser MAS NUEVO que el codigo: si no, falta compilar y el paquete saldria con la version vieja ---
powershell -NoProfile -Command "$j=(Get-Item 'target\%MAIN_JAR%').LastWriteTime; $f=Get-ChildItem 'src\main\java' -Recurse -Filter *.java | Sort-Object LastWriteTime -Descending | Select-Object -First 1; if ($f.LastWriteTime -gt $j) { Write-Host ('El archivo ' + $f.Name + ' es mas nuevo que el JAR.'); exit 1 }"
if errorlevel 1 (
  echo.
  echo [ERROR] Has cambiado codigo y no has compilado despues.
  echo Haz antes en IntelliJ: Maven - clean y luego Maven - package. Despues vuelve a ejecutar este archivo.
  echo.
  pause
  exit /b 1
)

echo Preparando archivos...
if exist "%TMP_DIR%" rmdir /s /q "%TMP_DIR%"
mkdir "%TMP_DIR%"
copy /y "target\%MAIN_JAR%" "%TMP_DIR%\" >nul
copy /y "jacob.jar" "%TMP_DIR%\" >nul
copy /y "jacob-1.21-x64.dll" "%TMP_DIR%\" >nul
copy /y "jacob-1.21-x86.dll" "%TMP_DIR%\" >nul

if exist "%OUT_DIR%\%APP_NAME%" rmdir /s /q "%OUT_DIR%\%APP_NAME%"
if exist "%OUT_DIR%\%APP_NAME%.zip" del /q "%OUT_DIR%\%APP_NAME%.zip"
if exist "%OUT_DIR%\Bitacora26-actualizacion.zip" del /q "%OUT_DIR%\Bitacora26-actualizacion.zip"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

echo Creando la aplicacion (puede tardar un minuto)...
jpackage --type app-image ^
  --name "%APP_NAME%" ^
  --app-version %APP_VERSION% ^
  --input "%TMP_DIR%" ^
  --main-jar %MAIN_JAR% ^
  --main-class Main ^
  --icon "icono.ico" ^
  --dest "%OUT_DIR%" ^
  --add-modules java.desktop,java.sql,java.naming,java.logging,java.xml,java.net.http,jdk.unsupported ^
  --java-options "--enable-native-access=ALL-UNNAMED" ^
  %CONSOLE_OPT%

if errorlevel 1 (
  echo.
  echo [ERROR] jpackage ha fallado. Revisa los mensajes de arriba.
  pause
  exit /b 1
)

rmdir /s /q "%TMP_DIR%"

rem --- Lo que necesita el programa junto al EXE ---
echo Copiando la carpeta rtl-sdr (para la cascada)...
if exist "rtl-sdr\rtl_tcp.exe" (
  xcopy /e /i /y /q "rtl-sdr" "%OUT_DIR%\%APP_NAME%\rtl-sdr" >nul
) else (
  echo [AVISO] No encuentro la carpeta rtl-sdr: la cascada no funcionara en el otro PC.
)
rem rtl_tcp necesita VCRUNTIME140.dll (Visual C++). Se copia la que trae el Java incluido, para que
rem funcione en un ordenador recien instalado sin tener que descargar nada.
if exist "%OUT_DIR%\%APP_NAME%\runtime\bin\vcruntime140.dll" copy /y "%OUT_DIR%\%APP_NAME%\runtime\bin\vcruntime140.dll" "%OUT_DIR%\%APP_NAME%\rtl-sdr\" >nul
if exist "%OUT_DIR%\%APP_NAME%\runtime\bin\vcruntime140_1.dll" copy /y "%OUT_DIR%\%APP_NAME%\runtime\bin\vcruntime140_1.dll" "%OUT_DIR%\%APP_NAME%\rtl-sdr\" >nul
if exist "LEEME_USUARIO.txt" copy /y "LEEME_USUARIO.txt" "%OUT_DIR%\%APP_NAME%\LEEME.txt" >nul
rem --- Instaladores de OmniRig, Zadig, WSJT-X... (SOLO para preparar el PC del radioclub) ---
rem     Normalmente NO se incluyen (la version que se sube a GitHub va sin ellos).
rem     Para incluirlos, cambia el 0 por un 1 en la linea de abajo.
set INCLUIR_INSTALADORES=0
if "%INCLUIR_INSTALADORES%"=="1" if exist "instaladores" (
  echo Copiando la carpeta Instaladores...
  xcopy /e /i /y /q "instaladores" "%OUT_DIR%\%APP_NAME%\Instaladores" >nul
)
rem --- Licencias de los componentes que van incluidos (obligatorio al repartir el programa) ---
if exist "licencias" (
  xcopy /e /i /y /q "licencias" "%OUT_DIR%\%APP_NAME%\licencias" >nul
) else (
  echo [AVISO] No encuentro la carpeta licencias: hay que incluirla antes de repartir el ZIP.
)
rem Licencia GPL v3 del programa, terminos adicionales y creditos (la GPL obliga a repartirlos con el programa)
for %%F in (LICENSE.txt TERMINOS-ADICIONALES.txt CREDITOS.txt) do if exist "%%F" copy /y "%%F" "%OUT_DIR%\%APP_NAME%\licencias\" >nul

echo Comprimiendo en ZIP...
powershell -NoProfile -Command "Compress-Archive -Path '%OUT_DIR%\%APP_NAME%' -DestinationPath '%OUT_DIR%\%APP_NAME%.zip' -Force"

rem --- ZIP pequeno de actualizacion: solo la carpeta "app" (el programa), sin Java ni lo demas ---
echo Creando el ZIP de actualizacion...
powershell -NoProfile -Command "Compress-Archive -Path '%OUT_DIR%\%APP_NAME%\app' -DestinationPath '%OUT_DIR%\Bitacora26-actualizacion.zip' -Force"

echo.
echo ==================== LISTO ====================
echo Carpeta: %OUT_DIR%\%APP_NAME%   (ejecutable: %APP_NAME%.exe)
echo ZIP:     %OUT_DIR%\%APP_NAME%.zip   (este es el que se pasa a otros PCs)
echo Version: %APP_VERSION%
echo Actualizacion: %OUT_DIR%\Bitacora26-actualizacion.zip
echo   (se sube a GitHub, en Releases, con la etiqueta v%APP_VERSION%)
echo ===============================================
pause
