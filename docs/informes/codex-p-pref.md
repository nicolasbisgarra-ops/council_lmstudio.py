# P-PREF · Preferencias por secciones y configuración de barra y ruleta

Rama: `codex/p-pref`. Base: `6d7140b`. VM asignada: **ib3**. Entrega local, sin push ni PR.

## Relevamiento y distribución de ajustes

| Sección | Campos de `TrazoSettings` / contenido previo |
|---|---|
| Apariencia | `themeMode`, `accent`, `accent2`, `paperTone`. OLED ya era una opción del tema y del tono de hoja. |
| Lápiz | `sideButton`, `quickColors`, `swapColors`; explicación existente de los clics en el aire (sin campo configurable propio). `penColor`, `penWidth`, `brush` siguen editándose en el editor y pertenecen a esta sección al restablecer. |
| Hoja | `paper`, papel inicial de cuadernos nuevos. |
| Herramientas | `eraserMode`, `eraserRadiusDp`; nuevos `toolbarTools`, `wheelItems`, `toolbarButtonSize`. `shape` sigue en el editor y pertenece a esta sección al restablecer. |
| Reconocimiento | `holdMillis`, `tremorRadiusDp`, `learnFromCorrections`, `showHoldIndicator`. |
| General | `haptics`, `fingerDraws`, `gesturesEnabled`. No había otros ajustes visibles sin encaje. |

`highlighterColor` y `highlighterWidth` son ajustes operativos del resaltador en el editor: quedan fuera de los cambios y de los restablecimientos por sección, por la exclusión expresa del brief. El restablecimiento global existente conserva su alcance.

## Implementación

- Lista inicial de seis secciones, cada una con ícono y resumen. Páginas internas con sección, consulta y confirmación guardables mediante `rememberSaveable`; botón de navegación y Atrás del sistema vuelven a la lista.
- Búsqueda de nombres de ajustes y de secciones, sin distinguir mayúsculas ni tildes; cada resultado abre la página correspondiente.
- Restablecimiento global y por sección con confirmación; el restablecimiento por sección copia solo sus campos.
- Barra y ruleta se editan con casillas y flechas subir/bajar, con vista previa inmediata. PEN no se puede ocultar de la barra; la ruleta impide bajar de tres entradas.
- Persistencia independiente de orden y visibilidad en claves `toolbarTools`, `wheelItems`, `toolbarButtonSize`. Campos agregados al final de `TrazoSettings`, con defaults.
- `EditorToolbar.kt`: cambio mínimo en la iteración de herramientas y en el argumento de tamaño. `ToolWheel.kt`: construcción de entradas desde preferencias; mantiene los colores centrales, las acciones, los estados de deshacer/rehacer y los clics del lápiz.

La lógica pura vive en `core/settings/ToolPreferences.kt`, con claves estables de texto. Se evitó mover `EditorTool` de app o agregar dependencias entre módulos. App mapea esas claves al enum existente y a `WheelEntry`. Se normaliza al leer y al actualizar: desconocidos fuera, repetidos conservan su primera posición, PEN se repone al principio si falta, lista vacía/corrupta vuelve al default; ruleta con menos de tres vuelve al default y se aplica un tope de diez. Las nuevas preferencias con tipo incorrecto también usan defaults.

Defaults: barra con las seis herramientas en el orden previo; ruleta con esas seis más deshacer y rehacer. Hay ocho entradas únicas disponibles, así que hoy el máximo efectivo es ocho; el contrato limita a diez. Tamaños 40/48/56 dp. **Diferencia del brief con la base:** `ToolButton` tenía default de 40 dp; se siguió el pedido explícito MEDIUM = 48 dp para las herramientas. No se alteró el tamaño de los otros botones.

## Archivos

- `core/src/main/kotlin/io/trazo/core/settings/ToolPreferences.kt` (nuevo).
- `core/src/test/kotlin/io/trazo/core/settings/ToolPreferencesTest.kt` (nuevo).
- `app/src/main/kotlin/io/trazo/app/data/SettingsStore.kt`.
- `app/src/main/kotlin/io/trazo/app/data/ToolPreferences.kt` (nuevo).
- `app/src/main/kotlin/io/trazo/app/ui/SettingsScreen.kt`.
- `app/src/main/kotlin/io/trazo/app/ui/SettingsSections.kt` (nuevo).
- `app/src/main/kotlin/io/trazo/app/ui/ToolPreferencesEditor.kt` (nuevo).
- `app/src/main/kotlin/io/trazo/app/ui/ToolPreferenceLabels.kt` (nuevo).
- `app/src/main/kotlin/io/trazo/app/ui/EditorToolbar.kt`.
- `app/src/main/kotlin/io/trazo/app/ui/ToolWheel.kt`.
- `docs/COORDINACION.md` y este informe.

