# Experiencia de usuario y comportamiento del lápiz

## Flujo A — escribir y convertir
1. Usuario abre cuaderno; la página de tinta se ve inmediatamente.
2. Escribe/dibuja normalmente con lápiz. Se capturan muestras crudas + históricas, presión/tiempo y herramienta.
3. Traza por ejemplo una forma reconocible como integral. **No levanta el S Pen** después del último movimiento.
4. Al quedar quieto, se inicia un temporizador de contacto continuo (`dwell`). Se muestra indicador discreto que no tape la escritura.
5. Tras `1000 ms` quieto, si sigue en contacto y el conjunto de trazos coincide claramente, se hace un reemplazo **atómico**: se retiran solo los trazos del gesto, se insertan los trazos de tinta correspondientes a la salida enseñada. Se preserva todo el resto de la hoja.
6. El usuario puede seguir escribiendo **dentro / alrededor** de la integral, raíz, fracción. Resultado es lápiz digital ordinario, no texto o imagen.
7. Undo inmediatamente restaura el dibujo original (toda la sustitución es un único comando de historial).

## Flujo B — enseñar una plantilla propia
- Abrir biblioteca de patrones → nuevo patrón.
- Dibujar con lápiz en zona izquierda: `Gesto a reconocer`.
- Dibujar con lápiz en zona derecha: `Cómo quiero que quede`.
- Configurar nombre, tamaño de salida, umbral opcional, pausa opcional, conjunto de ejemplos opcional.
- Guardar persistente y probarlo sobre hoja de ensayo.
- Modificar un patrón existente sin mutar retroactivamente los trazos ya puestos en notas.
- Permitir salida de varios trazos (ej.: radical y barra horizontal).

## Flujo C — editar después del reemplazo
- Borrador parcial que recorta tramos de tinta; no borra todo el bloque salvo herramienta elegida.
- Selección con lazo para mover/rotar/escalar conjunto de trazos manuscritos; agregar o borrar límites manualmente.
- Zoom sin pixelación y sin cambio de geometría al guardar.
- Rehacer/deshacer del reemplazo y de la edición.

## Casos límite de la pausa
| Caso | Resultado esperado |
|------|--------------------|
| Dibuja símbolo y sostiene 1 s estando apoyado | Intentar reconocer; convertir solo con coincidencia clara |
| Levanta el lápiz en 900 ms | Nunca convertir; conservar tinta original |
| Mantiene lápiz 2 s tras trazo no reconocido | Conservar tinta original; sin pérdida |
| Apoya punta sin dibujar nada | No insertar nada: evitar falsos positivos |
| Movimiento pequeño involuntario | Tolerar temblor dentro de radio configurable; temporizador no se reinicia por ruido leve |
| Empieza a escribir de nuevo antes de 1 s | Cancelar/reiniciar pausa según movimiento |
| Android emite `ACTION_CANCEL` por palma o navegación | Cancelar trazo/pending dwell conforme política; nunca convertir |
| Levanta tras conversión confirmada | No recrear o duplicar el último trazo |
| Un gesto de 2–3 trazos | Agrupar solo candidatos cercanos recientes en tiempo/espacio; no tocar otra escritura |
| Dos patrones se parecen mucho | No convertir automáticamente; ofrecer selección sin generar texto en página |
| Cambia orientación o usa multiventana | Conservar coordenadas en espacio de documento y estado activo seguro |
| Cambia de lápiz a dedo | Dedo hace pan/zoom por defecto; impedir que la palma pinte mientras hay stylus activo |

**Importante:** las tolerancias y thresholds son valores de diseño iniciales, no resultados de pruebas físicas. Validar con personas y S Pen real.

## UI propuesta
- Hoja clara/cuadriculada/punteada, con margen configurable; barra lateral compacta.
- Herramientas: lápiz, goma, lazo, mover, patrones, deshacer/rehacer.
- Biblioteca de patrones con vista previa **dibujada con tinta**; etiquetas de interfaz pueden ser texto convencional.
- Modo oscuro opcional sin pérdida de legibilidad de tintas existentes.
- Usable en tablet (patrones en panel) y teléfono (patrones en hoja/modal).
