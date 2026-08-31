package com.symmetricalpalmtree.notesproutsn.ext.document

import android.view.KeyEvent
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownFormatter

/**
 * Every `Ctrl` chord the editor answers — the format bar's fourteen plus og's chord-only four
 * (`Ctrl+P` mode toggle, `Ctrl+0` paragraph, `Ctrl+4`–`6` the headings the bar has no room for), the
 * page flips, and find / reflow.
 *
 * `Ctrl+Z/Y/A/C/V/X` are deliberately **absent**: they must fall through to the `EditText`, which
 * already implements undo, redo, select-all and the clipboard. A chord claimed here is a chord the
 * writer loses.
 *
 * On Ratta the IME stays connected (hardware keys arrive only through it), so an input method sits
 * upstream in the key path and may claim a chord before this sees it — which is a reason to keep the
 * set small, not a reason to hide the keyboard.
 *
 * It is a collaborator rather than a method on the screen for the module's size rule, and the shape
 * is the one the rest of this package uses: the table lives here, the acts stay with whoever owns
 * them.
 */
internal class EditorShortcuts(
    private val format: FormatActions,
    private val isPreviewing: () -> Boolean,
    private val setPreviewing: (Boolean) -> Unit,
    /** One page flip — the guards and the edge toasts are the caller's ([FlipRules]). */
    private val flipPage: (Int) -> Unit,
    private val closeOverflow: () -> Unit,
    private val openFind: () -> Unit,
    private val reflow: () -> Unit,
) {

    /** Answer one key event, or leave it to the system. */
    fun handle(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || !event.isCtrlPressed) return false
        val shift = event.isShiftPressed
        // Preview is read-only; the mode toggle stays live there (og's rule) and so do the page
        // flips, which are as much a reading act as a writing one.
        if (isPreviewing()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_P -> if (!shift) { setPreviewing(false); return true }
                KeyEvent.KEYCODE_PAGE_UP -> { flipPage(DocumentContract.PAGE_PREV); return true }
                KeyEvent.KEYCODE_PAGE_DOWN -> { flipPage(DocumentContract.PAGE_NEXT); return true }
            }
            return false
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_P -> if (!shift) { setPreviewing(true); return true }
            // Claimed even in the notebook scope, where [FlipRules] answers BLOCKED and nothing
            // moves: a swallowed chord is silent, and letting it through would hand a page-turn
            // key to the IME instead.
            KeyEvent.KEYCODE_PAGE_UP -> { closeOverflow(); flipPage(DocumentContract.PAGE_PREV); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { closeOverflow(); flipPage(DocumentContract.PAGE_NEXT); return true }
            // Paragraph and H4–H6 are chord-only, as in og: the bar stops at H3, the grammar does not.
            KeyEvent.KEYCODE_0 -> if (!shift) { closeOverflow(); format.block(MarkdownFormatter.Block.PARAGRAPH); return true }
            KeyEvent.KEYCODE_4 -> if (!shift) { closeOverflow(); format.block(MarkdownFormatter.Block.HEADING, 4); return true }
            KeyEvent.KEYCODE_5 -> if (!shift) { closeOverflow(); format.block(MarkdownFormatter.Block.HEADING, 5); return true }
            KeyEvent.KEYCODE_6 -> if (!shift) { closeOverflow(); format.block(MarkdownFormatter.Block.HEADING, 6); return true }
            KeyEvent.KEYCODE_1 -> if (!shift) return tool(FormatTool.H1)
            KeyEvent.KEYCODE_2 -> if (!shift) return tool(FormatTool.H2)
            KeyEvent.KEYCODE_3 -> if (!shift) return tool(FormatTool.H3)
            KeyEvent.KEYCODE_B -> if (!shift) return tool(FormatTool.BOLD)
            KeyEvent.KEYCODE_I -> if (!shift) return tool(FormatTool.ITALIC)
            KeyEvent.KEYCODE_X -> if (shift) return tool(FormatTool.STRIKETHROUGH)
            KeyEvent.KEYCODE_E -> if (!shift) return tool(FormatTool.CODE)
            KeyEvent.KEYCODE_Q -> if (shift) return tool(FormatTool.QUOTE)
            KeyEvent.KEYCODE_8 -> if (shift) return tool(FormatTool.BULLET)
            KeyEvent.KEYCODE_7 -> if (shift) return tool(FormatTool.ORDERED)
            KeyEvent.KEYCODE_9 -> if (shift) return tool(FormatTool.TASK)
            KeyEvent.KEYCODE_K -> return tool(if (shift) FormatTool.IMAGE else FormatTool.LINK)
            KeyEvent.KEYCODE_MINUS -> if (shift) return tool(FormatTool.RULE)
            // Find, and its shifted sibling: the same reflow the source strip's button runs, from
            // the keyboard.
            KeyEvent.KEYCODE_F -> {
                closeOverflow()
                if (shift) reflow() else openFind()
                return true
            }
        }
        return false
    }

    /** Run a tool from a chord and claim the key. */
    private fun tool(tool: FormatTool): Boolean {
        closeOverflow()
        format.run(tool)
        return true
    }
}
