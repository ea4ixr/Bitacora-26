@echo off
cd /d "%~dp0"
set "LOG=%~dp0diagnostico.txt"

echo === Contenido de la carpeta app === > "%LOG%"
dir /b "salida\Bitacora 26\app" >> "%LOG%" 2>&1
echo. >> "%LOG%"
echo === Configuracion === >> "%LOG%"
type "salida\Bitacora 26\app\Bitacora 26.cfg" >> "%LOG%" 2>&1
echo. >> "%LOG%"
echo === Arranque del programa === >> "%LOG%"
"salida\Bitacora 26\Bitacora 26.exe" >> "%LOG%" 2>&1
echo. >> "%LOG%"
echo === Codigo de salida: %errorlevel% === >> "%LOG%"

type "%LOG%"
echo.
echo Se ha guardado tambien en: %LOG%
pause