## Pruebas y autorrevisión

Pruebas nuevas escritas antes que la implementación. Primera corrida dirigida: rojo esperado por ausencia de `ToolPreferences`. Primera suite completa en **ib3**: **203 pruebas, 0 fallas, 0 errores, 0 omitidas**, con JDK 21 y target 17 sin cambiar el proyecto. Las corridas obligatorias previas a los commits siguientes también quedaron verdes. Corrida final del árbol sincronizado, después de las correcciones de autorrevisión: **verde**, nuevamente **203 pruebas, 0 fallas, 0 errores, 0 omitidas**.

```sh
./gradlew -q -Ptrazo.coreOnly=true :core:test
```

Incluye nueve pruebas nuevas: migración desde claves ausentes, listas vacías/corruptas, PEN obligatoria, orden, desconocidos/duplicados, mínimo de tres, límite superior, serialización de ida y vuelta e idempotencia sobre las 256 selecciones posibles de las ocho entradas.

La autorrevisión adversarial se hizo **después** de los tres commits de implementación, releyendo el diff completo contra `6d7140b`. Hallazgos corregidos en commits separados de la implementación:

1. Las filas reordenables no tenían identidad estable de Compose: se agregó `key(option)` para que foco y estado interno sigan a cada entrada al subirla o bajarla.
2. Al abrir una sección desde la búsqueda no se limpiaba el foco del campo: ahora se llama a `clearFocus()` antes de navegar.
3. Buscar «vibración» no encontraba el ajuste llamado «Vibrar…»: se agregó ese nombre al índice.
4. El chequeo final del diff completo contra la base detectó una línea vacía sobrante al final de `ToolPreferenceLabels.kt`; se quitó en un commit adicional, sin amend.

Además se revisaron las firmas reales de `ToolButton`, `ChoiceRow`, `LabeledSlider`, `ColorPickerDialog` y el uso existente de `BackHandler`/`rememberSaveable`; imports, tipos y nulos; defaults desde prefs viejas; claves nuevas sin colisiones; alcance de cada reset; selección y acciones de la ruleta; y diff mínimo de la barra. Un chequeo estático contrastó las claves de core con los enums reales de app, los tamaños 40/48/56, las claves leídas/escritas y los archivos excluidos: **OK**. `git diff --check`: **OK**.

## Commits y límites

SHA del commit funcional con la autorrevisión corregida: **`f84b9882888de237e5ca95c5e074ade46228cf7d`**.

- `5db6037`: lógica pura y pruebas JVM.
- `c811b4c`: datos, persistencia y aplicación en barra/ruleta.
- `af81406`: páginas, buscador y editores de preferencias.
- `f84b988`: correcciones de la autorrevisión adversarial.

`39e8d92` incorpora el informe y el cierre de coordinación. Un commit adicional elimina la línea vacía detectada en la comprobación final y actualiza este informe. El SHA de HEAD de la entrega completa se informa en el mensaje final. Todos los commits son locales, sin amend.

La app no se compila ni se ejecuta en esta VM por falta de SDK Android, según el brief. Pendiente la validación Android/Compose por el orquestador, incluida rotación, navegación atrás y uso real de las casillas, flechas y vistas previas. No se cambiaron dependencias, SDK, versiones ni el umbral de conflictos. Los archivos excluidos por el pedido permanecen intactos.

La barra compacta previa mantiene las herramientas en un grupo que no se parte: con seis botones de 48 dp más gestos/patrones necesita 368 dp, y con botones de 56 dp necesita 416 dp (antes del margen del grupo). Puede quedar apretada en ventanas angostas. Se deja explícito para integrar con la sesión que rediseña la barra; no se reestructuró `EditorToolbar.kt`, como pide el brief. La vista previa de preferencias sí se acomoda en varias filas.

Recorrido Android sugerido para integración: abrir las seis páginas y volver con ambos botones Atrás; buscar «lapiz», «OLED», «vibración» y un texto sin resultados; rotar dentro de una sección y con una confirmación abierta; cancelar y confirmar cada restablecimiento; ocultar/reordenar herramientas, probar los tres tamaños y reiniciar la app; dejar tres entradas en la ruleta, volver a agregar entradas y comprobar orden y estados de deshacer/rehacer.
