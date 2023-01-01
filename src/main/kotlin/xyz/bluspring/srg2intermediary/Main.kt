package xyz.bluspring.srg2intermediary

fun main(args: Array<out String>) {
    val version = args.getOrNull(0) ?: throw IllegalArgumentException("No version provided!")
    println("SRG to Intermediary Mapping Generator")
    println("for Minecraft version $version")

    val downloader = MappingDownloader(version)
    downloader.downloadFiles()

    val remapper = Remapper(downloader)
    remapper.remap()
}