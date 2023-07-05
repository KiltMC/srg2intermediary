package xyz.bluspring.srg2intermediary

import net.minecraftforge.srgutils.IMappingBuilder
import net.minecraftforge.srgutils.IMappingFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.jar.JarFile

class Remapper(downloader: MappingDownloader, private val version: String) {
    val officialMappings = IMappingFile.load(downloader.srgMappingsFile) // official -> srg
    val srgMappings = officialMappings.reverse() // srg -> official (result of being reversed)

    val mojangMappings = IMappingFile.load(downloader.mojangMappingsFile).reverse() // official -> moj (result of being reversed)
    val intermediaryMappings = IMappingFile.load(downloader.intermediaryFile) // official -> intermediary

    val classParents = mutableMapOf<String, MutableList<String>>()

    init {
        val startTime = System.currentTimeMillis()

        val jar = JarFile(downloader.clientFile)
        for (entry in jar.entries()) {
            if (!entry.name.endsWith(".class"))
                continue

            val stream = jar.getInputStream(entry)
            val classReader = ClassReader(stream)
            val classNode = ClassNode(Opcodes.ASM9)

            classReader.accept(classNode, 0)

            classParents[classNode.name] = mutableListOf(
                classNode.superName
            ).apply {
                this.addAll(classNode.interfaces)
            }
        }

        println("Loaded all Minecraft classes and mapped them to their parents. (took ${System.currentTimeMillis() - startTime} ms)")
    }

    private val alreadyRemapped = mutableMapOf<String, Pair<String, String?>>()

    // Class names are official mapped.
    private fun getParentTree(className: String): List<String> {
        val parents = classParents[className] ?: return listOf("java/lang/Object")
        val tree = mutableListOf<String>()

        for (parent in parents) {
            tree.add(parent)
            tree.addAll(getParentTree(parent))
        }

        return tree
    }

    fun remap() {
        val startTime = System.currentTimeMillis()
        println("Creating mappings with MojMap class names and SRG method/field names to Intermediary...")

        // Pre-map the methods because otherwise method name collisions occur.
        intermediaryMappings.classes.forEach { iClass ->
            val srgClass = officialMappings.getClass(iClass.original)
            val mojClass = mojangMappings.getClass(iClass.original)

            iClass.methods.forEach method@{ iMethod ->
                val srgMethod = srgClass.getMethod(iMethod.original, iMethod.descriptor) ?: return@method
                val mojMethod = mojClass.getMethod(iMethod.original, iMethod.descriptor) ?: return@method

                alreadyRemapped["${srgClass.mapped}:${srgMethod.mapped}"] = Pair(iMethod.mapped, mojMethod.mappedDescriptor)
            }
        }

        // the args for each of these is (from -> to)
        val mappingBuilder = IMappingBuilder.create("searge", "intermediary")
        srgMappings.classes.forEach { srgClass ->
            val mojangClass = mojangMappings.getClass(srgClass.mapped)
            val intermediaryClass = intermediaryMappings.getClass(srgClass.mapped)

            if (intermediaryClass == null) {
                println("Failed to map class: SRG:${srgClass.original} OFFICIAL:${srgClass.mapped} MOJ:${mojangClass.mapped}")

                return@forEach
            }

            val mappedClass = mappingBuilder.addClass(mojangClass.mapped, intermediaryClass.mapped)

            srgClass.fields.forEach fieldMapper@{ field ->
                if (alreadyRemapped.contains(field.original)) {
                    val remapped = alreadyRemapped[field.original]!!
                    mappedClass.field(field.original, remapped.first)
                        .descriptor(remapped.second)

                    return@fieldMapper
                }

                val intermediaryField = intermediaryClass.getField(field.mapped)
                val mojField = mojangClass.getField(field.mapped)

                if (intermediaryField == null) {
                    // Don't handle those that aren't going to be remapped anyway.
                    if (field.original == field.mapped || !field.original.startsWith("f_"))
                        return@fieldMapper

                    val descriptor = mojField?.mappedDescriptor

                    mappedClass.field(field.original, field.mapped)
                        .descriptor(descriptor)

                    //alreadyRemapped[field.original] = Pair(field.mapped, descriptor)

                    return@fieldMapper
                }

                //alreadyRemapped[field.original] = Pair(intermediaryField.mapped, intermediaryField.mappedDescriptor)

                mappedClass.field(field.original, intermediaryField.mapped)
                    .descriptor(
                        if (intermediaryField.descriptor?.contains("L") == true && intermediaryField.descriptor?.contains(";") == true)
                            mojField?.mappedDescriptor ?: intermediaryField.mappedDescriptor
                        else
                            intermediaryField.mappedDescriptor
                    )
            }

            srgClass.methods.forEach methodMapper@{ method ->
                // what the fuck is this, why is this even possible
                if (method.original.startsWith("f_"))
                    return@methodMapper

                val tree = getParentTree(srgClass.original)
                for (parent in tree) {
                    val parentName = srgMappings.getClass(parent)?.mapped ?: continue

                    if (alreadyRemapped.contains("$parentName:${method.original}")) {
                        val remapped = alreadyRemapped["$parentName:${method.original}"]!!
                        mappedClass.method(remapped.second, method.original, remapped.first)

                        return@methodMapper
                    }
                }

                val intermediaryMethod = intermediaryClass.getMethod(method.mapped, method.mappedDescriptor)
                val mojMethod = mojangClass.getMethod(method.mapped, method.mappedDescriptor)

                if (intermediaryMethod == null) {
                    // Don't handle stuff like <init> and such.
                    if (method.original == method.mapped)
                        return@methodMapper

                    // these might be remapped somewhere else, so don't bother
                    if (method.mapped.length <= 2 || method.mapped.endsWith("_"))
                        return@methodMapper

                    val descriptor = mojangMappings.remapDescriptor(method.mappedDescriptor)
                    mappedClass.method(descriptor, method.original, method.mapped)

                    //alreadyRemapped[method.original] = Pair(method.mapped, descriptor)

                    return@methodMapper
                }

                mappedClass.method(mojMethod?.mappedDescriptor ?: intermediaryMethod.mappedDescriptor, method.original, intermediaryMethod.mapped)
                //alreadyRemapped[method.original] = Pair(intermediaryMethod.mapped, intermediaryMethod.mappedDescriptor)
            }
        }


        val remappedFile = File(System.getProperty("user.dir"), "remapped_$version.tiny")
        mappingBuilder.build().write(remappedFile.toPath(), IMappingFile.Format.TINY)

        println("Finished creating a map for SRG to Intermediary! The file has been created at ${remappedFile.absolutePath}. (took ${System.currentTimeMillis() - startTime}ms)")
    }
}