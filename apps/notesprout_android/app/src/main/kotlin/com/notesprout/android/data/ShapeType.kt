package com.notesprout.android.data

enum class ShapeType {
    RECTANGLE,   // square = RECTANGLE with aspectLocked + w==h
    ELLIPSE,     // circle  = ELLIPSE  with aspectLocked + w==h
    TRIANGLE,    // isosceles, apex up
    DIAMOND,     // rhombus (vertices at box edge midpoints)
    TRAPEZOID,   // isosceles, narrow top (0.6×w), full-width bottom
    PENTAGON,    // regular, point up
    HEXAGON,     // regular, flat top
    STAR,        // n-point (pointCount), outer/inner radius ratio = STAR_INNER_RATIO
    ARCH,        // semicircle top + straight sides + flat closed base
    LINE,        // open segment
    ARROW,       // open segment + arrowhead at the end
}
