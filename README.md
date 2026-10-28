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

Consola en vivo, jugadores conectados, controles e información de conexión (imagen superior). Además, cada servidor tiene pestañas para sus mods o plugins, jugadores, copias de seguridad y rendimiento:

| Plugins (Paper) | Mods (Forge) |
|---|---|
| ![Plugins](docs/screenshots/plugins.png) | ![Mods de Forge](docs/screenshots/forge-mods.png) |
| **Jugadores** | **Copias de seguridad** |
| ![Jugadores](docs/screenshots/jugadores.png) | ![Copias](docs/screenshots/copias.png) |
| **Rendimiento** | **Actualizar mods** |
| ![Rendimiento](docs/screenshots/rendimiento.png) | ![Actualizaciones](docs/screenshots/actualizaciones.png) |

## Qué hace (v0.2)

- **Asistente en 5 pasos**: nombre y tipo → versión → RAM y puerto → opciones de juego → EULA e instalación.
- **Tipos**: Vanilla (Mojang), Paper (plugins, optimizado), Fabric, Forge y NeoForge (mods). Forge y NeoForge se instalan ejecutando su instalador oficial (`--installServer`) con el Java correcto; BlockHost detecta cómo arrancarlos (fichero de argumentos en las versiones modernas, `.jar` en las antiguas desde 1.12.2).
- **Java automático**: detecta qué Java pide cada versión de Minecraft (8/17/21/25) y descarga Eclipse Temurin solo si hace falta. Se cachea y se comparte entre servidores.
- **Panel del servidor**: iniciar/detener (parada limpia con `stop`), consola en vivo, envío de comandos, lista de jugadores conectados, abrir carpeta.
- **Editar y borrar servidores** (botón ⚙): nombre, RAM, puerto, MOTD, jugadores, dificultad, modo de juego, PvP y modo online. Borrar pide escribir el nombre del servidor, porque no se puede deshacer.
- **Jugadores**: conectados (hacer operador, echar, banear), operadores, lista blanca (con interruptor para activarla) y baneados, sin escribir comandos. En marcha se aplican con comandos; detenido, se editan `ops.json`, `whitelist.json` y `banned-players.json` (con el UUID oficial de Mojang o el de modo offline).
- **Copias de seguridad**: manuales y **automáticas cada X horas** mientras el servidor está en marcha (guarda solo las últimas N), sin cortar el juego (`save-off` → `save-all flush` → copia → `save-on`). **Restaurar** una copia con el servidor detenido; antes se guarda el mundo actual por si acaso. Respeta `level-name`.
- **Rendimiento**: CPU y RAM del servidor y del PC, con gráficas de los últimos 5 minutos y un resumen en la cabecera.
- IPs de conexión (local y red) y comprobación de puerto ocupado antes de arrancar.
- Flags de JVM optimizadas (Aikar) según la RAM asignada.

## Mods, plugins y modpacks

- **Pestaña «Mods»** (Fabric, Forge, NeoForge) o **«Plugins»** (Paper): lista con versión y origen, activar/desactivar (sin borrarlos) y quitar.
- **Buscar en Modrinth** (mods, plugins y modpacks, con sus iconos): solo muestra lo que tiene versión para tu Minecraft y tu loader (los plugins, para Paper, Spigot o Bukkit). Elige la versión compatible más reciente e **instala solas las dependencias obligatorias**.
- **Actualizar**: «Buscar actualizaciones» encuentra versiones más nuevas en Modrinth de lo que tienes instalado (también de lo añadido a mano, por la huella del fichero), te deja elegir cuáles, las descarga y comprueba la compatibilidad antes de sustituir nada. Si tienes una versión estable no te ofrece betas.
- **Tus propios .jar**: añade cualquier mod desde tu PC.
- **Compatibilidad antes de instalar**: todo se descarga primero a una carpeta temporal y se comprueba como lo haría el loader al arrancar. Fabric: `depends`, `breaks`, `conflicts` de `fabric.mod.json`, con los mods incrustados. Forge/NeoForge: dependencias de `mods.toml` / `neoforge.mods.toml` con sus rangos Maven (`[47,)`), incluidos los mods de `META-INF/jarjar`. Paper: `depend` y `api-version` de `plugin.yml` / `paper-plugin.yml`. Comprueba versión de Minecraft, del loader y de Java, lo que falta, duplicados, incompatibles, mods de otro loader y mods solo de cliente. Si algo se rompe, avisa y por defecto no instala.
- **Modpacks** de Modrinth (`.mrpack`) de Fabric, Forge o NeoForge, desde la búsqueda o desde un archivo: en un servidor existente si coinciden loader y versión de Minecraft, o con **«+ Desde modpack»** se crea un servidor nuevo con la versión de Minecraft y del loader exactas que pide el pack. Solo se descargan los archivos de servidor, se verifican sus huellas (SHA-1/SHA-512) y solo desde los dominios que permite el formato.
- Si falta una dependencia en mods añadidos a mano, un botón la busca e instala desde Modrinth.

