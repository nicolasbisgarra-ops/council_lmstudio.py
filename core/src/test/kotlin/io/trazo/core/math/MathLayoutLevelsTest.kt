package io.trazo.core.math

import io.trazo.core.math.layout.LayoutSymbol
import io.trazo.core.math.layout.MathLayoutConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Los 70 escenarios de la revisión final, con alturas individuales y niveles intercalados. */
@RunWith(Parameterized::class)
class MathLayoutLevelsTest(private val caso: Caso, private val escala: Float, private val ordenElegido: Int) {
    data class Caso(val id: String, val entrada: List<LayoutSymbol>, val esperado: String, val posicion: Int? = null) {
        override fun toString() = id
    }
    @Test fun verificaEstructuraYPosicion() {
        val entrada = caso.entrada.map { LayoutSymbol(it.label,it.minX*escala,it.minY*escala,it.maxX*escala,it.maxY*escala) }
        val esperados = if(caso.posicion == null) caso.esperado.split('\n').map { MathParser.parseExpression(it) } else emptyList()
        for ((orden, simbolos) in listOf(entrada, entrada.reversed(), entrada.shuffled(kotlin.random.Random(923))).withIndex().filter { it.index == ordenElegido }) {
            var texto: String? = null
            var error: MathError? = null
            var obtenido: List<Expr>? = null
            try {
                texto = MathLayoutConverter.convert(simbolos)
                obtenido = texto.split('\n').map { MathParser.parseExpression(it) }
            } catch (e: MathException) { error = e.error }
            val detalle = "${caso.id}; escala=$escala; orden=$orden; esperado=${caso.esperado}; obtenido=${texto?.replace("\n","\\n")}; error=$error"
            if(caso.posicion != null) {
                assertTrue(detalle, error is MathError.Syntax)
                assertEquals(detalle, caso.posicion, (error as MathError.Syntax).position)
                assertFalse(detalle, error.detail.contains("(posición"))
            } else {
                assertNull(detalle, error)
                assertEquals(detalle, esperados, obtenido)
            }
        }
    }
    companion object {
        fun s(t:String,x:Number,y:Number,w:Number=10,h:Number=20): LayoutSymbol = LayoutSymbol(t,x.toFloat(),y.toFloat(),x.toFloat()+w.toFloat(),y.toFloat()+h.toFloat())
        fun f(x:Int=0,y:Int=0,n:String="1",d:String="2")=listOf(s(n,x+4,y),s("Barra",x,y+20,24,2),s(d,x+4,y+27))
        fun q(x:Int=0,y:Int=0,n:String="4")=listOf(s("Raíz",x,y,30,20),s(n,x+12,y))
        @JvmStatic @Parameterized.Parameters(name="{0}, escala={1}, orden={2}") fun casos(): List<Array<Any>> {
            val cs=mutableListOf<Caso>()
            fun c(id:String,a:List<LayoutSymbol>,e:String){cs+=Caso(id,a,e)}
            fun err(id:String,a:List<LayoutSymbol>,pos:Int){cs+=Caso(id,a,"error@$pos",pos)}
            c("A01-dos-renglones-con-cifras",listOf(s("1",0,0),s("2",12,0),s("3",0,60),s("4",12,60)),"12\n34")
            c("A02-renglon-intercalado",listOf(s("1",0,0),s("2",12,0),s("x",6,60)),"12\nx")
            c("A03-fraccion-dos-cifras",listOf(s("1",4,0),s("2",16,0),s("Barra",0,25,34,2),s("3",4,34),s("4",16,34)),"(12)/(34)")
            c("A04-numerador-dos-cifras",listOf(s("1",4,0),s("2",16,0),s("Barra",0,25,34,2),s("3",4,34)),"(12)/(3)")
            c("A05-denominador-dos-cifras",listOf(s("1",12,0),s("Barra",0,25,34,2),s("3",4,34),s("4",16,34)),"(1)/(34)")
            c("A06-raiz-y-numero-siguiente",q()+listOf(s("3",0,60),s("4",12,60)),"√(4)\n34")
            c("A07-numero-y-fraccion-siguiente",listOf(s("1",0,0),s("2",12,0))+f(0,80),"12\n(1)/(2)")
            c("A08-cifras-y-exp-otra-linea",listOf(s("1",0,0),s("2",12,0),s("x",0,60),s("2",12,48,6,10)),"12\nx^2")
            c("B01-exponente-largo",listOf(s("x",0,14),s("2",12,0,6,10),s("Más",20,0,6,10),s("3",28,0,6,10),s("Más",36,0,6,10),s("4",44,0,6,10)),"x^(2+3+4)")
            c("B02-exponente-negativo-largo",listOf(s("x",0,14),s("Menos",12,4,6,2),s("2",20,0,6,10),s("Más",28,0,6,10),s("3",36,0,6,10)),"x^(-2+3)")
            c("B03-exponente-producto",listOf(s("x",0,14),s("2",12,0,6,10),s("Por",20,0,6,10),s("3",28,0,6,10)),"x^(2*3)")
            c("B04-base-angosta",listOf(s("1",0,14,2,20),s("2",6,0,6,10),s("Más",14,0,6,10),s("3",22,0,6,10)),"1^(2+3)")
            c("B05-exponente-decimal",listOf(s("x",0,14),s("1",12,0,6,10),s("Punto",20,8,2,2),s("5",24,0,6,10)),"x^1.5")
            c("B06-base-decimal",listOf(s("1",0,12),s("Punto",12,20,2,2),s("5",18,12),s("2",30,0,6,10)),"1.5^2")
            c("B07-exponente-letra-y-variable",listOf(s("x",0,12),s("y",12,0,6,10),s("z",24,12)),"x^y*z")
            c("B08-exponente-griega-y-variable",listOf(s("x",0,12),s("alfa",12,0,6,10),s("beta",24,12)),"x^α*β")
            c("B09-potencias-con-exponentes-letras",listOf(s("x",0,12),s("y",12,0,6,10),s("z",24,12),s("w",36,0,6,10)),"x^y*z^w")
            c("B10-exponente-compuesto-y-variable",listOf(s("x",0,14),s("y",12,0,6,10),s("Más",20,0,6,10),s("z",28,0,6,10),s("a",40,14)),"x^(y+z)*a")
            c("B11-numero-tras-potencia",listOf(s("2",0,12),s("2",12,0,6,10),s("3",26,12)),"2^2*3")
            c("B12-menos-fino",listOf(s("x",0,12),s("Menos",12,4,6,2),s("2",20,0,6,10)),"x^(-2)")
            for(h in listOf(14,15,16,18,20)) c("B13-altura-exp-$h",listOf(s("x",0,12),s("2",12,0,6,h)),"x^2")
            c("B14-potencia-tres-niveles",listOf(s("x",0,30,10,40),s("2",12,10,6,20),s("3",20,0,4,10)),"x^(2^3)")
            c("B15-potencia-raiz-y-numero",q(0,10)+listOf(s("2",32,0,6,10),s("3",48,10)),"√(4)^2*3")
            c("B16-exponente-parentesis-largo",listOf(s("x",0,14),s("(",12,0,4,10),s("2",18,0,6,10),s("Más",26,0,6,10),s("3",34,0,6,10),s("Más",42,0,6,10),s("4",50,0,6,10),s(")",58,0,4,10)),"x^(2+3+4)")
            c("C01-denominador-con-potencia-cercana",listOf(s("1",4,0),s("Barra",0,25,30,2),s("x",4,32),s("2",16,20,6,10)),"(1)/(x^2)")
            c("C02-denominador-con-potencia-separada",listOf(s("1",4,0),s("Barra",0,25,30,2),s("x",4,40),s("2",16,28,6,10)),"(1)/(x^2)")
            c("C03-numerador-con-potencia",listOf(s("x",4,0),s("2",16,-12,6,10),s("Barra",0,25,30,2),s("3",4,32)),"(x^2)/(3)")
            c("C04-operadores-finos-en-fraccion",listOf(s("1",4,0),s("Más",16,5,10,10),s("2",28,0),s("Barra",0,25,48,2),s("3",4,34),s("Menos",16,43,10,2),s("4",28,34)),"(1+2)/(3-4)")
            c("C05-numerador-negativo",listOf(s("Menos",4,9,6,2),s("1",12,0),s("Barra",0,25,32,2),s("2",12,34)),"(-1)/(2)")
            c("C06-denominador-negativo",listOf(s("1",12,0),s("Barra",0,25,32,2),s("Menos",4,43,6,2),s("2",12,34)),"(1)/(-2)")
            c("C07-fraccion-negativa-con-potencia",listOf(s("Menos",0,20,8,2))+f(12)+listOf(s("2",38,-3,6,10)),"-((1)/(2))^2")
            c("C08-dos-fracciones-de-numeros",f(0,0,"12","34")+listOf(s("Más",30,11))+f(44,0,"56","78"),"(12)/(34)+(56)/(78)")
            c("C09-fraccion-en-raiz-anidada",listOf(s("Raíz",0,0,100,100),s("Raíz",12,0,70,80),s("1",36,0),s("Barra",28,25,40,2),s("2",36,35)),"√(√((1)/(2)))")
            c("C10-raiz-en-fraccion-en-raiz",listOf(s("Raíz",0,0,80,90))+q(16)+listOf(s("Barra",12,30,50,2),s("2",28,42)),"√((√(4))/(2))")
            c("C11-fracciones-anidadas-y-potencia",listOf(s("1",4,0,10,10),s("Barra",0,14,24,2),s("2",4,21,10,10),s("Barra",0,38,40,2),s("3",12,46),s("2",42,13,6,10)),"(((1)/(2))/(3))^2")
            c("D01-grupo-con-raiz",listOf(s("(",0,0))+q(12)+listOf(s(")",48,0)),"(√(4))")
            c("D02-raiz-con-grupo-fraccion",listOf(s("Raíz",0,0,100,65),s("(",12,24),s("1",30,0),s("Barra",24,25,30,2),s("2",30,35),s(")",70,24)),"√(((1)/(2)))")
            c("D03-grupo-con-fraccion-y-potencia",listOf(s("(",0,11))+f(12)+listOf(s(")",42,11),s("2",54,-1,6,10)),"((1)/(2))^2")
            c("D04-grupo-parentesis-angosto",listOf(s("(",0,12,2,20),s("x",6,12),s(")",20,12,2,20),s("2",30,0,6,10)),"(x)^2")
            c("D05-raiz-de-parentesis-anidados",listOf(s("Raíz",0,0,100,30),s("(",12,0),s("(",24,0),s("x",36,0),s(")",48,0),s(")",60,0)),"√(((x)))")
            c("D06-grupo-doble-fraccion-y-raiz",listOf(s("(",0,11))+f(12)+listOf(s("Más",42,11))+q(54,11)+listOf(s(")",90,11)),"((1)/(2)+√(4))")
            c("D07-grupo-tres-niveles",listOf(s("(",0,0),s("(",12,0),s("(",24,0),s("x",36,0),s(")",48,0),s(")",60,0),s(")",72,0)),"(((x)))")
            c("E01-potencias-dos-lineas",listOf(s("x",0,12),s("2",12,0,6,10),s("y",0,72),s("3",12,60,6,10)),"x^2\ny^3")
            c("E02-fraccion-y-potencia-dos-lineas",f()+listOf(s("x",0,112),s("2",12,100,6,10)),"(1)/(2)\nx^2")
            err("E03-posicion-segunda-linea",listOf(s("x",0,0),s("Raíz",0,100,50,20),s("y",12,100),s("Más",24,100),s("garabato",36,100)),4)
            err("E04-posicion-grupo-raiz-fraccion",listOf(s("(",0,11),s("Raíz",12,0,60,70),s("garabato",32,0),s("Barra",24,25,30,2),s("2",32,35),s(")",80,11)),4)
            err("E05-raiz-vacia",listOf(s("Raíz",0,0,30,20)),0)
            err("E06-raiz-vacia-con-prefijo",listOf(s("x",0,0),s("Más",12,0),s("Raíz",24,0,30,20)),2)
            err("E07-caja-invertida",listOf(LayoutSymbol("1",0f,20f,10f,0f)),0)
            err("E08-caja-NaN",listOf(LayoutSymbol("1",0f,0f,10f,Float.NaN)),0)
            c("F01-caja-ancho-cero",listOf(s("1",4,0,0,20),s("Barra",0,20,24,2),s("2",4,27)),"(1)/(2)")
            c("F02-caja-alto-cero",listOf(s("1",4,10,10,0),s("Barra",0,20,24,2),s("2",4,27)),"(1)/(2)")
            c("F03-cifras-alturas-distintas",listOf(s("1",0,8,4,12),s("2",7,0,10,20)),"12")
            c("F04-decimal-punto-bajo",listOf(s("1",0,0),s("Punto",12,18,2,2),s("5",18,0)),"1.5")
            c("F05-decimal-coma-baja",listOf(s("1",0,0),s("Coma",12,18,2,4),s("5",18,0)),"1.5")
            c("F06-punto-caja-convencional",listOf(s("1",0,0),s("Punto",12,0),s("5",24,0)),"1.5")
            c("F07-menos-caja-fina",listOf(s("2",0,0),s("Menos",12,9,14,2),s("1",30,0)),"2-1")
            c("F08-decimal-numerador",listOf(s("1",4,0),s("Punto",16,18,2,2),s("5",22,0),s("Barra",0,25,40,2),s("2",12,34)),"(1.5)/(2)")
            c("G01-bateria-R07b-escala",listOf(s("x",0,14),s("2",12,0,6,10),s("Más",20,0,6,10),s("3",28,0,6,10)),"x^(2+3)")
            c("G02-exponente-largo-sin-umbral",listOf(s("x",0,12),s("2",12,0,6,10),s("Más",20,0,6,10),s("3",28,0,6,10),s("Más",36,0,6,10),s("4",44,0,6,10)),"x^(2+3+4)")
            c("G03-fraccion-exponente-denominador",listOf(s("1",4,0),s("Barra",0,25,40,2),s("x",4,40),s("2",16,28,6,10),s("Más",24,28,6,10),s("3",32,28,6,10)),"(1)/(x^(2+3))")
            c("G04-raiz-exponente-letra-por-variable",q(0,10)+listOf(s("alfa",32,0,6,10),s("beta",44,10)),"√(4)^α*β")
            c("G05-fraccion-exponente-letra-por-variable",f()+listOf(s("alfa",26,-3,6,10),s("beta",38,11)),"((1)/(2))^α*β")
            c("G06-grupo-exponente-letra-por-variable",listOf(s("(",0,12),s("x",12,12),s(")",24,12),s("y",36,0,6,10),s("z",48,12)),"(x)^y*z")
            c("G07-exponente-largo-de-fraccion",f()+listOf(s("2",26,-3,6,10),s("Más",34,-3,6,10),s("3",42,-3,6,10),s("Más",50,-3,6,10),s("4",58,-3,6,10)),"((1)/(2))^(2+3+4)")
            c("G08-digitos-exponente-tras-varias-lineas",listOf(s("x",0,12),s("1",12,0,6,10),s("2",19,0,6,10),s("y",15,60)),"x^12\ny")
            check(cs.size == 70 && cs.map { it.id }.distinct().size == 70)
            return cs.flatMap { c -> listOf(.01f,.5f,1f,3f,100f).flatMap { e -> (0..2).map { arrayOf<Any>(c,e,it) } } }
        }
    }
}
