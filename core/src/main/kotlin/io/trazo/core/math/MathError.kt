package io.trazo.core.math

/** Errores del motor. Cada uno tiene un mensaje corto en castellano para mostrar junto a la línea. */
sealed interface MathError {
    val message: String

    /** [position] es el índice del carácter en la línea. */
    data class Syntax(val detail: String, val position: Int) : MathError {
        override val message get() = "No se entiende la expresión: $detail (posición ${position + 1})"
    }
    data class Undefined(val name: String) : MathError {
        override val message get() = "La variable $name no está definida"
    }
    data class Cycle(val names: List<String>) : MathError {
        override val message get() = "Definición circular: ${(names + names.first()).joinToString(" → ")}"
    }
    data class Redefined(val name: String, val firstLine: Int) : MathError {
        override val message get() = "$name ya está definida en la línea ${firstLine + 1}"
    }
    data class DependsOnError(val name: String) : MathError {
        override val message get() = "No se puede calcular porque $name tiene un error"
    }
    data class Domain(val detail: String) : MathError {
        override val message get() = detail
    }
    data object DivisionByZero : MathError {
        override val message get() = "División por cero"
    }
}

class MathException(val error: MathError) : Exception(error.message)
