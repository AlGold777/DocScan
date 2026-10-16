package app.docscan.android

import android.system.Os
import android.system.OsConstants
import app.docscan.core.artifact.DirectorySync
import app.docscan.core.artifact.AtomicArtifactPublisher
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Android adapter for syncing private-storage directory entries after publication. */
class AndroidDirectorySync : DirectorySync {
    override fun sync(directory: Path) {
        val descriptor = Os.open(directory.toString(), OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}

/** Publishes within app-private storage using atomic rename, without replacing an object name. */
class AndroidAtomicArtifactPublisher : AtomicArtifactPublisher {
    override fun publish(temporary: Path, target: Path) {
        synchronized(PUBLICATION_LOCK) {
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                throw FileAlreadyExistsException(target.toString())
            }
            Os.rename(temporary.toString(), target.toString())
        }
    }

    private companion object {
        /** All app processes use the default process; serialize immutable-name publication. */
        val PUBLICATION_LOCK = Any()
    }
}
