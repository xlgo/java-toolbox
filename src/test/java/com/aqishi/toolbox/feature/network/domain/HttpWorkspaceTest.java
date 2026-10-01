package com.aqishi.toolbox.feature.network.domain;

import com.aqishi.toolbox.feature.network.domain.HttpWorkspace.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpWorkspaceTest {
    private Request request(String name) { return new Request(name,"POST","{{baseUrl}}/echo","Authorization: Bearer {{token}}","{\"x\":\"{{value}}\"}",true); }
    private Environment env(){return new Environment("dev",Map.of("baseUrl",new Variable("http://127.0.0.1",false),"token",new Variable("s3cr3t",true),"value",new Variable("$1\\path",false)));}
    @Test void resolvesAllRequestPartsAndMasksPreview(){
        Request real=HttpWorkspace.resolve(request("echo"),env(),false);
        assertEquals("http://127.0.0.1/echo",real.url());assertEquals("Authorization: Bearer s3cr3t",real.headers());
        assertEquals("{\"x\":\"$1\\path\"}",real.body());
        assertEquals("Authorization: Bearer ******",HttpWorkspace.resolve(request("echo"),env(),true).headers());
        assertTrue(request("echo").url().contains("{{baseUrl}}"));
    }
    @Test void missingVariablesFailBeforeSending(){assertThrows(IllegalArgumentException.class,()->HttpWorkspace.resolve(request(""),null,false));}
    @Test void rejectsHeaderInjectionEvenInMaskedPreview(){
        var env=new Environment("dev",Map.of("baseUrl",new Variable("http://localhost",false),"token",new Variable("one\r\nX-Evil: two",true)));
        assertThrows(IllegalArgumentException.class,()->HttpWorkspace.resolve(request(""),env,true));
    }
    @Test void variableValuesAreNotRecursivelyEvaluated(){
        var template=new Request("","GET","{{x}}","","",false);
        assertEquals("{{y}}",HttpWorkspace.resolve(template,new Environment("e",Map.of("x",new Variable("{{y}}",false))),false).url());
    }
    @Test void editsReplaceNamedRequestsAndLimitHistory(){
        Document doc=Document.empty().save(request("saved"));
        doc=doc.save(new Request("saved","GET","https://example.test","","",false));assertEquals(1,doc.requests().size());assertFalse(doc.requests().get(0).favorite());
        for(int i=0;i<60;i++)doc=doc.remember(request("r"+i));assertEquals(50,doc.history().size());assertEquals("r59",doc.history().get(0).name());
        assertTrue(doc.deleteRequest("saved").requests().isEmpty());
        doc=doc.environment(env());assertEquals(1,doc.environment(env()).environments().size());assertTrue(doc.deleteEnvironment("dev").environments().isEmpty());
    }
    @Test void rejectsUnsupportedVersionAndInvalidVariables(){
        assertThrows(IllegalArgumentException.class,()->new Document(2,List.of(),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->new Environment("e",Map.of("bad name",new Variable("value",false))));
    }
}
