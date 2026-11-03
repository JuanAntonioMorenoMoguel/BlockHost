# Prueba de extremo a extremo

Crea servidores reales (descarga Minecraft y Java), los arranca, manda comandos, prueba autocompletar,
copias, exportar/importar mundos y los borra al final. Usa una carpeta de datos aparte, no toca tus servidores.

Windows (PowerShell), desde la raíz del repo:

```powershell
.\gradlew.bat classes
$env:APPDATA = "$PWD\build\e2e-data"
javac -cp build\classes\java\main -d build\e2e tools\e2e\E2E.java
java -cp "build\classes\java\main;build\e2e" E2E PAPER 1.21.4 25601 upload
java -cp "build\classes\java\main;build\e2e" E2E VANILLA 1.21.4 25611
java -cp "build\classes\java\main;build\e2e" E2E FABRIC 1.21.4 25621
```

El último argumento `upload` prueba también subir el mundo y obtener un enlace (litterbox.catbox.moe).
