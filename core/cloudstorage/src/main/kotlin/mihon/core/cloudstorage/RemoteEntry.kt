package mihon.core.cloudstorage

data class RemoteEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)
