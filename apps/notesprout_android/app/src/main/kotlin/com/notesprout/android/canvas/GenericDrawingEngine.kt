package com.notesprout.android.canvas

import android.view.MotionEvent
import android.view.View

class GenericDrawingEngine : DrawingEngine {

    private var attachedView: View? = null
    private var callback: StrokeCallback? = null
    private val currentPoints = mutableListOf<StrokePoint>()
    private var currentTool = ToolType.GEL_PEN

    private val touchListener = View.OnTouchListener { _, event ->
        handleMotionEvent(event)
        true
    }

    override fun attach(view: View, callback: StrokeCallback) {
        detach()
        attachedView = view
        this.callback = callback
        view.setOnTouchListener(touchListener)
    }

    override fun detach() {
        attachedView?.setOnTouchListener(null)
        attachedView = null
        callback = null
        currentPoints.clear()
    }

    override fun setToolType(tool: ToolType) {
        currentTool = tool
    }

    override fun refreshCanvas() {
        attachedView?.postInvalidate()
    }

    private fun handleMotionEvent(event: MotionEvent) {
        val cb = callback ?: return
        val isStylusInput = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPoints.clear()
                val pressure = if (isStylusInput) event.pressure else 0.5f
                val point = StrokePoint(
                    x = event.x,
                    y = event.y,
                    pressure = pressure,
                    tilt = if (isStylusInput) event.getAxisValue(MotionEvent.AXIS_TILT) else 0f
                )
                currentPoints.add(point)
                cb.onStrokeBegin(event.x, event.y, pressure)
            }

            MotionEvent.ACTION_MOVE -> {
                val pressure = if (isStylusInput) event.pressure else 0.5f
                val point = StrokePoint(
                    x = event.x,
                    y = event.y,
                    pressure = pressure,
                    tilt = if (isStylusInput) event.getAxisValue(MotionEvent.AXIS_TILT) else 0f
                )
                currentPoints.add(point)
                cb.onStrokeMove(event.x, event.y, pressure)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val pressure = if (isStylusInput) event.pressure else 0.5f
                currentPoints.add(
                    StrokePoint(
                        x = event.x,
                        y = event.y,
                        pressure = pressure,
                        tilt = if (isStylusInput) event.getAxisValue(MotionEvent.AXIS_TILT) else 0f
                    )
                )
                cb.onStrokeEnd(currentPoints.toList())
                currentPoints.clear()
            }
        }
    }
}
