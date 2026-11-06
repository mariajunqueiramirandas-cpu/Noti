package app.noti.study

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MathTextTest {
    private val style = MathText.Style(32f, Color.BLACK)

    @Test fun parsesScriptsFractionsAndCommands() {
        val nodes = Parser("x_1^2 + \\frac{a}{b} \\leq \\sqrt[3]{y}").parseAll()
        assertTrue(nodes[0] is Scripts)
        assertEquals(Cls.Bin, nodes[1].cls)
        assertTrue(nodes[2] is Frac)
        assertEquals(Cls.Rel, nodes[3].cls)
        assertTrue((nodes[4] as Root).index != null)
    }

    @Test fun unbracedArgumentsAreOneToken() {
        val frac = Parser("\\frac12").parseAll().single() as Frac
        assertEquals("1", (frac.num as Sym).text)
        assertEquals("2", (frac.den as Sym).text)
        val power = Parser("x^23").parseAll()
        assertEquals("2", ((power[0] as Scripts).sup as Sym).text)
        assertEquals("3", (power[1] as Sym).text)
    }

    @Test fun badInputDegradesInsteadOfThrowing() {
        listOf("\\frac{a", "x^", "\\left( x", "}}{{", "\\begin{pmatrix} 1 & 2", "\\unknowncommand{x}", "$", "^_^_").forEach {
            MathText.layout("Try \$$it\$ and $$$it$$", 400f, style)
        }
    }

    @Test fun unknownCommandsAreShownByName() {
        val n = Parser("\\frobnicate").parseAll().single()
        assertEquals("\\frobnicate", (n as Words).text)
    }

    @Test fun aFractionIsTallerThanItsLineAndStacksAroundTheAxis() {
        val plain = Math.layout("x", 32f, Color.BLACK, display = true)
        val frac = Math.layout("\\frac{a+b}{c}", 32f, Color.BLACK, display = true)
        assertTrue(frac.asc > plain.asc)
        assertTrue(frac.desc > plain.desc)
    }

    @Test fun proseWrapsAndDisplayMathGetsItsOwnLine() {
        val narrow = MathText.layout("The speed of a falling stone grows by about ten metres per second every second.", 220f, style)
        assertTrue(narrow.lineCount >= 3)
        assertTrue(narrow.width <= 220f)
        val withDisplay = MathText.layout("Energy: $\$E = mc^2$$ is famous.", 600f, style)
        assertEquals(3, withDisplay.lineCount)
    }

    @Test fun wideFormulasNeverOverflowTheWidth() {
        val block = MathText.layout("$$\\int_0^\\infty e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2} \\quad \\text{and} \\quad \\sum_{n=1}^{\\infty} \\frac{1}{n^2} = \\frac{\\pi^2}{6}$$", 260f, style)
        assertTrue(block.width <= 260.5f)
    }

    /** Renders a sample sheet to app/build/reports/study-ui/math.png for a visual check. */
    @Test fun rendersASampleSheet() {
        val samples = listOf(
            "**Kinematics.** A ball is thrown at \$v_0 = 12\\,\\mathrm{m\\,s^{-1}}$ at $\\theta = 30^\\circ$. Find the range \$R$.",
            "$\$R = \\frac{v_0^2 \\sin 2\\theta}{g}$$",
            "Inline: $\\vec{F} = m\\vec{a}$, $\\hat{n}$, $\\bar{x}$, $\\dot{x}$, $\\ddot{\\theta}$, $\\tilde{y}$, \$x' $, \$f''(x)$.",
            "$$\\int_0^{\\pi} \\sin x\\,dx = 2 \\qquad \\sum_{k=1}^{n} k = \\frac{n(n+1)}{2} \\qquad \\lim_{x\\to 0}\\frac{\\sin x}{x} = 1$$",
            "$$\\left( \\frac{\\partial^2}{\\partial x^2} - \\frac{1}{c^2}\\frac{\\partial^2}{\\partial t^2} \\right) \\psi = 0$$",
            "$$\\sqrt{x^2+y^2} \\neq \\sqrt[3]{27} \\approx 3 \\pm 0.1 \\quad \\boxed{\\Delta E = h\\nu}$$",
            "$\$A = \\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}, \\quad \\det A = -2, \\quad |x| = \\begin{cases} x & x \\geq 0 \\\\ -x & x < 0 \\end{cases}$$",
            "Greek: $\\alpha\\beta\\gamma\\delta\\epsilon\\lambda\\mu\\pi\\rho\\sigma\\omega\\,\\Omega\\Sigma\\Delta$ and $\\hbar\\nabla\\infty\\ell$; ions $\\mathrm{H_2O}$, $\\mathbf{v}\\cdot\\mathbf{w}$.",
        )
        val width = 900
        val blocks = samples.map { MathText.layout(it, width - 48f, style) }
        val height = blocks.sumOf { it.height.toDouble() + 24 }.toInt() + 48
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var y = 24f
        for (b in blocks) { b.draw(canvas, 24f, y); y += b.height + 24f }
        val dir = File("build/reports/study-ui").apply { mkdirs() }
        File(dir, "math.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Something was drawn in every block.
        var dark = 0
        for (py in 0 until height step 3) for (px in 0 until width step 3) if (Color.red(bitmap.getPixel(px, py)) < 128) dark++
        assertTrue(dark > 500)
    }
}
