package xyz.bluspring.srg2intermediary

import net.minecraftforge.fart.api.ClassProvider
import net.minecraftforge.fart.internal.EnhancedClassRemapper
import net.minecraftforge.fart.internal.EnhancedRemapper
import net.minecraftforge.fart.internal.RenamingTransformer
import net.minecraftforge.srgutils.IMappingBuilder
import net.minecraftforge.srgutils.IMappingFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.Path

class Remapper(downloader: MappingDownloader, private val version: String) {
    val officialSrgMappings = IMappingFile.load(downloader.srgMappingsFile) // official -> srg
    val srgOfficialMappings = officialSrgMappings.reverse() // srg -> official (result of being reversed)

    val officialMojangMappings = IMappingFile.load(downloader.mojangMappingsFile).reverse() // official -> moj (result of being reversed)
    val mojOfficialMappings = officialMojangMappings.reverse() // moj -> official
    val officialIntermediaryMappings = IMappingFile.load(downloader.intermediaryFile) // official -> intermediary

    init {
        val startTime = System.currentTimeMillis()

        //remapJar(downloader.clientFile, officialSrgMappings, "srg")
        remapJar(downloader.clientFile, officialMojangMappings, "mojmap")
        //remapJar(downloader.clientFile, officialIntermediaryMappings, "intermediary")

        println("Mapped all Minecraft classes. (took ${System.currentTimeMillis() - startTime} ms)")
    }

    fun remap() {
        val startTime = System.currentTimeMillis()
        println("Creating mappings with MojMap class names and SRG method/field names to Intermediary...")

        // init remappers
        val officialRemapper = EnhancedRemapper(ClassProvider.builder().apply {
            this.addLibrary(Path("client_${version}_mojmap.jar"))
        }.build(), mojOfficialMappings) { println(it) }
        val srgRemapper = EnhancedRemapper(ClassProvider.builder().apply {
            this.addLibrary(Path("client_${version}.jar"))
        }.build(), officialSrgMappings) { println(it) }
        val intermediaryRemapper = EnhancedRemapper(ClassProvider.builder().apply {
            this.addLibrary(Path("client_${version}.jar"))
        }.build(), officialIntermediaryMappings) { println(it) }

        // the args for each of these is (from -> to)
        val mappingBuilder = IMappingBuilder.create("searge", "intermediary")

        // get all Mojmapped classes
        run {
            val jarFile = JarFile(File("client_${version}_mojmap.jar"))

            for (entry in jarFile.stream()) {
                if (!entry.name.endsWith(".class"))
                    continue

                val classReader = jarFile.getInputStream(entry).use { ClassReader(it) }
                val classNode = ClassNode(Opcodes.ASM9)
                classReader.accept(classNode, 0)

                val officialInfo = officialRemapper.getClass(classNode.name).orElseThrow()
                val classMap = mappingBuilder.addClass(classNode.name, intermediaryRemapper.map(officialInfo.mapped))

                for (field in classNode.fields) {
                    val intermediaryName = intermediaryRemapper.mapFieldName(
                        officialInfo.mapped,
                        officialRemapper.mapFieldName(classNode.name, field.name, field.desc),
                        officialRemapper.mapDesc(field.desc)
                    )

                    if (field.name == intermediaryName)
                        continue

                    classMap.field(
                        srgRemapper.mapFieldName(
                            officialInfo.mapped,
                            officialRemapper.mapFieldName(classNode.name, field.name, field.desc),
                            officialRemapper.mapDesc(field.desc)
                        ),
                        intermediaryName
                    )
                        .descriptor(field.desc)
                }

                for (method in classNode.methods) {
                    val intermediaryName = intermediaryRemapper.mapMethodName(
                        officialInfo.mapped,
                        officialRemapper.mapMethodName(classNode.name, method.name, method.desc),
                        officialRemapper.mapMethodDesc(method.desc)
                    )

                    if (method.name == intermediaryName)
                        continue

                    classMap.method(method.desc,
                        srgRemapper.mapMethodName(
                            officialInfo.mapped,
                            officialRemapper.mapMethodName(classNode.name, method.name, method.desc),
                            officialRemapper.mapMethodDesc(method.desc)
                        ),
                        intermediaryName
                    )
                }
            }
        }

        val remappedFile = File(System.getProperty("user.dir"), "remapped_$version.tiny")
        mappingBuilder.build().write(remappedFile.toPath(), IMappingFile.Format.TINY)

        println("Finished creating a map for SRG to Intermediary! The file has been created at ${remappedFile.absolutePath}. (took ${System.currentTimeMillis() - startTime}ms)")
    }

    private fun remapJar(sourceJar: File, mappings: IMappingFile, name: String) {
        val classProvider = ClassProvider.builder().apply {
            this.addLibrary(sourceJar.toPath())
        }.build()

        val remapper = EnhancedRemapper(classProvider, mappings) { println(it) }
        val jar = JarFile(sourceJar)

        val newFile = File("client_${version}_$name.jar")
        if (newFile.exists())
            newFile.delete()

        newFile.createNewFile()

        val outputJar = JarOutputStream(newFile.outputStream())

        for (entry in jar.stream()) {
            if (entry.name.startsWith("META-INF"))
                continue

            if (entry.name.endsWith(".class")) {
                val classReader = jar.getInputStream(entry).use { ClassReader(it) }
                val classNode = ClassNode(Opcodes.ASM9)
                classReader.accept(classNode, 0)

                val classWriter = ClassWriter(0)

                val visitor = EnhancedClassRemapper(classWriter, remapper, RenamingTransformer(remapper, false))
                classNode.accept(visitor)

                outputJar.putNextEntry(JarEntry(remapper.map(classNode.name) + ".class"))
                outputJar.write(classWriter.toByteArray())
                outputJar.closeEntry()
            } else {
                outputJar.putNextEntry(entry)
                jar.getInputStream(entry).use { outputJar.write(it.readAllBytes()) }
                outputJar.closeEntry()
            }
        }

        outputJar.close()
    }
}