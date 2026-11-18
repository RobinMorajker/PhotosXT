package com.photoxt.core.render

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The editor's shader through the Khronos reference compiler, as GLSL ES 3.00 (a phone's driver
 * rejects what it rejects, such as the reserved word `half`). CI installs `glslang-tools`;
 * without `glslangValidator` on the PATH the test is skipped.
 */
class ShaderCompilesTest {
    @Test
    fun theEditShaderIsValidGlslEs300() {
        val validator = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "glslangValidator") }
            .firstOrNull(File::canExecute)
        assumeTrue("glslangValidator is not installed", validator != null)
        val dir = Files.createTempDirectory("shader").toFile()
        listOf("edit.vert" to EditShader.VERTEX, "edit.frag" to EditShader.FRAGMENT)
            .forEach { (name, source) ->
                val file = File(dir, name).apply { writeText(source) }
                val run = ProcessBuilder(validator!!.path, file.path)
                    .redirectErrorStream(true)
                    .start()
                val output = run.inputStream.bufferedReader().readText()
                assertEquals(output, 0, run.waitFor())
            }
        dir.deleteRecursively()
    }
}
