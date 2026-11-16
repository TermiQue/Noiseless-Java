package io.noiseless;

import io.noiseless.server.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static io.noiseless.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class AiProfilesTest {
    @TempDir Path temp;
    @Test void encryptsCredentialsScopesProfilesAndPreservesSavedKey() throws Exception {
        try(var db=new Database(temp)){
            db.execute("INSERT INTO users VALUES(?,?,?,?,?)","a","a@example.com","hash","A","now"); db.execute("INSERT INTO users VALUES(?,?,?,?,?)","b","b@example.com","hash","B","now");
            AiProfiles profiles=new AiProfiles(db,temp,true); var request=obj("name","Local fixture","baseUrl","http://127.0.0.1:18089/v1","protocol","chat","apiKey","never-return-this-key","models",arr().add("mock"),"manualModel",true);
            var saved=profiles.save("a",request); String id=saved.path(0).path("id").asText(); assertFalse(str(saved).contains("never-return-this-key")); assertFalse(str(db.list("a","ai-profiles")).contains("never-return-this-key"));
            assertEquals("never-return-this-key",profiles.resolve("a",id).key()); assertThrows(Fault.class,()->profiles.resolve("b",id));
            request.remove("apiKey"); request.put("id",id); request.put("name","Renamed"); profiles.save("a",request); assertEquals("never-return-this-key",profiles.resolve("a",id).key());
            assertEquals("never-return-this-key",new AiProfiles(db,temp,true).resolve("a",id).key());
            request.put("baseUrl","http://127.0.0.1:18090/v1"); assertThrows(Fault.class,()->profiles.save("a",request));
            profiles.delete("a",id); assertEquals("server",profiles.preferred("a"));
        }
    }
    @Test void remoteProfilesCannotReachArbitraryInternalEndpoints() throws Exception {
        try(var db=new Database(temp)){
            db.execute("INSERT INTO users VALUES(?,?,?,?,?)","u","u@example.com","hash","U","now"); var profiles=new AiProfiles(db,temp,false);
            var request=obj("name","Unsafe","baseUrl","https://127.0.0.1","protocol","chat","apiKey","test","models",arr().add("test"));
            assertThrows(Fault.class,()->profiles.save("u",request)); request.put("baseUrl","http://169.254.169.254"); assertThrows(Fault.class,()->profiles.save("u",request));
        }
    }
}
