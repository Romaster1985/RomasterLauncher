# Romaster Launcher

Un launcher moderno y ligero para Android, inspirado en Nova Launcher y Total Launcher.

## Características (v1)

- **Pantalla de inicio** que utiliza el fondo de pantalla del sistema (compatible con el selector nativo de Android).
- **Cajón de aplicaciones** con búsqueda en tiempo real.
- **Añadir** iconos al escritorio (mantener pulsado en el cajón).
- **Dock** inferior con estilo moderno (glass / elevación).
- **Soporte de packs de iconos** (compatible con la mayoría de packs que siguen la convención Nova / ADW).
- Mantener pulsado en espacio vacío → cambiar fondo de pantalla / ajustes.
- Diseño moderno con profundidad, sombras suaves y colores oscuros atractivos.

## Cómo funciona el sistema de iconos

1. **Iconos por defecto**: se obtienen del `PackageManager` de cada aplicación.
2. **Packs de iconos**:
   - Se descubren buscando actividades con intent-filters conocidos (`com.novalauncher.THEME`, `org.adw.launcher.THEMES`, etc.).
   - Se carga el `appfilter.xml` del pack, que mapea `package/activity` → nombre de drawable.
   - Al mostrar un icono se consulta primero el pack actual; si no hay mapeo se usa el icono del sistema.

Puedes cambiar el pack desde **Ajustes → Pack de iconos**.

## Compilar

### Localmente
```bash
./gradlew assembleDebug
```
El APK quedará en `app/build/outputs/apk/debug/`.

### GitHub Actions
Al hacer push a `main`/`master` se construye automáticamente el APK de debug y release (sin firmar). Los artefactos se suben en la pestaña Actions.

## Estructura del proyecto

```
RomasterLauncher/
├── app/
│   ├── src/main/
│   │   ├── java/com/romaster/launcher/
│   │   │   ├── data/          # Repositorios, modelos, IconPackManager
│   │   │   └── ui/            # Activities y adapters
│   │   ├── res/               # Layouts, drawables, themes modernos
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── .github/workflows/build.yml
└── README.md
```

## Próximos pasos posibles

- Drag & drop real con animaciones
- Widgets completos (AppWidgetHost)
- Múltiples páginas de escritorio
- Carpetas
- Gestos
- Temas de iconos más avanzados (masking, scaling)
- Personalización de grid, tamaño de iconos, etc.

---

Hecho con ❤️ para la comunidad.
