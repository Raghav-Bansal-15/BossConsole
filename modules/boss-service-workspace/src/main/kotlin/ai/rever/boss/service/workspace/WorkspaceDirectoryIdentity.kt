package ai.rever.boss.service.workspace

import com.sun.jna.Memory
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinNT
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Compare filesystem identity, never two absent provider keys or a mutable timestamp. */
internal object WorkspaceDirectoryIdentity {
    fun read(path: Path): Any =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
            ?: if (Platform.isWindows()) windowsIdentity(path) else throw IOException("Directory identity unavailable")

    private fun windowsIdentity(path: Path): String {
        val api = Kernel32.INSTANCE
        val handle =
            api.CreateFile(
                path.toString(),
                WinNT.FILE_READ_ATTRIBUTES,
                WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
                null,
                WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS or WinNT.FILE_FLAG_OPEN_REPARSE_POINT,
                null,
            )
        if (handle == WinBase.INVALID_HANDLE_VALUE) throw IOException("Directory identity could not be opened")
        try {
            // FILE_ID_INFO: 64-bit volume serial followed by the filesystem's 128-bit file ID.
            // FileIdInfo is supported on the project's Windows 10+ baseline, including ReFS.
            return Memory(24).use { info ->
                if (!api.GetFileInformationByHandleEx(handle, 18, info, DWORD(24))) {
                    throw IOException("Directory identity could not be read")
                }
                "${info.getLong(0)}:${info.getLong(8)}:${info.getLong(16)}"
            }
        } finally {
            api.CloseHandle(handle)
        }
    }
}
