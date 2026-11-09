package io.trazo.core

/**
 * PDF para entregar: hoja blanca sin renglones, sin marcas del Modo IA, sin las hojas vacías y con un pie
 * «nombre · n» en cada hoja. Los resultados de las cuentas sí van (son parte de lo que el alumno resolvió).
 */
object HandIn {
    /** Una hoja sin tinta, sin formas, sin imágenes y sin resultados no se entrega. */
    fun isBlank(page: Page, results: List<InkStroke> = emptyList()): Boolean =
        page.strokes.isEmpty() && page.parametricShapes.isEmpty() && page.images.isEmpty() && results.isEmpty()

    /** Índices de las hojas que van en la entrega; si todas están vacías va la primera (un PDF necesita una hoja). */
    fun pagesToInclude(count: Int, pageAt: (Int) -> Page, results: (Page) -> List<InkStroke> = { emptyList() }): List<Int> =
        (0 until count).filter { i -> pageAt(i).let { !isBlank(it, results(it)) } }.ifEmpty { listOf(0) }

    fun pagesToInclude(pages: List<Page>, results: (Page) -> List<InkStroke> = { emptyList() }): List<Int> =
        pagesToInclude(pages.size, pages::get, results)

    /** Pie de la hoja [number] (desde 1) de [total]. */
    fun footer(notebookName: String, number: Int, total: Int): String {
        val name = notebookName.trim()
        val pages = if (total > 1) "$number / $total" else ""
        return listOf(name, pages).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
