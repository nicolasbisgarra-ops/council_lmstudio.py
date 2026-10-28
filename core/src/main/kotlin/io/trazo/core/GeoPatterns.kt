package io.trazo.core

/**
 * Presets de geometría (ids `trazo-geo-v1-*`, los del paquete de ChatGPT). Al reconocerlos, el controlador
 * no pega tinta fija: crea una [ParametricShape] editable del tipo de [ParametricTemplates.recommendedKindFor],
 * ajustada a la caja del gesto.
 *
 * El ejemplo de gesto suele ser el contorno de la forma (sin rótulos). Para octágono,
 * trapecio y arco se aceptan dos trazos consecutivos que recorren cada mitad del contorno.
 * El dibujo paramétrico resultante sigue siendo continuo y editable. El usuario puede
 * enseñar ejemplos adicionales en el editor.
 */
object GeoPatterns {
    const val PREFIX = "trazo-geo-v1-"

    private class Spec(val slug: String, val name: String, val kind: GeoKind, val w: Float, val h: Float)

    // Se instalan solo al pasar de la biblioteca 3 a la 4; no se reinstalan presets borrados en v3.
    internal val V4_IDS: Set<String> = setOf("octagono", "trapecio", "estrella-5", "semicirculo", "arco", "flecha-doble")
        .mapTo(linkedSetOf()) { PREFIX + it }

    private val specs = listOf(
        Spec("circulo", "Círculo", GeoKind.CIRCLE, 160f, 160f),
        Spec("elipse-h", "Elipse horizontal", GeoKind.ELLIPSE, 200f, 120f),
        Spec("elipse-v", "Elipse vertical", GeoKind.ELLIPSE, 120f, 200f),
        Spec("cuadrado", "Cuadrado", GeoKind.SQUARE, 160f, 160f),
        Spec("rectangulo-h", "Rectángulo horizontal", GeoKind.RECTANGLE, 220f, 130f),
        Spec("rectangulo-v", "Rectángulo vertical", GeoKind.RECTANGLE, 130f, 220f),
        Spec("redondeado-h", "Rectángulo redondeado", GeoKind.ROUNDED_RECTANGLE, 220f, 130f),
        Spec("redondeado-v", "Rectángulo redondeado vertical", GeoKind.ROUNDED_RECTANGLE, 130f, 220f),
        Spec("capsula-h", "Cápsula", GeoKind.CAPSULE, 220f, 100f),
        Spec("capsula-v", "Cápsula vertical", GeoKind.CAPSULE, 100f, 220f),
        Spec("rombo", "Rombo", GeoKind.DIAMOND, 160f, 200f),
        Spec("triangulo", "Triángulo", GeoKind.TRIANGLE, 180f, 160f),
        Spec("paralelogramo", "Paralelogramo", GeoKind.PARALLELOGRAM, 220f, 130f),
        Spec("pentagono", "Pentágono", GeoKind.PENTAGON, 170f, 160f),
        Spec("hexagono", "Hexágono", GeoKind.HEXAGON, 180f, 160f),
        // Tanda de prioridad A sin dependencia de la especificación pendiente en Drive.
        Spec("octagono", "Octágono", GeoKind.OCTAGON, 180f, 180f),
        Spec("trapecio", "Trapecio", GeoKind.TRAPEZOID, 210f, 140f),
        Spec("estrella-5", "Estrella de cinco puntas", GeoKind.STAR_5, 180f, 180f),
        Spec("semicirculo", "Semicírculo", GeoKind.SEMICIRCLE, 190f, 110f),
        Spec("arco", "Arco", GeoKind.ARC, 190f, 110f),
        Spec("flecha-doble", "Flecha doble", GeoKind.DOUBLE_ARROW, 200f, 80f),
        Spec("ejes-r2", "Ejes ℝ²", GeoKind.AXES_R2, 200f, 160f),
        Spec("ejes-r3", "Ejes ℝ³", GeoKind.AXES_R3, 0f, 0f),
        Spec("cubo", "Cubo", GeoKind.CUBOID, 0f, 0f),
        Spec("cilindro", "Cilindro", GeoKind.CYLINDER, 0f, 0f),
        Spec("cono", "Cono", GeoKind.CONE, 0f, 0f),
        Spec("esfera", "Esfera", GeoKind.SPHERE, 170f, 170f),
        Spec("vector-2d", "Vector", GeoKind.VECTOR, 0f, 0f),
    )

    fun all(): List<InkPattern> = cache

    fun isGeo(p: InkPattern): Boolean = p.id.startsWith(PREFIX)

    private val cache: List<InkPattern> by lazy {
        specs.map { s ->
            val id = PREFIX + s.slug
            var shape = ParametricTemplates.create(id, s.kind, GeoPoint(200f, 200f), 180f).copy(showAxisLabels = false)
            if (s.w > 0f) shape = shape.copy(width = s.w, height = s.h)
            InkPattern(
                id = id,
                name = s.name,
                gestureExamples = listOf(GestureExamples.normalize(gestureOutline(shape))),
                outputInk = shape.render(),
                sizing = OutputSizing.FIT_GESTURE,
                builtIn = true,
                category = PatternCategory.SHAPES,
            )
        }
    }

    /**
     * Los tres contornos que colisionaban como un único trazo usan DOS medias vueltas contiguas.
     * Es un gesto humano válido: levantar el lápiz una vez y terminar desde el mismo vértice.
     * El comparador exige igual cantidad de trazos, así el círculo, el rectángulo y la raíz de
     * un solo trazo ya no los desplazan. No se modifican las plantillas ni los trazos del usuario.
     *
     * Se separa en puntos del contorno original (vértices del polígono / mitad del arco), sin
     * duplicar geometría ni insertar marcadores artificiales. Cada segmento guarda su propio tiempo.
     */
    private fun gestureOutline(shape: ParametricShape): List<InkStroke> {
        val contour = outline(shape)
        if (shape.kind !in setOf(GeoKind.OCTAGON, GeoKind.TRAPEZOID, GeoKind.ARC)) return contour
        if (contour.size != 1) return contour
        val points = contour.single().points
        val last = points.lastIndex
        if (last < 4) return contour
        return (0..1).map { part ->
            val from = part * last / 2
            val to = (part + 1) * last / 2
            InkStroke(
                id = "geo-half-${part + 1}",
                points = points.subList(from, to + 1).mapIndexed { k, p -> p.copy(timeMs = k * 8L) },
                startTime = part * 400L,
            )
        }
    }

    /** Contorno de [shape] como se dibuja a mano: un trazo por recorrido, sin guiones ni rótulos. */
    fun outline(shape: ParametricShape): List<InkStroke> =
        ParametricGeometry.draw(shape).paths.filter { it.points.size >= 2 }.mapIndexed { i, path ->
            val pts = if (path.closed) path.points + path.points.first() else path.points
            InkStroke(id = "geo-${i + 1}", points = pts.mapIndexed { k, p -> InkPoint(p.x, p.y, 0.8f, k * 8L) }, startTime = i * 400L)
        }
}
