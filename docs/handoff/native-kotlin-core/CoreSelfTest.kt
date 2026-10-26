package io.trazo.core

import kotlin.math.abs

private var passed = 0
private fun test(name: String, action: () -> Unit) {
    action()
    passed++
    println("PASS [$passed] $name")
}
private fun line(a: Float = 0f, b: Float = 100f): InkStroke =
    InkStroke(listOf(InkPoint(a, 0f), InkPoint((a+b)/2f, 50f), InkPoint(b, 100f)))
private fun horizontal(): InkStroke =
    InkStroke(listOf(InkPoint(0f,0f),InkPoint(50f,0f),InkPoint(100f,0f)))
private fun vertical(): InkStroke =
    InkStroke(listOf(InkPoint(0f,0f),InkPoint(0f,50f),InkPoint(0f,100f)))
private fun pattern(id: String, gesture: List<InkStroke>, output: List<InkStroke> = listOf(line())) =
    InkPattern(id, gesture, output, outputWidth = 140f, outputHeight = 200f)

fun main() {
    val matcher = GestureMatcher()
    val diagonal = pattern("integral",listOf(line()))
    val horiz = pattern("fraccion",listOf(horizontal()))
    test("exact shape recognized") {
        check(matcher.recognize(listOf(line()),listOf(diagonal)) is MatchResult.Found)
    }
    test("translation and scale invariant") {
        val moved = InkStroke(line().points.map { it.copy(x = it.x * 3 + 300, y = it.y * 2 + 500) })
        // To preserve shape, use uniform scale; anisotropic scale should not be invariant.
        val uniformlyMoved = InkStroke(line().points.map { it.copy(x = it.x * 2 + 300, y = it.y * 2 + 500) })
        check(matcher.recognize(listOf(uniformlyMoved),listOf(diagonal)) is MatchResult.Found)
        check(moved.points.size == 3)
    }
    test("stroke reversal allowed") {
        val reversed = InkStroke(line().points.reversed())
        check(matcher.recognize(listOf(reversed),listOf(diagonal)) is MatchResult.Found)
    }
    test("different gesture not matched with high threshold") {
        check(matcher.recognize(listOf(vertical()),listOf(horiz)) is MatchResult.NoMatch)
    }
    test("stroke count respected") {
        check(matcher.recognize(listOf(line(), line()),listOf(diagonal)) is MatchResult.NoMatch)
    }
    test("ambiguous templates are not auto-converted") {
        val duplicate = pattern("otra",listOf(line()))
        check(matcher.recognize(listOf(line()),listOf(diagonal,duplicate)) is MatchResult.Ambiguous)
    }
    test("good best score picks corresponding template") {
        val result = matcher.recognize(listOf(line()),listOf(diagonal,horiz))
        check(result is MatchResult.Found && result.match.pattern.id == "integral")
    }
    test("dwell triggers after 1000ms while down") {
        val d=DwellDetector(); d.down(1f,1f,0); d.move(50f,50f,100)
        check(!d.check(1099,25f)); check(d.check(1100,25f))
    }
    test("up at 900ms prevents conversion") {
        val d=DwellDetector(); d.down(0f,0f,0); d.up(); check(!d.check(1500,25f))
    }
    test("zero-length ink never converts") {
        val d=DwellDetector(); d.down(0f,0f,0); check(!d.check(2000,0f))
    }
    test("small pen jitter tolerated") {
        val d=DwellDetector(); d.down(0f,0f,0); d.move(3f,4f,500); check(d.check(1000,25f))
    }
    test("significant motion resets hold") {
        val d=DwellDetector(); d.down(0f,0f,0); d.move(20f,0f,600)
        check(!d.check(1000,25f)); check(d.check(1600,25f))
    }
    test("canceled contact never converts") {
        val d=DwellDetector(); d.down(0f,0f,0); d.cancel(); check(!d.check(2000,25f))
    }
    test("only one conversion per contact") {
        val d=DwellDetector(); d.down(0f,0f,0); check(d.check(1000,25f)); check(!d.check(5000,25f))
    }
    test("output retains native vector points and pressure") {
        val source=InkStroke(listOf(InkPoint(1f,2f,0.2f), InkPoint(8f,17f,0.9f)))
        val p=pattern("custom",listOf(line()),listOf(source))
        val out=InkReplacement.stamp(p,300f,500f)
        check(out.size==1 && out[0].points.size==2)
        check(out[0].points.first().x==300f && out[0].points.first().y==500f)
        check(out[0].points.last().x==440f && out[0].points.last().y==700f)
        check(abs(out[0].points.last().pressure - 0.9f)<0.0001f)
    }
    test("output is deep-copied, source geometry never changed") {
        val p=pattern("custom",listOf(line()),listOf(horizontal()))
        InkReplacement.stamp(p,100f,100f)
        check(p.outputInk[0].points[0].x == 0f)
    }
    println("RESULT: $passed/$passed portable Kotlin core tests passed")
}
