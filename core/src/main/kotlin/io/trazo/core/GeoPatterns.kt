package io.trazo.core

/**
 * Presets de geometría (ids `trazo-geo-v1-*`, los del paquete de ChatGPT). Al reconocerlos, el controlador
 * no pega tinta fija: crea una [ParametricShape] editable del tipo de [ParametricTemplates.recommendedKindFor],
 * ajustada a la caja del gesto.
 *
 * El ejemplo de gesto es el contorno de la forma (guiones como línea continua, sin rótulos), así se
 * reconoce dibujada a mano con los mismos trazos; el usuario puede enseñar más ejemplos en el editor.
 */
object GeoPatterns {
    const val PREFIX = "trazo-geo-v1-"

    private class Spec(val slug: String, val name: String, val kind: GeoKind, val w: Float, val h: Float)

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
                gestureExamples = listOf(GestureExamples.normalize(outline(shape))),
                outputInk = shape.render(),
                sizing = OutputSizing.FIT_GESTURE,
                builtIn = true,
                category = PatternCategory.SHAPES,
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
