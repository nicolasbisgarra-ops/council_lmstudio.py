package io.trazo.core.math

import io.trazo.core.math.layout.LayoutSymbol
import io.trazo.core.math.layout.MathLayoutConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Los 132 casos de las revisiones 1–3 y los 40 de la revisión 4, sin archivos temporales. */
@RunWith(Parameterized::class)
class MathLayoutBatteryTest(private val variante: Variante) {
    data class Caso(val id: String, val entrada: List<LayoutSymbol>, val esperado: String, val posicion: Int? = null)
    data class Variante(val caso: Caso, val escala: Float, val altura: Float) {
        override fun toString() = "${caso.id}, escala=$escala, altura=$altura"
    }

    @Test fun conservaEstructuraValorYPosicion() {
        val caso = variante.caso
        // La altura cambia junto con las distancias verticales: conserva los niveles de escritura.
        // Variar sólo el alto de una caja hasta cruzar la barra cambiaría el significado del caso.
        val entrada = caso.entrada.map {
            LayoutSymbol(it.label, it.minX * variante.escala, it.minY * variante.escala * variante.altura / 20f,
                it.maxX * variante.escala, it.maxY * variante.escala * variante.altura / 20f)
        }
        for (orden in listOf(entrada, entrada.reversed(), entrada.shuffled(kotlin.random.Random(32)))) {
            if (caso.posicion != null) {
                val error = assertThrows(variante.toString(), MathException::class.java) {
                    MathLayoutConverter.convert(orden).split('\n').forEach { MathParser.parseExpression(it) }
                }.error as MathError.Syntax
                assertEquals(variante.toString(), caso.posicion, error.position)
                assertFalse("El detalle conserva una posición local: $error", error.detail.contains("(posición"))
            } else {
                val texto = MathLayoutConverter.convert(orden)
                val esperado = caso.esperado.split('\n').map { MathParser.parseExpression(it) }
                val obtenido = texto.split('\n').map { MathParser.parseExpression(it) }
                assertEquals("$variante: $texto", esperado, obtenido)
                for ((a, b) in esperado.zip(obtenido)) {
                    val valor = Evaluator.evaluate(a) { ExactValue.of(Rational.of(2)) }
                    val actual = Evaluator.evaluate(b) { ExactValue.of(Rational.of(2)) }
                    assertEquals("$variante: valor", valor, actual)
                }
                VALORES[caso.id]?.let { valor ->
                    assertEquals("$variante: evaluación de referencia", valor,
                        Evaluator.evaluate(obtenido.single()) { ExactValue.of(Rational.of(2)) }.display())
                }
            }
        }
    }

