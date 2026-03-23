package xyz.bluspring.srg2intermediary

import net.minecraftforge.srgutils.IMappingFile

fun main(args: Array<out String>) {
    val version = args.getOrNull(0) ?: throw IllegalArgumentException("No version provided!")
    val isMojMap = args.getOrNull(1) == "mojmap"
    println("SRG to Intermediary Mapping Generator")
    println("for Minecraft version $version")
    println()
    println()

    // unsure why SRGUtils doesn't have this marked as true but okay
    IMappingFile.Format::class.java.getDeclaredField("hasNames").apply {
        this.isAccessible = true
    }.set(IMappingFile.Format.TINY, true)

    val downloader = MappingDownloader(version)
    downloader.downloadFiles()

    val remapper = Remapper(downloader, version, isMojMap)
    remapper.remap()
}