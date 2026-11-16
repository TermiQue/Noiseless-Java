package io.noiseless;

import io.noiseless.core.PrivateFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivateFilesTest {
    @TempDir Path directory;
    @Test void credentialsArePrivateAndNeverOverwriteExistingFiles() throws Exception {
        Path path=directory.resolve("private.key"); PrivateFiles.create(path); Files.writeString(path,"test credential");
        if(Files.getFileAttributeView(path,PosixFileAttributeView.class)!=null)
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(path));
        else {
            var entries=Files.getFileAttributeView(path,AclFileAttributeView.class).getAcl();
            var user=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            assertEquals(1,entries.size()); assertEquals(user,entries.getFirst().principal());
            assertEquals(AclEntryType.ALLOW,entries.getFirst().type());
        }
        assertThrows(FileAlreadyExistsException.class,()->PrivateFiles.create(path));
        assertEquals("test credential",Files.readString(path));
    }
}
