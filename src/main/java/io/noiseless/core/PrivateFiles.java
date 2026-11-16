package io.noiseless.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Set permissions before writing credentials, including on Windows NTFS. */
public final class PrivateFiles {
    private PrivateFiles() {}
    public static void create(Path path) throws IOException {
        try {
            Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch(UnsupportedOperationException e) {
            Files.createFile(path);
            try {
                var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
                if(acl==null) throw new IOException("Credential storage requires POSIX permissions or Windows ACL support");
                var user=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            } catch(IOException | RuntimeException failure) {
                Files.deleteIfExists(path); throw failure;
            }
        }
    }
}
