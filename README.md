# BlockHost

App de escritorio (Java 21 + JavaFX) para crear y gestionar servidores de Minecraft en tu propio PC, gratis y sin Aternos.

![Panel del servidor](docs/screenshots/panel.png)

## Capturas

### Asistente de creación

| 1. Nombre y tipo | 2. Versión |
|---|---|
| ![Paso 1](docs/screenshots/wizard-1-tipo.png) | ![Paso 2](docs/screenshots/wizard-2-version.png) |
| **3. Recursos** | **4. Opciones de juego** |
| ![Paso 3](docs/screenshots/wizard-3-recursos.png) | ![Paso 4](docs/screenshots/wizard-4-juego.png) |
| **5. Resumen e instalación** | |
| ![Paso 5](docs/screenshots/wizard-5-resumen.png) | |

### Panel del servidor

Consola en vivo, jugadores conectados, controles e información de conexión (imagen superior).

## Qué hace (v0.1)

- **Asistente en 5 pasos**: nombre y tipo → versión → RAM y puerto → opciones de juego → EULA e instalación.
- **Tipos**: Vanilla (Mojang), Paper (plugins, optimizado) y Fabric (mods).
- **Java automático**: detecta qué Java pide cada versión de Minecraft (8/17/21/25) y descarga Eclipse Temurin solo si hace falta. Se cachea y se comparte entre servidores.
- **Panel del servidor**: iniciar/detener (parada limpia con `stop`), consola en vivo, envío de comandos, lista de jugadores conectados, copias de seguridad del mundo en `.zip`, abrir carpeta.
- IPs de conexión (local y red) y comprobación de puerto ocupado antes de arrancar.
- Flags de JVM optimizadas (Aikar) según la RAM asignada.

## Mods y modpacks (servidores Fabric)

- **Pestaña «Mods»** en el panel de cada servidor Fabric: lista de mods con versión y origen, activar/desactivar (sin borrarlos) y quitar.
- **Buscar en Modrinth**: solo muestra mods que funcionan en servidor y que tienen versión para tu Minecraft y Fabric. Elige la versión compatible más reciente e **instala solas las dependencias obligatorias**.
- **Tus propios .jar**: añade cualquier mod desde tu PC.
- **Compatibilidad antes de instalar**: todo se descarga primero a una carpeta temporal y se comprueba como lo haría Fabric al arrancar (`depends`, `breaks`, `conflicts` de `fabric.mod.json`, incluidos los mods incrustados). Comprueba versión de Minecraft, de Fabric Loader y de Java, mods que faltan, duplicados, incompatibles, mods de Forge/NeoForge/Quilt, plugins de Paper y mods solo de cliente. Si algo se rompe, avisa y por defecto no instala.
- **Modpacks** (`.mrpack` de Modrinth, desde la búsqueda o desde un archivo): en un servidor existente si coincide la versión de Minecraft, o con **«+ Desde modpack»** se crea un servidor nuevo con la versión de Minecraft y de Fabric Loader exactas que pide el pack. Solo se descargan los archivos de servidor, se verifican sus huellas (SHA-1/SHA-512) y solo desde los dominios que permite el formato.
- Si falta una dependencia en mods añadidos a mano, un botón la busca e instala desde Modrinth.

## Requisitos para desarrollar

- JDK 21 (el usuario final no lo necesita: el instalador lleva su propio runtime).

## Comandos

```bash
./gradlew run          # Ejecutar la app       (Windows: gradlew.bat run)
./gradlew build        # Compilar
./gradlew test         # Tests
./gradlew jpackage     # Instalador nativo en build/jpackage/ (.msi/.exe en Windows)
```

En Windows, `jpackage` necesita **WiX Toolset** instalado para generar `.msi`/`.exe`. Sin WiX, `./gradlew jpackageImage` genera la app portable.

## Dónde guarda las cosas

| Sistema | Carpeta |
|---|---|
| Windows | `%APPDATA%\BlockHost` |
| macOS | `~/Library/Application Support/BlockHost` |
| Linux | `~/.local/share/blockhost` |

Dentro: `servers/<nombre>/` (cada servidor con su `blockhost.json` y, si tiene mods, `blockhost-mods.json` con su origen), `runtimes/java-N/` (Java descargados) y `cache/`.

## Estructura

```
src/main/java/com/blockhost/
├── Launcher.java                 Punto de entrada
├── core/                         Lógica sin dependencias de UI
│   ├── providers/                Vanilla (piston-meta), Paper (Fill v3), Fabric (meta.fabricmc.net)
│   ├── mods/                     Modrinth, .mrpack, lectura de fabric.mod.json y comprobación de compatibilidad
│   ├── JavaRuntimeManager.java   Descarga/caché de Temurin vía API de Adoptium
│   ├── ServerInstaller.java      Orquesta la instalación del asistente
│   ├── ServerProcess.java        Proceso del servidor, consola, estado y jugadores
│   ├── Backups.java              Copias del mundo
│   └── Json.java                 Parser JSON mínimo (sin dependencias)
└── ui/
    ├── App.java                  Ventana principal y lista de servidores
    ├── WizardDialog.java         Asistente
    ├── ServerPane.java           Panel de cada servidor
    ├── ModsPane.java             Pestaña de mods
    ├── ModrinthDialog.java       Buscador de mods y modpacks
    └── ModpackServerDialog.java  Crear servidor desde modpack
```

## Siguientes pasos sugeridos

1. **Túnel integrado** (playit.gg) para que entren amigos sin abrir puertos ni pelear con CGNAT.
2. Forge / NeoForge (requieren ejecutar su instalador con `--installServer`), y con ello sus mods y modpacks.
3. Actualizar mods instalados desde Modrinth; modpacks de CurseForge (requiere clave de API).
4. Editar ajustes de un servidor existente, borrar servidores, whitelist/ops visuales.
5. Backups automáticos programados y restauración.
