# Documentación técnica relevante (consultada el 26/10/2026)

Referencias externas de Google/Android, para contrastar APIs antes de programar:

1. Android Ink API — compilación de apps de tinta con Compose:
   https://developer.android.com/agents/skills/jetpack-compose/ink/ink-app-builder/skill
   *Arquitectura wet ink/dry ink, `InProgressStrokes` y `CanvasStrokeRenderer`.*
2. AndroidX Ink release notes:
   https://developer.android.com/jetpack/androidx/releases/ink
   *Última estable mostrada: `1.0.0`; beta/alpha futura no necesaria para MVP.*
3. Android MotionEvent y soporte de stylus en Compose:
   https://developer.android.com/develop/ui/compose/touch-input/stylus-input/advanced-stylus-features
   *Pressure, tilt, orientation, stylus/eraser, eventos, rechazo de palma.*
4. Compatibilidad con lápices en pantallas grandes:
   https://developer.android.com/develop/ui/views/touch-and-input/input-compatibility-on-large-screens
   *`TOOL_TYPE_STYLUS`, `TOOL_TYPE_ERASER`, eventos históricos, `ACTION_CANCEL` y `FLAG_CANCELED`.*

**Nota:** verificar imports y versionado contra el SDK instalado al programar. Esta guía no sustituye documentación actual ni garantiza que componentes alpha estén estables.
