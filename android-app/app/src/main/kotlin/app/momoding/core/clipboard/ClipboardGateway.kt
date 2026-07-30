package app.momoding.core.clipboard

interface ClipboardGateway {
    suspend fun read(): ClipboardSnapshot
    suspend fun setText(text: String, sensitive: Boolean)
    suspend fun clear()
}
