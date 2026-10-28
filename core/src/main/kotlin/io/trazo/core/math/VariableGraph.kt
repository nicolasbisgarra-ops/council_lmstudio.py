package io.trazo.core.math

/** Resultado de una línea del cuaderno. */
sealed interface LineResult {
    data object Empty : LineResult
    data class Value(val value: MathValue) : LineResult
    data class Assignment(val name: String, val value: MathValue) : LineResult
    data class Check(val holds: Boolean, val left: MathValue, val right: MathValue) : LineResult
    data class Failure(val error: MathError) : LineResult
}

/**
 * Evalúa una hoja completa. Las variables pueden usarse antes o después de la línea que las define;
 * las dependencias forman un grafo y los ciclos se informan en todas las variables que participan.
 * Una definición repetida es un error de la línea posterior: la primera sigue valiendo.
 */
object VariableGraph {

    fun evaluate(lines: List<String>): List<LineResult> {
        val statements: List<Result<Statement>> = lines.map { line ->
            try {
                Result.success(MathParser.parseStatement(line))
            } catch (e: MathException) {
                Result.failure(e)
            }
        }

        // Primera definición de cada nombre.
        val definedAt = linkedMapOf<String, Int>()
        val redefined = mutableMapOf<Int, MathError>()
        statements.forEachIndexed { i, r ->
            val s = r.getOrNull() as? Statement.Assign ?: return@forEachIndexed
            val first = definedAt[s.name]
            if (first == null) definedAt[s.name] = i else redefined[i] = MathError.Redefined(s.name, first)
        }

        val deps: Map<String, List<String>> = definedAt.mapValues { (_, i) ->
            (statements[i].getOrNull() as Statement.Assign).expr.variables().filter { it in definedAt }
        }
        val cycleOf = findCycles(definedAt.keys.toList(), deps)

        val cache = mutableMapOf<String, Result<MathValue>>()
        fun valueOf(name: String): Result<MathValue> = cache.getOrPut(name) {
            val line = definedAt[name] ?: return@getOrPut Result.failure(MathException(MathError.Undefined(name)))
            cycleOf[name]?.let { return@getOrPut Result.failure(MathException(MathError.Cycle(it))) }
            val s = statements[line].getOrThrow() as Statement.Assign
            compute(s.expr, ::valueOf)
        }

        return statements.mapIndexed { i, r ->
            redefined[i]?.let { return@mapIndexed LineResult.Failure(it) }
            val s = r.getOrElse { return@mapIndexed LineResult.Failure((it as MathException).error) }
            when (s) {
                Statement.Empty -> LineResult.Empty
                is Statement.Assign -> valueOf(s.name).fold({ LineResult.Assignment(s.name, it) }, ::failure)
                is Statement.Query -> compute(s.expr, ::valueOf).fold({ LineResult.Value(it) }, ::failure)
                is Statement.Check -> {
                    val left = compute(s.left, ::valueOf)
                    val right = compute(s.right, ::valueOf)
                    when {
                        left.isFailure -> failure(left.exceptionOrNull()!!)
                        right.isFailure -> failure(right.exceptionOrNull()!!)
                        else -> LineResult.Check(left.getOrThrow().sameAs(right.getOrThrow()), left.getOrThrow(), right.getOrThrow())
                    }
                }
            }
        }
    }

    private fun failure(t: Throwable) = LineResult.Failure((t as MathException).error)

    private fun compute(expr: Expr, valueOf: (String) -> Result<MathValue>): Result<MathValue> = try {
        Result.success(Evaluator.evaluate(expr) { name -> resolve(name, valueOf) })
    } catch (e: MathException) {
        Result.failure(e)
    }

    private fun resolve(name: String, valueOf: (String) -> Result<MathValue>): MathValue {
        return valueOf(name).getOrElse { e ->
            val error = (e as MathException).error
            // Las variables del ciclo ya lo informan en su línea; quien las usa ve "depende de un error".
            throw MathException(if (error == MathError.Undefined(name)) error else MathError.DependsOnError(name))
        }
    }

    /** Tarjan: devuelve, para cada nombre en un ciclo, los nombres del ciclo en orden de definición. */
    private fun findCycles(names: List<String>, deps: Map<String, List<String>>): Map<String, List<String>> {
        val index = mutableMapOf<String, Int>()
        val low = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        val out = mutableMapOf<String, List<String>>()
        var counter = 0

        fun visit(v: String) {
            index[v] = counter
            low[v] = counter
            counter++
            stack.addLast(v)
            onStack += v
            for (w in deps.getValue(v)) {
                if (w !in index) {
                    visit(w)
                    low[v] = minOf(low.getValue(v), low.getValue(w))
                } else if (w in onStack) {
                    low[v] = minOf(low.getValue(v), index.getValue(w))
                }
            }
            if (low[v] == index[v]) {
                val component = mutableSetOf<String>()
                do {
                    val w = stack.removeLast()
                    onStack -= w
                    component += w
                } while (w != v)
                if (component.size > 1 || v in deps.getValue(v)) {
                    val ordered = names.filter { it in component }
                    for (w in component) out[w] = ordered
                }
            }
        }

        for (n in names) if (n !in index) visit(n)
        return out
    }
}
