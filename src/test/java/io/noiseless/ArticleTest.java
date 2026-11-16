package io.noiseless;

import io.noiseless.core.Article;
import org.junit.jupiter.api.Test;
import static io.noiseless.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ArticleTest {
    @Test void allBundledSixTypesRemainCompatible() throws Exception {
        try(var in=getClass().getResourceAsStream("/seed/index.txt")) {
            for(String file:new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split("\n")) if(!file.isBlank()) try(var data=getClass().getResourceAsStream("/seed/"+file)) { assertDoesNotThrow(()->Article.validate(MAPPER.readTree(data)),file); }
        }
    }
    @Test void rejectsAnswersOutsideOptionsAndDuplicateNumbers() {
        var a=obj("format","noiseless-1","title","Test","paragraphs",arr().add(obj("en","A","zh","")),"questions",arr().add(obj("number",1,"en","?","zh","","answer","C","explanation","","evidence","","options",arr().add(obj("key","A","en","one")).add(obj("key","B","en","two")))));
        assertThrows(Fault.class,()->Article.validate(a));
        ((com.fasterxml.jackson.databind.node.ObjectNode)a.path("questions").get(0)).put("answer","A"); assertDoesNotThrow(()->Article.validate(a));
        ((com.fasterxml.jackson.databind.node.ArrayNode)a.path("questions")).add(a.path("questions").get(0).deepCopy()); assertThrows(Fault.class,()->Article.validate(a));
    }
}
