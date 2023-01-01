package xyz.bluspring.srg2intermediary

import net.minecraftforge.srgutils.IMappingFile

class Remapper(private val downloader: MappingDownloader) {
    val srgMappings = IMappingFile.load(downloader.srgMappingsFile)
    val mojangMappings = IMappingFile.load(downloader.mojangMappingsFile)
    val intermediaryMappings = IMappingFile.load(downloader.intermediaryFile)

    fun remap() {
        println("Creating ")
    }
}