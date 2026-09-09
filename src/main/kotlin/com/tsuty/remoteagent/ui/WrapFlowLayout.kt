package com.tsuty.remoteagent.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.SwingUtilities

/**
 * [FlowLayout] that reports the size the wrapped rows really need.
 *
 * Plain [FlowLayout] wraps its rows when the container is narrow but still reports the height of a
 * single row. Inside a vertically stacking parent (the transcript) that height is the one handed
 * out, so every row but the first is clipped away — that is how the permission buttons went
 * missing in a narrow tool window.
 */
class WrapFlowLayout(align: Int, hgap: Int, vgap: Int) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: Container): Dimension = layoutSize(target, false)

    override fun layoutContainer(target: Container) {
        super.layoutContainer(target)
        // The width is only known once the parent has laid us out, so the first measurement may
        // have been taken for a single row. Ask for one more pass when the rows do not fit.
        if (target.height < preferredLayoutSize(target).height) {
            SwingUtilities.invokeLater { target.revalidate() }
        }
    }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val insets = target.insets
            val maxRowWidth = (availableWidth(target) - insets.left - insets.right).coerceAtLeast(1)
            val size = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0
            var rows = 0
            for (i in 0 until target.componentCount) {
                val component = target.getComponent(i)
                if (!component.isVisible) continue
                val componentSize = if (preferred) component.preferredSize else component.minimumSize
                if (rowWidth > 0 && rowWidth + hgap + componentSize.width > maxRowWidth) {
                    size.width = maxOf(size.width, rowWidth)
                    size.height += rowHeight
                    rows++
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth > 0) rowWidth += hgap
                rowWidth += componentSize.width
                rowHeight = maxOf(rowHeight, componentSize.height)
            }
            if (rowWidth > 0 || rows == 0) {
                size.width = maxOf(size.width, rowWidth)
                size.height += rowHeight
                rows++
            }
            size.width += insets.left + insets.right
            size.height += insets.top + insets.bottom + vgap * (rows + 1)
            return size
        }
    }

    /** Width the rows may use: the first ancestor that already knows its own width wins. */
    private fun availableWidth(target: Container): Int {
        var component: Component? = target
        while (component != null) {
            if (component.width > 0) return component.width
            component = component.parent
        }
        return Int.MAX_VALUE
    }
}