## Requisitos para desarrollar

- JDK 21 para ejecutar Gradle (Gradle 8.14 no funciona con Java 26). El usuario final no necesita Java: la app lleva su propio runtime.

## Abrir la app sin consola (Windows)

```bat
gradlew.bat app
```

Genera `build\jpackage\BlockHost\BlockHost.exe`: se abre con doble clic como cualquier programa, **sin ventana de consola detrás** y sin necesitar Java instalado (lleva el suyo). Puedes copiar la carpeta `BlockHost` donde quieras. **La primera vez que la abres crea un acceso directo en el escritorio** (y si lo borras, puedes volver a crearlo desde el enlace de la barra lateral). En Linux crea también la entrada en el menú de aplicaciones. `gradlew run` (y un `.bat` que lo llame) siempre abre una consola, porque arranca Gradle; úsalo solo para desarrollar.

## Instalador de Windows

```bat
gradlew.bat installer
```

Genera `build\jpackage\BlockHost-0.2.0.exe`: un instalador normal de Windows (elegir carpeta, entrada en el menú Inicio, acceso directo en el escritorio y desinstalar desde «Aplicaciones»). Se instala para tu usuario, sin pedir permisos de administrador, y lleva su propio Java. Una versión nueva sustituye a la anterior sin tocar tus servidores. Necesita [WiX Toolset 3](https://github.com/wixtoolset/wix3/releases) instalado para generarlo.

**Sin instalar nada**: cada push a `main` compila en GitHub Actions el instalador y la versión portable (`BlockHost-portable.zip`); se descargan desde la pestaña *Actions* → la ejecución → *Artifacts* → `BlockHost-Windows`. Al subir una etiqueta `v*` (p. ej. `v0.2.0`) se publican además como *Release*.

## Comandos

```bash
./gradlew app          # App portable sin consola (BlockHost.exe en Windows)
./gradlew installer    # Instalador de Windows (.exe, necesita WiX 3)
./gradlew run          # Ejecutar la app desde Gradle, para desarrollar (Windows: gradlew.bat run)
./gradlew build        # Compilar
./gradlew test         # Tests
```

## Dónde guarda las cosas

| Sistema | Carpeta |
|---|---|
| Windows | `%APPDATA%\BlockHost` |
| macOS | `~/Library/Application Support/BlockHost` |
| Linux | `~/.local/share/blockhost` |

Dentro: `servers/<nombre>/` (cada servidor con su `blockhost.json`, `backups/` con las copias y, si tiene mods o plugins, `blockhost-mods.json` con su origen), `runtimes/java-N/` (Java descargados) y `cache/`.

## Estructura

```
src/main/java/com/blockhost/
├── Launcher.java                 Punto de entrada
├── core/                         Lógica sin dependencias de UI
│   ├── providers/                Vanilla (piston-meta), Paper (Fill v3), Fabric (meta.fabricmc.net), Forge y NeoForge
│   ├── mods/                     Modrinth, modpacks .mrpack, lectura de mods/plugins (JSON, TOML, YAML) y compatibilidad
│   ├── JavaRuntimeManager.java   Descarga/caché de Temurin vía API de Adoptium
│   ├── ServerInstaller.java      Orquesta la instalación del asistente
│   ├── ServerProcess.java        Proceso del servidor, consola, estado y jugadores
│   ├── Backups.java              Copias del mundo: crear, listar, limpiar y restaurar
│   ├── PlayerLists.java          Operadores, lista blanca y baneados
│   ├── ProcessStats.java         CPU y RAM del servidor y del equipo
│   └── Json.java                 Parser JSON mínimo (sin dependencias)
└── ui/
    ├── App.java                  Ventana principal y lista de servidores
    ├── WizardDialog.java         Asistente
    ├── ServerPane.java           Panel de cada servidor
    ├── ModsPane.java             Pestaña de mods o plugins (y actualizaciones)
    ├── PlayersPane.java          Pestaña de jugadores
    ├── BackupsPane.java          Pestaña de copias (y copias automáticas)
    ├── StatsPane.java            Pestaña de rendimiento
    ├── EditServerDialog.java     Ajustes de un servidor existente
    ├── ModSearchDialog.java      Buscador de mods, plugins y modpacks de Modrinth
    └── ModpackServerDialog.java  Crear servidor desde modpack
```

## Siguientes pasos sugeridos

1. **Túnel integrado** (playit.gg) para que entren amigos sin abrir puertos ni pelear con CGNAT.
2. Explicar en lenguaje claro por qué se ha caído un servidor (leyendo el crash report).
3. Plugins desde Hangar (el repositorio de PaperMC), además de Modrinth.
