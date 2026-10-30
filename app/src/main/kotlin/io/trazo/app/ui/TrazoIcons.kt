package io.trazo.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Íconos propios de la interfaz (barra de herramientas, menús). Son vectores de trazo redondeado sobre
 * una grilla de 24; el color lo pone el `Icon` que los muestra. Solo decoran la interfaz: nada de esto
 * llega a la página.
 */
object TrazoIcons {
    val Undo by lazy { icon("Undo", stroke("M9 14 L4 9 L9 4"), stroke("M4 9 H14.5 A5.5 5.5 0 0 1 14.5 20 H11")) }
    val Redo by lazy { icon("Redo", stroke("M15 14 L20 9 L15 4"), stroke("M20 9 H9.5 A5.5 5.5 0 0 0 9.5 20 H13")) }
    val Pen by lazy {
        icon(
            "Pen",
            stroke("M4.2 19.8 L5.3 15.4 L15.6 5.1 A2.1 2.1 0 0 1 18.6 5.1 L18.9 5.4 A2.1 2.1 0 0 1 18.9 8.4 L8.6 18.7 Z"),
            stroke("M13.9 6.8 L17.2 10.1"),
        )
    }
    val Highlighter by lazy {
        icon(
            "Highlighter",
            stroke("M15 3.2 L20.8 9 L13 16.8 L7.2 11 Z"),
            stroke("M7.2 11 L5.4 15.2 L8.8 18.6 L13 16.8"),
            stroke("M3.5 21 H11"),
        )
    }
    val Eraser by lazy {
        icon(
            "Eraser",
            stroke("M14.4 4 L19.5 9.1 L9.6 19 L4.5 13.9 Z"),
            fill("M4.5 13.9 L8 10.4 L13.1 15.5 L9.6 19 Z"),
            stroke("M13.5 20.5 H20"),
        )
    }
    val Lasso by lazy {
        icon(
            "Lasso",
            stroke("M12 3.8 C16.9 3.8 20.4 6.3 20.4 9.3 C20.4 12.3 16.9 14.6 12 14.6 C7.1 14.6 3.6 12.3 3.6 9.3 C3.6 6.3 7.1 3.8 12 3.8 Z"),
            stroke("M7.6 13.6 C6.2 15.2 6.7 17.3 8.6 17.7 C10.3 18.1 10.4 20 9 21"),
        )
    }
    val Shapes by lazy {
        icon(
            "Shapes",
            stroke("M12 2.9 L16.6 10.6 H7.4 Z"),
            stroke("M3.6 14 H10.1 V20.5 H3.6 Z"),
            stroke("M17.2 13.9 A3.3 3.3 0 1 1 17.2 20.5 A3.3 3.3 0 1 1 17.2 13.9 Z"),
        )
    }
    val ShapeLine by lazy { icon("ShapeLine", stroke("M5 19 L19 5")) }
    val ShapeArrow by lazy { icon("ShapeArrow", stroke("M5 19 L18.5 5.5"), stroke("M10 5 H19 V14")) }
    val ShapeRect by lazy { icon("ShapeRect", stroke("M4 6 H20 V18 H4 Z")) }
    val ShapeEllipse by lazy { icon("ShapeEllipse", stroke("M12 5 A8.5 7 0 1 1 12 19 A8.5 7 0 1 1 12 5 Z")) }
    val ShapeTriangle by lazy { icon("ShapeTriangle", stroke("M12 4.5 L20.5 19 H3.5 Z")) }
    val ShapeAxes by lazy { icon("ShapeAxes", stroke("M5 3.5 V19 H20.5"), stroke("M3 5.5 L5 3.5 L7 5.5"), stroke("M18.5 17 L20.5 19 L18.5 21")) }
    val Pan by lazy {
        icon(
            "Pan",
            stroke("M12 3 V21 M3 12 H21"),
            stroke("M9 6 L12 3 L15 6 M9 18 L12 21 L15 18 M6 9 L3 12 L6 15 M18 9 L21 12 L18 15"),
        )
    }
    val ZoomIn by lazy {
        icon(
            "ZoomIn",
            stroke("M10.5 4 A6.5 6.5 0 1 1 10.5 17 A6.5 6.5 0 1 1 10.5 4 Z"),
            stroke("M15.4 15.4 L20 20"),
            stroke("M7.8 10.5 H13.2 M10.5 7.8 V13.2"),
        )
    }
    val ZoomOut by lazy {
        icon(
            "ZoomOut",
            stroke("M10.5 4 A6.5 6.5 0 1 1 10.5 17 A6.5 6.5 0 1 1 10.5 4 Z"),
            stroke("M15.4 15.4 L20 20"),
            stroke("M7.8 10.5 H13.2"),
        )
    }
    /** Dos formas de distinto ancho apoyadas sobre la misma línea. */
    val Align by lazy { icon("Align", stroke("M3 19.5 H21"), stroke("M5 16 V7 H10 V16 Z"), stroke("M14 16 V10 H19 V16 Z")) }
    val Fit by lazy { icon("Fit", stroke("M4 9 V4 H9 M15 4 H20 V9 M20 15 V20 H15 M9 20 H4 V15")) }
    val Patterns by lazy {
        icon(
            "Patterns",
            stroke("M6 3.5 H18 A2.5 2.5 0 0 1 20.5 6 V18 A2.5 2.5 0 0 1 18 20.5 H6 A2.5 2.5 0 0 1 3.5 18 V6 A2.5 2.5 0 0 1 6 3.5 Z"),
            stroke("M14.6 7 C13.4 6 11.8 6.6 11.8 8.6 V15.4 C11.8 17.4 10.2 18 9 17"),
        )
    }
    val Wand by lazy {
        icon(
            "Wand",
            stroke("M4 20 L14.5 9.5"),
            stroke("M13 8 L16 11"),
            stroke("M17.5 2.8 V6.8 M15.5 4.8 H19.5"),
            stroke("M20 10.5 V13.5 M18.5 12 H21.5"),
            stroke("M9.5 3 V5.6 M8.2 4.3 H10.8"),
        )
    }
    val Gesture by lazy {
        icon("Gesture", stroke("M3 16.5 C5 10 8 8.2 9.6 10.6 C11.2 13 8.6 17 10.6 18.1 C13 19.4 15 11.2 17.6 11.2 C19.6 11.2 19.6 15 21 15"))
    }
    val Pages by lazy { icon("Pages", stroke("M8.5 3.5 H16.3 L19.8 7 V17.3 H8.5 Z"), stroke("M5.2 7 V20.6 H15.8")) }
    val Paper by lazy {
        icon("Paper", stroke("M6 3.5 H18 V20.5 H6 Z"), stroke("M9 8.5 H15 M9 12 H15 M9 15.5 H13"))
    }
    val Notebook by lazy {
        icon("Notebook", stroke("M7 3.5 H18.5 V20.5 H7 Z"), stroke("M10 3.5 V20.5"), stroke("M4.5 7.5 H7.5 M4.5 12 H7.5 M4.5 16.5 H7.5"))
    }
    val Sort by lazy { icon("Sort", stroke("M4 7 H20 M7 12 H17 M10 17 H14")) }
    val Import by lazy { icon("Import", stroke("M12 3.5 V14.5 M7.5 10 L12 14.5 L16.5 10"), stroke("M4 15.5 V20 H20 V15.5")) }
    val Export by lazy { icon("Export", stroke("M12 14.5 V3.5 M7.5 8 L12 3.5 L16.5 8"), stroke("M4 15.5 V20 H20 V15.5")) }
    val Rotate by lazy { icon("Rotate", stroke("M4.5 12 A7.5 7.5 0 1 0 6.7 6.7"), stroke("M6.7 2.9 V6.7 H10.5")) }
    val Calculate by lazy { icon("Calculate", stroke("M5.5 3.5 H18.5 V20.5 H5.5 Z"), stroke("M8.5 7.5 H15.5"), stroke("M8.5 12.5 H15.5 M8.5 16.5 H15.5")) }
    val Duplicate by lazy { icon("Duplicate", stroke("M9 9 H19.5 V19.5 H9 Z"), stroke("M15 9 V4.5 H4.5 V15 H9")) }
    val Palette by lazy {
        icon(
            "Palette",
            stroke("M12 3.5 A8.5 8.5 0 1 0 12 20.5 C13.2 20.5 13.8 19.6 13.3 18.6 C12.7 17.4 13.4 16 14.8 16 H16.5 A4 4 0 0 0 20.5 12 C20.5 7.3 16.7 3.5 12 3.5 Z"),
            fill("M7.5 10.4 A1.3 1.3 0 1 1 7.5 13 A1.3 1.3 0 1 1 7.5 10.4 Z"),
            fill("M10 6.2 A1.3 1.3 0 1 1 10 8.8 A1.3 1.3 0 1 1 10 6.2 Z"),
            fill("M14.8 6.6 A1.3 1.3 0 1 1 14.8 9.2 A1.3 1.3 0 1 1 14.8 6.6 Z"),
        )
    }
    val SelectAll by lazy {
        icon(
            "SelectAll",
            stroke("M4 7 V4 H7 M10.5 4 H13.5 M17 4 H20 V7 M20 10.5 V13.5 M20 17 V20 H17 M13.5 20 H10.5 M7 20 H4 V17 M4 13.5 V10.5"),
        )
    }
    val Grow by lazy { icon("Grow", stroke("M14 4 H20 V10 M20 4 L13.5 10.5"), stroke("M10 20 H4 V14 M4 20 L10.5 13.5")) }
    val Copy by lazy { icon("Copy", stroke("M8.5 8 H18.5 V20.5 H8.5 Z"), stroke("M5.5 16.5 V3.5 H15.5")) }
    val Paste by lazy { icon("Paste", stroke("M8.5 5 H6 V20.5 H18 V5 H15.5"), stroke("M9 3.5 H15 V7 H9 Z"), stroke("M9.5 12 H14.5 M9.5 15.5 H13")) }
    /** Agarradera para arrastrar una barra (seis puntos). */
    val Drag by lazy { icon("Drag", stroke("M9 6 L9.01 6 M15 6 L15.01 6 M9 12 L9.01 12 M15 12 L15.01 12 M9 18 L9.01 18 M15 18 L15.01 18")) }
    val Shrink by lazy { icon("Shrink", stroke("M20 10 H14 V4 M14 10 L20.5 3.5"), stroke("M4 14 H10 V20 M10 14 L3.5 20.5")) }
}

private class IconPart(val d: String, val filled: Boolean)

private fun stroke(d: String) = IconPart(d, filled = false)

private fun fill(d: String) = IconPart(d, filled = true)

private fun icon(name: String, vararg parts: IconPart): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (p in parts) {
            if (p.filled) {
                addPath(addPathNodes(p.d), fill = SolidColor(Color.Black))
            } else {
                addPath(
                    addPathNodes(p.d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
    }.build()
