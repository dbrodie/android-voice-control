package com.brainstormity.voicecontrol.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * LIVE smoke test against the real JEV API. Skipped unless JEV_KEY is set. Uses a real phone screenshot:
 *   adb -s emulator-5554 exec-out screencap -p > /tmp/shot.png
 *   JEV_KEY=... ./gradlew :app:testDebugUnitTest --tests '*JevLiveTest*'
 * (JEV_SHOT overrides the screenshot path.)
 */
class JevLiveTest {
    private val task = "Open Settings and turn on Wi-Fi"

    @Test fun askAndRefineAgainstRealJev() {
        val key = System.getenv("JEV_KEY").orEmpty()
        assumeTrue("JEV_KEY not set: live JEV test skipped", key.isNotBlank())
        val shotFile = File(System.getenv("JEV_SHOT") ?: "/tmp/shot.png")
        assumeTrue("no screenshot at $shotFile", shotFile.exists())

        val full = Awt.read(shotFile)
        val img = Awt.shrink(full, 1568)
        println("Screenshot ${full.width}x${full.height} -> ${img.width}x${img.height}")
        val jpeg = img.jpeg(0.85f)
        println("JPEG ${jpeg.size / 1024} KB")

        val client = JevClient({ key })
        val texts = TaskParser.textOptions(task)
        val urls = TaskParser.websites(task)
        val apps = TaskParser.appsInTask(task, setOf("com.android.settings"))
        val history = listOf("OPEN_APP settings")

        var t0 = System.nanoTime()
        val r = client.ask(task, jpeg, history, texts, urls, apps)
        val askSeconds = (System.nanoTime() - t0) / 1e9
        val a = r.action!!
        val target = r.target!!
        val text = r.text?.choice?.toIntOrNull()?.let { texts.getOrNull(it) }
        println("ASK  ${"%.2f".format(askSeconds)} s: action=${a.choice} conf=${AgentRules.pct(a.confidence)} " +
            "probs=${a.probabilities.entries.sortedByDescending { it.value }.take(4)}")
        println("     target=${target.box} conf=${AgentRules.pct(target.confidence)} text=${r.text?.choice} ($text) " +
            "usage=${r.usage} cost=$${AgentRules.usd(AgentRules.costOf(r.usage.inputTokens))}")
        val cx = (target.box.cx * full.width).toInt()
        val cy = (target.box.cy * full.height).toInt()
        println("     -> tap at ($cx, $cy) on the ${full.width}x${full.height} screen")
        assertTrue(a.choice in AgentRules.PHONE_ACTIONS)
        assertTrue(target.box.right >= target.box.left && target.box.bottom >= target.box.top)

        val c = CloseUp.around(target.box, img.width, img.height)
        val closeJpeg = img.crop(c.left, c.top, c.cropW, c.cropH).scale(c.outW, c.outH).jpeg(0.90f)
        t0 = System.nanoTime()
        val rr = client.refine(task, closeJpeg, history)
        val refineSeconds = (System.nanoTime() - t0) / 1e9
        val ct = rr.target!!
        val mapped = c.mapBack(ct.box)
        println("REFINE ${"%.2f".format(refineSeconds)} s: crop=(${c.left},${c.top},${c.right},${c.bottom}) " +
            "-> ${c.outW}x${c.outH}; closeup box=${ct.box} conf=${AgentRules.pct(ct.confidence)} usage=${rr.usage}")
        println("     mapped=$mapped -> tap at (${(mapped.cx * full.width).toInt()}, ${(mapped.cy * full.height).toInt()})")
        assertNotNull(rr.target)
    }

    /**
     * java.awt / javax.imageio through reflection: they exist on the test JVM at runtime, but the Android
     * unit-test compile classpath (android.jar) doesn't have them.
     */
    private class Awt(val img: Any) {
        val width: Int get() = call(img, "getWidth") as Int
        val height: Int get() = call(img, "getHeight") as Int

        fun crop(x: Int, y: Int, w: Int, h: Int) = Awt(call(img, "getSubimage", x, y, w, h)!!)

        fun scale(w: Int, h: Int): Awt {
            val bi = Class.forName("java.awt.image.BufferedImage")
            val out = bi.getConstructor(Int::class.java, Int::class.java, Int::class.java)
                .newInstance(w, h, bi.getField("TYPE_INT_RGB").getInt(null))
            val g = call(out, "createGraphics")!!
            val hints = Class.forName("java.awt.RenderingHints")
            val setHint = api(g).getMethod("setRenderingHint", Class.forName("java.awt.RenderingHints\$Key"), Any::class.java)
            setHint.invoke(g, hints.getField("KEY_INTERPOLATION").get(null), hints.getField("VALUE_INTERPOLATION_BICUBIC").get(null))
            api(g).getMethod("drawImage", Class.forName("java.awt.Image"), Int::class.java, Int::class.java,
                Int::class.java, Int::class.java, Class.forName("java.awt.image.ImageObserver"))
                .invoke(g, img, 0, 0, w, h, null)
            call(g, "dispose")
            return Awt(out)
        }

        fun jpeg(quality: Float): ByteArray {
            val imageIO = Class.forName("javax.imageio.ImageIO")
            val writer = (imageIO.getMethod("getImageWritersByFormatName", String::class.java).invoke(null, "jpg") as Iterator<*>).next()!!
            val out = ByteArrayOutputStream()
            val ios = imageIO.getMethod("createImageOutputStream", Any::class.java).invoke(null, out)!!
            api(writer).getMethod("setOutput", Any::class.java).invoke(writer, ios)
            val param = call(writer, "getDefaultWriteParam")!!
            val iwp = Class.forName("javax.imageio.ImageWriteParam")
            iwp.getMethod("setCompressionMode", Int::class.java).invoke(param, iwp.getField("MODE_EXPLICIT").getInt(null))
            iwp.getMethod("setCompressionQuality", Float::class.java).invoke(param, quality)
            val iio = Class.forName("javax.imageio.IIOImage")
            val meta = Class.forName("javax.imageio.metadata.IIOMetadata")
            val image = iio.getConstructor(Class.forName("java.awt.image.RenderedImage"), List::class.java, meta)
                .newInstance(img, null, null)
            api(writer).getMethod("write", meta, iio, iwp).invoke(writer, null, image, param)
            call(ios, "close")
            call(writer, "dispose")
            return out.toByteArray()
        }

        companion object {
            /** The nearest public java.* / javax.* class (implementations like SunGraphics2D aren't accessible). */
            fun api(o: Any): Class<*> {
                var c: Class<*> = o.javaClass
                while (!(c.name.startsWith("java.") || c.name.startsWith("javax."))) c = c.superclass ?: break
                return c
            }

            fun call(o: Any, name: String, vararg args: Int): Any? =
                api(o).methods.first { it.name == name && it.parameterCount == args.size }
                    .invoke(o, *args.toTypedArray())

            fun read(f: File): Awt =
                Awt(Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, f)!!)

            /** PNG (maybe RGBA) -> RGB, shrunk so the longer side is <= maxSide. */
            fun shrink(src: Awt, maxSide: Int): Awt {
                val longest = maxOf(src.width, src.height)
                val s = if (longest <= maxSide) 1.0 else maxSide.toDouble() / longest
                return src.scale(Math.round(src.width * s).toInt(), Math.round(src.height * s).toInt())
            }
        }

    }
}
