package xyz.bluspring.srg2intermediary

fun main(args: Array<out String>) {
    val version = args.getOrNull(0) ?: throw IllegalArgumentException("No version provided!")
    val isMojMap = args.getOrNull(1) == "mojmap"
    println("SRG to Intermediary Mapping Generator")
    println("for Minecraft version $version")
    println()
    println()

    val downloader = MappingDownloader(version)
    downloader.downloadFiles()

    val remapper = Remapper(downloader, version, isMojMap)
    remapper.remap()
}