    companion object {
        private val VALORES = mapOf(
            "O01" to "16", "R02" to "2", "R04" to "25/12", "R05a" to "16", "R05b" to "2/3",
            "N11" to "1/8388608", "N12" to "1/32", "N13" to "8388608", "N14" to "32",
            "N43" to "6", "N51" to "1/4", "N52" to "8", "V12" to "3/4", "V19" to "4"
        )
        fun s(t: String,x: Number,y: Number,w: Number=10,h: Number=20): LayoutSymbol {
            val xx=x.toFloat(); val yy=y.toFloat()
            return LayoutSymbol(t,xx,yy,xx+w.toFloat(),yy+h.toFloat())
        }
        fun f(a: Number=0,n: String="1",d: String="2",y: Number=0): List<LayoutSymbol> {
            val x=a.toFloat(); val yy=y.toFloat()
            return listOf(s(n,x+4,yy),s("Barra",x,yy+20,24,2),s(d,x+4,yy+27))
        }
        fun q(a: Number=0,y: Number=0,n: String="4")=listOf(s("Raíz",a,y,30,20),s(n,a.toFloat()+12,y))
        fun escalar(a: List<LayoutSymbol>,v: Float)=a.map{ LayoutSymbol(it.label,it.minX*v,it.minY*v,it.maxX*v,it.maxY*v) }
        fun mover(a: List<LayoutSymbol>,x: Float,y: Float)=a.map{ LayoutSymbol(it.label,it.minX+x,it.minY+y,it.maxX+x,it.maxY+y) }
        val e=listOf(s("x",0,12),s("2",12,0,6,10))
        val a=listOf(s("Barra",0,38,40,2),s("1",4,0,10,10),s("Barra",0,14,24,2),s("2",4,21,10,10),s("3",12,46))
        val r=listOf(s("Raíz",0,0,50,60),s("1",20,0),s("Barra",15,25,30,2),s("2",20,35))
        val u=listOf(s("Raíz",0,0,50,20),s("2",12,0))
        fun g(x: Int,n: String,d: String)=q(x,0,n)+listOf(s("Barra",x,25,36,2),s(d,x+12,32))
        @JvmStatic @Parameterized.Parameters(name="{0}") fun casos(): List<Array<Any>> {
            val out=mutableListOf<Caso>()
            fun c(id:String,entrada:List<LayoutSymbol>,esperado:String)=out.add(Caso(id,entrada,esperado))
            fun err(id:String,entrada:List<LayoutSymbol>,pos:Int)=out.add(Caso(id,entrada,"error@"+pos,pos))
            c("O01",listOf(s("8",0,11),s("Dividido",12,11))+f(24),"8/((1)/(2))")
            c("O02",a,"((1)/(2))/(3)")
            c("O03",r,"√((1)/(2))")
            c("O04",listOf(s("2",100,0,6,10),s("x",0,100)),"2\nx")
            c("O05",listOf(s("2",0,0),s("Menos",12,9,14,2),s("1",30,0)),"2-1")
            c("O06a",f()+listOf(s("Más",30,11))+f(44,"3","4"),"(1)/(2)+(3)/(4)")
            c("O06b",e+listOf(s("Más",24,12))+f(40),"x^2+(1)/(2)")
            c("O07a",u,"√(2)")
            c("O07b",escalar(u,.5f),"√(2)")
            c("O08",f()+listOf(s("3",30,11)),"(1)/(2)*3")
            c("O09",f()+listOf(s("x",0,100)),"(1)/(2)\nx")
            err("O10",listOf(s("2",0,0),s("3",100,0)),2)
            c("O11",listOf(s("1",4,0,0,20),s("Barra",0,20,24,2),s("2",4,27)),"(1)/(2)")
            err("O12",listOf(s("Raíz",0,0,40,20),s("x",12,0),s("Más",23,0),s("garabato",34,0)),4)
            c("C01",listOf(s("2",0,0),s("x",12,0)),"2x")
            c("C02",listOf(s("2",0,0),s("(",12,0),s("x",24,0),s(")",36,0)),"2(x)")
            c("C03",listOf(s("x",0,0),s("(",12,0),s("y",24,0),s(")",36,0)),"x(y)")
            c("C04",listOf(s("x",0,12),s("1",12,0,6,10),s("2",19,0,6,10)),"x^12")
            c("C05",listOf(s("x",4,12),s("1",16,0,6,10),s("2",23,0,6,10),s("Barra",0,35,40,2),s("3",12,43)),"(x^12)/(3)")
            c("C06",listOf(s("1",4,0),s("Barra",0,20,30,2))+q(0,27,"2"),"(1)/(√(2))")
            c("C07",listOf(s("f( )",0,0),s("x",12,0),s(")",24,0)),"f(x)")
            c("C08",listOf(s("x",0,20,10,30),s("2",12,5,6,14),s("3",20,0,4,7)),"x^(2^3)")
            c("C09",listOf(s("Raíz",0,0,70,40),s("Raíz",12,0,40,30),s("4",24,0)),"√(√(4))")
            c("R01a",q()+listOf(s("Más",36,0),s("1",48,0)),"√(4)+1")
            c("R01b",listOf(s("3",0,0),s("Más",12,0))+q(24),"3+√(4)")
            c("R01c",q()+listOf(s("x",0,100)),"√(4)\nx")
            c("R01d",listOf(s("1",4,0),s("Barra",0,20,30,2))+q(0,27,"2"),"(1)/(√(2))")
            c("R01e",q(0,10)+listOf(s("2",32,0,6,10)),"√(4)^2")
            c("R01f",r+listOf(s("Más",56,20),s("3",68,20)),"√((1)/(2))+3")
            c("R02",g(0,"4","2")+listOf(s("Más",42,25))+g(56,"9","3"),"(√(4))/(2)+(√(9))/(3)")
            c("R03",f()+f(0,"3","4",100),"(1)/(2)\n(3)/(4)")
            c("R04",f()+listOf(s("Más",30,11))+f(44,"3","4")+listOf(s("Más",74,11))+f(88,"5","6"),"(1)/(2)+(3)/(4)+(5)/(6)")
            c("R05a",listOf(s("8",0,11),s("Barra",12,11))+f(24),"8/((1)/(2))")
            c("R05b",f()+listOf(s("Dividido",30,11))+f(44,"3","4"),"((1)/(2))/((3)/(4))")
            c("R06",listOf(s("2",12,0,6,10),s("x",0,100)),"2\nx")
            c("R07a",f()+listOf(s("2",26,-3,6,10)),"((1)/(2))^2")
            c("R07b",listOf(s("x",0,14),s("2",12,0,6,10),s("Más",20,0,6,10),s("3",28,0,6,10)),"x^(2+3)")
            c("R07c",listOf(s("x",0,18,10,40),s("1",16,0,4,8),s("Barra",12,10,14,1),s("2",16,14,4,8)),"x^((1)/(2))")
            c("R08",listOf(s("1",4,0),s("Barra",0,25,24,2),s("2",4,32),s("Más",30,10),s("3",48,0),s("Barra",44,25,24,2),s("4",48,32),s("Barra",0,60,80,2),s("5",32,70)),"((1)/(2)+(3)/(4))/(5)")
            err("R09a",f(0,"garabato","2"),1)
            err("R09b",f(0,"1","garabato"),5)
            err("R09c",f()+listOf(s("Más",30,11),s("garabato",44,11)),8)
            err("R09d",f()+listOf(s("Más",30,11))+f(44,"garabato","4"),9)
            err("R09e",listOf(s("Raíz",0,0,70,40),s("Raíz",12,0,40,30),s("garabato",24,0)),4)
            err("R09f",listOf(s("x",0,0),s("Más",12,0),s("Barra",30,9,24,2)),2)
            for (factor in listOf(.01f,.1f,.5f,2f,10f,100f)) {
                c("S-raiz-"+factor,escalar(u,factor),"√(2)")
                c("S-fraccion-"+factor,escalar(f(),factor),"(1)/(2)")
                c("S-exponente-"+factor,escalar(e,factor),"x^2")
                c("S-anidacion-"+factor,escalar(r,factor),"√((1)/(2))")
            }
            err("I01",listOf(LayoutSymbol("1",10f,0f,0f,20f)),0)
            err("I02",listOf(LayoutSymbol("1",0f,0f,Float.POSITIVE_INFINITY,20f)),0)
            err("N01",r.map { if (it.label=="1") it.copy(label="garabato") else it },3)
            err("N02",r.map { if (it.label=="2") it.copy(label="garabato") else it },7)
            err("N03",listOf(s("3",0,0),s("Más",12,0),s("Raíz",24,0,30,20),s("garabato",36,0)),4)
            err("N04",listOf(s("3",0,11),s("Más",12,11))+f(24,"garabato","2"),3)
            err("N05",listOf(s("x",0,12),s("garabato",12,0,6,10)),2)
            err("N06",listOf(s("x",0,12),s("2",12,0,6,10),s("Más",26,12),s("garabato",40,12)),4)
            c("N07",listOf(s("1",44,0),s("Barra",0,20,100,2),s("2",44,27),s("x",44,100)),"(1)/(2)\nx")
            c("N08",listOf(s("1",44,0),s("Barra",0,20,100,2),s("2",44,27))+f(0,"3","4",100),"(1)/(2)\n(3)/(4)")
            c("N09",e+listOf(s("3",19,0,6,10)),"x^23")
            c("N10",listOf(s("x",0,14),s("2",12,0,6,10),s("Más",20,0,6,10),s("3",28,0,6,10),s("Más",40,14),s("1",52,14)),"x^(2+3)+1")
            c("N11",f()+listOf(s("2",26,-3,6,10),s("3",33,-3,6,10)),"((1)/(2))^23")
            c("N12",f()+listOf(s("2",26,-3,6,10),s("Más",34,-3,6,10),s("3",42,-3,6,10)),"((1)/(2))^(2+3)")
            c("N13",q(0,10)+listOf(s("2",32,0,6,10),s("3",39,0,6,10)),"√(4)^23")
            c("N14",q(0,10)+listOf(s("2",32,0,6,10),s("Más",40,0,6,10),s("3",48,0,6,10)),"√(4)^(2+3)")
            c("N15",listOf(s("z",0,0),s("3",12,0)),"z*3")
            c("N16",listOf(s("alfa",0,0),s("3",12,0)),"α*3")
            c("N17",listOf(s("gamma",0,0),s("3",12,0)),"γ*3")
            val igual=a.map { if (it.label=="Barra" && it.width==40f) it.copy(maxX=24f) else it }
            c("N19a",igual,"((1)/(2))/(3)")
            c("N19b",igual.reversed(),"((1)/(2))/(3)")
            c("N20",listOf(s("Raíz",0,0,80,80))+a.map {it.copy(minX=it.minX+15,maxX=it.maxX+15)},"√(((1)/(2))/(3))")
            c("N21",listOf(s("1",16,0,4,8),s("Barra",12,10,14,1),s("2",16,14,4,8),s("x",0,40,10,40)),"(1)/(2)\nx")
            c("N22",listOf(s("x",0,0),s("Más",12,0))+q(24,0)+listOf(s("Más",60,0))+q(72,0,"9"),"x+√(4)+√(9)")
            c("N23",listOf(s("Raíz",0,0,90,60))+f(15)+listOf(s("Más",45,11))+f(59,"3","4"),"√((1)/(2)+(3)/(4))")
            c("N25",listOf(s("2",0,0),s("3",12,0),s("4",24,0)),"234")
            err("N26",listOf(s("2",0,0),s("3",100,0),s("4",112,0)),2)
            c("N27",listOf(s("x",0,14),s("2",12,0,6,10),s("Menos",20,4,6,2),s("3",28,0,6,10)),"x^(2-3)")
            c("N28",listOf(s("(",0,12),s("x",12,12),s("Más",24,12),s("1",36,12),s(")",48,12),s("2",60,0,6,10)),"(x+1)^2")
            c("N29",listOf(s("Pi",0,12),s("2",12,0,6,10)),"π^2")
            c("N30",listOf(s("alfa",0,12),s("2",12,0,6,10)),"α^2")
            c("N31",listOf(s("3",0,0),s("Por",12,0))+q(24),"3*√(4)")
            c("N32",r+listOf(s("2",52,-3,6,10)),"√((1)/(2))^2")
            c("N33",f()+listOf(s("Más",30,11))+f(44,"3","4")+listOf(s("x",44,100)),"(1)/(2)+(3)/(4)\nx")
            err("N34",listOf(s("x",0,0),s("Por",12,0),s("Raíz",24,0,30,20),s("garabato",36,0)),4)
            err("N35",listOf(s("2",0,0),s("3",100,0),s("garabato",120,0)),3)
            err("N36",listOf(s("x",0,0),s("Más",12,0))+r.map { it.copy(minX=it.minX+30,maxX=it.maxX+30) }.map { if(it.label=="1")it.copy(label="garabato")else it },5)
            c("N37",f()+f(30,"3","4"),"(1)/(2)*(3)/(4)")
            for (factor in listOf(.01f,.1f,.5f,2f,10f,100f)) {
                c("NS-estructura-"+factor,escalar(f()+listOf(s("2",26,-3,6,10),s("3",33,-3,6,10)),factor),"((1)/(2))^23")
                c("NS-laterales-"+factor,escalar(e+listOf(s("Más",24,12))+f(40),factor),"x^2+(1)/(2)")
            }
            c("NT-positivo",mover(e,10000f,10000f),"x^2")
            c("NT-negativo",mover(e,-100f,-100f),"x^2")
            c("N40",listOf(s("x",0,12),s("2",12,0,6,10),s("Más",24,12),s("y",36,12),s("3",48,0,6,10)),"x^2+y^3")
            c("N41",listOf(s("x",0,12),s("2",12,0,6,10),s("y",24,12),s("3",36,0,6,10)),"x^2*y^3")
            c("N42",listOf(s("2",0,12),s("2",12,0,6,10),s("Más",24,12),s("3",36,12),s("3",48,0,6,10)),"2^2+3^3")
            c("N43",listOf(s("x",0,0),s("Más",12,0),s("y",24,0),s("2",36,-12,6,10)),"x+y^2")
            c("N46",listOf(s("1",44,0),s("Barra",0,20,100,2),s("2",44,27),s("3",44,100)),"(1)/(2)\n3")
            err("N47",listOf(s("Pi",0,0),s("Más",12,0),s("garabato",24,0)),2)
            err("N48",listOf(s("alfa",0,0),s("Más",12,0),s("garabato",24,0)),2)
            err("N49",listOf(s("(",0,0),s("x",12,0),s(")",24,0),s("garabato",36,0)),3)
            err("N50",f()+listOf(s("Dividido",30,11))+f(44,"garabato","4"),10)
            c("N51",f()+mover(escalar(q(),.5f),26f,-3f),"((1)/(2))^(√(4))")
            c("N52",q(0,10)+mover(escalar(q(0,0,"9"),.5f),32f,-3f),"√(4)^(√(9))")
            c("V01",listOf(s("(",0,0),s("x",12,0),s("Más",24,0))+q(36)+listOf(s(")",72,0)),"(x+√(4))")
            c("V02",listOf(s("(",0,0))+q(12)+listOf(s("Más",48,0),s("x",60,0),s(")",72,0)),"(√(4)+x)")
            c("V03",listOf(s("(",0,11))+f(12)+listOf(s(")",42,11)),"((1)/(2))")
            c("V04",listOf(s("(",0,11))+f(12)+listOf(s("Más",42,11))+f(56,"3","4")+listOf(s(")",86,11)),"((1)/(2)+(3)/(4))")
            c("V05",listOf(s("(",0,0),s("(",12,0),s("x",24,0),s("Más",36,0),s("1",48,0),s(")",60,0),s(")",72,0)),"((x+1))")
            c("V06",listOf(s("(",0,0),s("x",12,0),s("Más",24,0),s("(",36,0),s("y",48,0),s(")",60,0),s(")",72,0)),"(x+(y))")
            c("V07",listOf(s("x",0,11),s("Más",12,11),s("(",24,11))+f(36)+listOf(s(")",66,11)),"x+((1)/(2))")
            c("V08",listOf(s("(",0,11))+f(12)+listOf(s(")",42,11),s("2",54,-1,6,10)),"((1)/(2))^2")
            c("V09",listOf(s("(",0,11),s("x",12,11),s("Más",24,11))+f(36)+listOf(s(")",66,11),s("2",78,-1,6,10)),"(x+(1)/(2))^2")
            c("V10",f()+listOf(s("Más",28,11),s("x",40,11),s("2",52,-1,6,10)),"(1)/(2)+x^2")
            c("V11",q()+listOf(s("x",32,0),s("2",44,-12,6,10)),"√(4)*x^2")
            c("V12",f()+listOf(s("2",26,-3,6,10),s("3",40,11)),"((1)/(2))^2*3")
            c("V13",q(0,10)+listOf(s("2",32,0,6,10),s("3",48,10)),"√(4)^2*3")
            c("V14",listOf(s("Pi",0,18,10,40),s("1",16,0,4,8),s("Barra",12,10,14,1),s("2",16,14,4,8)),"π^((1)/(2))")
            c("V15",listOf(s("delta",0,12),s("2",12,0,6,10)),"δ^2")
            c("V16",listOf(s("12",0,12),s("3",12,0,6,10)),"12^3")
            c("V17",e+listOf(s("3",26,12)),"x^2*3")
            c("V18",e+listOf(s("3",40,12)),"x^2*3")
            c("V19",f()+listOf(s("Menos",26,-3,6,10),s("2",34,-3,6,10)),"((1)/(2))^(-2)")
            c("V20",listOf(s("x",0,12),s("Menos",12,0,6,10),s("2",20,0,6,10)),"x^(-2)")
            c("V21",f()+listOf(s("(",26,-3,4,10),s("2",32,-3,6,10),s("Más",40,-3,6,10),s("3",48,-3,6,10),s(")",56,-3,4,10)),"((1)/(2))^(2+3)")
            c("V22",f()+listOf(s("x",4,70)),"(1)/(2)\nx")
            c("V23",f()+listOf(s("3",4,70)),"(1)/(2)\n3")
            err("V24",listOf(s("x",0,0),s("garabato",0,100)),0)
            err("V25",listOf(s("x",0,0))+q(0,100,"garabato"),2)
            err("V26",listOf(s("(",0,0),s("x",12,0),s("Más",24,0),s("garabato",36,0),s(")",48,0)),3)
            err("V27",q()+listOf(s("Más",36,0))+q(48,0,"garabato"),7)
            err("V28",f()+listOf(s("Más",30,11))+q(44,11,"garabato"),10)
            c("V29",listOf(s("(",0,11),s("2",12,11))+f(24)+listOf(s(")",54,11)),"(2*(1)/(2))")
            c("V30",listOf(s("(",0,0),s("1",12,0),s("(",24,0),s("2",36,0),s(")",48,0),s(")",60,0)),"(1*(2))")
            c("V31",listOf(s("(",0,0))+q(12)+listOf(s(")",48,0)),"(√(4))")
            c("V32",listOf(s("1",0,0),s("Punto",12,0),s("5",24,0)),"1.5")
            c("V33",listOf(s("1",0,12),s("2",12,12),s("3",24,0,6,10)),"12^3")
            c("V34",listOf(s("theta",0,12),s("2",12,0,6,10)),"θ^2")
            c("V35",listOf(s("alfa",0,18,10,40),s("1",16,0,4,8),s("Barra",12,10,14,1),s("2",16,14,4,8)),"α^((1)/(2))")
            c("V36",listOf(s("gamma",0,12),s("2",12,0,6,10)),"γ^2")
            c("V37",listOf(s("x",0,12),s("y",12,0,6,10),s("3",22,12)),"x^y*3")
            c("V38",listOf(s("x",0,12),s("2",12,0,6,10),s("3",30,12),s("Más",42,12),s("1",54,12)),"x^2*3+1")
            c("V39",listOf(s("1",44,0),s("Barra",0,20,100,2),s("2",44,27),s("3",44,70)),"(1)/(2)\n3")
            c("V40",f()+listOf(s("Más",28,11),s("y",40,11),s("2",52,-1,6,10)),"(1)/(2)+y^2")
            check(out.size == 172) { "Faltan casos de las revisiones: ${out.size}/172" }
            check(out.map { it.id }.distinct().size == out.size)
            return out.flatMap { caso ->
                listOf(.5f, 1f, 2f, 3f).flatMap { escala ->
                    listOf(10f, 20f, 40f).map { altura -> arrayOf<Any>(Variante(caso, escala, altura)) }
                }
            }
        }
    }
}
