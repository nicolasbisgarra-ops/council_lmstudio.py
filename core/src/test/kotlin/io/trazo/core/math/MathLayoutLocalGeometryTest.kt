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
/** Los 119 escenarios de la segunda revisión, con escalas extremas y estiramientos por eje. */
@RunWith(Parameterized::class)
class MathLayoutLocalGeometryTest(private val c:C,private val sx:Float,private val sy:Float,private val ord:Int) {
 data class C(val id:String,val a:List<LayoutSymbol>,val expected:String,val pos:Int?=null) { override fun toString()=id }
 @Test fun contrastaContraEstructuraIndependiente() {
  val a=c.a.map { LayoutSymbol(it.label,it.minX*sx,it.minY*sy,it.maxX*sx,it.maxY*sy) }
  val input=when(ord) { 0->a; 1->a.reversed(); else->a.shuffled(kotlin.random.Random(71+ord)) }
  var result:String?=null
  var error:MathError?=null
  var actual:List<Statement>?=null
  try { result=MathLayoutConverter.convert(input); actual=result.split('\n').map(MathParser::parseStatement) }
  catch(e:MathException) { error=e.error }
  val msg="CASO="+c.id+"; sx="+sx+"; sy="+sy+"; orden="+ord+"; esperado="+c.expected.replace("\n","↵")+"; obtenido="+result?.replace("\n","↵")+"; error="+error
  if(c.pos!=null) {
   assertTrue(msg,error is MathError.Syntax)
   assertEquals(msg,c.pos,(error as MathError.Syntax).position)
   assertFalse(msg,error.detail.contains("(posición"))
  } else {
   assertNull(msg,error)
   assertEquals(msg,c.expected.split('\n').map(MathParser::parseStatement),actual)
  }
 }
 companion object {
  fun s(t:String,x:Number,y:Number,w:Number=10,h:Number=20)=LayoutSymbol(t,x.toFloat(),y.toFloat(),x.toFloat()+w.toFloat(),y.toFloat()+h.toFloat())
  data class G(val a:List<LayoutSymbol>,val e:String) {
   val l get()=a.minOf { it.minX }; val r get()=a.maxOf { it.maxX }
   val t get()=a.minOf { it.minY }; val b get()=a.maxOf { it.maxY }
   val w get()=r-l
  }
  fun G.move(dx:Float=0f,dy:Float=0f)=G(a.map { it.copy(minX=it.minX+dx,maxX=it.maxX+dx,minY=it.minY+dy,maxY=it.maxY+dy) },e)
  fun G.scale(k:Float)=G(a.map { LayoutSymbol(it.label,it.minX*k,it.minY*k,it.maxX*k,it.maxY*k) },e)
  fun at(t:String,h:Float=20f,w:Float=10f)=G(listOf(s(t,0,10-h,w,h)),when(t){"alfa"->"α";"beta"->"β";"gamma"->"γ";"Menos"->"-";"Más"->"+";else->t})
  fun num(t:String,heights:List<Int> = t.map { 20 }):G {
   val a=t.mapIndexed { i,ch -> s(ch.toString(),i*12,10-heights[i],10,heights[i]) }
   return G(a,t)
  }
  fun seq(a:G,b:G,op:String="+"):G {
   val mark=G(listOf(s(if(op=="-")"Menos" else op,a.r+4,if(op=="-")-1 else -10,8,if(op=="-")2 else 20)),op)
   val moved=b.move(a.r+16-b.l)
   return G(a.a+mark.a+moved.a,a.e+op+b.e)
  }
  fun frac(a:G,b:G):G {
   val width=maxOf(a.w,b.w)+12
   val aa=a.move((width-a.w)/2-a.l,-6-a.b)
   val bb=b.move((width-b.w)/2-b.l,6-b.t)
   return G(aa.a+listOf(s("Barra",0,0,width,2))+bb.a,"("+a.e+")/("+b.e+")")
  }
  fun root(a:G):G {
   val aa=a.move(12-a.l)
   val top=minOf(-12f,aa.t-2)
   val bottom=maxOf(12f,aa.b+2)
   return G(listOf(s("Raíz",0,top,aa.r+4,bottom-top))+aa.a,"√("+a.e+")")
  }
  fun group(a:G):G {
   val aa=a.move(8-a.l)
   val top=minOf(-12f,aa.t-2)
   val bottom=maxOf(12f,aa.b+2)
   return G(listOf(s("(",0,top,4,bottom-top))+aa.a+listOf(s(")",aa.r+4,top,4,bottom-top)),"("+a.e+")")
  }
  fun power(a:G,b:G):G {
   val small=b.scale(.5f)
   val bb=small.move(a.r+2-small.l,-16f)
   return G(a.a+bb.a,"("+a.e+")^("+b.e+")")
  }
  fun product(a:G,b:G)=G(a.a+b.move(a.r+6-b.l).a,"("+a.e+")*("+b.e+")")
  fun lines(a:G,b:G)=G(a.a+b.move(-b.l,maxOf(100f,a.b-b.t+35)).a,a.e+"\n"+b.e)
  @JvmStatic @Parameterized.Parameters(name="{0};sx={1};sy={2};orden={3}")
  fun cases():List<Array<Any>> {
   val cs=mutableListOf<C>()
   fun add(id:String,g:G) { cs+=C(id,g.a,g.e) }
   fun raw(id:String,a:List<LayoutSymbol>,e:String) { cs+=C(id,a,e) }
   fun err(id:String,a:List<LayoutSymbol>,pos:Int) { cs+=C(id,a,"error@"+pos,pos) }
   add("N01-tres-alturas-con-pie",num("789",listOf(25,11,18)))
   add("N02-alturas-y-otra-linea",lines(num("456",listOf(22,9,17)),num("123",listOf(12,24,19))))
   add("N03-fraccion-alturas",frac(num("27",listOf(12,23)),num("43",listOf(26,15))))
   add("N04-fraccion-cifras-muchas",frac(num("1234",listOf(11,22,17,26)),num("5678",listOf(24,13,20,10))))
   add("N05-negativo-fraccion",frac(G(listOf(s("Menos",0,-1,7,2))+num("23",listOf(14,21)).move(11f).a,"-23"),num("67",listOf(18,25))))
   raw("N06-12-tres-cajas-superpuestas",listOf(s("1",0,0,2,20),s("2",1,0),s("3",13,0)),"123")
   val exp=seq(seq(at("alfa",16f),at("beta",24f),"+"),at("gamma",12f),"-")
   add("E01-largo-alturas",power(at("x",28f),exp))
   add("E02-negativo-largo",power(at("x",26f),G(listOf(s("Menos",0,-1,7,2),s("2",10,-7,7,17),s("+",21,-12,8,22),s("3",32,-5,7,15)),"-2+3")))
   add("E03-negativos-cociente",power(at("x"),frac(G(listOf(s("Menos",0,-1,6,2),s("2",10,-10)),"-2"),at("3",24f))))
   add("E04-potencia-producto-griegas",power(at("x"),product(product(at("alfa",23f),at("beta",16f)),at("gamma",21f))))
   add("E05-potencia-de-potencia",power(at("x",30f),power(at("y",24f),at("3",12f))))
   add("E06-largo-tras-anidado",power(at("x",30f),seq(power(at("y",24f),at("3",12f)),at("2",22f))))
   add("E07-dos-potencias-en-exponente",power(at("x",30f),seq(power(at("y",24f),at("2",12f)),power(at("z",22f),at("3",14f)))))
   add("E08-exponente-parentesis",power(at("x",24f),group(exp)))
   add("E09-base-parentesis-alto",power(group(frac(at("1"),at("2"))),at("3",18f)))
   add("E10-potencia-seguida-cifras",product(power(at("2",24f),num("34",listOf(20,11))),num("56",listOf(14,23))))
   add("E11-potencia-griega-seguida-numero",product(power(at("x",24f),at("alfa",14f)),at("7",18f)))
   add("E12-dos-potencias-griegas",product(power(at("alfa",24f),at("beta",14f)),power(at("gamma",20f),at("x",16f))))
   add("F01-denominador-potencia-alturas",frac(num("42",listOf(16,25)),power(at("alfa",28f),exp)))
   add("F02-numerador-y-denominador-potencias",frac(power(at("2",26f),at("3",16f)),power(at("x",24f),at("beta",18f))))
   add("F03-denominador-exponente-fraccion",frac(at("7",24f),power(at("x",30f),frac(at("1",15f),at("2",23f)))))
   add("F04-fraccion-potencia-raiz",power(frac(num("12",listOf(15,23)),num("34",listOf(22,14))),root(at("9",22f))))
   add("F05-fraccion-entre-potencias",frac(power(at("x",32f),frac(at("1"),at("2"))),power(at("y",26f),root(at("4")))))
   add("F06-denominador-parentesis",frac(at("1",24f),group(seq(power(at("x",24f),at("2",15f)),at("3",18f)))))
   add("F07-numerador-con-negativo",frac(seq(at("x",24f),at("2",12f),"-"),at("3",20f)))
   add("F08-denominador-negativo",frac(at("7",24f),G(listOf(s("Menos",0,-1,7,2),s("3",11,-8,10,18)),"-3")))
   add("F09-fraccion-a-la-derecha-de-raiz",seq(root(at("4",24f)),frac(at("1",18f),power(at("x",24f),at("2",15f)))))
   add("F10-tres-fracciones-alturas",seq(seq(frac(at("1",11f),at("2",25f)),frac(at("3",26f),at("4",13f))),frac(at("5",18f),at("6",23f))))
   add("F11-anidado-denominador-negativo",frac(at("1",18f),frac(G(listOf(s("Menos",0,-1,7,2),s("2",11,-10)),"-2"),at("3",24f))))
   add("F12-barras-anidadas-tres",frac(frac(at("1",14f),frac(at("2",24f),at("3",18f))),frac(at("4",12f),at("5",26f))))
   add("R01-raiz-de-potencia",root(power(at("x",26f),at("2",16f))))
   add("R02-raiz-de-potencia-larga",root(power(at("x",26f),exp)))
   add("R03-raiz-de-fraccion-con-potencia",root(frac(at("1",16f),power(at("x",24f),at("2",16f)))))
   add("R04-raiz-con-grupo-y-exponente",root(power(group(frac(at("1",22f),at("2",16f))),at("3",18f))))
   add("R05-potencia-de-raices",power(root(root(at("4",22f))),root(at("9",18f))))
   add("R06-raices-laterales-fraccion",frac(seq(root(at("4",24f)),root(at("9",16f))),root(at("16",20f,20f))))
   add("R07-raiz-de-fraccion-de-raices",root(frac(root(at("4",18f)),root(at("9",26f)))))
   add("G01-parentesis-potencia-fraccion",group(power(frac(at("1",12f),at("2",24f)),at("3",15f))))
   add("G02-parentesis-raiz-potencia-fraccion",group(root(frac(at("1",26f),power(at("x",24f),at("2",15f))))))
   add("G03-raiz-de-parentesis-potencia",root(group(power(at("x",26f),seq(at("2",24f),at("3",14f))))))
   add("G04-parentesis-ya-grupos",group(seq(group(frac(at("1",16f),at("2",24f))),group(root(at("4",22f))))))
   add("G05-muchos-grupos",group(group(group(root(frac(at("1",18f),at("2",24f)))))))
   add("G06-grupo-con-exponente-griego-y-numero",product(power(group(root(frac(at("1"),at("2")))),at("alfa",16f)),at("3",24f)))
   val mix=power(group(root(frac(power(at("x",26f),at("2",12f)),seq(at("y",24f),at("3",15f))))),seq(at("alfa",18f),at("beta",23f)))
   add("M01-mezcla-en-dos-lineas",lines(mix,frac(num("23",listOf(12,24)),power(at("z",28f),root(at("4",22f))))))
   add("M02-mezcla-en-tres-lineas",lines(lines(mix,root(group(frac(at("1",26f),at("2",14f))))),power(at("gamma",26f),frac(at("2",18f),at("3",22f)))))
   add("M03-grupo-raiz-fraccion-potencia-y-cifras",lines(product(mix,num("45",listOf(17,25))),num("67",listOf(22,14))))
   val basic=listOf(at("x",24f),frac(at("1",16f),at("2",22f)),root(at("4",24f)),group(seq(at("x",26f),at("1",15f))))
   for((i,b) in basic.withIndex()) for((j,e) in basic.withIndex()) {
    add("C"+i+j+"-producto-de-potencias",product(power(b,e),at("beta",23f)))
    add("D"+i+j+"-fraccion-de-potencia",frac(at("7",18f),power(b,e)))
   }
   raw("L01-numero-tras-potencia-ajustado",listOf(s("x",0,22,10,28),s("alfa",12,8,7,16),s("7",22,26,10,24)),"x^α*7")
   raw("L02-exponente-variables-alturas",listOf(s("x",0,20,10,26),s("alfa",12,0,7,15),s("beta",23,-3,8,18),s("gamma",35,1,7,14),s("z",49,22,10,24)),"x^(α*β*γ)*z")
   raw("L03-linea-mixta-con-igual",listOf(s("x",0,12,10,24),s("2",12,0,7,12),s("Igual",26,16,10,16),s("1",42,16,8,20),s("2",52,12,8,24)),"x^2=12")
   raw("L04-negativo-exp-fino-y-resta",listOf(s("x",0,17,10,25),s("Menos",12,6,6,2),s("2",20,-1,7,17),s("Menos",31,28,10,2),s("3",45,20,9,22)),"x^(-2)-3")
   err("Q01-posicion-grupo-raiz-y-fraccion",listOf(s("(",0,-10,3,90),s("Raíz",8,-3,68,66),s("garabato",30,0,10,22),s("Barra",22,28,36,2),s("2",30,38,10,18),s(")",80,-10,3,90)),4)
   err("Q02-posicion-letra-exp-y-error",listOf(s("x",0,18,10,26),s("alfa",12,0,7,16),s("gamma",28,20,10,24),s("Más",42,22,10,20),s("garabato",56,18,10,26)),6)
   err("Q03-posicion-segunda-linea-estructuras",listOf(s("x",0,0),s("Más",12,0),s("1",24,0),s("Raíz",0,100,50,52),s("1",20,100,10,14),s("Barra",14,120,28,2),s("garabato",20,131,10,18)),7)
   raw("Z01-base-de-ancho-cero",listOf(s("x",0,16,0,24),s("2",3,1,6,12)),"x^2")
   raw("Z02-exponente-ancho-cero",listOf(s("x",0,16,10,24),s("2",13,1,0,12)),"x^2")
   raw("Z03-cifras-caja-altura-cero",listOf(s("1",0,10,2,0),s("2",6,-10,10,20)),"12")
   err("Z04-raiz-ancho-cero-con-numero",listOf(s("Raíz",0,0,0,20),s("2",12,0)),0)
   raw("Z05-numero-despues-de-dos-potencias",listOf(s("2",0,20,10,28),s("3",12,2,7,15),s("4",26,22,10,26),s("5",38,3,7,14),s("6",52,20,10,28)),"2^3*4^5*6")

   val tightGroup=listOf(s("(",0,-12,4,24),s("x",8,-10),s("+",22,-10,8,20),s("1",34,-10),s(")",48,-12,4,24))
   raw("T01-parentesis-roba-linea-inferior",tightGroup+listOf(s("2",8,30),s("3",20,30)),"(x+1)\n23")
   raw("T02-parentesis-roba-linea-superior",listOf(s("2",8,-50),s("3",20,-50))+tightGroup,"23\n(x+1)")
   raw("T03-grupo-denominador-roba-numerador",listOf(s("7",25,-24,10,18),s("Barra",0,0,64,2),s("(",6,6,4,28),s("x",14,10),s("+",26,10),s("1",38,10),s(")",50,6,4,28)),"(7)/((x+1))")
   raw("T04-control-parentesis-mas-bajo",tightGroup.map { if(it.label in listOf("(",")")) it.copy(minY=-10f,maxY=10f) else it }+listOf(s("2",8,30),s("3",20,30)),"(x+1)\n23")
   var longSum=at("2")
   repeat(14) { longSum=seq(longSum,at("2")) }
   add("T05-raiz-suma-larga",root(longSum))
   add("T06-raiz-numero-largo",root(num("12345678901234567890123456789")))
   add("T07-raiz-con-grupo-largo",root(group(longSum)))
   add("T08-raices-anidadas-largas",root(root(longSum)))
   var shortSum=at("2")
   repeat(8) { shortSum=seq(shortSum,at("2")) }
   add("T09-control-raiz-suma-corta",root(shortSum))
   add("T10-control-raiz-grupo-corto",root(group(shortSum)))
   err("T11-raiz-con-error-primero-largo",root(longSum).a.mapIndexed { idx,box -> if(idx==1) box.copy(label="garabato") else box },2)
   for(h in listOf(22,24,26,28,30)) {
    raw("U"+h+"-exponente-alto-mismo-pie",listOf(s("x",0,20,10,h),s("alfa",12,0,7,15),s("beta",23,-3,8,18),s("gamma",35,1,7,14),s("z",49,20,10,h)),"x^(α*β*γ)*z")
   }
   raw("T12-dos-potencias-pie-identico",listOf(s("2",0,20,10,28),s("3",12,2,7,15),s("4",26,20,10,28),s("5",38,2,7,15),s("6",52,20,10,28)),"2^3*4^5*6")
   raw("T13-decimal-denominador-alturas",listOf(s("7",15,0,8,24),s("Barra",0,30,44,2),s("1",6,50,5,12),s("Punto",14,61,2,2),s("2",20,38,10,24)),"(7)/(1.2)")
   raw("T14-decimal-multi-digito",listOf(s("1",0,8,5,12),s("2",8,0,10,20),s("Punto",20,18,2,2),s("3",26,4,8,16),s("4",37,-4,10,24)),"12.34")
   err("T15-error-en-exponente-largo",listOf(s("x",0,18,10,26),s("1",12,0,6,12),s("+",22,-1,8,13),s("2",34,2,6,10),s("+",44,-1,8,13),s("garabato",56,1,6,11)),7)


   val thinMinus=listOf(s("1",0,0,8,20),s("Menos",9,8,4,2),s("2",15,0,8,20))
   raw("V01-resta-menos-descentrado",thinMinus,"1-2")
   raw("V02-control-resta-menos-centrado",thinMinus.map { if(it.label=="Menos") it.copy(minY=9f,maxY=11f) else it },"1-2")
   add("V03-resta-dentro-de-raiz",root(G(thinMinus,"1-2")))
   add("V04-resta-en-numerador",frac(G(thinMinus,"1-2"),at("3",22f)))
   raw("V05-resta-en-exponente",listOf(s("x",0,12,10,24),s("2",12,0,6,10),s("Menos",19,3,3,2),s("3",23,0,6,10)),"x^(2-3)")
   raw("V06-division-fina-entre-cifras",thinMinus.map { if(it.label=="Menos") it.copy(label="Dividido") else it },"1/2")
   raw("V07-barra-en-linea-entre-cifras",thinMinus.map { if(it.label=="Menos") it.copy(label="Barra",minY=9f,maxY=11f) else it },"1/2")
   raw("V08-resta-con-otra-linea",thinMinus+listOf(s("3",0,65,9,24),s("4",12,69,8,20)),"1-2\n34")
   raw("V09-resta-con-letras",thinMinus.map { if(it.label=="1") it.copy(label="x") else if(it.label=="2")it.copy(label="y") else it },"x-y")

   check(cs.size == 119 && cs.map { it.id }.distinct().size == 119)
   val scales=listOf(1e-6f to 1e-6f,.003f to .003f,1f to 1f,23f to 23f,1e6f to 1e6f,.01f to 100f,100f to .01f)
   return cs.flatMap { c->scales.flatMap { (sx,sy)->(0..4).map { arrayOf<Any>(c,sx,sy,it) } } }
  }
 }
}
